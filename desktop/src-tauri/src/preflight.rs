//! Windows preflight: report/set the "Lynko" firewall allow rule and run-at-login,
//! so first-run onboarding can enable both before pairing.
//!
//! Firewall state comes from PowerShell's structured NetSecurity cmdlets (no
//! localized netsh text; the rule is matched by program path + enabled +
//! inbound + allow, so a renamed, disabled, blocking or stale-path rule reads
//! as off). Elevation runs an `-EncodedCommand`, so no path or rule name is
//! ever interpolated into a command line.
use serde::Serialize;
use std::process::Command;

/// HKCU autostart value we own; also the firewall rule display name.
const VALUE_NAME: &str = "Lynko";
const RUN_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";

#[derive(Serialize)]
pub struct PreflightState {
    firewall: bool,
    autostart: bool,
}

#[tauri::command]
pub fn preflight_state() -> Result<PreflightState, String> {
    read_state()
}

/// Add (or remove) the inbound allow rule for this exe. Idempotent: skips the
/// elevation prompt when the current state already matches the request.
#[tauri::command]
pub fn set_firewall(on: bool) -> Result<PreflightState, String> {
    let rules = firewall_rules()?;
    if on && rules.is_empty() {
        let exe = current_exe()?;
        // Spec prefers private/domain over `any`; ponytail: a machine whose
        // active profile is Public ignores this rule — widen the -Profile list
        // if Public-LAN traffic gets blocked.
        elevate(&format!(
            "New-NetFirewallRule -DisplayName {} -Direction Inbound -Action Allow \
             -Program {} -Profile Private,Domain | Out-Null",
            ps_str(VALUE_NAME),
            ps_str(&exe)
        ))?;
    } else if !on && !rules.is_empty() {
        // Remove every rule that matches this exe, whatever it is called.
        let script = rules
            .iter()
            .map(|n| format!("Remove-NetFirewallRule -Name {};", ps_str(n)))
            .collect::<String>();
        elevate(&script)?;
    }
    let state = read_state()?;
    if state.firewall != on {
        return Err(if on {
            "failed to enable firewall rule".into()
        } else {
            "failed to remove firewall rule".into()
        });
    }
    Ok(state)
}

/// Write (or delete) the HKCU Run value pointing at this exe. Idempotent.
#[tauri::command]
pub fn set_autostart(on: bool) -> Result<PreflightState, String> {
    if on {
        let exe = current_exe()?;
        // Quoted so paths with spaces survive CreateProcess at login.
        run(
            &sys32("reg"),
            &[
                "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d",
                &format!("\"{exe}\""), "/f",
            ],
        )?;
    } else {
        // reg delete exits 1 when the value is already gone — ignore the exit
        // code; the re-read below decides success (keeps the command repeatable).
        run(&sys32("reg"), &["delete", RUN_KEY, "/v", VALUE_NAME, "/f"])?;
    }
    let state = read_state()?;
    if state.autostart != on {
        return Err(if on {
            "failed to enable autostart".into()
        } else {
            "failed to remove autostart".into()
        });
    }
    Ok(state)
}

// ── internals ──────────────────────────────────────────────────────

fn read_state() -> Result<PreflightState, String> {
    Ok(PreflightState {
        firewall: !firewall_rules()?.is_empty(),
        autostart: autostart_value()?,
    })
}

fn current_exe() -> Result<String, String> {
    std::env::current_exe()
        .map(|p| p.to_string_lossy().into_owned())
        .map_err(|e| format!("cannot resolve current exe: {e}"))
}

/// System32 from the OS, not from an inherited `SystemRoot` (which a launcher
/// could point anywhere). A packaged GUI process inherits PATH from whatever
/// launched it, so tools are always called by absolute path (issue #29, BUG-003).
fn sys32(tool: &str) -> String {
    format!(r"{}\{tool}.exe", system32_dir())
}

fn system32_dir() -> String {
    let mut buf = [0u16; 260];
    let n = unsafe {
        windows_sys::Win32::System::SystemInformation::GetSystemDirectoryW(
            buf.as_mut_ptr(),
            buf.len() as u32,
        )
    };
    if n > 0 && (n as usize) < buf.len() {
        return String::from_utf16_lossy(&buf[..n as usize]);
    }
    r"C:\Windows\System32".into()
}

