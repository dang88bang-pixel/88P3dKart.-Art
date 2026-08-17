# Building 3dxAgent REAL-CT45P v18.0.0-UNIFIED

This document explains how to build the **real** (non-simulated) version for the Honeywell CT45P.

## Requirements

- JDK 17+
- Android SDK (API 34 recommended)
- Android Studio or command line tools

## Quick Build (Unsigned)

```bash
cd android-app
./gradlew clean assembleRelease
```

The APK will be at:
`android-app/app/build/outputs/apk/release/app-release.apk`

## Build Signed Release (Recommended)

See the detailed guide:

→ `releases/PRODUCE_SIGNED_APK.md`

Short version:

```bash
export RELEASE_STORE_FILE=../releases/keystore/release.jks
export RELEASE_STORE_PASSWORD=...
export RELEASE_KEY_ALIAS=ct45p-release
export RELEASE_KEY_PASSWORD=...

./releases/build-signed-apk.sh
```

Then verify:
```bash
./releases/verify-release.sh releases/3dxAgent-REAL-CT45P-18.0.0-*.apk
```

## Deploy to Real CT45P

```bash
./releases/ct45p-deploy.sh releases/3dxAgent-REAL-CT45P-18.0.0-*.apk
```

This installs the APK, grants the most important permissions, and starts the app.

## What is Real in This Build

- Medical: Polar H10, Garmin (incl. SpO2), UART medical dongles
- Workshop: AdbWifi + UART/BLE with proper permission + reconnect
- UWB: Real if hardware present, otherwise synthetic fallback
- Dauerbetrieb: TacticalForegroundService
- Security: Command signing + mTLS client (Keystore)
- Audit: Long-term logging

See `docs/REAL_STATUS.md` for the full current status.

## CI

Push to the branch or manually trigger:
**Actions → "Build 3dxAgent REAL-CT45P APK"**

For signed releases in CI, configure the GitHub Secrets listed in `releases/PRODUCE_SIGNED_APK.md`.

## Common Issues

- "JAVA_HOME not set" → Install JDK 17 and set JAVA_HOME
- Permission denied on device → Run the deploy script or grant manually
- Old APK still installed → `adb uninstall com.example.agent`

Good luck!
