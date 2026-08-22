# 3dxAgent 18.1.0 — Bereitstellen

Fertige CT45P-APK mit **allen Abhängigkeiten** und **allen Berechtigungen aktiv**.

## Was jetzt im Repo aktiv ist

| Bereich | Status |
|---|---|
| Manifest-Permissions (BT, Location inkl. Hintergrund, UWB, Wi-Fi RTT, NFC, Kamera, Sensoren, FGS, Boot, Akku) | aktiv |
| Runtime-Grant (Enrollment + Main, 2-Phasen) | aktiv |
| `TacticalForegroundService` + BLE-Scan-Service + Boot-Receiver | aktiv |
| Navigation 3D / Karte / Bluetooth / Alarm / Taktik | aktiv |
| Offline-Start ohne Gateway | aktiv |
| Gradle: Room, OkHttp, Retrofit, Navigation, WorkManager, Security-Crypto, UsbSerial, Java-WebSocket, MultiDex, Tests | aktiv |
| Deploy-Skript `pm grant` für alle dangerous Permissions | aktiv |

## APK erzeugen (GitHub Actions)

Diese Umgebung hat kein JDK und keinen Maven-Zugang. Der Workflow **Build APK** ist im Repo aktiv.

1. Öffnen: https://github.com/dingeldangbang/88P3dKart.-Art/actions/workflows/build-apk.yml
2. **Run workflow**
3. Branch: `arena/01a02a36-88p3dkart-art`
4. `build_type`: **release** · `sign_release`: **false**
5. Artifact **3dxagent-apks** herunterladen (~5–10 min)

## Auf den CT45P bringen

```bash
./releases/ct45p-deploy.sh app-release.apk
```

Das Skript macht `adb install -r -g`, erteilt danach jede Runtime-Permission und startet die App.

## Übergang: bereits signierte APK

https://github.com/dingeldangbang/88P3dKart.-Art/releases/download/v4.6.0-classification/3dxAgent-CT45P-v4.6.0-classification.apk

Danach trotzdem `./releases/ct45p-deploy.sh` verwenden, damit die Permissions aktiv sind.
