// Prevent a console window on Windows release builds
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use futures_util::{SinkExt, StreamExt};
use lynko_core::{
    Capabilities, Command, Event, PairRequest, PairResponse,
    LINK_PORT, PAIR_PORT, PROTOCOL_VERSION, SERVICE_TYPE,
};
/// HTTP port of the phone's LocalSend-style transfer server.
const TRANSFER_PORT: u16 = 7914;
use sha2::Digest;

/// Disk-file body stream with an exact size_hint. reqwest sends
/// Content-Length (not chunked TE) when size_hint is exact — the phone's
/// upload reader consumes exactly Content-Length bytes. Emits cumulative
/// `file_progress` per chunk. Fully Unpin: poll_next does blocking reads,
/// which is fine for local disk at 256 KB chunks.
struct FileReadStream {
    file: Option<std::fs::File>,
    done: u64,
    total: u64,
    id: String,
    name: String,
    app: tauri::AppHandle,
}

impl futures_util::Stream for FileReadStream {
    type Item = Result<bytes::Bytes, std::io::Error>;
    fn poll_next(
        mut self: std::pin::Pin<&mut Self>,
        _cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Self::Item>> {
        use std::io::Read;
        let this = &mut *self;
        let Some(f) = this.file.as_mut() else {
            return std::task::Poll::Ready(None);
        };
        let mut buf = vec![0u8; 256 * 1024];
        match f.read(&mut buf) {
            Ok(0) => {
                this.file = None;
                std::task::Poll::Ready(None)
            }
            Ok(n) => {
                buf.truncate(n);
                this.done += n as u64;
                let _ = this.app.emit("file_progress", serde_json::json!({
                    "id": this.id, "name": this.name,
                    "written": this.done, "total": this.total,
                }));
                std::task::Poll::Ready(Some(Ok(buf.into())))
            }
            Err(e) => {
                this.file = None;
                std::task::Poll::Ready(Some(Err(e)))
            }
        }
    }
    fn size_hint(&self) -> (usize, Option<usize>) {
        (self.total as usize, Some(self.total as usize))
    }
}
use mdns_sd::{ServiceDaemon, ServiceEvent};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::mpsc as tokio_mpsc;
use tokio_tungstenite::tungstenite::Message;

/// A phone as the UI sees it: discovered, paired, online/offline.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct Device {
    id: String,
    name: String,
    address: String,
    caps: Capabilities,
    paired: bool,
    online: bool,
}

/// Handle to the live control link (one phone at a time in v1).
struct LinkHandle {
    device_id: String,
    device_name: String,
    device_address: String,
    tx: tokio_mpsc::Sender<Message>,
    alive: Arc<AtomicBool>,
}

struct LynkoState {
    /// live mDNS browser results, keyed by instance id
    discovered: Mutex<HashMap<String, Device>>,
    /// remembered pairings, persisted to pairs.json
    paired: Mutex<HashMap<String, Device>>,
    /// active control link
    link: Mutex<Option<LinkHandle>>,
    app: Mutex<Option<AppHandle>>,
    /// Monotonic link generation counter. Each `connect_inner` bumps it;
    /// every spawned reconnect loop captures the value at spawn time and
    /// stops as soon as it no longer matches — exactly one reconnect chain
    /// per device can ever be alive, no matter how many drops stack up.
    link_gen: AtomicU64,
}

impl LynkoState {
    fn merged(&self) -> Vec<Device> {
        let disc = self.discovered.lock().unwrap();
        let paired = self.paired.lock().unwrap();
        let mut out: HashMap<String, Device> = paired
            .iter()
            .map(|(k, d)| (k.clone(), Device { online: false, ..d.clone() }))
            .collect();
        for (k, d) in disc.iter() {
            let mut d = d.clone();
            if let Some(p) = paired.get(k) {
                d.paired = true;
                d.caps = p.caps.clone();
            }
            out.insert(k.clone(), d);
        }
        out.into_values().collect()
    }

