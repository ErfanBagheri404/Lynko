# How the Myket APK was produced

## Artifact
`android/app/build/outputs/apk/base/release/app-base-universal-release.apk`

Upload the **universal** APK. Myket serves one file per version and does not
split by ABI, so the universal build (arm64-v8a + armeabi-v7a + x86_64) is the
right one — a per-ABI APK would fail on the other architectures.

## Build command

```bash
cd android
KEYSTORE_FILE=E:/Dev/Projects/Lynko/android/keystore/lynko-release.jks \
KEYSTORE_PASSWORD=<keystore pw> \
KEY_ALIAS=lynko-release \
KEY_PASSWORD=<key pw> \
./gradlew assembleBaseRelease
```

Signing is read from the environment in `app/build.gradle.kts`. **Without**
`KEYSTORE_FILE` the build silently falls back to the debug key — installable,
but Myket rejects a debug-signed APK. Always pass the env vars.

## Keystore
`android/keystore/lynko-release.jks` — alias `lynko-release`.
Back this file up. Losing it means you can never ship an update to the same
Myket listing; you would have to publish as a brand-new app.

## Version
`versionCode` / `versionName` come from `-PandroidVersionCode` /
`-PandroidVersionName`, defaulting to `1` / `"0.1.0"`. Every upload after the
first needs a strictly higher `versionCode`, e.g.:

```bash
./gradlew assembleBaseRelease -PandroidVersionCode=2 -PandroidVersionName=0.1.1
```

## Flavor
Only `base` exists in this checkout, and that is the correct one to upload.
Older CI runs produced `myket` / `play` / `bazaar` flavors; those are not
present on this branch, so do not go looking for a `myketRelease` variant.

## Verify before uploading

```bash
BUILD_TOOLS=~/AppData/Local/Android/Sdk/build-tools/36.0.0   # or 35.0.0
APK=android/app/build/outputs/apk/base/release/app-base-universal-release.apk

# 1. signed with the release cert, not the debug cert
$BUILD_TOOLS/apksigner.bat verify --print-certs "$APK"

# 2. no signature-level permission snuck into the release build
$BUILD_TOOLS/aapt2.exe dump permissions "$APK" | grep WRITE_SECURE \
  && echo "FAIL: protected permission present" || echo "OK"
```

Step 1 must print **`Signer #1 certificate DN: CN=Lynko, OU=Lynko Desktop
Companion, O=Erfan Bagheri, C=IR`** with SHA-256
`b5a776da…1bc8c01`. If it prints `CN=Android Debug` instead, the env vars were
not picked up and the build silently used the debug key — rebuild with them set.

Expected permission set (all normal-tier):
`INTERNET`, `RECORD_AUDIO`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
`CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`,
`FOREGROUND_SERVICE_CONNECTED_DEVICE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
`WAKE_LOCK`.
