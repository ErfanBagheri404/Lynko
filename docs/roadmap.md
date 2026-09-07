# Lynko roadmap

## v0.1 — desktop shell + discovery spike

- Tauri app runs on Windows, renders UI
- mDNS browser sees `_lynko._tcp` advertisers on the LAN (test with `avahi`/phone later)
- Pairing flow stubbed (PIN exchange over local HTTP)

## v0.2 — screen + touch (the heart)

- Kotlin app: MediaProjection consent → H.264 → WebRTC video track
- Desktop: video renders, click/swipe gestures forwarded, taps land on phone
- This is the make-or-break milestone. If latency is acceptable here, everything else is plumbing.

## v0.3 — text input + clipboard + notifications

- `ACTION_SET_TEXT` path for focused fields
- Clipboard sync both directions
- Notification mirroring to desktop (filtering rules later)

## v0.4 — files + status

- Drag & drop file push/pull over DataChannel
- Battery/charging status, connection quality indicator

## v1.0 — audio, polish, releases

- `AudioPlaybackCapture` → audio track (API 29+)
- Tauri updater live, signed installers
- Cafe Bazaar + Myket listings for the APK, GitHub Releases as primary

## Later

- Protocol hardening (protobuf, if profiling shows JSON cost)
- Multi-phone switching
- Wayland/desktop-Linux support (Electron fallback decision point)