    fn emit_devices(&self) {
        if let Some(app) = self.app.lock().unwrap().as_ref() {
            let _ = app.emit("discovery", &self.merged());
        }
    }
}

fn pairs_path(app: &AppHandle) -> std::path::PathBuf {
    app.path().app_config_dir().unwrap().join("pairs.json")
}

fn load_pairs(app: &AppHandle) -> HashMap<String, Device> {
    let path = pairs_path(app);
    std::fs::read_to_string(&path)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn save_pairs(app: &AppHandle, paired: &HashMap<String, Device>) {
    if let Ok(json) = serde_json::to_string_pretty(paired) {
        let path = pairs_path(app);
        let _ = std::fs::create_dir_all(path.parent().unwrap());
        let _ = std::fs::write(path, json);
    }
}

/* ------------------------------------------------------------------ */
/* discovery — continuous mDNS browser pushing events                  */
/* ------------------------------------------------------------------ */

fn start_discovery(app: AppHandle) {
    std::thread::spawn(move || {
        let state = app.state::<LynkoState>();
        let mdns = match ServiceDaemon::new() {
            Ok(m) => m,
            Err(e) => { eprintln!("mDNS daemon: {e}"); return; }
        };
        let receiver = match mdns.browse(SERVICE_TYPE) {
            Ok(r) => r,
            Err(e) => { eprintln!("mDNS browse: {e}"); return; }
        };
        loop {
            match receiver.recv_timeout(Duration::from_millis(800)) {
                Ok(ServiceEvent::ServiceResolved(info)) => {
                    let name = info
                        .get_property("pretty_name")
                        .map(|p| p.val_str().to_string())
                        .unwrap_or_else(|| info.get_fullname().to_string());
                    let addrs: Vec<_> = info.get_addresses().iter().cloned().collect();
                    // Prefer IPv4 for outbound HTTP/WS — Windows rejects IPv6
                    // link-local zone IDs in URLs even with %25 escaping.
                    let address = addrs
                        .iter()
                        .find(|ip| ip.is_ipv4())
                        .or_else(|| addrs.first())
                        .map(|ip| ip.to_string())
                        .unwrap_or_else(|| "unknown".into());
                    let caps = info
                        .get_property("cap")
                        .map(|p| Capabilities::from_txt(p.val_str()))
                        .unwrap_or_default();
                    let id = info.get_fullname().to_string();
                    state.discovered.lock().unwrap().insert(
                        id.clone(),
                        Device { id, name, address, caps, paired: false, online: true },
                    );
                    state.emit_devices();
                }
                Ok(ServiceEvent::ServiceRemoved(_, fullname)) => {
                    let mut disc = state.discovered.lock().unwrap();
                    if let Some(d) = disc.get_mut(&fullname) { d.online = false; }
                    drop(disc);
                    state.emit_devices();
                }
                Ok(_) => {}
                Err(_) => {}
            }
        }
    });
}

/* ------------------------------------------------------------------ */
/* pairing                                                             */
/* ------------------------------------------------------------------ */

fn ws_host(address: &str) -> String {
    if address.matches(':').count() > 1 {
        format!("[{}]", address.replace('%', "%25"))
    } else {
        address.to_string()
    }
}

fn host_url(address: &str, port: u16, path: &str) -> String {
    format!("http://{}:{}{}", ws_host(address), port, path)
}

#[tauri::command]
async fn pair_device(
    state: State<'_, LynkoState>,
    device_id: String,
    pin: String,
    desktop_name: String,
) -> Result<Device, String> {
    let device = {
        let disc = state.discovered.lock().unwrap();
        disc.get(&device_id).cloned().ok_or("device not found — scan first")?
    };

    let req = PairRequest { protocol_version: PROTOCOL_VERSION, pin, desktop_name };
    let url = host_url(&device.address, PAIR_PORT, "/pair");
    let pair: PairResponse = reqwest::Client::builder()
        .timeout(Duration::from_secs(5))
        .build()
        .map_err(|e| e.to_string())?
        .post(&url)
        .json(&req)
        .send()
        .await
        .map_err(|e| format!("phone unreachable: {e}"))?
        .json()
        .await
        .map_err(|e| e.to_string())?;

    if !pair.ok {
        return Err(pair.error.unwrap_or_else(|| "phone rejected pairing".into()));
    }

    let mut device = device;
    device.paired = true;
    device.caps = pair.capabilities;
    device.name = pair.device_name;

    let app = state.app.lock().unwrap().clone().ok_or("app not ready")?;
    { let mut paired = state.paired.lock().unwrap(); paired.insert(device.id.clone(), device.clone()); save_pairs(&app, &paired); }
    state.emit_devices();
    connect_inner(&state, &device.id).ok();
    Ok(device)
}

#[tauri::command]
fn forget_device(state: State<'_, LynkoState>, device_id: String) -> Result<(), String> {
    let app = state.app.lock().unwrap().clone().ok_or("app not ready")?;
    { let mut paired = state.paired.lock().unwrap(); paired.remove(&device_id); save_pairs(&app, &paired); }
    let mut link = state.link.lock().unwrap();
    if let Some(l) = link.as_ref() {
        if l.device_id == device_id { l.alive.store(false, Ordering::SeqCst); *link = None; }
    }
    state.emit_devices();
    Ok(())
}

/* ------------------------------------------------------------------ */
/* control link — async WebSocket to the phone                         */
/* ------------------------------------------------------------------ */

fn connect_inner(state: &LynkoState, device_id: &str) -> Result<(), String> {
    {
        let link = state.link.lock().unwrap();
        if let Some(l) = link.as_ref() {
            if l.device_id == device_id && l.alive.load(Ordering::SeqCst) { return Ok(()); }
        }
    }

    let device = {
        let merged = state.merged();
        merged.into_iter().find(|d| d.id == device_id).ok_or("unknown device")?
    };
    // mDNS expiry is unreliable (Android dozes multicast) — a paired device
    // that stopped advertising may still be reachable at its cached address.
    // Try the dial anyway; run_link reports the failure if it's really gone.
    if !device.online && !device.paired {
        return Err(format!("{} is offline", device.name));
    }

    let app = state.app.lock().unwrap().clone().ok_or("app not ready")?;
    let (tx, rx) = tokio_mpsc::channel::<Message>(64);
    let alive = Arc::new(AtomicBool::new(true));

    { let mut link = state.link.lock().unwrap(); *link = Some(LinkHandle { device_id: device.id.clone(), device_name: device.name.clone(), device_address: device.address.clone(), tx: tx.clone(), alive: alive.clone() }); }

    let gen = state.link_gen.fetch_add(1, Ordering::SeqCst) + 1;
    let _ = app.emit("log", serde_json::json!({ "msg": format!("link connecting to {} ({})", device.name, device.address) }));

    tauri::async_runtime::spawn(run_link(app.clone(), device, tx, rx, alive.clone(), gen));
    Ok(())
}

async fn run_link(
    app: AppHandle,
    device: Device,
    _tx: tokio_mpsc::Sender<Message>,
    mut rx: tokio_mpsc::Receiver<Message>,
    alive: Arc<AtomicBool>,
    gen: u64,
) {
    use tauri::{Emitter, Manager};

    let url = format!("ws://{}:{}/link", ws_host(&device.address), LINK_PORT);
    let ws_stream = match tokio_tungstenite::connect_async(&url).await {
        Ok((s, _)) => s,
        Err(e) => {
            let _ = app.emit("log", serde_json::json!({ "msg": format!("link failed: {e}") }));
            alive.store(false, Ordering::SeqCst);
            let _ = app.emit("link_state", serde_json::json!({ "connected": false }));
            // Dial failed (e.g. VPN just came up) — retry with backoff instead
            // of silently giving up. The phone's VpnGuard recreates its
            // listeners within ~1s of a network change.
            spawn_reconnect(app.clone(), device.id.clone(), gen);
            return;
        }
    };

    let _ = app.emit("link_state", serde_json::json!({ "connected": true, "device_id": &device.id }));
    let _ = app.emit("log", serde_json::json!({ "msg": format!("link UP to {}", device.name) }));

    let (mut ws_sink, mut ws_src) = ws_stream.split();

    // writer: rx channel → ws_sink
    {
        let alive_w = alive.clone();
        tokio::spawn(async move {
            while alive_w.load(Ordering::SeqCst) {
                match tokio::time::timeout(Duration::from_millis(300), rx.recv()).await {
                    Ok(Some(msg)) => { if ws_sink.send(msg).await.is_err() { break; } }
                    Ok(None) => break,
                    Err(_) => continue,
                }
            }
        });
    }

    // reader: ws_src → Tauri events
    while alive.load(Ordering::SeqCst) {
        match tokio::time::timeout(Duration::from_secs(30), ws_src.next()).await {
            Ok(Some(Ok(Message::Text(txt)))) => {
                if let Ok(ev) = serde_json::from_str::<Event>(&txt) {
                    let _ = app.emit("link_event", &ev);
                }
            }
            Ok(Some(Ok(Message::Binary(bin)))) => {
                use lynko_core::DecodedFrame;
                match lynko_core::decode_frame(&bin) {
                    DecodedFrame::Chunk { id, data } => {
                        let _ = app.emit("file_chunk", serde_json::json!({ "id": &id, "len": data.len() }));
                    }
                    _ => {
                        // Screen frame (b"LV1" + raw JPEG bytes): forward JPEG to canvas.
                        if bin.len() > 3 && &bin[..3] == lynko_core::FRAME_MAGIC {
                            use base64::Engine;
                            use tauri::Manager;
                            if let Some(win) = app.get_webview_window("main") {
                                let _ = win.emit("screen_frame", serde_json::json!({
                                    "jpeg": base64::engine::general_purpose::STANDARD.encode(&bin[3..]),
                                }));
                            }
                        }
                        // Audio chunk (b"LF1" + header + PCM i16 LE): forward PCM to player.
                        if let Some((rate, chans, count, samples)) = lynko_core::parse_audio_chunk(&bin) {
                            use tauri::Manager;
                            if let Some(win) = app.get_webview_window("main") {
                                let _ = win.emit("audio_chunk", serde_json::json!({
                                    "rate": rate,
                                    "chans": chans,
                                    "count": count,
                                    "pcm": samples,
                                }));
                            }
                        }
                    }
                }
            }
            Ok(Some(Ok(Message::Close(_)))) | Ok(None) | Err(_) => break,
            _ => {}
        }
    }

    alive.store(false, Ordering::SeqCst);
    let _ = app.emit("link_state", serde_json::json!({ "connected": false }));
    let _ = app.emit("log", serde_json::json!({ "msg": "link dropped" }));

    // Auto-reconnect after a mid-session drop (VPN toggle, Wi-Fi blip, MIUI
    // doze). `disconnect`/`forget` REPLACE the handle or clear it; a stale
    // entry for the same device here means the drop was not user-forced.
    // (alive was just set false at the top — the entry's mere presence for
    // this device is what distinguishes drop from explicit disconnect.)
    {
        let state = app.state::<LynkoState>();
        let link = state.link.lock().unwrap();
        if let Some(l) = link.as_ref() {
            if l.device_id == device.id {
                spawn_reconnect(app.clone(), device.id.clone(), gen);
            }
        }
    }
}

/// Backoff-reconnect loop for a dropped link. Stops when a fresh link takes
/// over (connect_inner replaces the handle) or the device is forgotten.
fn spawn_reconnect(app: AppHandle, device_id: String, gen: u64) {
    tauri::async_runtime::spawn(async move {
        let mut delay = Duration::from_secs(1);
        loop {
            tokio::time::sleep(delay).await;
            // Stop if a new connection replaced us or the device was forgotten.
            {
                let state = app.state::<LynkoState>();
                if state.link_gen.load(Ordering::SeqCst) != gen { return; }
                let link = state.link.lock().unwrap();
                match link.as_ref() {
                    None => return,            // forgotten
                    Some(l) if l.device_id == device_id && !l.alive.load(Ordering::SeqCst) => {}
                    Some(_) => return,         // different device took over
                }
            }
            let state = app.state::<LynkoState>();
            match connect_inner(&state, &device_id) {
                Ok(()) => return,
                Err(_) => { delay = std::cmp::min(delay * 2, Duration::from_secs(15)); }
            }
        }
    });
}

#[tauri::command]
fn connect(state: State<'_, LynkoState>, device_id: String) -> Result<(), String> {
    connect_inner(&state, &device_id)
}

#[tauri::command]
fn disconnect(state: State<'_, LynkoState>) -> Result<(), String> {
    let mut link = state.link.lock().unwrap();
    if let Some(l) = link.take() { l.alive.store(false, Ordering::SeqCst); }
    if let Some(app) = state.app.lock().unwrap().as_ref() {
        let _ = app.emit("link_state", serde_json::json!({ "connected": false }));
    }
    Ok(())
}

/// Send a JSON command over the link.
fn send_cmd(state: &LynkoState, cmd: &Command) -> Result<(), String> {
    let link = state.link.lock().unwrap();
    let l = link.as_ref().ok_or("no phone connected")?;
    if !l.alive.load(Ordering::SeqCst) { return Err("link is down".into()); }
    let json = serde_json::to_string(cmd).map_err(|e| e.to_string())?;
    l.tx.try_send(Message::text(json)).map_err(|e| e.to_string())
}

#[tauri::command]
fn send_copy(state: State<'_, LynkoState>, text: String) -> Result<(), String> {
    send_cmd(&state, &Command::Copy { text })
}

#[tauri::command]
fn request_paste(state: State<'_, LynkoState>) -> Result<(), String> {
    send_cmd(&state, &Command::Paste)
}

#[tauri::command]
fn request_status(state: State<'_, LynkoState>) -> Result<(), String> {
    send_cmd(&state, &Command::StatusGet)
}

#[tauri::command]
fn screen_start(state: State<'_, LynkoState>) -> Result<(), String> {
    send_cmd(&state, &Command::StartScreen)
}

#[tauri::command]
fn screen_stop(state: State<'_, LynkoState>) -> Result<(), String> {
    send_cmd(&state, &Command::StopScreen)
}

#[tauri::command]
fn audio_start(state: State<'_, LynkoState>) -> Result<(), String> {
    send_cmd(&state, &Command::StartAudio)
}

#[tauri::command]
fn audio_stop(state: State<'_, LynkoState>) -> Result<(), String> {
    send_cmd(&state, &Command::StopAudio)
}

/* ------------------------------------------------------------------ */
/* input injection + WebRTC signaling                                  */
/* ------------------------------------------------------------------ */

#[tauri::command]
fn inject_tap(state: State<'_, LynkoState>, x: f32, y: f32) -> Result<(), String> {
    send_cmd(&state, &Command::Tap { x, y })
}

#[tauri::command]
fn inject_swipe(state: State<'_, LynkoState>, x1: f32, y1: f32, x2: f32, y2: f32) -> Result<(), String> {
    send_cmd(&state, &Command::Swipe { x1, y1, x2, y2 })
}

#[tauri::command]
fn inject_drag_start(state: State<'_, LynkoState>, x: f32, y: f32) -> Result<(), String> {
    send_cmd(&state, &Command::DragStart { x, y })
}

#[tauri::command]
fn inject_drag_move(state: State<'_, LynkoState>, x: f32, y: f32) -> Result<(), String> {
    send_cmd(&state, &Command::DragMove { x, y })
}

#[tauri::command]
fn inject_drag_end(state: State<'_, LynkoState>, x: f32, y: f32) -> Result<(), String> {
    send_cmd(&state, &Command::DragEnd { x, y })
}

#[tauri::command]
fn inject_key(state: State<'_, LynkoState>, key: String) -> Result<(), String> {
    send_cmd(&state, &Command::Key { key })
}

#[tauri::command]
fn inject_text(state: State<'_, LynkoState>, text: String) -> Result<(), String> {
    send_cmd(&state, &Command::Text { text })
}

/// Forward a WebRTC SDP/ICE blob from the frontend to the phone.
#[tauri::command]
fn send_signal(state: State<'_, LynkoState>, payload: serde_json::Value) -> Result<(), String> {
    send_cmd(&state, &Command::Signal { payload })
}

#[tauri::command]
fn send_file(state: State<'_, LynkoState>, path: String) -> Result<String, String> {
    send_files_v2(state, vec![path])
}

/// LocalSend-style session file push (Apache-2.0, localsend.org):
/// POST /api/lynko/v2/prepare-upload with a sha256 manifest → phone consents
/// → POST /api/lynko/v2/upload?sessionId&fileId per file → cancel on abort.
#[tauri::command]
fn send_files_v2(state: State<'_, LynkoState>, paths: Vec<String>) -> Result<String, String> {
    let (addr, device_name, app) = {
        let link = state.link.lock().unwrap();
        let l = link.as_ref().ok_or("no phone connected")?;
        let app = state.app.lock().unwrap().clone().ok_or("app not ready")?;
        (l.device_address.clone(), l.device_name.clone(), app)
    };
    let session_id = uuid::Uuid::new_v4().to_string();
    let session_id_disp = session_id.clone();

    // Build the manifest: id, fileName, size, sha256 per file.
    let mut manifest_files = Vec::new();
    let mut metas = Vec::new();
    for p in &paths {
        let mut f = std::fs::File::open(p).map_err(|e| format!("cannot open {p}: {e}"))?;
        let size = f.metadata().map_err(|e| e.to_string())?.len();
        let name = std::path::Path::new(p)
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "file.bin".into());
        let id = uuid::Uuid::new_v4().to_string();
        // hash while we still have the file open, then reopen for upload
        let mut hasher = sha2::Sha256::new();
        std::io::copy(&mut f, &mut hasher).map_err(|e| e.to_string())?;
        let sha256 = format!("{:x}", hasher.finalize());
        manifest_files.push(serde_json::json!({
            "id": id, "fileName": name, "size": size, "sha256": sha256,
        }));
        metas.push((id, name, size, p.clone()));
    }

    let manifest = serde_json::json!({
        "sessionId": session_id,
        "sender": { "alias": whoami::fallible::username().unwrap_or_else(|_| "Desktop".into()),
            "deviceModel": "PC" },
        "files": manifest_files,
    });

    let _ = app.emit("log", serde_json::json!({ "msg": format!("transfer: asking {device_name} to accept {} file(s)", metas.len()) }));

    tauri::async_runtime::spawn(async move {
        let base = format!("http://{}:{}", ws_host(&addr), TRANSFER_PORT);
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(120))
            .build()
            .unwrap();

        // 1) prepare-upload — blocks until the user answers on the phone.
        let prep: serde_json::Value = match client
            .post(format!("{base}/api/lynko/v2/prepare-upload"))
            .json(&manifest)
            .send()
            .await
        {
            Ok(r) if r.status().as_u16() == 200 => match r.json().await {
                Ok(v) => v,
                Err(e) => { let _ = app.emit("log", serde_json::json!({ "msg": format!("transfer: bad manifest reply: {e}") })); return; }
            },
            Ok(r) if r.status().as_u16() == 403 => {
                let _ = app.emit("log", serde_json::json!({ "msg": "transfer declined on the phone" }));
                for (id, _, _, _) in &metas {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "ok": false, "error": "declined" }));
                }
                return;
            }
            Ok(r) => { let _ = app.emit("log", serde_json::json!({ "msg": format!("transfer: prepare failed: {}", r.status()) })); return; }
            Err(e) => { let _ = app.emit("log", serde_json::json!({ "msg": format!("transfer: unreachable: {e}") })); return; }
        };
        let _ = prep;

        // 2) upload each file's raw bytes, streaming for incremental progress
        for (id, name, size, p) in &metas {
            let id = id.clone();
            let name = name.clone();
            let size = *size;
            let p = p.clone();
            let file = match std::fs::File::open(&p) {
                Ok(f) => f,
                Err(e) => {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "ok": false, "error": format!("read: {e}") }));
                    continue;
                }
            };
            let stream = FileReadStream {
                file: Some(file),
                done: 0,
                total: size,
                id: id.clone(),
                name: name.clone(),
                app: app.clone(),
            };
            let url = format!("{base}/api/lynko/v2/upload?sessionId={session_id}&fileId={id}");
            match client.post(url).body(reqwest::Body::wrap_stream(stream)).send().await {
                Ok(r) if r.status().as_u16() == 200 => {
                    let _ = app.emit("file_progress", serde_json::json!({ "id": id, "name": name, "written": size, "total": size }));
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "ok": true }));
                }
                Ok(r) => {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "ok": false, "error": format!("upload: {}", r.status()) }));
                    let _ = client.post(format!("{base}/api/lynko/v2/cancel?sessionId={session_id}")).send().await;
                    return;
                }
                Err(e) => {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "ok": false, "error": format!("upload: {e}") }));
                    let _ = client.post(format!("{base}/api/lynko/v2/cancel?sessionId={session_id}")).send().await;
                    return;
                }
            }
        }
        let _ = app.emit("log", serde_json::json!({ "msg": format!("transfer complete: {} file(s) → {device_name}", metas.len()) }));
    });
    Ok(session_id_disp)
}

