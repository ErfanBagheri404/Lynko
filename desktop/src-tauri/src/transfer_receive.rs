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
//!
//! # Byte-offset resume (Lynko extension, beyond the spec)
//!
//! Incoming files stage under `<dest>/.lynko-incoming/<transfer-key>/<file>.part`
//! where `<transfer-key>` hashes (sender fingerprint, name, size) — stable
//! across retry attempts and desktop restarts, so a retry of the same file
//! resumes instead of restarting. Same directory as the final name keeps the
//! completion rename same-volume, i.e. atomic.
//!
//! Two resume wire forms, both additive, old senders keep working:
//! * prepare-upload reply gains a top-level "have" map: {fileId: offset}.
//!   A resume-aware sender seeks and uploads `?offset=<have>` (body = file[have..]).
//! * Legacy senders (current phone) re-POST the WHOLE file with no `offset`.
//!   The receiver consume-and-discards the first `have` bytes of that body and
//!   appends only the tail — network cost, zero wasted trust.
//!
//! Completion is always digest-gated: the declared sha256 is checked over the
//! FULL staged file before the rename out of `.part`. Checksum mismatch drops
//! the stage; a mid-body socket drop KEEPS it (that is what makes the next
//! attempt a resume). Stages untouched for 7 days are swept at server start.

use sha2::Digest;
use std::collections::HashMap;
use std::io::{Read, Write};
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
    /// Sender fingerprint (falls back to the IP) — the resume-key identity.
    pub sender_fp: String,
        /// Created-at timestamp for expiry sweep (120s, same as sender timeout).
        pub created_at: std::time::Instant,
        /// fileId in offer order — gives every share_status event a stable index.
        pub order: Vec<String>,
        /// Declared size parallel to `order` (survives files being removed on done).
        pub sizes: Vec<u64>,
        /// [{id,name,size}] in offer order — re-sent on every progress event.
        pub list: Vec<serde_json::Value>,
        /// Files that failed (checksum/abort) — feeds the "finished" errorCount.
        pub failed_count: usize,
        /// Per-file bytes written so far, parallel to `order`.
        pub written: Vec<u64>,
    }

pub struct FileEntry {
    pub id: String,
    pub name: String,
    pub size: u64,
    pub mime: String,
    pub sha256: Option<String>,
    /// Deterministic resume key: hash(sender fingerprint, name, size). Stored
    /// here so prepare-upload and upload agree on the same `.part` even when
    /// the upload endpoint can only see the sender IP.
    pub part_key: String,
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
        // Sweep abandoned stages before listening (once per launch; anything
        // older than 7 days was never resumed and never will be).
        purge_stale_parts(&download_dir(), std::time::Duration::from_secs(7 * 24 * 3600));
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

