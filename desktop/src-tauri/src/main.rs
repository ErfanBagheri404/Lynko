// Prevent a console window on Windows release builds
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod adb;
mod share_receive;
mod transfer_receive;

use futures_util::{SinkExt, StreamExt};
use lynko_core::{
    Capabilities, Command, Event, PairRequest, PairResponse,
    LINK_PORT, PAIR_PORT, PROTOCOL_VERSION, SERVICE_TYPE,
};
/// HTTP port of the phone's LocalSend v2.2 transfer server.
const TRANSFER_PORT: u16 = 53317;
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
    #[serde(default)]
    linked: bool,
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
    /// User setting (Settings view): when false, a dropped link stays down
    /// until the user reconnects manually. Gates spawn_reconnect.
    auto_reconnect: AtomicBool,
    /// Latest screen frame (raw JPEG bytes), served to the WebView over a
    /// localhost HTTP endpoint. Tauri's asset protocol refused to serve
    /// percent-encoded Windows temp paths, and base64-in-JSON events
    /// bottlenecked at ~6fps; an in-memory HTTP endpoint lets Chromium load
    /// frames natively with zero serialization.
    latest_frame: Mutex<Vec<u8>>,
    /// Bumped every time latest_frame changes, so the frontend can skip
    /// re-fetching an identical frame.
    frame_seq: AtomicU64,
}

impl LynkoState {
    fn merged(&self) -> Vec<Device> {
          let disc = self.discovered.lock().unwrap();
          let paired = self.paired.lock().unwrap();
          let link_id = self.link.lock().unwrap().as_ref().map(|h| h.device_id.clone());
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
          // The live-linked device is the one the desktop is talking to right
          // now — flag it so the UI can badge it instead of guessing from
          // `online` (a phone can be online but not the active link target).
          if let Some(id) = link_id {
              if let Some(d) = out.get_mut(&id) {
                  d.linked = true;
              }
          }
          out.into_values().collect()
      }

    fn emit_devices(&self) {
        if let Some(app) = self.app.lock().unwrap().as_ref() {
            let _ = app.emit("discovery", &self.merged());
        }
    }
}

/* ------------------------------------------------------------------ */
/* USB transport — adb localhost forwards (method per Zfinix/another)  */
/* ------------------------------------------------------------------ */

/// One USB phone entry in the discovered map. Address is always
/// 127.0.0.1 because adb forwards localhost:791x → device:791x over the
/// cable; pair/link/transfer code paths are unchanged.
fn usb_device_entry(serial: &str, model: &str) -> Device {
    Device {
        id: format!("usb:{serial}"),
        name: if model.is_empty() { format!("USB device {serial}") } else { model.to_string() },
        address: "127.0.0.1".into(),
        caps: Capabilities {
            screen_capture: true,
            input_injection: true,
            text_input: true,
            clipboard_sync: true,
            file_transfer: true,
            notifications: true,
            audio_capture: false,
            battery_status: true,
        },
        paired: false,
        online: true,
        linked: false,
    }
}

