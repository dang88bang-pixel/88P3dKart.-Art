# 3dxAgent REAL-CT45P — KOMPLETTE AUDIT & INVENTUR (2026-08-17)

**Ziel:** Alle Parts + Attribute + Aktions-Ketten + Knotenpunkte + Datenbanken (Geräte + Hersteller + Service/Techniker) + aktive Ausführbarkeit + visuelle Teile prüfen.
**Regel:** Alle mock/demo/simulierte/falsche/fehlende Teile **ersetzen** durch **aktive reale** Implementierungen (Android APIs, echte DBs, echte Action-Execute, echte WS-Interaktion).
**Branch:** arena/01a00b5e-88p3dkart-art
**Version:** 18.0.0-UNIFIED-REAL-CT45P

---

## 1. GESAMT-STATUS NACH BROAD AUDIT

### 1.1 Was 100% real & ausführbar ist
- **Device + Hersteller Datenbanken** (hervorragend):
  - `android-app/.../devicedb/DeviceDatabase.kt` + `edge-agent/device_db.py`
  - 100+ echte Einträge: Hersteller (IKEA, Philips, Xiaomi, Apple, Samsung, Dexcom, Abbott, Polar, Garmin, Honeywell, Dragino, RAK, ZENNER, ...), LoRaWAN, Wireless M-Bus, ISM 433, Medical BLE, Thread/Matter, Tracker (AirTag, SmartTag, Tile).
  - OUI, Company-ID (verifiziert gegen Nordic/SIG), GATT-Services, Tracker-Reset-Prozeduren (real).
- **Action Chains**:
  - `DeviceActionEngine.kt` + `DeviceRegistry.kt` — echte execute() mit Capability-Check.
  - Workshop: `Ct45pWorkshopBridge`, `UartBleBridge` (jetzt mit realem UsbSerialDevice.read), `AdbWifiDiscovery`.
  - Medical: PolarH10Manager, GarminManager (SpO2), UartMedicalDriver — echte BLE GATT + USB.
  - IMU → TacticalHealthMonitoring → WS `tactical_*` → Visualizer (real).
- **Datenbanken (Persistenz)**:
  - Room: `AppDatabase` (Spatial), `AuditLogRepository` (1 Jahr Retention).
  - Edge: `LocalVectorStore` + device_db.
- **Visuelle Teile**:
  - `web-visualizer/public/tactical/TacticalOverlay.js` + main.js — **ausschließlich** von realen WS-Nachrichten (`tactical_personnel`, `tactical_alert`, `tactical_overview`).
  - DeviceRegistry → Layer visibility, selection, position updates (real).
- **Knotenpunkte**:
  - MainActivity wiring, ForegroundService, PreReleaseVerification, SecurityManager (sign), SecureApiClient (mTLS).

### 1.2 Noch vorhandene mock/demo/simulierte/falsche/fehlende Teile (Broad Scan)
- **Kommentare / Platzhalter** (nicht kritisch aber unprofessionell):
  - `KeyRotationManager.kt`: "demo only", "no-op placeholder", TODO Keystore.
  - `DeviceSourceMapper.kt`: "Positionen wurden gestubbt" (Kommentar).
  - `MedicalDriverFactory.kt`: "Fallback: stub" (Log + Kommentar — Implementierung ist bereits passiv korrekt).
- **Intentional "Simulation" Features** (What-If — **nicht** in Live-Pfaden ersetzen):
  - Network3D: `simulate`, `topology_simulation`, `network/traffic/simulate` (API + WS) — dokumentierte What-If-Funktion.
  - docs/ + README enthalten `aura_demo.py`, Evakuierungs-Simulation — **als Feature** belassen.
- **Fehlend / unvollständig**:
  - Keine dedizierte **Techniker / Service-Doku Datenbank** (Wartung, Reparatur-Logs, Techniker-Profile).
  - `KeyRotationManager` nicht an realem Android Keystore / EncryptedSharedPreferences.
  - Keine vollständige "Techniker-Aktionskette" (z.B. "Gerät in Reparatur-Modus", "FRP-Bypass-Log", "Service-Ticket").
  - Keine explizite "Service/Techniker" Tabelle in Room oder edge DB.
  - Einige Tests verwenden "simulate" (akzeptabel).
  - Keine reale signierte APK im GitHub Release (nur Scripts + CI).

### 1.3 Aktions-Ketten (komplett geprüft)
**Gute Ketten (real):**
1. IMU (SensorManager) → updateMotionData → TacticalHealth → WS → Visualizer
2. BLE Medical (Polar/Garmin GATT) / UART → MedicalDriverFactory → updateVitalData → Tactical
3. USB OTG (UsbManager + felhr) → UartBleBridge (real read) + UartMedicalDriver → Workshop
4. AdbWifiDiscovery (NsdManager + Runtime.adb) → Ct45pWorkshopBridge
5. DeviceRegistry + DeviceActionEngine → execute() (Capability-geprüft)
6. Security → CommandSigner → API (mTLS)
7. ForegroundService + Audit (Room)

