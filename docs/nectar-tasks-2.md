# NectarLink follow-up tracks (batch 2)

Remaining "what to steal first" items from [[nectarlink]] research that were
NOT covered by batch 1 (transfer resume, device switches, OTP dismiss sync).

## Track E — Capability negotiation in `hello`

**Goal:** Phone advertises a capability matrix on connect; desktop greys out
features the phone can't do. Foundation for every later feature.

**Scope:**
- Phone (`android/.../LinkService.kt`): emit `caps` object in the `hello`
  event on WebSocket connect. Keys: screen_capture, input_injection,
  text_input, clipboard_sync, file_transfer, notifications, audio_capture,
  battery_status, otp_copy, dismiss_sync, elevated_mode, storage_browse,
  photos_browse, clipboard_history, calls_sms, toggles, recording,
  find_device, handoff, continuity_camera, lock_sleep. Values are booleans.
- Desktop (`desktop/src/App.tsx`, `enhancements/`): store `caps` per connected
  device in state. Grey out / hide unsupported features (disable Mirror if
  no screen_capture, disable Audio if no audio_capture, etc.). Show a
  "Capabilities" section in Settings or the device card.
- Protocol: `hello` event carries `{caps: {...}}`.
- Tests: desktop unit test for caps gating; phone unit test for caps emission.

**Verification:** `npm run build` (tsc) + `npm test` (mjs) + gradle unit tests.

## Track F — Security statement + threat table

**Goal:** Documented security model as a trust surface (docs + UI panel).

**Scope:**
- `docs/SECURITY.md`: threat model, threat table, security properties,
  browser drive-by defense, constant-time checks, encryption status
  (WebSocket/HTTP on LAN, DTLS if applicable), pairing PIN, device keys.
- Update `docs/architecture.md` threat model section to match current
  implementation (WebSocket JSON + HTTP transfer, not WebRTC).
- Desktop UI: "Security" section in Settings linking to SECURITY.md + showing
  key security properties.
- Phone UI: "Security" info screen (optional, minimal).

**Verification:** docs render; UI compiles.

## Track G — Power Levels (elevated mode)

**Goal:** Detect and expose a Power Level ladder so users can unlock
consent-gated capabilities on MIUI without root via Wireless debugging.

**Scope:**
- Phone: detect current level. L1 = basic (always). L2 = accessibility
  service enabled. L3 = wireless debugging available / shell access gained.
  Expose via `power_level` in caps or a dedicated event.
- Desktop: Power Level indicator + guide to elevate (instructions for enabling
  Wireless debugging in Developer options).
- Settings: "Power Level" section with current level + instructions.
- Document the unlock path in docs/POWER-LEVELS.md.

**Verification:** compiles; UI shows level.

## Track H — Clipboard history with at-rest encryption

**Goal:** Persist clipboard history (50 items, 7-day retention) with at-rest
encryption on desktop, synced from phone clipboard events.

**Scope:**
- Desktop: encrypted storage (OS keychain via Tauri). Store up to 50 items,
  7-day retention, FIFO eviction. Use the same .part + BLAKE3 + FIFO queue
  storage design as file transfer (resume-anything).
- Phone clipboard events already sync; desktop persists them.
- UI: clipboard history panel on desktop (list, copy, delete, clear).
- Tests: unit tests for eviction/retention logic.

**Verification:** compiles; unit tests pass.