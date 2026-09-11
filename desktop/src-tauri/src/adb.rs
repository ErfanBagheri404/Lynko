use anyhow::{anyhow, Result};
use serde::Serialize;
use std::path::PathBuf;
use tokio::process::Command;

#[cfg(windows)]
use std::os::windows::process::CommandExt;

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x08000000;

// ── ADB binary discovery ────────────────────────────────────────────

fn adb_path() -> Option<PathBuf> {
    let name = if cfg!(windows) { "adb.exe" } else { "adb" };

    // Android SDK standard locations
    if let Ok(home) = std::env::var("HOME") {
        let p = PathBuf::from(&home)
            .join("Library/Android/sdk/platform-tools")
            .join(name);
        if p.exists() { return Some(p); }
    }
    if let Ok(local) = std::env::var("LOCALAPPDATA") {
        let p = PathBuf::from(&local)
            .join("Android/Sdk/platform-tools")
            .join(name);
        if p.exists() { return Some(p); }
    }
    if let Ok(android) = std::env::var("ANDROID_HOME") {
        let p = PathBuf::from(&android).join("platform-tools").join(name);
        if p.exists() { return Some(p); }
    }
    // PATH fallback
    Some(PathBuf::from(name))
}

fn adb_cmd() -> Command {
    let mut cmd = Command::new(adb_path().unwrap_or_else(|| PathBuf::from("adb")));
    #[cfg(windows)]
    { cmd.creation_flags(CREATE_NO_WINDOW); }
    cmd
}

async fn run_adb(args: &[&str]) -> Result<String> {
    let out = adb_cmd()
        .args(args)
        .output()
        .await
        .map_err(|e| anyhow!("adb not found or failed: {e}"))?;
    if !out.status.success() {
        let stderr = String::from_utf8_lossy(&out.stderr);
        return Err(anyhow!("adb {} failed: {}", args.join(" "), stderr.trim()));
    }
    Ok(String::from_utf8_lossy(&out.stdout).to_string())
}

// ── Public types ────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize)]
pub struct UsbDevice {
    pub serial: String,
    pub model: String,
    pub state: String,
}

// ── Device enumeration ──────────────────────────────────────────────

pub async fn list_devices() -> Result<Vec<UsbDevice>> {
    let stdout = run_adb(&["devices", "-l"]).await?;
    let mut devices = Vec::new();

    for line in stdout.lines().skip(1) {
        let line = line.trim();
        if line.is_empty() { continue; }
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() < 2 { continue; }
        let serial = parts[0].to_string();
        let state = parts[1].to_string();
        if state != "device" { continue; }
        if serial.starts_with("emulator") { continue; }
        let model = parts
            .iter()
            .find(|p| p.starts_with("model:"))
            .map(|p| p.trim_start_matches("model:").to_string())
            .unwrap_or_else(|| serial.clone());

        devices.push(UsbDevice { serial, model, state });
    }
    Ok(devices)
}

// ── Port forwarding ─────────────────────────────────────────────────

pub async fn forward(serial: &str, local_port: u16, remote_port: u16) -> Result<()> {
    run_adb(&[
        "-s", serial, "forward",
        &format!("tcp:{local_port}"),
        &format!("tcp:{remote_port}"),
    ]).await?;
    Ok(())
}

pub async fn remove_all_forwards(serial: &str) {
    let _ = run_adb(&[
        "-s", serial, "forward", "--remove-all",
    ]).await;
}

/// adb shell — kept for future use (e.g. waking the screen via keyevent).
#[allow(dead_code)]
pub async fn shell(serial: &str, cmd: &str) -> Result<String> {
    run_adb(&["-s", serial, "shell", cmd]).await
}

/// Lynko's canonical ports (pair 7912 / link 7913 / transfer 7914).
/// We forward LOCAL ports with the SAME numbers, so the USB device is
/// dialable at 127.0.0.1 with the unchanged pair/link/transfer code paths
/// — no port plumbing anywhere else needed.
pub const FORWARDED_PORTS: [u16; 3] = [crate::PAIR_PORT, crate::LINK_PORT, crate::TRANSFER_PORT];

/// Set up ADB localhost:port -> device:port forwards for one USB device.
/// After this, dialing 127.0.0.1:7912/7913/7914 reaches the phone through
/// the USB data lines — no Wi-Fi, no mDNS, unaffected by VPN.
pub async fn setup_forwards(serial: &str) -> Result<()> {
    remove_all_forwards(serial).await; // stale forwards from a previous session
    for &p in &FORWARDED_PORTS {
        forward(serial, p, p).await?;
    }
    Ok(())
}
