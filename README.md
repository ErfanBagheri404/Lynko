<div align="center">

<img src="desktop/src-tauri/icons/icon.png" width="96" alt="Lynko icon">

# Lynko

**Your phone, on your desktop.**

An open-source alternative to Samsung Flow and AirDroid. Mirror, control and move files with **any Android phone** from Windows, Linux or macOS — over your own Wi-Fi.

**No USB · No ADB · No root · No cloud · No account**

[![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Linux%20%7C%20macOS%20%7C%20Android-2A2E33?labelColor=16181A)](https://github.com/ErfanBagheri404/Lynko/releases)
[![Release](https://img.shields.io/github/v/release/ErfanBagheri404/Lynko?logo=github&labelColor=16181A&color=FFB454)](https://github.com/ErfanBagheri404/Lynko/releases)
[![CI](https://img.shields.io/github/actions/workflow/status/ErfanBagheri404/Lynko/ci.yml?branch=main&logo=github&labelColor=16181A)](https://github.com/ErfanBagheri404/Lynko/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-FFB454?labelColor=16181A)](LICENSE)
[![Stars](https://img.shields.io/github/stars/ErfanBagheri404/Lynko?style=flat&logo=github&labelColor=16181A&color=FFB454)](https://github.com/ErfanBagheri404/Lynko/stargazers)

[Download](#-download) · [Features](#-features) · [Quick start](#-quick-start) · [How it works](#-how-it-works) · [Build](#-build) · [Roadmap](#-roadmap) · [Brand](#-brand) · [FAQ](#-faq)

</div>

---

## ✨ Features

| Feature | What it does |
|---|---|
| **Auto-discovery** | Your phone appears on the desktop by itself — mDNS on the local network |
| **PIN pairing** | A random 4-digit code, shown on the phone, confirmed once on the PC |
| **Screen mirror** | Live view in a resizable window, four presets from *Eco* (360 px) to *Native* (1080 px) |
| **Mouse → touch** | Click, scroll, swipe and long-press on the phone screen |
| **Keyboard → phone** | Type into any app through the Lynko input method |
| **Audio** | Phone audio on your PC speakers — 16 kHz mono, Android 10+ |
| **Clipboard sync** | Copy on either device, paste on the other; both directions |
| **File transfer** | Drag & drop PC → phone, or send from the phone → PC with a consent prompt and SHA-256 check |
| **Notifications** | Phone notifications on the desktop, with quick reply |
| **Status** | Battery level, charging state and connection quality |
| **Focus controls** | Block chosen apps from mirroring, hide the preview, mute animations |
| **Global hotkeys** | `Ctrl+Shift+L` show/hide the window, `Ctrl+Shift+M` toggle the mirror |
| **Update check** | The desktop app notices new releases and links the matching asset |
| **Three languages** | English, فارسی, 简体中文 (desktop); English and فارسی on the phone |
| **100% local** | No servers, no accounts, no telemetry — traffic never leaves your LAN |

## 📥 Download

Latest builds live in [**Releases**](https://github.com/ErfanBagheri404/Lynko/releases):

| Platform | Files |
|---|---|
| 🪟 **Windows** | `Lynko_x64-setup.exe` · `Lynko_x64_en-US.msi` · portable `.zip` |
| 🐧 **Linux** | `.deb` · `.rpm` · `.AppImage` |
| 🍎 **macOS** | Universal `.dmg` (Apple Silicon + Intel, unsigned) |
| 🤖 **Android** | `lynko-android-universal.apk`, plus `arm64-v8a` / `armeabi-v7a` / `x86_64` |

Android builds are debug-signed for now: install from the APK, allow "install unknown apps" when asked.

Every push to `main` also publishes a rolling **continuous** prerelease, so the desktop update check always has something newer to point at.

## 🚀 Quick start

**Phone** — Android 8.0 or newer (audio needs 10+)

1. Install the APK and open Lynko.
2. Tap **Start**.
3. Grant screen capture, accessibility and notifications — the checklist turns green as each one lands.

**Computer**

1. Install or unzip Lynko and open it on the same Wi-Fi.
2. Your phone appears in the list. Click **Pair**.
3. Type the 4-digit code shown on the phone.
4. Screen, input, clipboard, files and audio are live.

> 💡 Both devices must be on the same LAN. If Windows asks about the firewall on first launch, allow Lynko on private networks.

## 🧠 How it works

```text
┌──────────────────┐   mDNS discovery (udp 5353)   ┌──────────────────┐
│   Desktop PC     │ ──── HTTP pairing :7912 ────► │  Android phone   │
│  Tauri 2 + Rust  │ ──── WebSocket link :7913 ─── │  Kotlin (native) │
│                  │   JSON control + binary       │                  │
│  WebView UI      │   LV1 JPEG · LA1 audio · LF1  │  MediaProjection │
└──────────────────┘                               └──────────────────┘
```

- **`core/`** — Rust protocol crate shared by both sides: framing (`LV1` video, `LA1` audio, `LF1` file chunks), pairing, discovery.
- **`desktop/`** — Tauri 2 with a React + TypeScript UI, native window drops, Rust backend for the link, updates and hotkeys.
- **`android/`** — Kotlin, no heavyweight dependencies: `MediaProjection` capture, `AccessibilityService` for taps and typing, an input method for the keyboard.

One WebSocket carries everything: control JSON and binary frames are multiplexed behind a lock-guarded writer, with a backpressure-aware sender.

## 🛠️ Build

```bash
# Desktop (Windows / Linux / macOS)
cd desktop
npm ci
npm run tauri build

# Android
cd android
./gradlew assembleBaseDebug

# Rust core tests
cd core
cargo test
```

> 🐧 Linux needs the usual Tauri dependencies: `libwebkit2gtk-4.1-dev libgtk-3-dev libayatana-appindicator3-dev`. AppImage builds target Ubuntu 22.04 so they run on the oldest current LTS.

## 🗺️ Roadmap

- [ ] Hardware H.264 encoding (scrcpy-level CPU usage)
- [ ] TLS on the link socket and a rotating PIN
- [ ] Signed and notarized macOS build
- [ ] Multi-phone switching
- [ ] Wayland-friendly screen path on Linux

## 🎨 Brand

The mark is a stair glyph — steps, not a cable — because the point is getting somewhere, not the wire. It sits on a near-black tile with a single amber signal colour.

<img src="desktop/src-tauri/icons/icon.png" width="72" alt="Lynko mark">

| Role | Colour |
|---|---|
| Signal (mark, accents) | `#FFB454` |
| Signal, deep | `#C98B2E` |
| Paper (background) | `#0A0B0D` |
| Card | `#121316` |
| Ink (primary text) | `#E8E6E1` |
| Muted text | `#8B8D90` |
| Connected | `#4ADE80` |

Type is the system UI stack. The launcher icon keeps the glyph inside the 66 dp adaptive safe zone, and there is a monochrome layer for themed icons.

## ❓ FAQ

<details>
<summary><b>Do I need USB debugging?</b></summary>
No. Lynko never touches ADB. Pairing is PIN-based over your LAN.
</details>

<details>
<summary><b>Where does my data go?</b></summary>
No cloud, no accounts, no telemetry. All traffic stays on your local network.
</details>

<details>
<summary><b>Is the connection encrypted?</b></summary>
Not yet — video, audio, input and clipboard ride plain WebSocket on your local network, and the 4-digit PIN gates pairing. Use Lynko on networks you trust, and treat public or shared Wi-Fi as off-limits until TLS lands.
</details>

<details>
<summary><b>Why a PIN at all?</b></summary>
It stops someone else on the same network from pairing with your phone silently. The code is generated per install and shown only on the phone.
</details>

<details>
<summary><b>The phone never shows up</b></summary>
Check that both devices are on the same subnet and that the phone's Wi-Fi allows multicast. On Windows, allow Lynko through the firewall for private networks. Guest networks that isolate clients (AP isolation) block discovery entirely.
</details>

<details>
<summary><b>Nothing responds to clicks</b></summary>
The accessibility service must stay enabled. MIUI, ColorOS and similar skins sometimes revoke it after an update — reopen the phone app and confirm every checklist item is green.
</details>

<details>
<summary><b>iOS / iPhone support?</b></summary>
Not planned — Apple's platform doesn't allow screen capture the way Android does.
</details>

## 🙏 Credits

- [LocalSend](https://localsend.org) (Apache-2.0) — Lynko's file transfer flow (manifest → consent → upload → SHA-256 verify) is adapted from LocalSend's v2 receive protocol.
- Built with [Tauri 2](https://tauri.app), [Rust](https://rust-lang.org), [Kotlin](https://kotlinlang.org) and [Android NsdManager](https://developer.android.com/training/connect-devices-wirelessly/nsd).

<div align="center">

**Apache-2.0** · ErfanBagheri404

</div>
