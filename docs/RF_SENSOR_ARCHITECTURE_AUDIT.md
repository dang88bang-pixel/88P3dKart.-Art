# RF-Sensorik, Radio-SLAM und Hardwareautomation: Architektur-Audit

**Stand:** 2026-08-14  
**Repository:** `dang88bang-pixel/88P3dKart.-Art`  
**Ziel:** belastbare Einordnung der vorgeschlagenen CT45P-/CSI-/UWB-/mmWave-/SDR-Architektur

## 1. Kurzentscheidung

Die Spezifikation vermischt drei grundsätzlich verschiedene Ebenen:

1. **physikalisch plausible Forschungsansätze**, beispielsweise ESP32-CSI,
   Impulsradar, Range-Doppler-Verarbeitung und bewegungsbasierte SAR;
2. **Produktbehauptungen ohne Repository- oder Hardwareevidenz**, beispielsweise
   zentimetergenaue Through-Wall-3D-Rekonstruktion, Vitalzeichenerkennung,
   RTAB-Map, SceneView/Filament, TinyML-WASM und ein autonomer LangGraph-Agent;
3. **offensive oder sicherheitsumgehende Funktionen**, darunter Bruteforce,
   Payload-Injektion, SLA-/FRP-Umgehung und Deaktivierung von Verified Boot.

Nur Ebene 1 kann als **separater, kontrollierter Forschungsstrang** übernommen
werden. Ebene 2 darf erst nach messbaren Hardware- und Feldgates als Capability
erscheinen. Ebene 3 gehört nicht in den 3dxAgent-Produktpfad.

Die empfohlene Architektur bleibt:

```text
externe, instrumentierte RF-/Radar-Knoten
  -> deterministischer Linux-Sensor-Hub
  -> Zeitabgleich, Kalibrierung, Qualitätsbewertung
  -> experimentelle Feature-Extraktion
  -> autoritativer Gateway-Fusions-/Alarmkern
  -> begrenzte, authentifizierte Projektion
  -> CT45P-Control-Plane
```

Der CT45P ist weder RF-Messlabor noch Through-Wall-Autorität. Er bleibt
Bedien-, Enrollment-, Alarm- und Visualisierungsgerät.

## 2. Repository-grounded Befund

| Behauptete Komponente | Tatsächlicher Stand | Entscheidung |
|---|---|---|
| Honeywell-Thermaldiagnose | Öffentlicher Android-Thermal-Listener und getrennte Akkuwerte sind jetzt als Quellcode vorhanden, aber noch nicht kompiliert oder auf CT45P validiert. Kein Honeywell-Mobility-Edge-SDK. | Öffentliche Android Thermal API zuerst; OEM-SDK nur mit dokumentierter, lizenzierter API. |
| ESP32-CSI | Keine ESP32-CSI-Firmware, kein CSI-Framevertrag und kein Gatewayadapter. | Externer Forschungsadapter, nicht CT45P-Capability. |
| WebSerial-Hardwarebridge | Native Kotlin-App; kein browserbasierter Produktionspfad. | Nicht parallel einführen. Android USB Host oder Gateway-USB verwenden. |
| Novelda/XeThru X4 | Kein ausgewähltes Board, Protokoll, Treiber oder kalibrierter Aufbau. Vorhandene UWB-DFT ist ausdrücklich experimentell. | Separates Radarprofil; nicht mit UWB-Ranging verwechseln. |
| HLK-LD2450 | Android verwendet einen TI-mmWave-TLV-Parser bei 921600 Baud. LD2450 benötigt einen anderen Frameparser und standardmäßig 256000 Baud. | Neuer Adapter mit eigener USB-Identität und Golden Frames erforderlich. |
| RTL-SDR bei 2,4 GHz | Kein Adapter. Übliche RTL-SDR-V4-Hardware endet bei etwa 1,7 GHz und kann 2,4 GHz nicht direkt empfangen. | 2,4-GHz-Behauptung für RTL-SDR ablehnen; geeignete kohärente Hardware neu auswählen. |
| Wi-Fi Direct Sensor-Mesh | Kein `WifiP2pManager`, kein Mesh-Protokoll, keine Zeitsynchronisation und keine Node-Pose. | Gateway-basiertes IP-Netz bevorzugen. |
| ARCore/RTAB-Map/SAR | Keine ARCore-, RTAB-Map- oder Pose-Graph-Abhängigkeit. | Forschungsprojekt mit Capability- und Gerätegate. |
| SVO 16³/Greedy Meshing | `AdaptiveOctree`/`SmartMeshIntegrator` vorhanden, aber kein 16³-Chunkformat, kein Greedy Mesher und kein belastbares Retentionverfahren. | Vorhandenen Prototyp nicht als SVO-Produktpipeline bezeichnen. |
| SceneView/Filament | OpenGL-ES-Renderer vorhanden; keine SceneView-/Filament-Abhängigkeit. | UI-Technologie nicht ohne Benchmark migrieren. |
| SQLite-Vec/FastEmbed | Nicht vorhanden. | Kein Anteil an einer „Fusionsmatrix“. Kontextsuche getrennt von Messfusion halten. |
| Whisper-WASM/LangGraph/MCP | Nicht vorhanden. | Erst nach statischem Toolvertrag und Safety-Interlock evaluieren. |
| BootROM-/FRP-/Verified-Boot-Tools | Nicht vorhanden und außerhalb des zulässigen Produktumfangs. | Nicht implementieren. |

