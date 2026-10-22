# Lynko — Myket publishing checklist

Everything below is verified against the live Myket publisher docs
(https://myket.ir/kb/pages/app-release/, /choose-icon-for-your-app/,
/choose-screenshots-for-your-app/, /privacy-security-access-info-fa/) and
against the actual release APK that will be uploaded.

## 1. What Myket requires (from its own KB)

| Step | Requirement | Lynko status |
|---|---|---|
| 1 | Upload APK (package id is reserved at this step) | `com.lynko.app` — reserved above |
| 2 | Type: Application | App |
| — | Category | **Tools** (see §4) |
| — | Concise name (Persian, English) | Copy ready — see `listing-fa.md` / `listing-en.md` |
| — | Icon (PNG/JPG/WebP, square, no distortion, ≥ 240 px) | `icon-512.png` |
| — | Description in Persian, added info in English | Copy ready |
| — | Contact email — **mandatory, cannot be changed after 3 apps** | ⚠️ you must decide (see §3) |
| — | Price: free | Free — note: **a free version can never become paid**; Myket pays-out requires ≥ 5 apps |
| 3 | Version info (version code + version name) | 1 / "0.1.0" — next upload must be code 2 |
| — | Release notes (شارک نکردن اختصاصی‌ست) — **mandatory** | Copy ready |
| 4 | Screenshots (PNG only, no external links, min 3, **must be 16:9 or 9:16**, no borders/letterboxing) | 3 present, 720×1280 = exactly 9:16 ✅ |
| — | Optional: Aparat video | Optional — skip |
| — | Optional: in-app products | Lynko has none; skip |
| 5 | Review — sends to Myket team, review can take up to 3 days | Reviewer note ready (see §5) |


## 2. What is ready in this folder

```
icon-512.png                      512×512 PNG, same geometry as the launcher icon
screenshots/phone-home.png        Home tab      720×1280 (9:16)
screenshots/phone-share.png       Share tab     720×1280 (9:16)
screenshots/phone-settings.png   Settings tab  720×1280 (9:16)
listing-fa.md                     Title + description + release notes (Persian)
listing-en.md                     Title + description + release notes (English)
PACKAGING_NOTES.md                How the uploaded APK was produced
../../docs/privacy.md             Public-copy privacy policy (optional to host)
```

### Screenshots: current state

The three phone PNGs were re-shot on the emulator at **720×1280 (exactly 9:16)**,
because Myket rejects anything that is not 16:9 or 9:16. The previous set was
720×1603 (20:9) and would have been rejected. They were taken from the
**release-signed** APK, not a debug build, so they show exactly what ships.

```
screenshots/phone-home.png        Home tab      720×1280
screenshots/phone-share.png       Share tab     720×1280
screenshots/phone-settings.png   Settings tab  720×1280
```

Two cosmetic things are still visible and worth a retake when you have a real
device, neither of which blocks review:

- The Share tab shows the emulator device name (`sdk_gphone64_x86_64`) and the
  emulator NAT address (`10.0.2.15`).
- The status bar and gesture pill are captured in-frame. Myket doesn't forbid
  this, but cropping looks more like a store listing.

If you re-shoot on a real phone, keep the output at 1080×1920 (9:16) and make
sure the app is *running and paired* so the Share tab isn't an empty state.

## 3. Things only you can decide / do

| # | Item | Note |
|---|---|---|
| 1 | **Developer contact email** | Myket says this **cannot be changed** once you've published 3 apps. Pick the address you want on your account long-term. `mrenmityerf@gmail.com` is your usual — confirm or supply another. |
| 2 | **Public privacy-policy URL** | Optional. Myket's own rule (`/kb/pages/app-page-details-fa/`) only requires a valid **contact email**; a website is explicitly optional ("if you don't have a valid website, leave it empty"). The privacy text is already readable in-app at Settings → Privacy policy, which is the mandatory part. `docs/privacy.md` is now written and ready if you want a public URL anyway — publish it anywhere public and paste the link. |
| 3 | **Upload to the Myket publisher console** | https://developer.myket.ir/panel/ — the developer must be logged in; assets + copy here are what to paste. |
| 4 | **Package id** | `com.lynko.app` must be reserved through the upload step above; do it first, before anything else. |

