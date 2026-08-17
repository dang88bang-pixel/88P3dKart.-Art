# Getting Started with 3dxAgent REAL-CT45P on a real device

**Version:** v18.0.0-UNIFIED-REAL-CT45P

This guide helps you get the **real** (non-simulated) version running on a Honeywell CT45P.

## 1. Prerequisites on the device

- CT45P with Android 12+ (recommended)
- Developer options enabled + USB debugging
- Pair any medical devices you want to use (Polar H10, Garmin watch, etc.) via Bluetooth settings first

## 2. Build or Download a Signed APK

See:
- `releases/PRODUCE_SIGNED_APK.md`
- `docs/BUILD.md`

Recommended: Use the signed build script on a machine with JDK 17 + Android SDK.

## 3. Deploy to the CT45P (easiest way)

```bash
./releases/ct45p-deploy.sh releases/3dxAgent-REAL-CT45P-18.0.0-UNIFIED-signed-*.apk
```

This will:
- Install the APK
- Grant the most important permissions
- Start the app

## 4. What you should see / test

1. **IMU** — Move the device → Tactical Health values should react (readiness, stress, fatigue)
2. **Workshop** — Connect via ADB WiFi or plug a USB UART adapter → should discover and connect
3. **Medical** — If you paired a Polar H10 or Garmin:
   - Real HR and HRV should appear
   - On newer Garmin devices: SpO2 should also appear
4. **Foreground Service** — Turn screen off / lock device → the notification "3dxAgent Tactical" should stay and data should continue flowing
5. **Web Visualizer** — Connect to the WebSocket → you should see live `tactical_personnel`, `tactical_alert`, `tactical_overview`

## 5. Forcing a specific Medical Driver (for testing)

Currently the app uses `MedicalDriverFactory`, which tries Polar → Garmin → UART → Stub.

If you want to test a specific driver during development, you can temporarily modify `MedicalDriverFactory.create()` or inject it from MainActivity.

## 6. Troubleshooting

- No heart rate appearing → Make sure the device is bonded in Android Bluetooth settings
- USB UART not connecting → Check that the app got USB permission (the dialog should appear)
- App stops in background → The ForegroundService should prevent this. Check the notification is present.
- Old data / crash after update → Uninstall first: `adb uninstall com.example.agent`

## 7. Useful adb commands

```bash
adb logcat | grep -E 'Polar|Garmin|UartMedical|Tactical|Workshop|Uwb'
adb shell dumpsys activity services | grep TacticalForeground
```

Good luck!
