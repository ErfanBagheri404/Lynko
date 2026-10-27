# LocalSend behavior study & Lynko implementation

Reference: LocalSend v2.2 (OpenSend/LocalSend, the open-source LAN file-transfer app). We studied its
sharing tabs and reused its *behaviors and features* — never its attachment/attachment-picker UIs,
which are unsuitable for Lynko. This file is the spec of what was borrowed and where it landed in Lynko.

## Behaviors borrowed

| # | LocalSend behavior (v2.2) | Lynko implementation |
|---|---|---|
| 1 | **Named cause + way out** — when no receiver is selected, LocalSend doesn't show a dead dropzone; it says why (e.g. "same network required") and offers a real path forward. | `App.tsx` `FilesView` target card: `.share-target.off` row — `shareTarget` label + `shareTargetNone` ("No phone linked") + `shareSameWifi` hint + `shareTroubleshoot` button that switches to the devices (pairing) tab. CSS in `styles.css` (`.share-target`, `.share-target-none`, `.share-target-hint`). |
| 2 | **File-drop without a link = guided failure** — dropping files with no connected peer must not throw a raw hook error; it names the cause and opens pairing. | `App.tsx` `dropHandler`: if `!linkRef.current.connected && paths.length` → toast `sharePickRecipient` ("Pick a recipient first"), `setView("devices")`. |
| 3 | **Live radar = reachable devices only** — LocalSend's discover tab lists only live peers; offline mDNS ghosts are not tappable. | `DevicesView`: `unpaired = devices.filter(d => !d.paired && d.online)`; offline entries are not rendered as pairable. |
| 4 | **QR standard polarity** — QR renders dark modules on light background (inverted QRs only decode on inversion-tolerant scanners — most phone cameras are not). | `DevicesView` QR: `width 240`, `margin 4` (spec required quiet zone), `color: {dark: "#0a0b0d", light: "#ffffff"}`. |
| 5 | **Share offer sheet (spec 4.1)** — sender identity + file list, accept/decline, progress while streaming, history of past transfers. | `IncomingShare.tsx`: `IncomingShare` (offer: sender + files + Accept/Close) and `ShareHistory` (past transfers with size/duration); `ShareProgress` inline progress; strings in `locales/en.json` / `fa.json` (keys added in commit `ebfb094`). |
| 6 | **Transfer progress stream** — byte-level progress during receive, cancelable. | `src-tauri/src/transfer_receive.rs`: streaming receive with progress events to the UI (`transfer-progress`), cancel command wired in `main.rs`. |
| 7 | **History delete only when non-empty** — the "Delete history" action must be hidden when there is nothing to delete. | `FilesView`: delete-history button rendered only when history has entries (commit `8ee6884`); verified in the built app via CDP (`delete button present: False` when empty, `The history is empty.` placeholder). |
| 8 | **Radar hint** — persistent one-liner under the radar telling the user what's needed to see peers (same network). | `.radar-hint` block in `DevicesView` + CSS `.radar-hint`. |

## What was explicitly NOT borrowed

- LocalSend's attachment picker / file-list UIs (card grids, attachment rows): rejected — unsuitable for Lynko's
  file-transfer model (Lynko receives from a phone's Share Sheet, it doesn't pick attachments).
- LocalSend's global hotkey/launcher UI: Lynko keeps its own hotkeys (`enhancements/hooks.ts` `useHotkeys`).

## Files touched (uncommitted)

- `desktop/src/App.tsx` — target card, drop handler, radar/QR fixes, FilesView history block (+203/-16 lines)
- `desktop/src/enhancements/IncomingShare.tsx` — offer + progress + history components
- `desktop/src/enhancements/hooks.ts` — `useTransfers`/`usePreferences` wiring
- `desktop/src/enhancements/styles.css` — history/empty/hint styles
- `desktop/src/styles.css` — `.share-target*`, `.radar-hint`, ink fixes
- `desktop/src/icons.tsx` — IconBell/IconBellOff/IconCamera/IconPin
- `desktop/src/locales/en.json`, `fa.json` — shareTarget, shareTargetNone, shareSameWifi, shareTroubleshoot,
  sharePickRecipient, shareClose, shareAccept, shareHistory (+46 lines each)
- `desktop/src-tauri/src/main.rs`, `transfer_receive.rs`, `Cargo.toml/.lock`, gen schemas — receive/progress/cancel commands
- `core/src/lib.rs`, `sim/src/main.rs` — core + sim harness updates
- `android/...` — phone-side share sender + consent + privacy strings (Kotlin files listed in git status)

## E2E

`e2e_test.py` (repo root) drives the sim (simulator) end-to-end: phone share → desktop receive.
Run: `python e2e_test.py` from the repo root after `cargo build --release` (sim + core).
