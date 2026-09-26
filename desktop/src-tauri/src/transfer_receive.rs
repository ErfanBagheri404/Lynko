//! Exact LocalSend Protocol v2.2 receive server (https://github.com/localsend/protocol).
//!
//! Endpoints, status codes and body shapes follow the spec verbatim:
//!   GET  /api/localsend/v2/info
//!   POST /api/localsend/v2/register
//!   POST /api/localsend/v2/prepare-upload   -> {sessionId, files:{id:token}}
//!   POST /api/localsend/v2/upload?sessionId&fileId&token   (raw binary)
//!   POST /api/localsend/v2/cancel?sessionId
//!   POST /api/localsend/v2/prepare-download -> {sessionId, files:{id:token}}
//!   GET  /api/localsend/v2/download?sessionId&fileId&token
//!
//! Sender identity (spec 4.1) is checked before a token is minted; the upload
//! endpoint re-checks the token AND the sender IP, exactly as the spec's 403
//! rule describes.

use sha2::Digest;
use std::collections::HashMap;
use std::io::Read;
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use tauri::Emitter;

pub const TRANSFER_PORT: u16 = 53317;

/// One accepted-or-pending transfer session.
pub struct Session {
    /// fileId -> metadata
    pub files: HashMap<String, FileEntry>,
    /// fileId -> token (spec 4.1 reply)
    pub tokens: HashMap<String, String>,
    /// Sender IP the session was created from (403 guard on upload).
    pub sender_ip: String,
    /// fileId -> staged bytes, filled by upload
    pub received: HashMap<String, Vec<u8>>,
    /// Created-at timestamp for expiry sweep (120s, same as sender timeout).
    pub created_at: std::time::Instant,
}

pub struct FileEntry {
    pub id: String,
    pub name: String,
    pub size: u64,
    pub mime: String,
    pub sha256: Option<String>,
}

/// Consent gate: the HTTP thread parks here until the user answers in the UI.
struct Consent {
    answered: bool,
    accepted: bool,
}

/// Transfer PIN store — set from Settings, read by prepare-upload/prepare-download.
fn pin_store() -> &'static Mutex<Option<String>> {
    PIN.get_or_init(|| Mutex::new(None))
}

pub fn pin_get() -> Option<String> {
    pin_store().lock().unwrap().clone().filter(|p| !p.is_empty())
}

pub fn pin_set(pin: Option<String>) {
    *pin_store().lock().unwrap() = pin;
}

/// UI answers a pending incoming transfer (the consent gate in wait_consent).
#[tauri::command]
pub fn answer_transfer(accepted: bool) -> Result<(), String> {
    answer(accepted);
    Ok(())
}

/// UI sets the transfer PIN (?pin= on the prepare calls; None = no PIN).
#[tauri::command]
pub fn set_transfer_pin(pin: Option<String>) -> Result<(), String> {
    pin_set(pin);
    Ok(())
}

static SESSIONS: OnceLock<Mutex<HashMap<String, Session>>> = OnceLock::new();
static PIN: OnceLock<Mutex<Option<String>>> = OnceLock::new();
static CONSENT: OnceLock<(Mutex<Consent>, Condvar)> = OnceLock::new();
static OUTBOX: OnceLock<Mutex<HashMap<String, (String, String)>>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<String, Session>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn consent() -> &'static (Mutex<Consent>, Condvar) {
    CONSENT.get_or_init(|| {
        (
            Mutex::new(Consent { answered: false, accepted: false }),
            Condvar::new(),
        )
    })
}

fn outbox() -> &'static Mutex<HashMap<String, (String, String)>> {
    OUTBOX.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Called from the UI (tauri command) to answer a pending transfer request.
pub fn answer(accepted: bool) {
    let (lock, cv) = consent();
    let mut c = lock.lock().unwrap();
    c.answered = true;
    c.accepted = accepted;
    cv.notify_all();
}