## 3. Thermik und Energie

### 3.1 Was Android tatsächlich bereitstellt

Seit API 29 liefert `PowerManager` einen geräteweit aggregierten Thermal Status
und Statusänderungen. Das ist geeigneter als die Annahme, eine normale App könne
kontinuierlich alle CPU-Kerntemperaturen lesen. Die Android-Dokumentation fordert
außerdem, Listener nach Gebrauch wieder zu entfernen.

Die Batterietemperatur aus `ACTION_BATTERY_CHANGED` ist eine andere Messgröße:
Sie beschreibt nicht CPU, GPU, USB-Radar oder LiDAR. Beide Größen dürfen nicht
unter einem unqualifizierten Feld `thermal_c` vermischt werden.

### 3.2 Ausgangsproblem und umgesetzte erste Korrektur

Der Ausgangsstand initialisierte den Gateway-EKF mit einem unqualifizierten,
festen Temperaturwert von 45 °C. Eine CT45P-Batterietemperatur wäre als
LiDAR-Rauschsignal fachlich ebenfalls falsch. Die erste sichere Korrektur ist im
Quellstand umgesetzt:

- `DeviceThermalMonitor` nutzt den öffentlichen Android-Thermal-Status und liest
  Akkutemperatur/-stand getrennt;
- `DeviceThermalPolicy` reduziert bei `MODERATE`, pausiert lokale Sensorlast ab
  `SEVERE` und nimmt sie erst unterhalb `MODERATE` wieder auf;
- der Live-Renderer wechselt im reduzierten Zustand von kontinuierlichem Rendering
  auf bedarfsgesteuertes Rendering;
- Android-Akkutemperatur wird nicht als Sensor-Rig-Temperatur an den EKF gesendet;
- Gateway-Sensortelemetrie startet ohne erfundenen Temperaturwert und verlangt
  eine explizite `thermal_source`.

Diese Änderung ist **Source present** plus gatewayseitig testbar, aber noch kein
Android-Build- oder CT45P-Hardwarebeleg. Der Health-Vertrag muss mindestens
trennen:

```text
source                    CT45P | LIDAR | MMWAVE | GATEWAY_CPU | ...
thermal_status            NONE | LIGHT | MODERATE | SEVERE | ...
battery_temperature_c     optional
sensor_temperature_c      optional, nur für den benannten Sensor
observed_at               UTC
age_ms                    Freshness
quality_flags             strukturierte Flags
```

Ein pauschales Limit von 45 °C ist nicht automatisch ein OEM-Grenzwert. Für die
App ist zunächst der vom Betriebssystem gemeldete Thermal Status maßgeblich.
Geräte- oder Akkuschwellwerte benötigen Honeywell-Dokumentation und Tests für die
konkrete SKU/Batterie.

### 3.3 Degradationsstrategie

| Thermal Status | CT45P-Verhalten | Gateway-Verhalten |
|---|---|---|
| `NONE/LIGHT` | normale UI-Rate | unverändert |
| `MODERATE` | Rendering-/Preview-Rate senken | Health anzeigen; keine Messwahrheit ändern |
| `SEVERE` | lokale hochlastige Verarbeitung pausieren; Alarmprojektion aktiv lassen | autoritative Erfassung fortsetzen |
| `CRITICAL+` | Sensor-Relay geordnet stoppen, Bediener warnen | Data-Loss/Freshness nach Policy bewerten |