fn powershell() -> String {
    sys32(r"WindowsPowerShell\v1.0\powershell")
}

/// Enabled inbound allow rules whose program is this exe — by name, so a
/// disable removes them even when they were renamed.
fn firewall_rules() -> Result<Vec<String>, String> {
    const QUERY: &str = r#"$ErrorActionPreference='Stop'
Get-NetFirewallApplicationFilter | Where-Object { $_.Program -ieq $env:LYNKO_EXE } |
  Get-NetFirewallRule |
  Where-Object { $_.Enabled -eq 'True' -and $_.Direction -eq 'Inbound' -and $_.Action -eq 'Allow' } |
  ForEach-Object { $_.Name }"#;
    let out = Command::new(powershell())
        .args(["-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", QUERY])
        .env("LYNKO_EXE", current_exe()?)
        .output()
        .map_err(|e| format!("firewall query failed: {e}"))?;
    // A service/tool failure is an error, not "the rule is off".
    if !out.status.success() {
        return Err(format!(
            "firewall query failed: {}",
            String::from_utf8_lossy(&out.stderr).trim()
        ));
    }
    Ok(String::from_utf8_lossy(&out.stdout)
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .map(str::to_owned)
        .collect())
}

/// True only when the Run value exists *and* points at this executable, so a
/// moved/reinstalled install reads as off instead of enabled.
fn autostart_value() -> Result<bool, String> {
    let out = Command::new(sys32("reg"))
        .args(["query", RUN_KEY, "/v", VALUE_NAME])
        .output()
        .map_err(|e| format!("reg failed: {e}"))?;
    if !out.status.success() {
        return Ok(false);
    }
    let text = String::from_utf8_lossy(&out.stdout).to_lowercase();
    let Some(idx) = text.find("reg_sz") else {
        return Ok(false);
    };
    let data = text[idx + "reg_sz".len()..].trim().trim_matches('"').trim();
    Ok(data == current_exe()?.to_lowercase())
}

/// Spawn a helper and ignore its exit code — callers re-read real state instead
/// of parsing tool-specific exit codes (reg delete is 1 when already absent).
fn run(cmd: &str, args: &[&str]) -> Result<(), String> {
    Command::new(cmd)
        .args(args)
        .output()
        .map_err(|e| format!("{cmd} failed: {e}"))?;
    Ok(())
}

/// PowerShell single-quoted literal: only `'` needs escaping (doubled).
fn ps_str(s: &str) -> String {
    format!("'{}'", s.replace('\'', "''"))
}

/// Run a PowerShell snippet elevated via UAC. The snippet travels as base64
/// (UTF-16LE, `-EncodedCommand`), so paths and rule names cannot break out of
/// the command line. Err("elevation declined") when the user rejects the prompt.
fn elevate(script: &str) -> Result<(), String> {
    use base64::Engine;
    let inner: Vec<u8> = format!("$ErrorActionPreference='Stop'\n{script}")
        .encode_utf16()
        .flat_map(u16::to_le_bytes)
        .collect();
    let enc = base64::engine::general_purpose::STANDARD.encode(inner);
    let outer = format!(
        "try {{ $p = Start-Process -FilePath {} -ArgumentList \
         @('-NoProfile','-NonInteractive','-WindowStyle','Hidden','-EncodedCommand','{enc}') \
         -Verb RunAs -Wait -PassThru -ErrorAction Stop; exit $p.ExitCode }} \
         catch {{ Write-Output 'ELEVATION_DECLINED'; exit 1 }}",
        ps_str(&powershell())
    );
    let out = Command::new(powershell())
        .args(["-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", &outer])
        .output()
        .map_err(|e| format!("powershell failed: {e}"))?;
    if String::from_utf8_lossy(&out.stdout).contains("ELEVATION_DECLINED") {
        return Err("elevation declined".into());
    }
    if !out.status.success() {
        return Err(format!(
            "firewall change failed (exit {})",
            out.status.code().unwrap_or(-1)
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::ps_str;

    #[test]
    fn ps_str_escapes_quotes() {
        assert_eq!(
            ps_str(r"C:\Program Files\Lynko\lynko.exe"),
            r"'C:\Program Files\Lynko\lynko.exe'"
        );
        assert_eq!(ps_str("it's"), r"'it''s'");
    }
}