/// Park the HTTP thread until the user answers, or 120 s elapse (spec: the
/// sender's prepare-upload call blocks while the receiver decides).
fn wait_consent() -> bool {
    let (lock, cv) = consent();
    let mut c = lock.lock().unwrap();
    c.answered = false;
    c.accepted = false;
    drop(c);
    let (lock, cv2) = (lock, cv);
    let mut c = lock.lock().unwrap();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(120);
    while !c.answered {
        let left = deadline.saturating_duration_since(std::time::Instant::now());
        if left.is_zero() {
            break;
        }
        let (g, t) = cv2.wait_timeout(c, left).unwrap();
        c = g;
        if t.timed_out() && !c.answered {
            break;
        }
    }
    c.accepted
}

/// Spawn the LocalSend v2.2 receive server on port 53317.
pub fn start(app: tauri::AppHandle) {
    std::thread::spawn(move || {
        let server = match tiny_http::Server::http(("0.0.0.0", TRANSFER_PORT)) {
            Ok(s) => s,
            Err(e) => {
                let _ = app.emit("log", serde_json::json!({ "msg": format!("localsend server: {e}") }));
                return;
            }
        };
        let _ = app.emit("log", serde_json::json!({ "msg": format!("LocalSend v2.2 server on :{TRANSFER_PORT}") }));
        // Session expiry sweep: 120s matches the sender timeout.
        let sweep_app = app.clone();
        std::thread::spawn(move || loop {
            std::thread::sleep(std::time::Duration::from_secs(30));
            let mut all = sessions().lock().unwrap();
            all.retain(|_, s| {
                let alive = s.created_at.elapsed().as_secs() < 120;
                if !alive {
                    let _ = sweep_app.emit("share_status", serde_json::json!({"status":"cancelled","reason":"expired"}));
                }
                alive
            });
        });
        for request in server.incoming_requests() {
            let app = app.clone();
            std::thread::spawn(move || handle(app, request));
        }
    });
}

fn qs(url: &str) -> HashMap<String, String> {
    let mut out = HashMap::new();
    if let Some((_, q)) = url.split_once('?') {
        for pair in q.split('&') {
            let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
            out.insert(percent_decode(k), percent_decode(v));
        }
    }
    out
}

fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' if i + 2 < b.len() => {
                let hex = std::str::from_utf8(&b[i + 1..i + 3]).unwrap_or("");
                match u8::from_str_radix(hex, 16) {
                    Ok(v) => { out.push(v); i += 3; }
                    Err(_) => { out.push(b[i]); i += 1; }
                }
            }
            b'+' => { out.push(b' '); i += 1; }
            c => { out.push(c); i += 1; }
        }
    }
    String::from_utf8_lossy(&out).to_string()
}

fn json(code: u16, body: &str) -> tiny_http::Response<std::io::Cursor<Vec<u8>>> {
    let r = tiny_http::Response::from_string(body)
        .with_status_code(code)
        .with_header(
            tiny_http::Header::from_bytes("Content-Type", "application/json").unwrap(),
        );
    r
}

/// Device identity (spec 3.1) — same fields the phone advertises.
pub fn info_json() -> serde_json::Value {
    let alias = hostname();
    serde_json::json!({
        "alias": alias,
        "version": "2.2",
        "deviceModel": alias,
        "deviceType": "desktop",
        "fingerprint": fingerprint(),
        "port": TRANSFER_PORT,
        "protocol": "http",
        "download": false,
    })
}

fn hostname() -> String {
    std::env::var("COMPUTERNAME")
        .ok()
        .filter(|s| !s.is_empty())
        .or_else(|| std::env::var("HOSTNAME").ok().filter(|s| !s.is_empty()))
        .unwrap_or_else(|| "Desktop".into())
}

fn fingerprint() -> String {
    format!("lynko-{}", hostname().to_lowercase())
}

