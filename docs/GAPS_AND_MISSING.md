# 3dxAgent REAL-CT45P — Was dem Projekt noch fehlt (Stand 2026-08-17)

**Branch:** `arena/01a00b5e-88p3dkart-art` (alle 15 Branches zusammengeführt, 48+ Commits)  
**Version:** v18.0.0-UNIFIED-REAL-CT45P  
**Letzter Scan:** 2026-08-17 (frische Code-Analyse)

**Status:** Die Kern-Pfade (IMU → TacticalHealthMonitoring → WS → Visualizer, AdbWifi/UartBle/Ct45pWorkshopBridge) sind **100% real** (keine Simulationen/Mocks mehr in den operativen Pfaden).

**Trotzdem existieren noch klare Lücken.**

---

## 1. Kritisch (blockiert produktiven Einsatz)

### 1.1 Medical / Vital-Sensoren (größte Lücke)
- **Datei:** `android-app/app/src/main/java/com/example/agent/tactical/TacticalHealthMonitoring.kt:620`
- **Interface:** `MedicalMonitoringService`
- **Implementierung:** `RealMedicalMonitoringService` (expliziter Stub)
  ```kotlin
  // "Real active default implementation (stub that can be replaced...)"
  while (running) { delay(2000) }   // tut NICHTS
  ```
- **Fehlt komplett:**
  - `PolarH10Manager.kt` (BLE HR + HRV)
  - `GarminManager.kt`
  - `UartMedicalDriver.kt`
- Nur IMU-Proxy (`updateMotionData`) + manuelles `updateVitalData`.

### 1.2 UWB / Präzise Lokalisierung
- `UwbManager.kt` existiert nicht mehr.
- Nur `offline/UwbDoppler.kt` (nicht verdrahtet in MainActivity / Pipeline).
- Kein `startRanging()`, keine Phase-Extraktion, kein NLOS-Handling.

### 1.3 USB OTG + UART Lifecycle (Workshop)
- Betroffen: `UartBleBridge.kt`, `Ct45pWorkshopBridge.kt`, `SerialManager.kt`
- Fehlt: `UsbManager.requestPermission()` + BroadcastReceiver + stabiler Reconnect nach Detach.

### 1.4 Kein dedizierter Dauerbetrieb / Foreground
- Einzelne Services existieren (`BluetoothAccessoryScanService`, `DataAcquisitionService`, `TriangulationService`).
- **Fehlt:** Ein zentraler `ForegroundService` mit `startForeground()` für IMU + Workshop + Alarme + Doze-Handling.

---

## 2. Wichtig (teilweise vorhanden)

### 2.1 Gateway Authentication
- Interface `GatewayAuthService` existiert.
- **Implementierungen: 0**
- Keine mTLS / Token / Zertifikats-Handhabung.

### 2.2 LiDAR & mmWave
- Parser vorhanden (`RplidarStandardParser.kt`, `TiMmwaveParser.kt`).
- Fehlt: Stream-Reassembly, Vendor-TLV Golden-Tests, robuste Fragment-Recovery.

### 2.3 BLE Token Firmware
- 25 Dateien vorhanden.
- Probleme: Advertising-Update unvollständig, Battery=Platzhalter, Byte-Vertrag teilweise inkonsistent.

### 2.4 Persistenz & Audit
- Room vorhanden.
- Fehlt: Langes Audit-Log (1 Jahr), transaktionale Outbox, Policy-Revision + Evidence.

### 2.5 Security
- Interfaces vorhanden, aber:
  - Keine Key-Rotation/Revocation
  - Keine Command-Signatur
  - Kein Threat-Model / Penetration-Tests

### 2.6 Web Visualizer + E2E
- Real-WS-Handler (`tactical_*`) sauber (keine Demo-Injection mehr).
- Fehlt: Gateway-E2E, Auth am Visualizer, Historie/Export.

---

## 3. Build, Release & CI (sehr große praktische Lücke)

