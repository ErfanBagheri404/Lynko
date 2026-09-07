# Lynko architecture

## Goal

Reproduce the Samsung Flow experience for any Android phone: install companion app,
grant a few one-time permissions, and the desktop discovers and pairs with the phone
over the LAN. No USB debugging, no ADB, no root, no cloud service.

## Non-goals (v1)

- iOS (no MediaProjection/Accessibility equivalent exists for third parties)
- Cellular/remote access (LAN only)
- Google Play distribution initially (AccessibilityService policy + account constraints)

## Components

### Phone — `android/` (Kotlin, native)

Single app, one foreground service hosting all capabilities:

- **Capture:** `MediaProjection` consent → `MediaCodec` H.264 encode → WebRTC video track.
  Consent dialog appears once per session (Android behavior, unavoidable without ADB).
- **Input:** `AccessibilityService.dispatchGesture` for taps/swipes/drags, `ACTION_SET_TEXT`
  for text fields. Multi-stroke pinch supported (API 26+). Raw key injection (games) is weaker
  than ADB — accepted v1 limitation.
- **Notifications:** `NotificationListenerService` → DataChannel JSON events.
- **Clipboard:** `ClipboardManager` reads in foreground service (Android 10+ blocks
  background reads; foreground exemption is the whole point of the service).
- **Files:** `MediaStore` writes; DataChannel binary chunks for transfer.
- **Discovery:** `NsdManager` mDNS advertise (`_lynko._tcp`), plus embedded Ktor HTTP/WS
  listener for local signaling (SDP/ICE exchange with the desktop).
- **Battery/status:** `BatteryManager` + heartbeat on the DataChannel.

Permissions the user grants once (plus per-session screen consent): Accessibility,
Notification access. Min SDK 26; audio capture requires 29+.

### Desktop — `desktop/` (Tauri 2, React/TS, Rust)

- WebView2 renders the WebRTC `<video>` element (Chromium → full WebRTC support)
- Mouse/keyboard capture, drag & drop targets in the frontend
- Rust side: mDNS discovery (`mdns-sd` crate), pairing state, file IO, updater
  (`tauri-plugin-updater`)

### Shared — `core/` (Rust crate)

Protocol version, capability flags, DataChannel message types, framing. Compiled into
the Tauri app and (later) into the Android app via JNI.

## Connection flow

```
1. Phone app starts foreground service, advertises _lynko._tcp via NSD
2. Desktop browses mDNS, sees "Erfan's Pixel" on the LAN
3. Desktop → phone HTTP: GET /pair (PIN shown on phone, entered on desktop)
4. Pairing succeeds → both sides store a device key
5. WebRTC signaling over the local WebSocket: SDP offer/answer + ICE
6. P2P connection established: video track + audio track + DataChannel
7. DataChannel carries: input events, clipboard, notifications, file chunks, status
```

## Versioning

Single source of truth: git tag. CI injects into `tauri.conf.json`, Gradle
`versionName`/`versionCode`. Pairing handshake exchanges protocol versions and
capability flags; mismatch warns but never hard-fails unless breaking.

## Threat model (LAN)

- mDNS discovery is visible to everyone on the LAN — pairing requires PIN, unpaired
  devices get nothing beyond a name
- All media/data rides DTLS-SRTP (WebRTC) — no plaintext transport
- No cloud relay, no account, no telemetry