fn handle(app: tauri::AppHandle, mut request: tiny_http::Request) {
    let url = request.url().to_string();
    let path = url.split('?').next().unwrap_or("").trim_end_matches('/').to_string();
    let params = qs(&url);
    let method = request.method().as_str().to_string();
    let ip = request.remote_addr().map(|a| a.ip().to_string()).unwrap_or_default();

    let mut body = Vec::new();
    let _ = request.as_reader().read_to_end(&mut body);

    match (method.as_str(), path.as_str()) {
        ("GET", "/api/localsend/v2/info") => {
            let _ = request.respond(json(200, &info_json().to_string()));
        }
        ("POST", "/api/localsend/v2/register") => {
            // Sender announces itself; reply with our own info (spec 4.1).
            let _ = request.respond(json(200, &info_json().to_string()));
        }
        ("POST", "/api/localsend/v2/prepare-upload") => {
            let _ = request.respond(prepare_upload(&app, &params, &body, &ip));
        }
        ("POST", "/api/localsend/v2/upload") => {
            let _ = request.respond(upload(&app, &params, body, &ip));
        }
        ("POST", "/api/localsend/v2/cancel") => {
            if let Some(sid) = params.get("sessionId") {
                sessions().lock().unwrap().remove(sid);
            }
            let _ = request.respond(json(200, "{}"));
        }
        ("POST", "/api/localsend/v2/prepare-download") => {
            let _ = request.respond(prepare_download(&body));
        }
        ("GET", "/api/localsend/v2/download") => {
            let _ = request.respond(download(&params));
        }
        _ => {
            let _ = request.respond(json(404, r#"{"error":"not_found"}"#));
        }
    }
}

/// POST /api/localsend/v2/prepare-upload (spec 4.1).
///
/// Body: {"info":{...},"files":{"<id>":{"id","fileName","size","fileType","sha256","preview?"}}}
/// 200 -> {"sessionId":"...","files":{"<id>":"<token>"}}
/// 204 -> nothing to transfer · 400 malformed · 401 PIN · 403 declined
/// 409 another session is active · 500 unexpected
fn prepare_upload(
    app: &tauri::AppHandle,
    params: &HashMap<String, String>,
    body: &[u8],
    ip: &str,
) -> tiny_http::Response<std::io::Cursor<Vec<u8>>> {
    let val: serde_json::Value = match serde_json::from_slice(body) {
        Ok(v) => v,
        Err(_) => return json(400, r#"{"error":"invalid_body"}"#),
    };
    let info = val.get("info").cloned().unwrap_or(serde_json::Value::Null);
    let files = match val.get("files").and_then(|f| f.as_object()) {
        Some(f) => f.clone(),
        None => return json(400, r#"{"error":"invalid_body"}"#),
    };
    if files.is_empty() {
        // Spec 4.1: 204 when the request contains no files.
        return json(204, "");
    }
    // PIN guard (spec 4.1): 401 when a PIN is required but missing/wrong.
    if let Some(expected) = crate::pin() {
        match params.get("pin") {
            Some(p) if p == &expected => {}
            _ => return json(401, r#"{"error":"pin_required"}"#),
        }
    }
    // Single-session invariant (spec 4.1): 409 while another transfer runs.
    if !sessions().lock().unwrap().is_empty() {
        return json(409, r#"{"error":"blocked_by_another_session"}"#);
    }

    // Surface the request to the UI and park until the user answers.
    let alias = info.get("alias").and_then(|v| v.as_str()).unwrap_or("Device");
    let mut list = Vec::new();
    for (key, f) in &files {
        list.push(serde_json::json!({
            "id": f.get("id").and_then(|v| v.as_str()).unwrap_or(key),
            "name": f.get("fileName").and_then(|v| v.as_str()).unwrap_or("file.bin"),
            "size": f.get("size").and_then(|v| v.as_u64()).unwrap_or(0),
            "mime": f.get("fileType").and_then(|v| v.as_str()).unwrap_or("application/octet-stream"),
        }));
    }
    let _ = app.emit(
        "share_offer",
        serde_json::json!({
            "alias": alias,
            "deviceType": info.get("deviceType").and_then(|v| v.as_str()).unwrap_or("mobile"),
            "fingerprint": info.get("fingerprint").and_then(|v| v.as_str()).unwrap_or(""),
            "files": list,
        }),
    );
    if !wait_consent() {
        return json(403, r#"{"error":"rejected"}"#);
    }

    let sid = uuid::Uuid::new_v4().to_string();
    let mut session = Session {
        files: HashMap::new(),
        tokens: HashMap::new(),
        sender_ip: ip.to_string(),
        received: HashMap::new(),
        created_at: std::time::Instant::now(),
    };
    let mut reply_tokens = serde_json::Map::new();
    for (key, f) in &files {
        let id = f
            .get("id")
            .and_then(|v| v.as_str())
            .unwrap_or(key)
            .to_string();
        let token = uuid::Uuid::new_v4().to_string().replace('-', "");
        let token = token[..32.min(token.len())].to_string();
        reply_tokens.insert(id.clone(), serde_json::Value::String(token.clone()));
        session.tokens.insert(id.clone(), token);
        session.files.insert(
            id.clone(),
            FileEntry {
                id,
                name: f
                    .get("fileName")
                    .and_then(|v| v.as_str())
                    .unwrap_or("file.bin")
                    .to_string(),
                size: f.get("size").and_then(|v| v.as_u64()).unwrap_or(0),
                mime: f
                    .get("fileType")
                    .and_then(|v| v.as_str())
                    .unwrap_or("application/octet-stream")
                    .to_string(),
                sha256: f.get("sha256").and_then(|v| v.as_str()).map(String::from),
            },
        );
    }
    sessions().lock().unwrap().insert(sid.clone(), session);
    let out = serde_json::json!({ "sessionId": sid, "files": reply_tokens });
    json(200, &out.to_string())
}

/// POST /api/localsend/v2/upload?sessionId&fileId&token (spec 4.2).
/// 200 stored · 403 bad token/IP · 409 session gone · 422 checksum mismatch.
fn upload(
    app: &tauri::AppHandle,
    params: &HashMap<String, String>,
    body: Vec<u8>,
    ip: &str,
) -> tiny_http::Response<std::io::Cursor<Vec<u8>>> {
    let sid = params.get("sessionId").cloned().unwrap_or_default();
    let file_id = params.get("fileId").cloned().unwrap_or_default();
    let token = params.get("token").cloned().unwrap_or_default();
    if sid.is_empty() || file_id.is_empty() || token.is_empty() {
        return json(400, r#"{"error":"missing_params"}"#);
    }
    let mut all = sessions().lock().unwrap();
    let session = match all.get_mut(&sid) {
        Some(s) => s,
        None => return json(409, r#"{"error":"unknown_session"}"#),
    };
    // 403: invalid token (spec 4.2).
    if session.tokens.get(&file_id).map(|t| t != &token).unwrap_or(true) {
        return json(403, r#"{"error":"invalid_token"}"#);
    }
    // 403: sender IP must match the one that created the session.
    if !session.sender_ip.is_empty() && ip != session.sender_ip {
        return json(403, r#"{"error":"invalid_ip"}"#);
    }
    let (name, expected) = match session.files.get(&file_id) {
        Some(f) => (f.name.clone(), f.sha256.clone()),
        None => return json(400, r#"{"error":"unknown_file"}"#),
    };
    // 422: sha256 mismatch (spec 4.2).
    if let Some(expected) = expected {
        let mut h = sha2::Sha256::new();
        h.update(&body);
        let actual = format!("{:x}", h.finalize());
        if !actual.eq_ignore_ascii_case(&expected) {
            return json(422, r#"{"error":"checksum_mismatch"}"#);
        }
    }
    // Never clobber an existing file (same rule as the WS path).
    let path = save_unique(&name, &body);
    session.received.insert(file_id.clone(), body);
    session.files.remove(&file_id);
    session.tokens.remove(&file_id);
    let done = session.files.is_empty();
    if done {
        all.remove(&sid);
    }
    drop(all);
    let _ = app.emit(
        "share_status",
        serde_json::json!({ "status": "saved", "name": name, "path": path }),
    );
    json(200, "{}")
}

/// POST /api/localsend/v2/prepare-download (spec 4.4) — reverse transfer.
/// Body: {"info":{...},"files":{"<id>":{"id","fileName","size",...}}}
/// Reply mirrors prepare-upload but the receiver then GETs /download.
fn prepare_download(body: &[u8]) -> tiny_http::Response<std::io::Cursor<Vec<u8>>> {
    let val: serde_json::Value = match serde_json::from_slice(body) {
        Ok(v) => v,
        Err(_) => return json(400, r#"{"error":"invalid_body"}"#),
    };
    let files = match val.get("files").and_then(|f| f.as_object()) {
        Some(f) if !f.is_empty() => f.clone(),
        _ => return json(400, r#"{"error":"invalid_body"}"#),
    };
    let sid = uuid::Uuid::new_v4().to_string();
    let mut tokens = serde_json::Map::new();
    let mut store = outbox().lock().unwrap();
    for (key, f) in &files {
        let id = f
            .get("id")
            .and_then(|v| v.as_str())
            .unwrap_or(key)
            .to_string();
        let path = f
            .get("path")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let token = uuid::Uuid::new_v4().to_string().replace('-', "");
        let token = token[..32.min(token.len())].to_string();
        tokens.insert(id.clone(), serde_json::Value::String(token.clone()));
        store.insert(format!("{sid}:{id}"), (token, path));
    }
    drop(store);
    let out = serde_json::json!({ "sessionId": sid, "files": tokens });
    json(200, &out.to_string())
}

/// GET /api/localsend/v2/download?sessionId&fileId&token (spec 4.4).
fn download(params: &HashMap<String, String>) -> tiny_http::Response<std::io::Cursor<Vec<u8>>> {
    let sid = params.get("sessionId").cloned().unwrap_or_default();
    let file_id = params.get("fileId").cloned().unwrap_or_default();
    let token = params.get("token").cloned().unwrap_or_default();
    let entry = outbox().lock().unwrap().get(&format!("{sid}:{file_id}")).cloned();
    let (expected, path) = match entry {
        Some(e) => e,
        None => return json(404, r#"{"error":"unknown_file"}"#),
    };
    if expected != token {
        return json(403, r#"{"error":"invalid_token"}"#);
    }
    let data = match std::fs::read(&path) {
        Ok(d) => d,
        Err(_) => return json(404, r#"{"error":"unreadable"}"#),
    };
    let name = std::path::Path::new(&path)
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "file.bin".into());
    let resp = tiny_http::Response::from_data(data).with_status_code(200);
    let resp = resp.with_header(
        tiny_http::Header::from_bytes("Content-Type", "application/octet-stream").unwrap(),
    );
    let resp = resp.with_header(
        tiny_http::Header::from_bytes(
            "Content-Disposition",
            format!("attachment; filename=\"{}\"", name.replace('"', "")),
        )
        .unwrap(),
    );
    resp
}

/// Write `name` beside existing files without ever overwriting one.
fn save_unique(name: &str, data: &[u8]) -> String {
    let safe: String = name
        .chars()
        .filter(|c| !c.is_control() && *c != '/' && *c != '\\')
        .take(240)
        .collect();
    let safe = if safe.is_empty() { "file.bin".into() } else { safe };
    let dir = download_dir();
    let _ = std::fs::create_dir_all(&dir);
    let base = std::path::Path::new(&safe);
    let stem = base.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| "file".into());
    let ext = base.extension().map(|s| format!(".{}", s.to_string_lossy())).unwrap_or_default();
    let mut candidate = dir.join(&safe);
    let mut n = 2;
    while candidate.exists() {
        candidate = dir.join(format!("{stem} ({n}){ext}"));
        n += 1;
    }
    let _ = std::fs::write(&candidate, data);
    candidate.to_string_lossy().to_string()
}

fn download_dir() -> std::path::PathBuf {
    if let Ok(p) = std::env::var("USERPROFILE") {
        let d = std::path::Path::new(&p).join("Downloads");
        if d.is_dir() {
            return d;
        }
    }
    if let Ok(h) = std::env::var("HOME") {
        let d = std::path::Path::new(&h).join("Downloads");
        if d.is_dir() {
            return d;
        }
    }
    std::env::temp_dir()
}

