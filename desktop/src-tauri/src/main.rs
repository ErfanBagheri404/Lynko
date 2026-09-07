// Prevent a console window on Windows release builds
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use lynko_core::{Capabilities, PROTOCOL_VERSION, SERVICE_TYPE};

/// Paired phone discovered on the LAN.
#[derive(Debug, Clone, serde::Serialize)]
struct Device {
    name: String,
    address: String,
    caps: Capabilities,
}

/// Placeholder discovery: returns protocol constants so the frontend can
/// render before real mDNS browsing lands (v0.1 milestone).
#[tauri::command]
fn protocol_info() -> serde_json::Value {
    serde_json::json!({
        "protocol_version": PROTOCOL_VERSION,
        "service_type": SERVICE_TYPE,
    })
}

/// Placeholder: scans the LAN for phones. Currently returns an empty list;
/// mDNS browsing via `mdns-sd` lands in the v0.1 milestone.
#[tauri::command]
async fn discover_devices() -> Vec<Device> {
    Vec::new()
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![protocol_info, discover_devices])
        .run(tauri::generate_context!())
        .expect("error while running Lynko");
}