/// Poll `adb devices` every 2s. On appear: set up the three port forwards
/// and insert/refresh a `usb:<serial>` device. On disappear: mark offline,
/// tear down forwards, drop the live link if it pointed at this device.
fn start_usb_watch(app: AppHandle) {
    std::thread::spawn(move || {
        let state = app.state::<LynkoState>();
        let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
        loop {
            let devices = match tauri::async_runtime::block_on(adb::list_devices()) {
                Ok(d) => d,
                Err(_) => {
                    // adb missing entirely → nothing to do this cycle
                    if !seen.is_empty() { seen.clear(); state.emit_devices(); }
                    std::thread::sleep(Duration::from_secs(4));
                    continue;
                }
            };

            let mut changed = false;
            let current: std::collections::HashSet<String> =
                devices.iter().map(|d| d.serial.clone()).collect();

            // Disappeared devices
            for gone in seen.difference(&current) {
                let id = format!("usb:{gone}");
                let mut disc = state.discovered.lock().unwrap();
                if let Some(d) = disc.get_mut(&id) { d.online = false; }
                drop(disc);
                let mut link = state.link.lock().unwrap();
                if let Some(l) = link.as_ref() {
                    if l.device_id == id {
                        l.alive.store(false, Ordering::SeqCst);
                        *link = None;
                        drop(link);
                        if let Some(a) = state.app.lock().unwrap().as_ref() {
                            let _ = a.emit("link_state", serde_json::json!({ "connected": false }));
                            let _ = a.emit("log", serde_json::json!({ "msg": "usb device unplugged" }));
                        }
                    }
                }
                let _ = tauri::async_runtime::block_on(adb::remove_all_forwards(gone));
                changed = true;
            }

            // Appeared / still-present devices
            for dev in &devices {
                let id = format!("usb:{}", dev.serial);
                if seen.contains(&dev.serial) {
                    // Refresh online flag if a previous cycle marked it offline
                    let mut disc = state.discovered.lock().unwrap();
                    if let Some(d) = disc.get_mut(&id) {
                        if !d.online { d.online = true; changed = true; }
                    }
                    continue;
                }
                match tauri::async_runtime::block_on(adb::setup_forwards(&dev.serial)) {
                    Ok(()) => {
                        state.discovered.lock().unwrap().insert(
                            id.clone(),
                            usb_device_entry(&dev.serial, &dev.model),
                        );
                        if let Some(a) = state.app.lock().unwrap().as_ref() {
                            let _ = a.emit("log", serde_json::json!({ "msg": format!("usb device connected: {}", dev.model) }));
                        }
                        seen.insert(dev.serial.clone());
                        changed = true;
                    }
                    Err(e) => {
                        if let Some(a) = state.app.lock().unwrap().as_ref() {
                            let _ = a.emit("log", serde_json::json!({ "msg": format!("usb forward setup failed for {}: {e}", dev.model) }));
                        }
                    }
                }
            }

            if changed { state.emit_devices(); }
            std::thread::sleep(Duration::from_secs(2));
        }
    });
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
                    // Phone advertises "cap" (and legacy "caps") — read either.
                    let caps = info
                        .get_property("cap")
                        .or_else(|| info.get_property("caps"))
                        .map(|p| Capabilities::from_txt(p.val_str()))
                        .unwrap_or_default();
                    let id = info.get_fullname().to_string();
                    state.discovered.lock().unwrap().insert(
                        id.clone(),
                        Device { id, name, address, caps, paired: false, online: true, linked: false },
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

// A USB cable only proves ADB is reachable, not that Lynko has started.
async fn pairing_service_ready(address: &str) -> bool {
    let Ok(client) = reqwest::Client::builder().no_proxy().timeout(Duration::from_secs(2)).build() else { return false; };
    // GET carries no PIN and cannot approve pairing. The existing server
    // responds with its typed rejection, proving the pairing handler is live.
    let Ok(response) = client.get(host_url(address, PAIR_PORT, "/pair")).send().await else { return false; };
    if !response.status().is_success() { return false; }
    let Ok(body) = response.json::<serde_json::Value>().await else { return false; };
    body["ok"] == false && body["error"] == "bad pin"
}

#[tauri::command]
async fn check_pairing_ready(state: State<'_, LynkoState>, device_id: String) -> Result<bool, String> {
    let device = state.discovered.lock().unwrap().get(&device_id).cloned().ok_or("Device disconnected")?;
    Ok(pairing_service_ready(&device.address).await)
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
    // Drop the link lock BEFORE calling emit_devices — merged() re-locks it,
    // so holding it here deadlocks the entire UI (Tauri command thread hangs,
    // WebView freezes, app shows "Not Responding").
    {
        let mut link = state.link.lock().unwrap();
        if let Some(l) = link.as_ref() {
            if l.device_id == device_id { l.alive.store(false, Ordering::SeqCst); *link = None; }
        }
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
    let (tx, rx) = tokio_mpsc::channel::<Message>(512);
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
    app.state::<LynkoState>().emit_devices();

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
                share_receive::expire(&app,gen);
                if txt.len() <= 70000 {
                    if let Ok(v)=serde_json::from_str::<serde_json::Value>(&txt) {
                        if let Some(t)=v.get("t").and_then(|v|v.as_str()).filter(|t|t.starts_with("share_")) {
                            share_receive::handle(&app,&_tx,gen,t,&v["d"]);
                            continue;
                        }
                    }
                }
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
                        // Screen frame (b"LV1" + raw JPEG bytes): stash the JPEG
                        // in memory and bump the sequence. The WebView loads it
                        // from the localhost frame server below — no base64, no
                        // JSON, no asset-protocol scope to fight.
                        if bin.len() > 3 && &bin[..3] == lynko_core::FRAME_MAGIC {
                            if let Some(st) = app.try_state::<LynkoState>() {
                                *st.latest_frame.lock().unwrap() = bin[3..].to_vec();
                                st.frame_seq.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
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

    share_receive::clear(gen);
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
/// Honors the Settings → auto-reconnect toggle.
fn spawn_reconnect(app: AppHandle, device_id: String, gen: u64) {
    if !app.state::<LynkoState>().auto_reconnect.load(Ordering::SeqCst) {
        let _ = app.emit("log", serde_json::json!({ "msg": "link dropped (auto-reconnect off)" }));
        return;
    }
    tauri::async_runtime::spawn(async move {
        // First attempt fires immediately: the phone's link server is still
        // alive (VpnGuard doesn't restart it), so the TCP reconnect should
        // succeed on first try. Only fall back to backoff on repeated failure.
        let mut delay = Duration::ZERO;
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
                // First retry is immediate (delay starts ZERO); after the
                // first failure jump to 1s, then exponential to 15s cap.
                Err(_) => { delay = if delay.is_zero() { Duration::from_secs(1) } else { std::cmp::min(delay * 2, Duration::from_secs(15)) }; }
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

/// Settings → auto-reconnect toggle (real, not decorative): false means a
/// dropped link stays down until the user reconnects manually.
#[tauri::command]
fn set_auto_reconnect(state: State<'_, LynkoState>, on: bool) -> Result<(), String> {
    state.auto_reconnect.store(on, Ordering::SeqCst);
    Ok(())
}

/// Async variant for high-rate commands (drag moves). Waits for channel
/// space instead of try_send silently dropping on Full — a dropped move is
/// an invisible dead swipe; a timed-out one is a visible error.
async fn send_cmd_a(state: State<'_, LynkoState>, cmd: &Command) -> Result<(), String> {
    let (tx, alive) = {
        let link = state.link.lock().unwrap();
        let l = link.as_ref().ok_or("no phone connected")?;
        (l.tx.clone(), l.alive.clone())
    };
    if !alive.load(Ordering::SeqCst) { return Err("link is down".into()); }
    let json = serde_json::to_string(cmd).map_err(|e| e.to_string())?;
    match tokio::time::timeout(Duration::from_millis(400), tx.send(Message::text(json))).await {
        Ok(Ok(_)) => Ok(()),
        Ok(Err(e)) => Err(format!("send closed: {e}")),
        Err(_) => Err("send backpressure timeout".into()),
    }
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
fn notif_reply(state: State<'_, LynkoState>, app: String, notifId: i32, text: String) -> Result<(), String> {
    send_cmd(&state, &Command::NotifReply { app, notif_id: notifId, text })
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
async fn inject_drag_start(state: State<'_, LynkoState>, x: f32, y: f32, dt: Option<u32>) -> Result<(), String> {
    send_cmd_a(state, &Command::DragStart { x, y, dt: dt.unwrap_or(0) }).await
}

#[tauri::command]
async fn inject_drag_move(state: State<'_, LynkoState>, x: f32, y: f32, dt: Option<u32>) -> Result<(), String> {
    send_cmd_a(state, &Command::DragMove { x, y, dt: dt.unwrap_or(0) }).await
}

#[tauri::command]
async fn inject_drag_end(state: State<'_, LynkoState>, x: f32, y: f32, dt: Option<u32>) -> Result<(), String> {
    send_cmd_a(state, &Command::DragEnd { x, y, dt: dt.unwrap_or(0) }).await
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
fn send_file(state: State<'_, LynkoState>, path: String, request_id: Option<String>) -> Result<String, String> {
    send_files_v2(state, vec![path], request_id)
}

/// Stable per-install identity (LocalSend "fingerprint": random string when
/// encryption is off — here derived from the machine name so two desktop
/// instances on one box are still distinguishable from a phone).
fn state_fingerprint(_state: &State<'_, LynkoState>) -> String {
    let host = std::env::var("COMPUTERNAME")
        .ok()
        .filter(|s| !s.is_empty())
        .or_else(|| std::env::var("HOSTNAME").ok().filter(|s| !s.is_empty()))
        .unwrap_or_else(|| "lynko-desktop".into());
    format!("lynko-{}", host.to_lowercase())
}

/// LocalSend `fileType` is a MIME type (spec 4.1), not the v1 category enum.
fn mime_of(name: &str) -> &'static str {
    let ext = std::path::Path::new(name)
        .extension()
        .map(|e| e.to_string_lossy().to_lowercase())
        .unwrap_or_default();
    match ext.as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "svg" => "image/svg+xml",
        "bmp" => "image/bmp",
        "heic" => "image/heic",
        "mp4" => "video/mp4",
        "mkv" => "video/x-matroska",
        "webm" => "video/webm",
        "mov" => "video/quicktime",
        "avi" => "video/x-msvideo",
        "mp3" => "audio/mpeg",
        "m4a" => "audio/mp4",
        "wav" => "audio/wav",
        "ogg" => "audio/ogg",
        "flac" => "audio/flac",
        "pdf" => "application/pdf",
        "zip" => "application/zip",
        "gz" | "tgz" => "application/gzip",
        "7z" => "application/x-7z-compressed",
        "rar" => "application/vnd.rar",
        "tar" => "application/x-tar",
        "apk" => "application/vnd.android.package-archive",
        "exe" | "msi" => "application/vnd.microsoft.portable-executable",
        "doc" => "application/msword",
        "docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" => "application/vnd.ms-excel",
        "xlsx" => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" => "application/vnd.ms-powerpoint",
        "pptx" => "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "txt" | "log" | "md" => "text/plain",
        "json" => "application/json",
        "xml" => "application/xml",
        "csv" => "text/csv",
        "html" | "htm" => "text/html",
        "css" => "text/css",
        "js" => "text/javascript",
        _ => "application/octet-stream",
    }
}

/// Spec 4.1 `metadata.modified`: ISO-8601 UTC (e.g. 2021-01-01T12:34:56Z).
fn modified_of(path: &str) -> String {
    let t = std::fs::metadata(path).and_then(|m| m.modified()).ok();
    match t {
        Some(time) => {
            let secs = time
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0);
            // Days since epoch -> civil date (Howard Hinnant's algorithm).
            let days = (secs / 86_400) as i64;
            let rem = (secs % 86_400) as u32;
            let z = days + 719_468;
            let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
            let doe = (z - era * 146_097) as i64;
            let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
            let y = yoe + era * 400;
            let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
            let mp = (5 * doy + 2) / 153;
            let d = doy - (153 * mp + 2) / 5 + 1;
            let m = if mp < 10 { mp + 3 } else { mp - 9 };
            let year = if m <= 2 { y + 1 } else { y };
            format!(
                "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}Z",
                year, m, d, rem / 3600, (rem % 3600) / 60, rem % 60
            )
        }
        None => String::new(),
    }
}

/// LocalSend-style session file push (Apache-2.0, localsend.org):
/// POST /api/lynko/v2/prepare-upload with a sha256 manifest → phone consents
/// → POST /api/lynko/v2/upload?sessionId&fileId per file → cancel on abort.
#[tauri::command]
fn send_files_v2(state: State<'_, LynkoState>, paths: Vec<String>, request_id: Option<String>) -> Result<String, String> {
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
        let id = if paths.len() == 1 { request_id.clone().unwrap_or_else(|| uuid::Uuid::new_v4().to_string()) } else { uuid::Uuid::new_v4().to_string() };
        // hash while we still have the file open, then reopen for upload
        let mut hasher = sha2::Sha256::new();
        std::io::copy(&mut f, &mut hasher).map_err(|e| e.to_string())?;
        let sha256 = format!("{:x}", hasher.finalize());
        manifest_files.push(serde_json::json!({
            "id": id, "fileName": name, "size": size,
            "fileType": mime_of(&name), "sha256": sha256,
            "metadata": { "modified": modified_of(p) },
        }));
        metas.push((id, name, size, p.clone()));
    }

    // Exact LocalSend v2.2 prepare-upload body: info + files keyed by file id.
    let mut files_map = serde_json::Map::new();
    for f in &manifest_files {
        let id = f["id"].as_str().unwrap_or("").to_string();
        files_map.insert(id, f.clone());
    }
    let manifest = serde_json::json!({
        "info": {
            "alias": whoami::fallible::username().unwrap_or_else(|_| "Desktop".into()),
            "version": "2.2",
            "deviceModel": "PC",
            "deviceType": "desktop",
            "fingerprint": state_fingerprint(&state),
            "port": TRANSFER_PORT,
            "protocol": "http",
            "download": false,
        },
        "files": files_map,
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
            .post(format!("{base}/api/localsend/v2/prepare-upload"))
            .json(&manifest)
            .send()
            .await
        {
            Ok(r) if r.status().as_u16() == 200 => match r.json().await {
                Ok(v) => v,
                Err(e) => {
                    let _ = app.emit("log", serde_json::json!({ "msg": format!("transfer: bad manifest reply: {e}") }));
                    for (id, name, _, _) in &metas {
                        let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": false, "error": "bad reply" }));
                    }
                    return;
                }
            },
            Ok(r) if r.status().as_u16() == 403 => {
                let _ = app.emit("log", serde_json::json!({ "msg": "transfer declined on the phone" }));
                for (id, name, _, _) in &metas {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": false, "error": "declined on phone" }));
                }
                return;
            }
            // Spec 4.1: 401 PIN required/invalid, 409 blocked by another session,
            // 204 finished (no transfer needed), 422 checksum mismatch.
            Ok(r) if r.status().as_u16() == 401 => {
                let _ = app.emit("log", serde_json::json!({ "msg": "transfer: phone requires a PIN" }));
                for (id, name, _, _) in &metas {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": false, "error": "PIN required" }));
                }
                return;
            }
            Ok(r) if r.status().as_u16() == 409 => {
                let _ = app.emit("log", serde_json::json!({ "msg": "transfer: phone is busy with another session" }));
                for (id, name, _, _) in &metas {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": false, "error": "phone busy" }));
                }
                return;
            }
            Ok(r) if r.status().as_u16() == 204 => {
                let _ = app.emit("log", serde_json::json!({ "msg": "transfer: nothing to send" }));
                for (id, name, _, _) in &metas {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": true }));
                }
                return;
            }
            Ok(r) => {
                let _ = app.emit("log", serde_json::json!({ "msg": format!("transfer: prepare failed: {}", r.status()) }));
                for (id, name, _, _) in &metas {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": false, "error": format!("phone: {}", r.status()) }));
                }
                return;
            }
            Err(e) => {
                let _ = app.emit("log", serde_json::json!({ "msg": format!("transfer: unreachable: {e}") }));
                for (id, name, _, _) in &metas {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": false, "error": "phone unreachable" }));
                }
                return;
            }
        };
        let _ = prep;
        // Spec 4.1 reply: {"sessionId": "...", "files": {"<fileId>": "<token>"}}
        let remote_session = prep["sessionId"].as_str().unwrap_or("").to_string();
        let tokens = prep["files"].clone();
        if remote_session.is_empty() {
            let _ = app.emit("log", serde_json::json!({ "msg": "transfer: phone returned no sessionId" }));
            for (id, name, _, _) in &metas {
                let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": false, "error": "no session" }));
            }
            return;
        }

        // 2) upload each file's raw bytes, streaming for incremental progress
        for (id, name, size, p) in &metas {
            let id = id.clone();
            let name = name.clone();
            let size = *size;
            let p = p.clone();
            let token = tokens[id.as_str()].as_str().unwrap_or("").to_string();
            let file = match std::fs::File::open(&p) {
                Ok(f) => f,
                Err(e) => {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": false, "error": format!("read: {e}") }));
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
            let url = format!("{base}/api/localsend/v2/upload?sessionId={remote_session}&fileId={id}&token={token}");
            match client.post(url).body(reqwest::Body::wrap_stream(stream)).send().await {
                Ok(r) if r.status().as_u16() == 200 => {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": true }));
                }
                // Spec 4.2: 422 = checksum mismatch (sha256).
                Ok(r) if r.status().as_u16() == 422 => {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": false, "error": "checksum mismatch" }));
                    let _ = client.post(format!("{base}/api/localsend/v2/cancel?sessionId={remote_session}")).send().await;
                    return;
                }
                // Spec 4.2: 403 = invalid token or IP address.
                Ok(r) if r.status().as_u16() == 403 => {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": false, "error": "invalid token" }));
                    let _ = client.post(format!("{base}/api/localsend/v2/cancel?sessionId={remote_session}")).send().await;
                    return;
                }
                Ok(r) => {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": false, "error": format!("upload: {}", r.status()) }));
                    let _ = client.post(format!("{base}/api/localsend/v2/cancel?sessionId={remote_session}")).send().await;
                    return;
                }
                Err(e) => {
                    let _ = app.emit("file_done", serde_json::json!({ "id": id, "name": name, "ok": false, "error": format!("upload: {e}") }));
                    let _ = client.post(format!("{base}/api/localsend/v2/cancel?sessionId={remote_session}")).send().await;
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

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct UpdateInfo {
    pub current_version: String,
    pub latest_version: String,
    pub release_name: String,
    pub release_notes: String,
    pub published_at: String,
    pub html_url: String,
    pub has_update: bool,
}

/// Query the latest GitHub Release for Lynko. Fallback to /releases (taking the
/// first entry) if /latest 404s (e.g. only pre-releases/continuous tags exist).
/// Results cached for 10 minutes to respect the 60 req/hr unauth GitHub limit.
#[tauri::command]
async fn check_update() -> Result<UpdateInfo, String> {
    static CACHE: tokio::sync::Mutex<Option<(std::time::Instant, UpdateInfo)>> =
        tokio::sync::Mutex::const_new(None);

    let mut lock = CACHE.lock().await;
    if let Some((when, ref info)) = *lock {
        if when.elapsed() < std::time::Duration::from_secs(600) {
            return Ok(info.clone());
        }
    }

    let client = reqwest::Client::builder()
        .user_agent("Lynko-Desktop/0.1.0 (https://github.com/ErfanBagheri404/Lynko)")
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())?;

    // Try /releases/latest first; if 404, fallback to /releases
    let url_latest = "https://api.github.com/repos/ErfanBagheri404/Lynko/releases/latest";
    let resp = client.get(url_latest).send().await;
    let json_val: serde_json::Value = match resp {
        Ok(r) if r.status().is_success() => r.json().await.map_err(|e| e.to_string())?,
        _ => {
            // fallback: first entry of /releases
            let url_all = "https://api.github.com/repos/ErfanBagheri404/Lynko/releases?per_page=1";
            let r2 = client
                .get(url_all)
                .send()
                .await
                .map_err(|e| format!("Network error: {e}"))?;
            if !r2.status().is_success() {
                return Err(format!("GitHub API returned {}", r2.status()));
            }
            let arr: Vec<serde_json::Value> = r2.json().await.map_err(|e| e.to_string())?;
            arr.into_iter().next().ok_or_else(|| "No releases found".to_string())?
        }
    };

    let tag = json_val
        .get("tag_name")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let name = json_val
        .get("name")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .unwrap_or(&tag)
        .to_string();
    let body = json_val
        .get("body")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let published_at = json_val
        .get("published_at")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let html_url = json_val
        .get("html_url")
        .and_then(|v| v.as_str())
        .unwrap_or("https://github.com/ErfanBagheri404/Lynko/releases")
        .to_string();

    let cur = env!("CARGO_PKG_VERSION").to_string();
    let clean_tag = tag.trim_start_matches('v').to_string();

    // Semver check: parse major.minor.patch. Fallback: string inequality.
    let has_update = is_newer(&cur, &clean_tag);

    let info = UpdateInfo {
        current_version: cur,
        latest_version: clean_tag,
        release_name: name,
        release_notes: body,
        published_at,
        html_url,
        has_update,
    };
    *lock = Some((std::time::Instant::now(), info.clone()));
    Ok(info)
}

fn parse_semver(s: &str) -> Option<(u32, u32, u32)> {
    let parts: Vec<&str> = s.split('.').collect();
    if parts.len() < 2 {
        return None;
    }
    let maj = parts[0].parse().ok()?;
    let min = parts[1].parse().ok()?;
    let pat = parts.get(2).and_then(|p| p.split('-').next()).and_then(|p| p.parse().ok()).unwrap_or(0);
    Some((maj, min, pat))
}

fn is_newer(current: &str, candidate: &str) -> bool {
    if candidate.is_empty() || candidate == current {
        return false;
    }
    match (parse_semver(current), parse_semver(candidate)) {
        (Some((c1, c2, c3)), Some((n1, n2, n3))) => (n1, n2, n3) > (c1, c2, c3),
        // If candidate is a continuous build or non-semver tag, don't flag as newer than release
        _ => false,
    }
}

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

/* ------------------------------------------------------------------ */
/* frame server                                                        */
/* ------------------------------------------------------------------ */

/// Serves the newest screen frame as JPEG over http://127.0.0.1:7919/frame.jpg.
///
/// Why: the old path base64-encoded every frame into a Tauri event (+33%
/// inflation, JSON serialization, ~6fps ceiling), and Tauri's asset protocol
/// refused percent-encoded Windows temp paths outright (broken-image icon).
/// Chromium loads an <img> from localhost natively — no serialization at all,
/// and because only the latest frame is held, a slow renderer can never build
/// up a backlog of stale frames.
fn start_frame_server(app: tauri::AppHandle) {
    std::thread::spawn(move || {
        let server = match tiny_http::Server::http("127.0.0.1:7919") {
            Ok(s) => s,
            Err(e) => {
                let _ = app.emit("log", serde_json::json!({ "msg": format!("frame server failed to bind: {e}") }));
                return;
            }
        };
        let _ = app.emit("log", serde_json::json!({ "msg": "frame server on http://127.0.0.1:7919/frame.jpg" }));
        for req in server.incoming_requests() {
            // Any path serves the newest frame; the frontend only ever asks
            // for /frame.jpg but tolerating anything avoids 404 noise.
            let (bytes, seq) = {
                let st = match app.try_state::<LynkoState>() {
                    Some(st) => st,
                    None => continue,
                };
                let b = st.latest_frame.lock().unwrap().clone();
                let s = st.frame_seq.load(std::sync::atomic::Ordering::Relaxed);
                (b, s)
            };
            let header = tiny_http::Header::from_bytes(
                &b"Content-Type"[..],
                &b"image/jpeg"[..],
            )
            .unwrap();
            let seq_header = tiny_http::Header::from_bytes(
                &b"X-Frame-Seq"[..],
                seq.to_string().as_bytes(),
            )
            .unwrap();
            let no_store = tiny_http::Header::from_bytes(
                &b"Cache-Control"[..],
                &b"no-store"[..],
            )
            .unwrap();
            // CORS: the WebView origin (tauri://localhost / http://tauri.localhost)
            // is NOT same-origin with 127.0.0.1:7919 — without this header the
            // browser blocks reading the fetch() response and the mirror stays
            // stuck on "waiting for frames". GET + no custom headers = simple
            // request, so no OPTIONS preflight handling is needed.
            let cors = tiny_http::Header::from_bytes(
                &b"Access-Control-Allow-Origin"[..],
                &b"*"[..],
            )
            .unwrap();
            // X-Frame-Seq is the frame's version token — the poll loop compares
            // it to skip redundant repaints. It is NOT on the CORS safelist
            // (only Cache-Control/Content-Language/Content-Length/Content-Type/
            // Expires/Last-Modified/Pragma are), so a cross-origin fetch reads
            // it back as null. Without exposing it the comparison is always
            // -1 === -1, the loop never paints, and the mirror sits on
            // "waiting for frames" while JPEGs pile up behind it.
            let expose = tiny_http::Header::from_bytes(
                &b"Access-Control-Expose-Headers"[..],
                &b"X-Frame-Seq"[..],
            )
            .unwrap();
            let resp = tiny_http::Response::from_data(bytes)
                .with_header(header)
                .with_header(seq_header)
                .with_header(no_store)
                .with_header(cors)
                .with_header(expose);
            let _ = req.respond(resp);
        }
    });
}

/// Transfer PIN (LocalSend spec `?pin=` on prepare calls), set from Settings.
/// None = transfers need no PIN.
fn pin() -> Option<String> {
    PIN.get_or_init(|| Mutex::new(None))
        .lock()
        .unwrap()
        .clone()
        .filter(|p| !p.is_empty())
}

static PIN: std::sync::OnceLock<Mutex<Option<String>>> = std::sync::OnceLock::new();

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
                auto_reconnect: AtomicBool::new(true),
                latest_frame: Mutex::new(Vec::new()),
                frame_seq: AtomicU64::new(0),
            });
            start_discovery(handle.clone());
            start_usb_watch(handle.clone());
            start_frame_server(handle.clone());
            transfer_receive::start(handle.clone());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            share_receive::answer_share, check_pairing_ready,
            protocol_info,
            list_devices,
            desktop_name,
            pair_device,
            forget_device,
            connect,
            disconnect,
            set_auto_reconnect,
            send_copy,
            notif_reply,
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
            transfer_receive::answer_transfer,
            transfer_receive::set_transfer_pin,
            set_pc_clipboard,
            get_pc_clipboard,
            check_update
        ])
        .run(tauri::generate_context!())
        .expect("error while running Lynko");
}

#[cfg(test)]
mod update_tests {
    use super::{is_newer, parse_semver};

    #[test]
    fn semver_parsing() {
        assert_eq!(parse_semver("0.1.0"), Some((0, 1, 0)));
        assert_eq!(parse_semver("1.2.3"), Some((1, 2, 3)));
        // pre-release suffix is ignored
        assert_eq!(parse_semver("1.2.3-rc1"), Some((1, 2, 3)));
        assert_eq!(parse_semver("continuous"), None);
        assert_eq!(parse_semver(""), None);
        assert_eq!(parse_semver("1"), None);
    }

    #[test]
    fn flags_only_genuinely_newer_versions() {
        assert!(is_newer("0.1.0", "0.2.0"));
        assert!(is_newer("0.1.0", "1.0.0"));
        assert!(is_newer("0.9.9", "0.10.0"));
        assert!(!is_newer("0.1.0", "0.1.0"));
        assert!(!is_newer("0.2.0", "0.1.0"));
        // a `continuous` CI tag must never nag the user as an "update"
        assert!(!is_newer("0.1.0", "continuous"));
        assert!(!is_newer("0.1.0", ""));
    }
}