| Bereich                    | Status                  | Fehlt |
|---------------------------|-------------------------|-------|
| Gradle Wrapper + JDK 17   | Vorhanden               | — |
| Android SDK               | Nicht im Env            | Nur außerhalb Sandbox |
| **.github/workflows/**    | **Komplett fehlt**      | Kein CI-Job für assembleRelease |
| Release Signing           | Nur Gradle-Properties   | Kein .jks / Keystore |
| Signierte APK             | Nicht produziert        | Nur Build-Skripte |
| GitHub Release + Checksum | Fehlt                   | Kein `gh release` |
| SBOM                      | Fehlt                   | — |

**Aktuell:** Nur Source + Build-Skripte. Keine fertige signierte Binary.

---

## 4. Tests & Verifikation

- 30 Android-Unit-Tests + 29 Python-Tests.
- **Fehlt:**
  - Instrumented Tests auf echtem CT45P
  - HIL-Tests (IMU, USB, BLE)
  - Golden-Frames für Parser
  - Keine realen CT45P-Logs im Repo

---

## 5. CI / DevOps Infrastruktur

- **Kein** `.github/workflows/` Verzeichnis
- Keine automatisierten Release-Builds
- Keine Matrix (Debug/Release)
- Keine Dependabot / SBOM

---

## 6. Was bereits real und gut ist

- 0 Simulationen in TacticalHealthMonitoring, MainActivity, Bridges, Visualizer (kritische Pfade)
- IMU (SensorManager) → `updateMotionData` → WS → Visualizer (echte Daten)
- Workshop real: AdbWifiDiscovery + UartBleBridge + Ct45pWorkshopBridge
- Alle Branches zusammengeführt (15 → 1)
- 150+ `.kt` Dateien, echte Android-APIs
- Version 18 + aktuelle Build-Skripte

---

## Nächste Schritte (Priorisiert)

1. **Medical Driver** (Polar BLE / UART) → höchste Priorität
2. USB Permission + Reconnect Lifecycle
3. UWB aktivieren / verdrahten
4. Zentralen `ForegroundService` + Doze-Handling
5. **CI Pipeline anlegen** (`.github/workflows/build-apk.yml`)
6. Echte signierte APK bauen + auf CT45P testen
7. GatewayAuthService implementieren + mTLS
8. BLE-Token-Firmware auf Hardware verifizieren
9. Instrumented + HIL Tests

---

**Zusammenfassung**  
Das Projekt ist nach Merge + Cleanup in einem starken **Source-Real-Zustand**.  
Die größten Blocker sind:
- Fehlender Medical-Driver
- Fehlender Dauerbetrieb (Foreground)
- Keine CI + signierte Release-APK
- UWB + USB-Lifecycle

Ohne Hardware-Tests auf einem realen CT45P bleibt es ein sehr fortgeschrittener Prototyp.

Letztes Update: 2026-08-17 (frische Scans)

---

## 8. Konkrete fehlende Dateien / Klassen (aus frischem Scan 2026-08-17)

### Medical (höchste Priorität)
- `android-app/app/src/main/java/com/example/agent/sensors/PolarH10Manager.kt` — **fehlt**
- `android-app/app/src/main/java/com/example/agent/sensors/GarminManager.kt` — **fehlt**
- `android-app/app/src/main/java/com/example/agent/sensors/UartMedicalDriver.kt` — **fehlt**
- `android-app/app/src/main/java/com/example/agent/sensors/MedicalDriverFactory.kt` — **fehlt**

### UWB
- `android-app/app/src/main/java/com/example/agent/sensors/UwbManager.kt` — **fehlt** (nur offline/UwbDoppler.kt existiert)
- Verdrahtung in `MainActivity.kt` und `ImuManager.kt` — **fehlt**

### USB / Workshop Lifecycle
- `android-app/app/src/main/java/com/example/agent/bridge/UsbPermissionReceiver.kt` — **fehlt**
- `android-app/app/src/main/java/com/example/agent/bridge/ReconnectManager.kt` — **fehlt**
- Erweiterung von `UartBleBridge.kt` um vollständigen Permission-Flow — **unvollständig**

### Background / Dauerbetrieb
- `android-app/app/src/main/java/com/example/agent/tactical/TacticalForegroundService.kt` — **fehlt**
- `android-app/app/src/main/java/com/example/agent/MainActivity.kt` → `startForegroundService(...)` — **fehlt**

### Authentication
- `android-app/app/src/main/java/com/example/agent/network/RealGatewayAuthService.kt` — **fehlt** (Interface existiert, 0 Implementierungen)

### CI / Release
- `.github/workflows/build-apk.yml` — **fehlt komplett**
- `android-app/keystore/release.jks` — **fehlt** (nur Gradle-Properties-Referenzen)

### Weitere nützliche Klassen
- `android-app/app/src/main/java/com/example/agent/health/ForegroundServiceController.kt`
- `android-app/app/src/main/java/com/example/agent/security/CommandSigner.kt`
- `android-app/app/src/main/java/com/example/agent/audit/AuditLogRepository.kt` (langes Log)

---

## 9. Missing vs. Present (kritische Bereiche)

| Bereich              | Vorhanden                          | Fehlt / unvollständig                     | Bewertung |
|----------------------|------------------------------------|-------------------------------------------|-----------|
| IMU → Tactical       | ImuManager + updateMotionData      | —                                         | ✅ Real   |
| Workshop (ADB+UART)  | AdbWifiDiscovery, UartBleBridge    | USB Permission + Reconnect                | ⚠️ Teilweise |
| Medical              | Interface + 1 Stub                 | Polar/Garmin/UART Driver                  | ❌ Kritisch |
| UWB                  | UwbDoppler (offline)               | UwbManager + Verdrahtung                  | ❌ Fehlt  |
| Background           | Einige Services                    | Zentraler ForegroundService               | ❌ Fehlt  |
| Auth                 | Interfaces                         | RealGatewayAuthService (0 Impl)           | ❌ Fehlt  |
| CI / Release         | Build-Skripte                      | .github/workflows + Keystore + Signed APK | ❌ Fehlt  |
| Tests auf Hardware   | JVM + Python                       | Instrumented + HIL auf CT45P              | ❌ Fehlt  |

---

## 10. Empfohlene nächste Implementierungen (mit Dateipfaden)

1. `sensors/PolarH10Manager.kt` — BLE Heart Rate Service + HRV Parsing
2. `bridge/UsbPermissionReceiver.kt` + Erweiterung `UartBleBridge`
3. `tactical/TacticalForegroundService.kt` + Start in MainActivity
4. `sensors/UwbManager.kt` + Integration
5. `.github/workflows/build-apk.yml` (mit JDK 17 + SDK Setup)
6. `network/RealGatewayAuthService.kt`
7. `sensors/MedicalDriverFactory.kt` (Factory für Polar/Garmin/UART)

**Ziel:** Nach diesen 7 Schritten wäre das Projekt deutlich näher an einem echten CT45P-Produkt.

Letztes Update: 2026-08-17 (inkl. frische Scans)