Thermisches Drosseln darf keine Events als erfolgreich verarbeitet markieren und
keine Alarm- oder Outboxeinträge verlieren.

## 4. Wi-Fi CSI

ESP-IDF kann CSI über `esp_wifi_set_csi_rx_cb`,
`esp_wifi_set_csi_config` und `esp_wifi_set_csi` liefern. Die Anzahl der
Unterträger ist jedoch PHY-abhängig: 802.11a/g bei 20 MHz und 802.11n bei 20 MHz
haben nicht dieselbe Zahl nutzbarer Töne. „56 Subcarrier“ ist nur in einem
konkreten HT-20-Profil richtig und kein universeller Wi-Fi-Vertrag.

Ein produktiver CSI-Frame benötigt mindestens:

```text
schema_version, node_id, boot_id, sequence
monotonic_timestamp_ns, synchronized_utc
channel, bandwidth, phy_mode, ltf_type
transmitter_id/pseudonym, antenna, rssi, noise_floor
complex_iq[] oder bounded features[]
node_pose + pose_covariance
calibration_id, firmware_hash, dropped_frame_count
```

Die ESP32-Callbackdokumentation warnt vor langer Verarbeitung im Wi-Fi-Task.
Daher gehören Queueing, Bounded Buffers und Feature-Extraktion in native
ESP-IDF-Tasks. Eine nicht nachgewiesene WASM-Runtime auf einem WROOM-32 ist keine
Produktvoraussetzung; TFLite Micro oder statisch kompilierte DSP-Features wären
zuerst zu benchmarken.

CSI liefert Kanalantworten, aber nicht automatisch Wände, Personenidentität oder
ein metrisches 3D-Modell. Dazu fehlen Geometrie, mehrere unabhängige Links,
Synchronisation, Kalibrierung, Ground Truth und eine validierte Inversionsmethode.

## 5. UWB-Impulsradar und Micro-Doppler

Der Novelda X4 ist ein Impulsradar-SoC, kein austauschbarer Ersatz für ein
UWB-ToF-/TDoA-Anchor-System. Laut Datenblatt besitzt er auswählbare
Sendemittenfrequenzen um 7,29 beziehungsweise 8,748 GHz und etwa 1,4 GHz
Sendebandbreite. Die theoretische freie Range-Auflösung

```text
ΔR = c / (2 B)
```

liegt bei 1,4 GHz ungefähr bei 10,7 cm. Das ist keine Garantie für Genauigkeit,
Objekttrennung oder Through-Wall-Leistung. Antenne, SNR, Material, Clutter,
Kalibrierung und Signalverarbeitung dominieren die reale Leistung.

Die maximale Pulswiederholrate darf nicht mit der Slow-Time-Ausgaberate einer
Vitalzeichenanalyse verwechselt werden. Atem-/Puls-Schätzungen benötigen
insbesondere:

- statische Clutter-Unterdrückung;
- Range-bin-Auswahl und Phasenentfaltung;
- Bewegungsgating;
- Fenster-/STFT-Parameter und Unsicherheit;
- Trennung mehrerer Personen;
- Ground Truth, etwa Atemgurt/ECG;
- klare Kennzeichnung als experimentell, nicht medizinisch.

3D-Imaging mit mehreren Radar-ICs erfordert laut X4-Dokumentation kohärente,
synchronisierte Instanzen und passende Antennengeometrie. Ein einzelnes bewegtes
Modul plus Smartphone-Pose wird dadurch nicht automatisch zu einem validierten
3D-Radar.

## 6. HLK-LD2450 versus vorhandener TI-mmWave-Pfad

Der LD2450 ist ein 24-GHz-FMCW-Multi-Target-Tracker. Sein serielles Protokoll
liefert laut Dokumentation bis zu drei Ziele mit X/Y/Speed/Distanz bei etwa 10 Hz;
der Default-UART ist 256000, 8N1, little-endian.

Der bestehende Repositorypfad ist dagegen auf TI-mmWave-Binärframes/TLVs und
921600 Baud ausgelegt. Eine Umschaltung nur der Baudrate wäre gefährlich. Erforderlich
sind:

1. eindeutige USB VID/PID-/Interfacezuordnung;
2. separater `Ld2450Parser` mit Stream-Reassembly und Resynchronisation;
3. harte Frame-, Ziel- und Koordinatengrenzen;
4. aufgezeichnete Golden Frames sowie Fragmentierungs-/Fehlertests;
5. Transform vom Radarframe in einen kalibrierten Rigframe;
6. Qualitätsmodell; LD2450-Zielnummern sind keine dauerhaften Identitäten;
7. echte Hardwaretests für Occlusion, Mehrwege, stationäre Ziele und Reconnect.

Der LD2450 liefert keine Atem-/Herzfrequenz und keine Through-Wall-3D-Konturen.

## 7. RTL-SDR und passives Radar

Ein typischer RTL-SDR V4 deckt ungefähr 0,5 MHz bis 1,7 GHz mit nur wenigen MHz
Momentanbandbreite ab. Direkter Empfang bei 2,4 GHz ist damit nicht möglich. Ein
Downconverter würde zusätzliche Oszillator-, Filter-, Kalibrier- und
Synchronisationsfehler einführen.

Passives Radar benötigt mindestens einen Referenzkanal und einen
Überwachungskanal mit bekannter Geometrie. Für brauchbare Kreuzambiguitäts- oder
Range-Doppler-Produkte müssen beide Kanäle kohärent beziehungsweise exakt
synchronisiert sein. Zwei beliebige USB-Dongles sind das nicht automatisch.

Sub-GHz-Forschung kann mit geeigneter Hardware möglich sein, aber:

- keine „Skelett-Ebene“ aus schmalbandigen Illuminatoren behaupten;
- keine Drohnen-/Fremdkommunikation dekodieren oder manipulieren;
- Spektrum-/Datenschutz-/Einsatzrecht vor Erfassung prüfen;
- nur autorisierte Testsender und kontrollierte Testflächen verwenden.

## 8. Kooperatives Mesh und Fusion

Feste Gewichte wie 35/25/15/15/10 Prozent sind kein Fusionsmodell. Sensoren messen
unterschiedliche Zustände, zu unterschiedlichen Zeiten und in unterschiedlichen
Frames. Die Gateway-Fusion benötigt stattdessen:

- gemeinsame Zeitbasis und Offset-/Drift-Schätzung;
- versionierte Extrinsics und Anchorpositionen;
- Measurement Models je Sensor;
- Kovarianz beziehungsweise belastbare Qualitätsindikatoren;
- Innovationsgating und Ausreißerbehandlung;
- Track-to-measurement-Association;
- Freshness-/Data-Loss-Zustände;
- Source-Provenance und Kalibrierungsrevision.

BLE-RSSI plus UKF erzeugt ohne bekannte Anchor-Geometrie keine präzise
Triangulation. Ein UKF kann systematischen Multipath-Bias nicht wegfiltern. BLE
soll zunächst als grobe Range-/Presence-Evidence mit kalibriertem Fehlerband
behandelt werden.

Ein Samsung A14 oder anderer Client darf erst nach Runtime-Capability-Bericht als
Gyro-, ARCore- oder RF-Knoten eingeplant werden. Modellnamen sind kein
Capability-Nachweis.

## 9. Radio-SLAM, SAR und Rendering

Handheld-SAR ist physikalisch möglich, setzt aber voraus:

- Radar-Rohdaten statt bereits geglätteter Zielkoordinaten;
- präzise Radar-/Kamera-/IMU-Zeitsynchronisation;
- kalibrierte Extrinsics und Antennenphase;
- ausreichend genaue 6-DoF-Trajektorie;
- Motion Compensation und Apertur-Sampling;
- kontrollierte Trajektorie und Ground Truth.

ARCore-Verfügbarkeit und Depth-Support sind geräteabhängig und auf der konkreten
CT45P-SKU nicht nachgewiesen. Ohne diese Gates darf die UI keine SAR-/AR-Pose als
verfügbar zeigen.

Der vorhandene `SmartMeshIntegrator` besitzt ein Octree, führt aber innerhalb von
Batches eine quadratische Nachbarschaftssuche aus und hat nur einen kommentierten
Retention-Platzhalter. Vor Echtzeit-Claims müssen implementiert werden:

- harte Chunk-/Voxelbudgets und Eviction;
- bounded Ingest/Backpressure;
- räumlicher Index für Clustering;
- Level of Detail;
- gemessene Speicher-, Akku-, Thermik- und FPS-Profile;
- kein Rendering synthetischer Personen ohne Quelle/Confidence.

## 10. Automation und Cyber-Sicherheitsgrenze