/* ------------------------------------------------------------------ */
/* misc commands                                                       */
/* ------------------------------------------------------------------ */

#[tauri::command]
fn protocol_info() -> serde_json::Value {
    serde_json::json!({ "protocol_version": PROTOCOL_VERSION, "service_type": SERVICE_TYPE })
}

#[tauri::command]
fn list_devices(state: State<'_, LynkoState>) -> Vec<Device> {
    state.merged()
}

#[tauri::command]
fn desktop_name() -> String {
    std::env::var("COMPUTERNAME")
        .ok()
        .filter(|s| !s.is_empty())
        .or_else(|| std::env::var("HOSTNAME").ok().filter(|s| !s.is_empty()))
        .or_else(|| {
            // macOS / Linux: hostname(1)
            std::process::Command::new("hostname")
                .output()
                .ok()
                .filter(|o| o.status.success())
                .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
                .filter(|s| !s.is_empty())
        })
        .unwrap_or_else(|| "Lynko Desktop".into())
}

#[tauri::command]
fn set_pc_clipboard(app: tauri::AppHandle, text: String) -> Result<(), String> {
    use tauri_plugin_clipboard_manager::ClipboardExt;
    app.clipboard().write_text(text).map_err(|e| e.to_string())
}

#[tauri::command]
fn get_pc_clipboard(app: tauri::AppHandle) -> Result<String, String> {
    use tauri_plugin_clipboard_manager::ClipboardExt;
    app.clipboard().read_text().map_err(|e| e.to_string())
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let handle = app.handle().clone();
            let paired = load_pairs(&handle);
            app.manage(LynkoState {
                discovered: Mutex::new(HashMap::new()),
                paired: Mutex::new(paired),
                link: Mutex::new(None),
                app: Mutex::new(Some(handle.clone())),
                link_gen: AtomicU64::new(0),
            });
            start_discovery(handle);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            protocol_info,
            list_devices,
            desktop_name,
            pair_device,
            forget_device,
            connect,
            disconnect,
            send_copy,
            request_paste,
            request_status,
            screen_start,
            screen_stop,
            audio_start,
            audio_stop,
            inject_tap,
            inject_swipe,
            inject_drag_start,
            inject_drag_move,
            inject_drag_end,
            inject_key,
            inject_text,
            send_signal,
            send_file,
            send_files_v2,
            set_pc_clipboard,
            get_pc_clipboard
        ])
        .run(tauri::generate_context!())
        .expect("error while running Lynko");
}
