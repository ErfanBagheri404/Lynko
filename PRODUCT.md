# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

## Users

Developers and power users who need to see, control, and interact with their Android phone from their desktop PC. Used primarily on a local network — at a desk, during development, presentations, or multitasking. No cloud, no accounts, no root.

## Product Purpose

Lynko mirrors an Android phone's screen to a desktop PC over Wi-Fi (no USB debugging for end users), enables full input control (tap, swipe, type, nav), streams audio, transfers files, relays clipboard, and surfaces notifications — all without touching the phone.

## Positioning

Zero-configuration LAN mirroring with full control. No cloud relay, no accounts, no USB cable. The phone is discovered automatically via mDNS, paired with a PIN, and fully controllable from the first frame. Samsung Flow parity without the Samsung requirement.

## Operating Context

- Phone and desktop on the same Wi-Fi network
- Developer workflow: phone on desk or in pocket, actions happen on desktop
- Presentation mode: phone screen shown on large desktop monitor
- Notification triage: see phone alerts on desktop, reply without picking up phone
- File transfer: drag files from desktop to phone (LocalSend-style, built-in)

## Capabilities and Constraints

- Screen mirroring (adaptive 30fps, JPEG frames over WebSocket)
- Audio streaming (16kHz mono PCM)
- Full input injection (tap, swipe, scroll, type via accessibility service)
- Clipboard sync (bidirectional)
- File transfer (one-way: desktop → phone)
- Notification relay with quick-reply
- USB forwarding for development (adb)
- No root required, no cloud, no accounts
- LAN-only (IPv4 preferred)
- MediaProjection requires per-session consent
- Accessibility + notification access must persist across reinstalls (MIUI challenge)

## Brand Commitments

- Name: Lynko
- Voice: Clean, direct, technical — speaks like a well-documented API
- Open source, GPL-3.0
- APK flavors: base, bazaar, myket, play
- Keystore outside GitHub

## Evidence on Hand

- Working desktop app: Tauri 2 + Rust core + React + TypeScript
- Working mobile app: Kotlin + Java-WebSocket + MediaProjection + Accessibility
- README.md with badges and feature list
- APK builds verified on Poco X3 Pro (MIUI, vayu)

## Product Principles

1. **Zero friction** — connect without configuration; the phone appears, you tap, it works
2. **No compromise** — full control, not a viewer; gestures, typing, audio, files, notifications
3. **Privacy by architecture** — LAN-only means no data leaves the network; no cloud to trust
4. **Respect the phone** — mirror is a window, not a replacement; phone stays usable
5. **Developer first** — USB forwarding, adb integration, control-flag diagnostics