    match (method.as_str(), path.as_str()) {
        ("GET", "/api/localsend/v2/info") => {
            let _ = request.respond(json(200, &info_json().to_string()));
        }
        ("POST", "/api/localsend/v2/register") => {
            // Sender announces itself; reply with our own info (spec 4.1).
            let _ = request.respond(json(200, &info_json().to_string()));
        }
        ("POST", "/api/localsend/v2/prepare-upload") => {
            let _ = request.as_reader().read_to_end(&mut body);
            let _ = request.respond(prepare_upload(&app, &params, &body, &ip));
        }
        ("POST", "/api/localsend/v2/upload") => {
            // NOT pre-read: upload() streams the body straight off the socket
            // so a large photo never lands in memory and progress is real.
            // Build the response first — respond() consumes the request.
            let resp = upload(&app, &params, &mut request, &ip);
            let _ = request.respond(resp);
        }
        ("POST", "/api/localsend/v2/cancel") => {
            if let Some(sid) = params.get("sessionId") {
                sessions().lock().unwrap().remove(sid);
                // Mirrors LocalSend canceledBySender — the UI shows it on the
                // progress panel instead of leaving a stuck bar.
                let _ = app.emit(
                    "share_status",
                    serde_json::json!({ "status": "canceledBySender", "sessionId": sid }),
                );
            }
            let _ = request.respond(json(200, "{}"));
        }
        ("POST", "/api/localsend/v2/prepare-download") => {
            let _ = request.as_reader().read_to_end(&mut body);
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
    // Who is calling: fingerprint is stable across retries and restarts, the
    // IP is only a fallback (DHCP/VPN can move it mid-session).
    let sender_key = info
        .get("fingerprint")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| ip.to_string());
    // PIN guard (spec 4.1): 401 when a PIN is required but missing/wrong.
    if let Some(expected) = crate::pin() {
        match params.get("pin") {
            Some(p) if p == &expected => {}
            _ => return json(401, r#"{"error":"pin_required"}"#),
        }
    }
    // Single-session invariant (spec 4.1): 409 while another transfer runs.
    // A retry by the SAME phone is not another transfer — a dropped socket or
    // a restarted app never got to POST /cancel, so its stale session would
    // block the resume for 120 s. Drop it and let the resume through.
    {
        let mut all = sessions().lock().unwrap();
        if !all.is_empty() {
            if all.values().all(|s| s.sender_fp == sender_key) {
                all.clear();
            } else {
                return json(409, r#"{"error":"blocked_by_another_session"}"#);
            }
        }
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
    // HashMap iteration is random; sort so both sides see picker order.
    list.sort_by_key(|f| f["name"].as_str().unwrap_or("").to_string());
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
    let order: Vec<String> = list.iter().map(|f| f["id"].as_str().unwrap_or("").to_string()).collect();
    let sizes: Vec<u64> = list.iter().map(|f| f["size"].as_u64().unwrap_or(0)).collect();
    let written = vec![0u64; order.len()];
    let mut session = Session {
        files: HashMap::new(),
        tokens: HashMap::new(),
        sender_ip: ip.to_string(),
        sender_fp: sender_key.clone(),
        created_at: std::time::Instant::now(),
        order,
        sizes,
        list: list.clone(),
        failed_count: 0,
        written,
    };
    let mut reply_tokens = serde_json::Map::new();
    // fileId -> bytes already staged. Non-empty only when a previous attempt of
    // the same file died mid-flight; resume-aware senders seek to it.
    let mut reply_have = serde_json::Map::new();
    for (key, f) in &files {
        let id = f
            .get("id")
            .and_then(|v| v.as_str())
            .unwrap_or(key)
            .to_string();
        let name = f
            .get("fileName")
            .and_then(|v| v.as_str())
            .unwrap_or("file.bin")
            .to_string();
        let size = f.get("size").and_then(|v| v.as_u64()).unwrap_or(0);
        let part_key = transfer_key(&sender_key, &name, size);
        let have = staged_have(&download_dir(), &part_key, &name, size);
        if have > 0 {
            reply_have.insert(id.clone(), serde_json::Value::from(have));
        }
        let token = uuid::Uuid::new_v4().to_string().replace('-', "");
        let token = token[..32.min(token.len())].to_string();
        reply_tokens.insert(id.clone(), serde_json::Value::String(token.clone()));
        session.tokens.insert(id.clone(), token);
        session.files.insert(
            id.clone(),
            FileEntry {
                id,
                name,
                size,
                mime: f
                    .get("fileType")
                    .and_then(|v| v.as_str())
                    .unwrap_or("application/octet-stream")
                    .to_string(),
                sha256: f.get("sha256").and_then(|v| v.as_str()).map(String::from),
                part_key,
            },
        );
    }
    sessions().lock().unwrap().insert(sid.clone(), session);
    let _ = app.emit(
        "share_status",
        serde_json::json!({
            "status": "accepted",
            "sessionId": sid,
            "alias": alias,
            "count": list.len(),
            "files": list,
        }),
    );
    let mut out = serde_json::json!({ "sessionId": sid, "files": reply_tokens });
    if !reply_have.is_empty() {
        // Lynko extension: how many bytes the receiver already holds per file.
        out["have"] = serde_json::Value::Object(reply_have);
    }
    json(200, &out.to_string())
}

/// Why a stream could not be stored. Maps 1:1 onto the LocalSend status codes
/// the upload endpoint must answer with.
#[derive(Debug, PartialEq)]
enum StoreError {
    /// Sender aborted mid-body. The `.part` STAGE IS KEPT — that is the fuel
    /// for the next attempt's byte-offset resume.
    Aborted,
    /// Declared sha256 does not match the assembled bytes; the stage is
    /// dropped (a corrupt prefix would poison every later resume).
    Checksum,
    /// Zero bytes arrived and nothing was staged: no file, no empty file.
    Empty,
    /// The assembled size does not match the declared size (body ended early
    /// or the sender's offset claimed ahead of our stage). Stage kept.
    Size,
    /// Local I/O failure. Stage kept.
    Io(String),
}

impl StoreError {
    fn status(&self) -> u16 {
        match self {
            StoreError::Aborted | StoreError::Empty | StoreError::Size => 400,
            StoreError::Checksum => 422,
            StoreError::Io(_) => 500,
        }
    }
    fn code(&self) -> String {
        match self {
            StoreError::Aborted => r#"{"error":"read_failed"}"#.into(),
            StoreError::Checksum => r#"{"error":"checksum_mismatch"}"#.into(),
            StoreError::Empty => r#"{"error":"empty_file"}"#.into(),
            StoreError::Size => r#"{"error":"size_mismatch"}"#.into(),
            StoreError::Io(e) => format!(r#"{{"error":"{e}"}}"#),
        }
    }
}

/// Sanitized file name shared by the staging path and the final name — the
/// phone controls this string, so control chars and separators never survive.
fn safe_name(name: &str) -> String {
    let safe: String = name
        .chars()
        .filter(|c| !c.is_control() && *c != '/' && *c != '\\')
        .take(240)
        .collect();
    if safe.is_empty() { "file.bin".into() } else { safe }
}

/// Deterministic resume key: hashes (sender identity, name, size) to 16 hex
/// chars. Stable across retries and desktop restarts; distinct senders with
/// the same file land in different stages.
fn transfer_key(sender: &str, name: &str, size: u64) -> String {
    let mut h = sha2::Sha256::new();
    h.update(sender.as_bytes());
    h.update(&[0x1f]);
    h.update(safe_name(name).as_bytes());
    h.update(&[0x1f]);
    h.update(size.to_le_bytes());
    format!("{:x}", h.finalize())[..16].to_string()
}

/// Staging dir for one transfer: `<dest>/.lynko-incoming/<key>/`. Subdirectory
/// of the destination so the completion rename stays same-volume.
fn part_dir(dest: &std::path::Path, key: &str) -> std::path::PathBuf {
    dest.join(".lynko-incoming").join(key)
}

/// The `.part` for one file inside its staging dir.
fn part_path(dest: &std::path::Path, key: &str, name: &str) -> std::path::PathBuf {
    part_dir(dest, key).join(format!("{}.part", safe_name(name)))
}

/// Bytes already staged for (sender, name, size). Clamped: a stage larger than
/// the declared size is corrupt and must not be advertised as progress.
fn staged_have(dest: &std::path::Path, key: &str, name: &str, size: u64) -> u64 {
    part_path(dest, key, name)
        .metadata()
        .map(|m| m.len().min(size))
        .unwrap_or(0)
}

/// Drop staging dirs untouched for `keep` (run once at server start; 7 days —
/// a stage nobody retried in a week is garbage, not a resume candidate).
fn purge_stale_parts(dest: &std::path::Path, keep: std::time::Duration) {
    let root = part_dir(dest, "");
    let Ok(rd) = std::fs::read_dir(&root) else { return };
    for entry in rd.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        if let Ok(files) = std::fs::read_dir(&path) {
            for f in files.flatten() {
                let fp = f.path();
                let stale = std::fs::metadata(&fp)
                    .ok()
                    .and_then(|m| m.modified().ok())
                    .and_then(|t| t.elapsed().ok())
                    .is_some_and(|e| e > keep);
                if stale {
                    let _ = std::fs::remove_file(&fp);
                }
            }
        }
        let _ = std::fs::remove_dir(&path); // only succeeds when empty
    }
    let _ = std::fs::remove_dir(&root);
}

/// sha256 of a staged file, read back from disk.
fn file_sha256(path: &std::path::Path) -> std::io::Result<String> {
    let mut f = std::fs::File::open(path)?;
    let mut h = sha2::Sha256::new();
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(format!("{:x}", h.finalize()))
}

/// Remove a stage and prune its now-empty parents, so a finished or discarded
/// transfer leaves no `.lynko-incoming/<key>/` skeleton behind.
fn discard_stage(part: &std::path::Path) {
    let _ = std::fs::remove_file(part);
    if let Some(root) = part.parent() {
        let _ = std::fs::remove_dir(root);
        if let Some(parent) = root.parent() {
            let _ = std::fs::remove_dir(parent);
        }
    }
}

/// Stream `reader` into the stage for (key, name) and publish only when the
/// assembled file matches `total` AND the declared `expected_sha`.
///
/// `start_offset` is the sender's declared file position for the body:
/// * `0` (legacy sender re-posts the WHOLE file): the first `have` bytes of
///   the body are consumed-and-discarded, the tail appends after our stage.
/// * `> 0` (resume-aware sender, body = file[start_offset..]): our stage must
///   reach that far; the stage tail is truncated and re-received.
///
/// Returns the final absolute path in `dir`. Keeps the stage on Aborted/Size/Io
/// so the next attempt resumes; deletes it on Checksum.
///
/// `on_progress` is called with (staged, total) at most 4×/s so the UI bar
/// stays cheap — and once before streaming starts so a resumed bar never lies
/// about being at zero.
fn store_stream<R: Read>(
    dir: &std::path::Path,
    name: &str,
    key: &str,
    expected_sha: Option<&str>,
    start_offset: u64,
    mut reader: R,
    total: u64,
    mut on_progress: impl FnMut(u64, u64),
) -> Result<std::path::PathBuf, StoreError> {
    let part = part_path(dir, key, name);
    let root = part.parent().unwrap().to_path_buf();
    let mut have = part.metadata().map(|m| m.len()).unwrap_or(0);
    let start = start_offset.min(total);

    // Work out how much of the arriving body is new and where it goes.
    let (skip, pos) = if start > 0 {
        // Sender seeks: it must have learned our `have` from prepare-upload.
        if start > have {
            // Our stage can't reach the sender's offset (stale stage from an
            // older attempt). Drop it — the next prepare advertises have=0 and
            // the sender re-sends from zero, which self-heals.
            discard_stage(&part);
            return Err(StoreError::Size);
        }
        if start < have {
            // Sender re-requests from an earlier offset: truncate our tail and
            // let it re-send those bytes.
            if let Ok(f) = std::fs::OpenOptions::new().write(true).open(&part) {
                let _ = f.set_len(start);
            }
            have = start;
        }
        (0, start)
    } else {
        // Legacy full-file body: consume-and-discard the prefix we already
        // hold, append only the bytes we lack.
        (have, have)
    };

    // Stage dirs appear only when bytes actually arrive — an empty attempt
    // leaves the destination folder exactly as it found it.
    std::fs::create_dir_all(&root).map_err(|e| StoreError::Io(e.to_string()))?;
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .open(&part)
        .map_err(|e| StoreError::Io(e.to_string()))?;
    use std::io::Seek;
    file.seek(std::io::SeekFrom::Start(pos))
        .map_err(|e| StoreError::Io(e.to_string()))?;

    let mut buf = vec![0u8; 256 * 1024];
    on_progress(have, total);
    let mut last_emit = std::time::Instant::now();
    // Consume-and-discard the bytes we already hold (legacy path). A drop
    // here still keeps the stage untouched — nothing new was lost. Skip
    // EXACTLY `skip` bytes — overshooting a 256 KB buffer would discard real
    // body bytes with them.
    let mut skipped = 0u64;
    while skipped < skip {
        let take = (skip - skipped).min(buf.len() as u64) as usize;
        let n = match reader.read(&mut buf[..take]) {
            Ok(0) => break,
            Ok(n) => n,
            Err(_) => return Err(StoreError::Aborted),
        };
        skipped += n as u64;
    }
    let mut appended = 0u64;
    loop {
        let n = match reader.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => n,
            // Socket dropped mid-body: keep the stage, report the drop.
            Err(_) => return Err(StoreError::Aborted),
        };
        if let Err(e) = file.write_all(&buf[..n]) {
            return Err(StoreError::Io(e.to_string()));
        }
        appended += n as u64;
        if last_emit.elapsed().as_millis() > 250 {
            last_emit = std::time::Instant::now();
            on_progress(have + appended, total);
        }
    }

    if have + appended == 0 {
        drop(file);
        discard_stage(&part);
        return Err(StoreError::Empty);
    }
    if have + appended != total {
        // Early EOF or overrun: stage kept, next attempt resumes from `have`.
        return Err(StoreError::Size);
    }
    // Digest gate: hash the FULL assembled file, resumed prefix included.
    drop(file);
    if let Some(expected) = expected_sha {
        let actual = file_sha256(&part).map_err(|e| StoreError::Io(e.to_string()))?;
        if !actual.eq_ignore_ascii_case(expected) {
            discard_stage(&part);
            return Err(StoreError::Checksum);
        }
    }
    let final_path = unique_path(dir, name);
    std::fs::rename(&part, &final_path).map_err(|e| {
        discard_stage(&part);
        StoreError::Io(e.to_string())
    })?;
    // The stage is empty now; prune the skeleton so Downloads stays clean.
    discard_stage(&part);
    on_progress(total, total);
    Ok(final_path)
}

/// POST /api/localsend/v2/upload?sessionId&fileId&token[&offset] (spec 4.2).
/// 200 stored · 403 bad token/IP · 409 session gone · 422 checksum mismatch.
///
/// The body is streamed into a staged `.part` under the destination and hashed
/// as it arrives, so a multi-MB photo never sits in memory.
///
/// Resume semantics (Lynko extension): a resume-aware sender appends
/// `&offset=<n>` saying the body starts at byte n of the file — the receiver
/// seeks its stage to n and continues there. A legacy sender sends the whole
/// file with no offset — the receiver keeps what it holds and discards the
/// matching prefix of the arriving body. Either way the declared sha256 is
/// re-verified over the assembled file before publication.
fn upload(
    app: &tauri::AppHandle,
    params: &HashMap<String, String>,
    request: &mut tiny_http::Request,
    ip: &str,
) -> tiny_http::Response<std::io::Cursor<Vec<u8>>> {
    let sid = params.get("sessionId").cloned().unwrap_or_default();
    let file_id = params.get("fileId").cloned().unwrap_or_default();
    let token = params.get("token").cloned().unwrap_or_default();
    if sid.is_empty() || file_id.is_empty() || token.is_empty() {
        return json(400, r#"{"error":"missing_params"}"#);
    }
    let start_offset: u64 = params.get("offset").and_then(|v| v.parse().ok()).unwrap_or(0);
    // Validate the session/token/ip BEFORE reading the body: a rejected
    // transfer must not cost us the upload.
    let (name, expected, size, part_key) = {
        let all = sessions().lock().unwrap();
        let session = match all.get(&sid) {
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
        match session.files.get(&file_id) {
            Some(f) => (f.name.clone(), f.sha256.clone(), f.size, f.part_key.clone()),
            None => return json(400, r#"{"error":"unknown_file"}"#),
        }
    };

    let dir = download_dir();
        let sid2 = sid.clone();
        let name2 = name.clone();
        let file_id2 = file_id.clone();
        let mut last = std::time::Instant::now() - std::time::Duration::from_secs(1);
        let store = store_stream(
            &dir,
            &name,
            &part_key,
            expected.as_deref(),
            start_offset,
            request.as_reader(),
            size,
            move |written, total| {
                // Aggregate for the "Total" row, throttled to ≤4/s; per-file
                // written/total rides along so the current file bar can animate.
                let idx = {
                    let mut all = sessions().lock().unwrap();
                    let Some(s) = all.get_mut(&sid2) else { return };
                    let Some(i) = s.order.iter().position(|id| id == &file_id2) else { return };
                    if let Some(w) = s.written.get_mut(i) {
                        *w = written;
                    }
                    i
                };
                let now = std::time::Instant::now();
                if now.duration_since(last) < std::time::Duration::from_millis(250) {
                    return;
                }
                last = now;
                let (total_written, total_size, count, files) = {
                    let all = sessions().lock().unwrap();
                    let Some(s) = all.get(&sid2) else { return };
                    let tw: u64 = s.written.iter().sum();
                    (tw, s.sizes.iter().sum::<u64>(), s.order.len(), s.list.clone())
                };
                let _ = app.emit(
                    "share_status",
                    serde_json::json!({
                        "status": "receiving", "name": name2, "written": written,
                        "size": total, "sessionId": sid2, "fileIndex": idx,
                        "totalWritten": total_written, "totalSize": total_size,
                        "count": count, "files": files,
                    }),
                );
            },
        );
        let final_path = match store {
            Ok(p) => p,
            Err(e) => {
                // Failed file: bump errorCount and re-emit aggregate so the UI
                // can show "Finished with error" without waiting for more.
                let (count, files) = {
                    let mut all = sessions().lock().unwrap();
                    let Some(s) = all.get_mut(&sid) else { return json(e.status(), &e.code()) };
                    s.failed_count += 1;
                    (s.order.len(), s.list.clone())
                };
                let _ = app.emit(
                    "share_status",
                    serde_json::json!({
                        "status": "failed", "error": e.code(), "name": name,
                        "sessionId": sid, "count": count, "files": files,
                    }),
                );
                return json(e.status(), &e.code());
            }
        };
        let path = final_path.to_string_lossy().to_string();
        let done = {
            let mut all = sessions().lock().unwrap();
            if let Some(session) = all.get_mut(&sid) {
                session.files.remove(&file_id);
                session.tokens.remove(&file_id);
            }
            all.is_empty()
        };
        let (total_written, total_size, count, files) = sessions()
            .lock()
            .unwrap()
            .get(&sid)
            .map(|s| {
                (
                    s.written.iter().sum::<u64>(),
                    s.sizes.iter().sum::<u64>(),
                    s.order.len(),
                    s.list.clone(),
                )
            })
            .unwrap_or_default();
        if done {
            // Whole session complete — record history entry.
            history_add(HistoryEntry {
                direction: "incoming".into(),
                name: list_name(&files),
                files: files.len() as u64,
                size: total_size,
                time: std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_secs())
                    .unwrap_or(0),
                dir: download_dir().to_string_lossy().to_string(),
            });
        }
        let _ = app.emit(
            "share_status",
            serde_json::json!({
                "status": if done { "saved" } else { "receiving" },
                "name": name, "path": path,
                "sessionId": sid, "fileId": file_id, "written": 0, "size": 0,
                "totalWritten": total_written, "totalSize": total_size,
                "count": count, "files": files,
            }),
        );
        json(200, "{}")
    }

    /// Best display name for a file list (mirrors the UI's "x of y" summary).
    fn list_name(files: &[serde_json::Value]) -> String {
        match files.first().and_then(|f| f["name"].as_str()) {
            Some(n) if files.len() <= 1 => n.to_string(),
            Some(n) => format!("{n} (+{})", files.len() - 1),
            None => "files".into(),
        }
    }

    /* ------------------------------------------------------------------ */
    /* receive history — history.json next to pairs.json                  */

    #[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
    pub struct HistoryEntry {
        /// "incoming" (desktop received) or "outgoing" (desktop sent).
        pub direction: String,
        /// Display name: single file name, or "first (+n)".
        pub name: String,
        pub files: u64,
        pub size: u64,
        /// Unix seconds.
        pub time: u64,
        /// Directory the files were saved to (incoming) or picked from.
        pub dir: String,
    }

    static HISTORY_PATH: OnceLock<std::path::PathBuf> = OnceLock::new();

    /// Called once at startup so history_add needs no AppHandle.
    pub fn init_history(path: std::path::PathBuf) {
        let _ = HISTORY_PATH.set(path);
    }

    fn history_add(entry: HistoryEntry) {
        let Some(path) = HISTORY_PATH.get() else { return };
        let mut list: Vec<HistoryEntry> = std::fs::read_to_string(path)
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        list.insert(0, entry);
        list.truncate(200);
        if let Ok(json) = serde_json::to_string_pretty(&list) {
            let _ = std::fs::create_dir_all(path.parent().unwrap());
            let _ = std::fs::write(path, json);
        }
    }

    /// Tauri command: the receive-history list for the History panel.
    #[tauri::command]
    pub fn history_list() -> Vec<HistoryEntry> {
        HISTORY_PATH
            .get()
            .and_then(|p| std::fs::read_to_string(p).ok())
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default()
    }

    /// Tauri command: wipe the receive history.
    #[tauri::command]
    pub fn history_clear() {
        if let Some(p) = HISTORY_PATH.get() {
            let _ = std::fs::write(p, "[]");
        }
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

/// Absolute target for `name` inside `dir`, never clobbering an existing file
/// (appends " (2)", " (3)", … like the desktop Downloads folder does).
fn unique_path(dir: &std::path::Path, name: &str) -> std::path::PathBuf {
    let safe: String = name
        .chars()
        .filter(|c| !c.is_control() && *c != '/' && *c != '\\')
        .take(240)
        .collect();
    let safe = if safe.is_empty() { "file.bin".into() } else { safe };
    let base = std::path::Path::new(&safe);
    let stem = base
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "file".into());
    let ext = base
        .extension()
        .map(|s| format!(".{}", s.to_string_lossy()))
        .unwrap_or_default();
    let mut candidate = dir.join(&safe);
    let mut n = 2;
    while candidate.exists() {
        candidate = dir.join(format!("{stem} ({n}){ext}"));
        n += 1;
    }
    candidate
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

#[cfg(test)]
mod tests {
    use super::*;

    /// unique_path is the "never clobber" rule the upload path depends on: a
    /// second transfer of the same name must not overwrite the first.
    #[test]
    fn unique_path_never_reuses_an_existing_name() {
        let dir = std::env::temp_dir().join(format!("lynko-unique-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let first = unique_path(&dir, "photo.jpg");
        assert_eq!(first.file_name().unwrap(), "photo.jpg");
        std::fs::write(&first, b"first").unwrap();
        let second = unique_path(&dir, "photo.jpg");
        assert_eq!(second.file_name().unwrap(), "photo (2).jpg");
        assert_ne!(first, second);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A hostile name (path traversal, control chars) must land INSIDE dir
    /// under a sanitized name — the phone controls this string.
    #[test]
    fn unique_path_sanitizes_traversal_and_control_chars() {
        let dir = std::env::temp_dir().join(format!("lynko-safe-{}", uuid::Uuid::new_v4()));
        let p = unique_path(&dir, "../../evil\u{0}.txt");
        assert_eq!(p.parent().unwrap(), dir);
        let name = p.file_name().unwrap().to_string_lossy().to_string();
        assert!(!name.contains('/') && !name.contains('\\'));
        assert!(!name.contains('\u{0}'));
    }

    fn scratch(tag: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("lynko-store-{tag}-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }
    fn leftovers(dir: &std::path::Path) -> Vec<String> {
        let mut out = Vec::new();
        let Ok(rd) = std::fs::read_dir(dir) else { return out };
        for entry in rd.flatten() {
            let p = entry.path();
            if p.is_dir() {
                out.extend(leftovers(&p));
            } else {
                let name = p.file_name().unwrap_or_default().to_string_lossy().to_string();
                if name.ends_with(".part") {
                    out.push(name);
                }
            }
        }
        out
    }

    fn data(tag: u8, len: usize) -> Vec<u8> {
        (0..len).map(|i| (i as u8).wrapping_mul(31).wrapping_add(tag)).collect()
    }

    /// The happy path: bytes land under the real name, contents match, the
    /// final progress callback always fires so the bar can reach 100%, and no
    /// stage is left behind.
    #[test]
    fn store_stream_writes_verified_bytes_and_ends_at_full() {
        let dir = scratch("ok");
        let data = data(3, 300_000);
        let sha = format!("{:x}", sha2::Sha256::digest(&data));
        let mut seen: Vec<(u64, u64)> = Vec::new();
        let path = store_stream(
            &dir, "photo.jpg", "k", Some(&sha), 0, data.as_slice(), data.len() as u64,
            |w, t| seen.push((w, t)),
        ).unwrap();
        assert_eq!(path.file_name().unwrap(), "photo.jpg");
        assert_eq!(std::fs::read(&path).unwrap(), data);
        assert_eq!(*seen.first().unwrap(), (0, data.len() as u64));
        assert_eq!(*seen.last().unwrap(), (data.len() as u64, data.len() as u64));
        assert!(leftovers(&dir).is_empty(), "stage left behind");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// 422 case: a wrong declared hash must NOT promote the stage, and
    /// Downloads must be left empty rather than holding an unverified photo.
    #[test]
    fn store_stream_refuses_checksum_mismatch_and_leaves_nothing() {
        let dir = scratch("bad");
        let data = b"lynko share sheet".repeat(64);
        let err = store_stream(&dir, "photo.jpg", "k", Some(&"0".repeat(64)), 0,
            data.as_slice(), data.len() as u64, |_, _| {}).unwrap_err();
        assert_eq!(err, StoreError::Checksum);
        assert_eq!(err.status(), 422);
        let entries: Vec<_> = std::fs::read_dir(&dir).unwrap().map(|e| e.unwrap().file_name()).collect();
        assert!(entries.is_empty(), "mismatched upload left files: {entries:?}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The point of the feature: a dropped connection must KEEP the stage, and
    /// the next offer of the same file must resume from that offset — not
    /// restart from zero — while the final digest still covers the assembled
    /// whole.
    #[test]
    fn store_stream_abort_keeps_the_stage_and_the_next_offer_resumes() {
        let dir = scratch("resume");
        let data = data(7, 512 * 1024);
        let sha = format!("{:x}", sha2::Sha256::digest(&data));
        // 1) sender dies after 100 KB.
        let mut r = std::io::Cursor::new(data.clone());
        let mut flaky = FlakyReader { inner: &mut r, left: 100 * 1024 };
        let err = store_stream(&dir, "movie.mp4", "k", Some(&sha), 0, &mut flaky,
            data.len() as u64, |_, _| {}).unwrap_err();
        assert_eq!(err, StoreError::Aborted);
        assert_eq!(leftovers(&dir), vec!["movie.mp4.part".to_string()], "stage must survive");
        // 2) legacy sender re-posts the WHOLE file — receiver discards the
        //    prefix it already holds and appends only the tail.
        let path = store_stream(&dir, "movie.mp4", "k", Some(&sha), 0, data.as_slice(),
            data.len() as u64, |_, _| {}).unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), data, "resumed file must be identical");
        assert!(leftovers(&dir).is_empty(), "published file left a stage");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Resume-aware sender form: it reads `have` from the prepare-upload
    /// reply and uploads `?offset=have` with only the remaining tail.
    #[test]
    fn store_stream_resumes_from_a_sender_declared_offset() {
        let dir = scratch("offset");
        let data = data(9, 128 * 1024);
        let sha = format!("{:x}", sha2::Sha256::digest(&data));
        // stage 48 KB via an aborted first attempt
        let mut r = std::io::Cursor::new(data.clone());
        let mut flaky = FlakyReader { inner: &mut r, left: 48 * 1024 };
        let _ = store_stream(&dir, "clip.mp4", "k", Some(&sha), 0, &mut flaky,
            data.len() as u64, |_, _| {}).unwrap_err();
        // sender seeks: offset=48 KB, body is the tail only
        let path = store_stream(&dir, "clip.mp4", "k", Some(&sha), 48 * 1024,
            &data[48 * 1024..], data.len() as u64, |_, _| {}).unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), data);
        assert!(leftovers(&dir).is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A short body keeps the stage and reports the gap; each retry makes
    /// monotone progress instead of restarting at zero.
    #[test]
    fn store_stream_short_body_keeps_the_stage_and_progress_is_monotone() {
        let dir = scratch("short");
        let data = data(11, 200 * 1024);
        let sha = format!("{:x}", sha2::Sha256::digest(&data));
        // attempt 1: dies at 64 KB
        let mut r = std::io::Cursor::new(data.clone());
        let mut flaky = FlakyReader { inner: &mut r, left: 64 * 1024 };
        assert_eq!(
            store_stream(&dir, "doc.bin", "k", Some(&sha), 0, &mut flaky, data.len() as u64, |_, _| {}),
            Err(StoreError::Aborted)
        );
        assert_eq!(leftovers(&dir), vec!["doc.bin.part".to_string()]);
        // attempt 2: only 128 KB of the re-sent body arrive — receiver keeps
        // what it staged and asks for the rest (body ends early: Size, not a
        // socket error, so this is a plain slice reader, not FlakyReader).
        assert_eq!(
            store_stream(&dir, "doc.bin", "k", Some(&sha), 0, &data[..128 * 1024], data.len() as u64, |_, _| {}),
            Err(StoreError::Size)
        );
        assert_eq!(leftovers(&dir), vec!["doc.bin.part".to_string()]);
        // attempt 3: full body completes it, digest over the whole file.
        let path = store_stream(&dir, "doc.bin", "k", Some(&sha), 0, data.as_slice(),
            data.len() as u64, |_, _| {}).unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), data);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Zero-byte transfer is rejected rather than creating an empty file.
    #[test]
    fn store_stream_rejects_empty_body() {
        let dir = scratch("empty");
        let err = store_stream(&dir, "note.txt", "k", None, 0, &b""[..], 0, |_, _| {}).unwrap_err();
        assert_eq!(err, StoreError::Empty);
        assert!(std::fs::read_dir(&dir).unwrap().next().is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Two transfers of the same name both survive, in picker order.
    #[test]
    fn store_stream_second_same_name_does_not_clobber_the_first() {
        let dir = scratch("dup");
        let a = store_stream(&dir, "doc.pdf", "k", None, 0, &b"first"[..], 5, |_, _| {}).unwrap();
        let b = store_stream(&dir, "doc.pdf", "k", None, 0, &b"second"[..], 6, |_, _| {}).unwrap();
        assert_eq!(std::fs::read(&a).unwrap(), b"first");
        assert_eq!(std::fs::read(&b).unwrap(), b"second");
        assert_eq!(b.file_name().unwrap(), "doc (2).pdf");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// An offset ahead of what we hold means our stage is stale: it is dropped
    /// so the next prepare advertises have=0 and the sender restarts cleanly.
    #[test]
    fn store_stream_offset_ahead_of_stage_drops_it() {
        let dir = scratch("gap");
        let data = data(13, 32 * 1024);
        let sha = format!("{:x}", sha2::Sha256::digest(&data));
        let err = store_stream(&dir, "clip.bin", "k", Some(&sha), 8 * 1024,
            &data[8 * 1024..], data.len() as u64, |_, _| {}).unwrap_err();
        assert_eq!(err, StoreError::Size);
        assert!(leftovers(&dir).is_empty(), "stale stage must be dropped");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The resume key is stable for one sender+file and distinct otherwise —
    /// the contract every .part lookup rests on.
    #[test]
    fn transfer_key_is_stable_per_sender_and_file() {
        let a = transfer_key("lynko-abc", "photo.jpg", 1024);
        assert_eq!(a, transfer_key("lynko-abc", "photo.jpg", 1024));
        assert_eq!(a.len(), 16);
        assert_ne!(a, transfer_key("lynko-xyz", "photo.jpg", 1024));
        assert_ne!(a, transfer_key("lynko-abc", "photo2.jpg", 1024));
        assert_ne!(a, transfer_key("lynko-abc", "photo.jpg", 2048));
    }

    /// Reader that serves `left` bytes then fails, like a dropped connection.
    struct FlakyReader<'a> {
        inner: &'a mut std::io::Cursor<Vec<u8>>,
        left: usize,
    }
    impl Read for FlakyReader<'_> {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            if self.left == 0 {
                return Err(std::io::Error::new(std::io::ErrorKind::ConnectionReset, "peer gone"));
            }
            let want = buf.len().min(self.left);
            let n = self.inner.read(&mut buf[..want])?;
            self.left -= n;
            Ok(n)
        }
    }
}

