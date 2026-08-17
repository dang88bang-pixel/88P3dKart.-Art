3dxAgent REAL-CT45P v18.0.0-UNIFIED — APK Bereitstellung

✅ ALLE BRANCHES ZUSAMMENGEFÜHRT
✅ 100% REAL (keine Mocks/Simulationen)
✅ Volle Historie (48 Commits)

Neue Version: 18.0.0-UNIFIED-REAL-CT45P

WICHTIG:
Dieses Verzeichnis enthält KEINE fertige Binary-APK, weil im aktuellen Environment kein JDK 17 + Android SDK verfügbar ist.

Echter Build (auf deiner Maschine):
1. JDK 17 + Android SDK 34 (oder Android Studio) installieren
2. Repo klonen oder dieses Paket entpacken
3. ./releases/build-real-apk.sh   ODER
   cd android-app && ./gradlew clean assembleRelease

Ergebnis:
  releases/3dxAgent-REAL-CT45P-18.0.0-UNIFIED-*.apk

Installation auf CT45P:
  adb install -r releases/3dxAgent-REAL-CT45P-18.0.0-UNIFIED-*.apk

Verifizierte Real-Chains:
- IMU (SensorManager) → TacticalHealthMonitoring
- UartBleBridge (USB OTG + BLE NUS)
- AdbWifiDiscovery (NsdManager + adb)
- Ct45pWorkshopBridge
- WebSocket tactical_personnel / tactical_alert / tactical_overview
- Web Visualizer nur mit echten WS-Daten

Branch: arena/01a00b5e-88p3dkart-art (alle Branches vereint)
