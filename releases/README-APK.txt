3dxAgent REAL-CT45P v18.1.0 — APK bereitstellen

ZIEL: fertige APK, alle Abhängigkeiten und Berechtigungen aktiv.

Diese Sandbox hat kein JDK und keinen Zugang zu Maven/Google.
Die APK wird deshalb über GitHub Actions gebaut (Workflow „Build APK“ ist aktiv).

1) Dieses Branch pushen (bereits Teil des Arena-Flows)
2) GitHub → Actions → Build APK → Run workflow
   Branch: arena/01a02a36-88p3dkart-art
   build_type: release
   sign_release: false   (oder true, wenn Keystore-Secrets gesetzt sind)
3) Artifact 3dxagent-apks herunterladen
4) Auf CT45P:
   ./releases/ct45p-deploy.sh app-release.apk

Das Deploy-Skript installiert mit -g und erteilt danach ALLE Runtime-Permissions.

Bereits vorhandene signierte APK (älterer Stand 4.6.0):
  https://github.com/dingeldangbang/88P3dKart.-Art/releases/tag/v4.6.0-classification

Was in 18.1.0 aktiv ist:
- Alle Manifest-Permissions (BT Scan/Connect/Advertise, Location inkl. Hintergrund,
  UWB, Wi-Fi RTT, NFC, Kamera, Sensoren, FGS-Typen, Boot, Akku-Whitelist)
- Runtime-Grant in Enrollment + MainActivity (2-Phasen inkl. Hintergrund-Ortung)
- TacticalForegroundService + BluetoothAccessoryScanService + Boot-Receiver
- Navigation (3D / Karte / BT / Alarm / Taktik)
- Offline-Start ohne Gateway
- Gradle-Dependencies: Room, OkHttp, Retrofit, Navigation, WorkManager,
  Security-Crypto, UsbSerial, Java-WebSocket, MultiDex, AndroidX Test