Nicht in den Produktumfang aufgenommen werden:

- Bruteforce- oder Paketinjektionsworkflows;
- autonome Auswahl/Ausführung von Exploits;
- Umgehung von SLA, FRP oder Gerätesperren;
- Löschen von FRP-Partitionen;
- Deaktivieren von AVB/Verified Boot oder `vbmeta`-Schutz;
- nicht autorisierte Firehose-/BROM-Payloads;
- LLM-generierte Flash-/Pinout-Aktionen ohne freigegebene Serviceunterlagen.

Zulässige Alternative für eigene, autorisierte Hardware:

```text
Asset-/Seriennummer prüfen
  -> Eigentums-/Auftragsnachweis
  -> OEM-Serviceverfahren auswählen
  -> signiertes Image + Hash + Compatibility Manifest prüfen
  -> Zwei-Personen-Freigabe für destructive writes
  -> Backup/Recovery Point
  -> OEM-/MDM-/Bootloader-API
  -> Ergebnis und Chain of Custody unveränderlich auditieren
```

Ein LLM darf hierfür höchstens Dokumentation auffinden oder einen nicht
ausführbaren Plan vorschlagen. Es erhält keinen generischen Shell-, USB-, Flash-
oder Netzwerk-Injection-Zugriff.

## 11. Praktischer, stufenweiser Zielplan

### Phase RF-0: Capability und Recht

- konkrete CT45P-/Client-SKUs und Android-Builds erfassen;
- ausgewählte ESP32-, Radar- und SDR-Boards festlegen;
- Funkzulassung, Datenschutz und Einsatzgrenzen klären;
- keine Capability ohne Runtime Report.

### Phase RF-1: Deterministische Adapter

- ESP32-CSI-Framevertrag und Firmwarefixture;
- optional separater LD2450-Adapter;
- X4-Board-/SDK-Auswahl und Rohdatenvertrag;
- monotone Sequenzen, Zeitabgleich, Backpressure und Health.

### Phase RF-2: Einzelmodalitäts-Benchmarks

Für jede Modalität separat Ground Truth, ROC/PR-Kurven, Miss-/False-Alarm-Rate,
Latenz, Drift und Degraded Cases messen. Keine multimodale Demo vor belastbarer
Einzelmodalität.

### Phase RF-3: Gateway-Fusion

- Measurement Models statt Prozentgewichte;
- Track State und Association;
- Kalibrierungsrevision und Evidence-Provenance;
- Replay-/Restart-/Out-of-order-Tests.

### Phase RF-4: CT45P-Projektion

- LOD-Streams und harte Framebudgets;
- Source/Confidence/Freshness sichtbar;
- Thermal-Status und Degraded Authority;
- keine synthetischen Avatare als Messwahrheit.

### Phase RF-5: Feldabnahme

- Material-/Raum-/Personenvarianten;
- Referenzinstrumente;
- Akku-/Thermik-/EMV-/Dauerlauf;
- Datenschutz- und Operatorabnahme.

## 12. Quellen für überprüfbare Teilbehauptungen

- Android `PowerManager` Thermal API:
  <https://developer.android.com/reference/android/os/PowerManager>
- Android Thermal API Guidance:
  <https://developer.android.com/games/optimize/adpf/thermal>
- ESP-IDF Wi-Fi CSI:
  <https://docs.espressif.com/projects/esp-idf/en/v5.4.2/esp32/api-guides/wifi.html>
- Novelda X4 Datenblatt:
  <https://dev.novelda.com/X4F103/_static/x4_datasheet_RevA.pdf>
- HLK-LD2450 Serial Protocol v1.03:
  <https://make.net.za/wp-content/datasheets/HLK%20LD2450%20Serial%20Communication%20Protocol%20v1.03.pdf>

## 13. Evidenzentscheidung

Die Spezifikation ist als Forschungsvision verwertbar, aber nicht als
Implementierungs- oder Releasebericht. Im aktuellen Repository sind weder
Through-Wall-3D, Vitalzeichenerkennung, Radio-SLAM, RTL-Passivradar, LangGraph,
Whisper-WASM noch forensische BootROM-Automation implementiert oder getestet.

Der belastbare nächste Schritt ist nicht ein autonomer „Hacking-Tasker“, sondern
ein versionierter Capability-/Health-/Sensorvertrag für ausgewählte externe
Knoten und ein kontrollierter Einzelmodalitäts-Benchmark am Linux-Gateway.
