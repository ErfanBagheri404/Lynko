# Lynko — Windows-complete plan

Status of the desktop as of this plan: shell + real mDNS scan + PC-side clipboard
work. The remaining Windows work is the **link layer**: a real connection to a
phone (or the simulator) with live events, commands, and binary streams. UI for
every feature view already exists; this plan wires them to the backend for real.

## Architecture (one link, everything over it)

```
Desktop (Rust)                          Phone / Simulator (Rust or Kotlin)
┌────────────────────┐                  ┌────────────────────────────┐
│ mDNS browser       │←─ _lynko._tcp ──│ mDNS advertiser            │
│ pairing HTTP client│── POST /pair ──→│ tiny_http pairing server   │
│ WS control link    │←── ws://…:7913 ─│ WS server (signaling+ctrl) │
│   ├ commands →     │                 │   ├ executes / replies     │
│   ├ events ←       │                 │   ├ emits (battery, notes) │
│   └ files (bin) →  │                 │   └ writes to downloads    │
└────────────────────┘                  └────────────────────────────┘
Screen+audio later: WebRTC over the same signaling WS (view stays ready).
```

- **Discovery:** mDNS browse runs forever as a Tauri event pump. New/lost
  services emit `discovery` events to the frontend (radar updates live, no
  scan button dependency).
- **Pairing:** desktop POSTs to `http://<phone>:7912/pair` with a 4-digit PIN.
  Simulator always accepts PIN 1234. On success the desktop stores the phone in
  `%APPDATA%/lynko/pairs.json` and re-announces it as `paired: true`.
- **Control link:** after pairing, desktop dials `ws://<phone>:7913/link`.
  JSON commands (`copy`, `paste`, `send_file`, `start_screen`, `battery_get`,
  `notify_test`) and events (`battery`, `notification`, `clipboard`,
  `file_progress`, `log`) flow over one socket. File payloads are binary WS
  frames with a small chunk header.
- **Frontend:** Rust state machine is the single source of truth; UI reflects
  it. Views subscribe to events; every view works against the simulator.

## Work order (each step compiles + runs before the next)

1. **lynko-core v1 protocol** — message enums (serde), chunk framing, pair
   request/response types, roundtrip tests. Both ends share it.
2. **Desktop Rust link layer** — mDNS daemon (continuous, evented), pairing
   client, WS control link with reconnect, persistent pair store. Commands
   callable from UI; events pushed out via Tauri event API.
3. **Simulator** — a fake phone binary in `sim/` implementing the other end of
   all three planes (mDNS, pairing, WS). Lets Windows work be finished and
   demoed today without an APK.
4. **Frontend wiring** — discovery events → radar; pairing dialog → HTTP PIN;
   link state → chips; battery/notifications → stream + toasts; clipboard
   bidirectional; files both directions with progress; screen/audio views ready
   for WebRTC with honest disabled states.
5. **E2E pass** — run simulator, walk every view live, fix in one batch.
6. **Polish pass** — craft-floor screenshot review per view with live data.
7. **CI** — tag-driven release workflow (MSI + NSIS + exe on GitHub Release).
8. **Commit/push.**

## What "Windows complete" means here

- Discovery, pairing, connect, disconnect — real, evented, persistent
- Clipboard sync — real both directions (PC↔sim/phone)
- Files — real send + receive with progress (sim writes to disk)
- Notifications — real stream from phone + Windows toast
- Battery — real events updating chip
- Screen/audio — transport ready (signaling via WS), video pipeline lands with
  the Android app (honest "needs phone" state, not fake UI)
- No dead buttons: every control either does its real job or explains exactly
  what it needs

## Risks / notes

- Phone app (Kotlin/WebRTC) is next phase; simulator stands in until then
- WebRTC receive view ships wired but dormant until the phone side exists
- Pairing PIN validation is the phone's job; desktop just shows and submits