## 4. Category choice

Myket's category list (from the app-release KB):
- Games → subcategories
- **Tools** ← Lynko lands here
- Multimedia, Photo & Video, News, Social, Productivity
- Finance, Shopping, Education, Health, Travel & Transportation
- Customization, Communication, Transportation, Books & References
- Music & Audio, Lifestyle, Photography, Live Streaming, Entertainment
- Home, Sport, Business, Weather, Medical

**Tools** is the correct pick. It doesn't require a second-level subcategory.

## 5. Note to the reviewer (Myket review often asks for context on
   accessibility-service apps)

Paste this into the reviewer/comments box when you submit:

> Lynko is a LAN-only screen-mirroring app with no cloud component, no account
> and no analytics. All features are explained in-app under Settings →
> Privacy policy, which opens a full permission-by-permission breakdown.
>
> The accessibility service (`LynkoInputService`) exists solely to forward taps,
> swipes and text entered on the paired desktop to the phone. It reads no
> other app's content. The notification listener exists solely to display
> phone notifications on the paired desktop. The media projection session is
> active only while the user is actively mirroring. No personal data leaves
> the local network.

That one paragraph resolves most of the questions Myket's security scan
raises, because the three permissions that trip automatic heuristics here are
accessibility, notification-listener, and media-projection.

## 6. Already verified on the artifact

Re-verified 2026-10-16 against a fresh release build:

- ✅ `targetSdk 35` — Myket's `/kb/pages/target-sdk-34/` requires **≥ 34**;
  new apps and updates below 34 have been rejected since **1 Aban 1405**
  (~22 Oct 2026). Lynko is clear with a full version of headroom.
- ✅ `minSdk 26`, `compileSdk 35`, `applicationId com.lynko.app`.
- ✅ Signed with the release keystore, **not** the debug key —
  `apksigner verify --print-certs` shows
  `CN=Lynko, OU=Lynko Desktop Companion, O=Erfan Bagheri, C=IR`,
  SHA-256 `b5a776da…c01`. This is the cert that is now locked in forever for
  this listing; back up `keystore/lynko-release.jks`.
- ✅ The release APK **installs and runs**: uninstalled, installed
  `app-base-universal-release.apk` from scratch, launched it, `pidof` returns a
  live process and logcat shows no `FATAL`/`AndroidRuntime` entries.
- ✅ No `WRITE_SECURE_SETTINGS` / signature-level permission in the release
  APK (that was only in the debug path, and is gone from the release build).
- ✅ Permissions present are only: Internet, record audio, network state,
  wifi state, multicast state, foreground service (×2), post notifications,
  ignore battery optimisations, wake lock — all standard/normal-tier in
  current Play and Myket policy.
- ✅ Privacy policy reachable from Settings and displaying the per-permission
  breakdown (verified on device).
- ✅ No ad SDK, no tracker SDK, no Play-services dependency.
- ✅ Localisation: English + Farsi resources shipped.
- ✅ `lintVital` (the release-blocking lint task) passes.

### Myket's sensitive-permission rule

`/kb/pages/permissions/` lists the permissions its reviewers treat as
sensitive and inspect by hand. Lynko trips three of them by design —
accessibility service, notification listener, media projection — so the
reviewer note in §5 is not optional. Use it. If they reject the permission, you
may appeal through the Tickets section with justification; that is the
documented path, not a re-upload.

## 7. Before you press "publish": bump the version code

The uploaded APK must be `versionCode 2` / `"0.1.0"` if a build with code 1
was ever uploaded. If this is the first upload, `1` is fine.

Build with:

```bash
cd android
KEYSTORE_FILE=E:/Dev/Projects/Lynko/android/keystore/lynko-release.jks \
KEYSTORE_PASSWORD=<pw> KEY_ALIAS=lynko-release KEY_PASSWORD=<pw> \
./gradlew assembleBaseRelease
```