**Schwache / fehlende Ketten:**
- Techniker-Repair-Action-Chain (FRP, eMMC, UART-Repair) → nur teilweise in Workshop.
- Key-Rotation → nicht real.
- Visuelle Interaktion: "Techniker klickt Reparatur" → kein dedizierter Service-Modus.

---

## 2. ABZURARBEITENDE INVENTUR-LISTE (Priorisiert — sequentiell)

### A. Sofort (kritisch für "komplett real")
- [ ] A1. KeyRotationManager → echte Android Keystore + EncryptedSharedPreferences Implementierung (ersetze Placeholder)
- [ ] A2. DeviceSourceMapper → Kommentar "gestubbt" entfernen + echte Position-Updates aus EKF/Triangulation verdrahten (falls noch nicht)
- [ ] A3. Techniker / Service Doku DB anlegen:
  - Neue Tabelle: `technician_profiles`, `service_tickets`, `repair_logs`, `device_service_history`
  - Room Entity + Dao + Repository (Android)
  - Python Mirror (`edge-agent/technician_db.py`)
  - Seed mit Beispielen (Honeywell CT45P Techniker, Polar Service, etc.)
- [ ] A4. DeviceActionEngine um echte **Service/Techniker-Aktionen** erweitern:
  - `start_repair_mode`, `log_frp_bypass`, `uart_repair_command`, `mark_device_serviced`
  - Verdrahtung in WorkshopBridge + UI (falls vorhanden)

### B. Datenbanken & Hersteller/Techniker
- [ ] B1. DeviceDatabase + device_db.py auf aktuellem Stand halten (bereits sehr gut)
- [ ] B2. Techniker-Datenbank + Service-Doku vollständig implementieren (A3)
- [ ] B3. Verknüpfung: DeviceRecord → ServiceHistory (FK)

### C. Aktions-Ketten + Knotenpunkte
- [ ] C1. Alle DeviceActionEngine Actions auf reale Ausführung prüfen + erweitern (A4)
- [ ] C2. Workshop-Repair-Kette (UART + BLE NUS für FRP/Repair) vollständig aktiv machen
- [ ] C3. Visuelle Interaktion: "Techniker-Modus" im Visualizer + App (Layer + Action Buttons → echte Execute)

### D. Visuelle Parts + tatsächliche Ausführung
- [ ] D1. Web Visualizer: Techniker-Overlay + echte Action-Buttons (WS getrieben)
- [ ] D2. MainActivity / TacticalDashboard: Service-Modus UI + Action-Ausführung
- [ ] D3. PreReleaseVerification + RealHardwareVerificationTest um Techniker/Service Checks erweitern

### E. Build & APK bei GitHub
- [ ] E1. CI Workflow erweitern (Unit + Instrumented Hinweis + signed APK auf Release)
- [ ] E2. releases/ Skripte finalisieren (bereits vorhanden)
- [ ] E3. **Echte signierte APK** via GitHub Release bereitstellen (braucht Secrets + Workflow-Dispatch)
- [ ] E4. RELEASE_CHECKLIST + PRODUCE_SIGNED_APK aktualisieren

### F. Dokumentation & Abschluss
- [ ] F1. docs/DEVICE_INTERACTION.md + DEVICE_DATABASE.md + neue SERVICE_TECHNIK_DOKU.md
- [ ] F2. INVENTUR_LISTE.md + REAL_STATUS.md + diese Datei aktualisieren
- [ ] F3. Alle verbleibenden "stub/demo/placeholder" Kommentare in Source bereinigen (außer dokumentierte What-If-Sims)

---

## 3. AKTUELLER STAND NACH DIESEM AUDIT (2026-08-17)

**Erledigt in dieser Session:**
- Broad Audit durchgeführt
- Neue `INVENTUR_AUDIT_2026-08-17.md` erstellt
- A1–A4, B1–B3, C1–C3, D1–D3, E1–E4 als sequentielle Liste angelegt
- Vorherige Medical/USB/Workshop/Tests-Punkte bereits als real markiert

**Nächster sequentieller Schritt:**
Starte mit **A1 + A3** (KeyRotation + Techniker-DB) → dann A2, A4 usw.

**Ergebnis-Ziel:**
- 0 verbleibende Mock/Placeholder in produktiven Pfaden
- Vollständige Geräte + Techniker/Service-Datenbanken
- Alle Aktionsketten + Knoten + visuelle Interaktionen **tatsächlich ausführbar**
- Signierte APK auf GitHub Release

**Hinweis zur APK:**
Keine JDK/SDK im Sandbox-Env vorhanden. Die reale APK wird über:
- `./releases/build-signed-apk.sh` (lokal)
- GitHub Actions Workflow (mit Secrets)
erzeugt und als Release-Asset bereitgestellt.

---

**Alle Punkte werden sequentiell (hintereinander) abgearbeitet.**
**Nächster Commit wird den ersten Block (A1 + A3) markieren.**
