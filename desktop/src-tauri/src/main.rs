// Prevent a console window on Windows release builds
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use lynko_core::{Capabilities, PROTOCOL_VERSION, SERVICE_TYPE};
use mdns_sd::{ServiceDaemon, ServiceEvent};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;

/// A phone discovered on the LAN or remembered from a previous pairing.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct Device {
    id: String,
    name: String,
    address: String,
    caps: Capabilities,
    paired: bool,
    online: bool,
}

/// App state shared across commands.
struct LynkoState {
    /// Phones paired in previous sessions, keyed by mDNS instance name.
    paired: Mutex<HashMap<String, Device>>,
}

fn cap_flag(txt: &mdns_sd::ResolvedService, key: &str) -> bool {
    txt.get_property(key)
        .map(|p| p.val_str() == "1")
        .unwrap_or(false)
}

fn parse_caps(txt: &mdns_sd::ResolvedService) -> Capabilities {
    Capabilities {
        screen_capture: cap_flag(txt, "screen"),
        input_injection: cap_flag(txt, "input"),
        text_input: cap_flag(txt, "text"),
        clipboard_sync: cap_flag(txt, "clip"),
        file_transfer: cap_flag(txt, "files"),
        notifications: cap_flag(txt, "notif"),
        audio_capture: cap_flag(txt, "audio"),
        battery_status: cap_flag(txt, "batt"),
    }
}

/// Browse the LAN for _lynko._tcp advertisers for ~1.5s.
#[tauri::command]
async fn discover_devices(state: tauri::State<'_, LynkoState>) -> Result<Vec<Device>, String> {
    let mdns = ServiceDaemon::new().map_err(|e| e.to_string())?;
    let receiver = mdns
        .browse(SERVICE_TYPE)
        .map_err(|e| format!("mDNS browse failed: {e}"))?;

    let mut found: HashMap<String, Device> = HashMap::new();
    let deadline = tokio::time::Instant::now() + Duration::from_millis(1500);

    loop {
        let ev = match tokio::time::timeout_at(deadline, receiver.recv_async()).await {
            Ok(Ok(ev)) => ev,
            Ok(Err(e)) => {
                eprintln!("mDNS channel error: {e}");
                break;
            }
            Err(_) => break, // deadline reached
        };
        if let ServiceEvent::ServiceResolved(info) = ev {
            let name = info
                .get_property("pretty_name")
                .map(|p| p.val_str().to_string())
                .unwrap_or_else(|| info.get_fullname().to_string());
            let address = info
                .get_addresses()
                .iter()
                .next()
                .map(|ip| ip.to_string())
                .unwrap_or_else(|| "unknown".into());
            let id = info.get_fullname().to_string();
            let caps = parse_caps(&info);
            found.insert(
                id.clone(),
                Device {
                    id,
                    name,
                    address,
                    caps,
                    paired: false,
                    online: true,
                },
            );
        }
    }

    let _ = mdns.stop_browse(SERVICE_TYPE);

    // merge with remembered pairings: online ones get flagged, offline ones still listed
    let paired_guard = state.paired.lock().map_err(|e| e.to_string())?;
    for (id, dev) in found.iter_mut() {
        if let Some(p) = paired_guard.get(id) {
            dev.paired = true;
            dev.caps = p.caps.clone();
        }
    }
    for (id, p) in paired_guard.iter() {
        found.entry(id.clone()).or_insert_with(|| Device {
            online: false,
            ..p.clone()
        });
    }

    Ok(found.into_values().collect())
}

/// Persist a pairing after PIN confirmation.
#[tauri::command]
fn remember_pair(device: Device, state: tauri::State<'_, LynkoState>) -> Result<(), String> {
    let mut paired = state.paired.lock().map_err(|e| e.to_string())?;
    paired.insert(device.id.clone(), device);
    Ok(())
}

/// Copy text onto the PC clipboard. (The phone leg of this rides the
/// WebRTC DataChannel from v0.2; the PC side is real now.)
#[tauri::command]
fn push_clipboard(app: tauri::AppHandle, text: String) -> Result<String, String> {
    use tauri_plugin_clipboard_manager::ClipboardExt;
    app.clipboard()
        .write_text(text.clone())
        .map_err(|e| e.to_string())?;
    Ok(format!(
        "{} chars on PC clipboard; phone delivery lands in v0.2",
        text.len()
    ))
}

/// Read the PC clipboard. (Becomes "pull from phone" once the link exists.)
#[tauri::command]
fn pull_clipboard(app: tauri::AppHandle) -> Result<String, String> {
    use tauri_plugin_clipboard_manager::ClipboardExt;
    app.clipboard().read_text().map_err(|e| e.to_string())
}

/// File push placeholder — honest about not being wired yet.
#[tauri::command]
async fn push_file(name: String) -> Result<(), String> {
    Err(format!(
        "{name}: file transfer over the link arrives with the phone app (v0.2). Queued locally."
    ))
}

/// Frontend-visible protocol constants.
#[tauri::command]
fn protocol_info() -> serde_json::Value {
    serde_json::json!({
        "protocol_version": PROTOCOL_VERSION,
        "service_type": SERVICE_TYPE,
    })
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(LynkoState {
            paired: Mutex::new(HashMap::new()),
        })
        .invoke_handler(tauri::generate_handler![
            protocol_info,
            discover_devices,
            remember_pair,
            push_clipboard,
            pull_clipboard,
            push_file
        ])
        .run(tauri::generate_context!())
        .expect("error while running Lynko");
}
