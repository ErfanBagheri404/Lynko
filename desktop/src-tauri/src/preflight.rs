//! Windows preflight: report/set the "Lynko" firewall allow rule and run-at-login,
//! so first-run onboarding can enable both before pairing.
//!
//! Uses only std::process (netsh / reg / powershell) — no extra crates.
use serde::Serialize;
use std::process::Command;

/// HKCU autostart value we own; also the firewall rule name.
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
/// elevation prompt when the rule already matches the request.
#[tauri::command]
pub fn set_firewall(on: bool) -> Result<PreflightState, String> {
    let have = firewall_rule()?;
    if on && !have {
        let exe = current_exe()?;
        elevated_netsh(&[
            "advfirewall".into(),
            "firewall".into(),
            "add".into(),
            "rule".into(),
            format!("name={VALUE_NAME}"),
            "dir=in".into(),
            "action=allow".into(),
            // Spec prefers private/domain over `any`; ponytail: a machine whose
            // active profile is Public ignores this rule — widen to
            // profile=private,domain,public if Public-LAN traffic gets blocked.
            "profile=private,domain".into(),
            format!("program=\"{exe}\""),
        ])?;
    } else if !on && have {
        elevated_netsh(&[
            "advfirewall".into(),
            "firewall".into(),
            "delete".into(),
            "rule".into(),
            format!("name={VALUE_NAME}"),
        ])?;
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
            "reg",
            &[
                "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d",
                &format!("\"{exe}\""), "/f",
            ],
        )?;
    } else {
        // reg delete exits 1 when the value is already gone — ignore the exit
        // code; the re-read below decides success (keeps the command repeatable).
        run("reg", &["delete", RUN_KEY, "/v", VALUE_NAME, "/f"])?;
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
        firewall: firewall_rule()?,
        autostart: autostart_value()?,
    })
}

fn current_exe() -> Result<String, String> {
    std::env::current_exe()
        .map(|p| p.to_string_lossy().into_owned())
        .map_err(|e| format!("cannot resolve current exe: {e}"))
}

/// `netsh advfirewall firewall show rule name=Lynko` exits 0 iff at least one
/// rule matched (verified: 0 with rules present, 1 + "No rules match the
/// specified criteria." when absent). Non-English Windows translates that
/// message, so the exit code is the signal and stdout only confirms the name.
fn firewall_rule() -> Result<bool, String> {
    let out = Command::new("netsh")
        .args(["advfirewall", "firewall", "show", "rule", "name=Lynko"])
        .output()
        .map_err(|e| format!("netsh failed: {e}"))?;
    if out.status.success() {
        return Ok(String::from_utf8_lossy(&out.stdout).contains(VALUE_NAME));
    }
    // ponytail: a netsh failure (e.g. service down) reads as "not enabled";
    // set_firewall(true) surfaces the real error on the next attempt.
    Ok(false)
}

/// `reg query ... /v Lynko` exits 0 iff the value exists (verified 1 +
/// "ERROR: The system was unable to find..." when absent).
fn autostart_value() -> Result<bool, String> {
    let out = Command::new("reg")
        .args(["query", RUN_KEY, "/v", VALUE_NAME])
        .output()
        .map_err(|e| format!("reg failed: {e}"))?;
    Ok(out.status.success())
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

/// Run netsh elevated via UAC. Returns Err("elevation declined") when the user
/// rejects the prompt (Start-Process throws, caught by the script's catch
/// block, which prints ELEVATION_DECLINED — same catch path verified in shell).
fn elevated_netsh(args: &[String]) -> Result<(), String> {
    // PowerShell single-quoted elements; Start-Process joins -ArgumentList
    // array items with spaces and does NOT re-quote them, so the program path
    // carries its own double quotes (netsh parses those itself).
    let list = args
        .iter()
        .map(|a| format!("'{}'", a.replace('\'', "''")))
        .collect::<Vec<_>>()
        .join(",");
    let script = format!(
        "try {{ $p = Start-Process -FilePath netsh -ArgumentList @({list}) \
         -Verb RunAs -Wait -PassThru -ErrorAction Stop; exit $p.ExitCode }} \
         catch {{ Write-Output 'ELEVATION_DECLINED'; exit 1 }}"
    );
    let out = Command::new("powershell")
        .args(["-NoProfile", "-WindowStyle", "Hidden", "-Command", &script])
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
