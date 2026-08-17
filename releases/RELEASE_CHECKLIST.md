# 3dxAgent REAL-CT45P v18.0.0 — Release Checklist

## Pre-Release (2026-08-17 — FULL AUDIT COMPLETE)
- [x] All critical Medical drivers (Polar + Garmin + UART via MedicalDriverFactory)
- [x] USB OTG + real UART read (UartBleBridge + felhr UsbSerialDevice)
- [x] Workshop (AdbWifi + UartBle + Ct45pWorkshopBridge)
- [x] ForegroundService + Dauerbetrieb
- [x] Audit logging (1 year retention)
- [x] Real KeyRotation via Android Keystore (KeyRotationManager)
- [x] Real Service/Technician DB + Action chains (start_repair_mode, log_frp_bypass, log_uart_repair)
- [x] Commands signed (SecurityManager + real keys)
- [x] mTLS client ready (SecureApiClient + Keystore)
- [x] Device + Hersteller + Service/Techniker Datenbanken (vollständig, real)

## Build
- [ ] Run `./releases/build-signed-apk.sh` locally with real keystore
- [ ] Verify signature with `./releases/verify-release.sh`
- [ ] Or trigger GitHub Action "Build 3dxAgent REAL-CT45P APK"

## GitHub Release (when using CI)
- [ ] Secrets configured:
  - RELEASE_STORE_BASE64
  - RELEASE_STORE_PASSWORD
  - RELEASE_KEY_ALIAS
  - RELEASE_KEY_PASSWORD
- [ ] Workflow completed successfully
- [ ] Artifact downloaded and signature verified

## Post-Release
- [ ] Install on real CT45P with `adb install -r ...`
- [ ] Grant all permissions
- [ ] Verify:
  - IMU drives Tactical Health
  - Workshop (ADB WiFi + UART/BLE) works
  - Real medical driver (Polar/Garmin/UART) delivers vitals
  - ForegroundService keeps running
  - Alarms can be acknowledged/snoozed
- [ ] Check Web Visualizer receives real `tactical_*` data

## Rollback
- Keep previous signed APK
- Document the keystore alias used

**Current recommended command:**
```bash
./releases/build-signed-apk.sh
./releases/verify-release.sh releases/3dxAgent-REAL-CT45P-18.0.0-UNIFIED-signed-*.apk
```
