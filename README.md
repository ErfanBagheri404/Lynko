# Lynko

Your phone, on your desktop. Open-source Samsung Flow alternative that works with **any** Android phone — no USB cable, no USB debugging, no root.

Windows desktop app + lightweight Android companion, connected over your local network.

```
┌─────────────┐   mDNS discovery + local pairing   ┌──────────────────┐
│  Windows PC  │ ◄──────── WebRTC (P2P) ──────────► │  Android phone   │
│  (Tauri/Rust)│   H.264 video · DataChannel events │  (Kotlin native)  │
└─────────────┘                                     └──────────────────┘
```

## What it does

| Feature | How | Status |
|---|---|---|
| Live phone screen on PC | `MediaProjection` + `MediaCodec` H.264 over WebRTC video track | planned |
| Mouse → touch | `AccessibilityService.dispatchGesture` | planned |
| Keyboard → phone input | Accessibility `ACTION_SET_TEXT` | planned |
| Clipboard sync | `ClipboardManager` + foreground service | planned |
| File drag & drop | WebRTC DataChannel + `MediaStore` | planned |
| Notification mirror | `NotificationListenerService` | planned |
| Phone audio on PC | `AudioPlaybackCapture` (API 29+) | v2 |
| Battery / connection status | `BatteryManager` heartbeat | planned |
| Device discovery | mDNS (`NsdManager`) + PIN pairing | planned |

**No ADB. No USB debugging. No cloud.** Everything stays on your LAN, encrypted by WebRTC's built-in DTLS.

## Why

- Samsung Flow is locked to Samsung phones and Samsung's proprietary stack
- scrcpy is excellent but requires USB debugging enabled + ADB; first pairing needs a cable or wireless-ADB setup
- AirDroid/Vysor are proprietary and cloud-tethered
- KDE Connect covers notifications/clipboard/files but not live screen + control

Lynko fills that gap: the Flow UX (install app, grant permissions once, PC sees phone) for every Android device.

## Repository layout

```
/core      Rust crate: shared protocol, DataChannel message framing (both ends)
/desktop   Tauri 2 app (Rust + React/TypeScript)
/android   Kotlin companion app
/docs      Architecture decisions and specs
```

One repo, one version. `v0.x.y` tags build every artifact in CI and attach them to a single GitHub Release.

## Stack

- **Phone:** Kotlin (native — AccessibilityService, MediaProjection, NotificationListener are platform APIs)
- **Transport:** WebRTC — `io.getstream:stream-webrtc-android` on phone, browser WebRTC in the Tauri webview
- **Desktop:** Tauri 2 + React + TypeScript, Rust core for discovery/pairing/files
- **Protocol:** JSON over DataChannel to start, protobuf later if profiling demands

## Status

Pre-development. Docs first, desktop spike next, phone app after.

## License

MIT
