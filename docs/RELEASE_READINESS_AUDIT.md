# Release-Readiness-Audit: 3dxAgent / CT45P

**Prüfdatum:** 2026-08-14

**Geprüfter Repository-Stand:** Branch `arena/019ffc03-88p3dkart-art`, Commit `fa3af61`

**Prüfart:** repository-grounded statische Prüfung und im verfügbaren Workspace ausführbare Tests

> **Ergebnis:** Der vorliegende Stand ist **kein v5.x-Produktionsrelease** und
> erzeugt derzeit **kein nachgewiesen gebautes oder signiertes APK**. Vorhandene
> Klassen belegen Implementierungsabsicht und teilweise Prototypfunktionalität,
> aber keine Ende-zu-Ende-, Hardware-, Compliance- oder Release-Abnahme.
>
> **Aktualisierung gegenüber der Erstprüfung:** Ein checksum-gepinnter Gradle-
> Wrapper, Gateway-Authentisierung, Android-Keystore-Enrollment, erneuerbare
> Gerätesessions, HTTPS/WSS-Ableitung, ein gateway-autoritatives Alarmservice,
> Alarm-REST/WebSocket-Projektion, persistente Events/Outbox und eine native
> Alarm-UI sind inzwischen im Quellstand vorhanden. Der vollständige Gateway-
> Testlauf vom 2026-08-14 bestand mit **82 Tests**. Der Android-Build bleibt in
> dieser Umgebung wegen nicht abrufbarer Gradle-/Maven-/Android-SDK-Artefakte
> blockiert; Android-Compile-, Installations- und Hardwareevidenz existiert daher
> weiterhin nicht. Details zum zusätzlich vorgeschlagenen KI-Agenten stehen in
> [Integrierter Agent: Audit und Zielarchitektur](INTEGRATED_AGENT_AUDIT.md). Die
> Repository- und Physikprüfung der später vorgeschlagenen CSI-/UWB-/mmWave-/SDR-/
> Radio-SLAM-Architektur einschließlich der Sicherheitsgrenze für
> Hardwareautomation steht im
> [RF-Sensor- und Hardwareautomations-Audit](RF_SENSOR_ARCHITECTURE_AUDIT.md).

Dieses Audit ersetzt Statusangaben wie „vollständig“, „getestet“, „signiert“ oder
„produktionsbereit“ durch überprüfbare Evidenz. Es ändert nicht die verbindliche
Zielarchitektur: Der CT45P ist die mobile Control Plane; ein Linux-Gateway ist die
autoritative Data Plane für Sensoradapter, Fusion, Alarmzustand, Persistenz und
Outbox-Zustellung.

---

## 1. Bewertungsmaßstab

Die folgenden Aussagen sind getrennt zu behandeln:

1. **Source present:** Datei oder Klasse existiert.
2. **Statically reviewed:** Aufrufpfade und offensichtliche Verträge wurden geprüft.
3. **Compiles:** Ein reproduzierbarer Build lief mit protokolliertem Ergebnis.
4. **Unit tested:** Deterministische Tests liefen gegen die konkrete Implementierung.
5. **Integrated:** Producer, Transport, Consumer, Persistenz und UI sind verbunden.
6. **Hardware validated:** Der Pfad wurde mit konkretem Gerät, Firmware und
   kalibriertem Aufbau geprüft.
7. **Operationally accepted:** Recovery, Security, Observability und Runbooks sind
   abgenommen.
8. **Released:** Ein identifizierbares, signiertes Artefakt mit Herkunft,
   Prüfsumme und Signaturprüfung liegt vor.

Eine vorhandene Klasse erfüllt nur Punkt 1. Ein erfolgreiches Unit-Testset erfüllt
weder Hardwarevalidierung noch Produktionsabnahme.

---

## 2. Releaseidentität und Buildsystem

### 2.1 Tatsächlicher Android-Stand

Aus `android-app/app/build.gradle.kts`:

| Merkmal | Repository-Wert |
|---|---|
| Projektart | Native Android-App, Kotlin/XML, Gradle Kotlin DSL |
| Namespace/Application ID | `com.example.agent` |
| Version | `2.0.0` |
| Version Code | `1` |
| Min/Target SDK | 31 / 34 |
| Release-Minifizierung | deaktiviert |
| Release-Signing-Konfiguration | nicht vorhanden |
| Android-Module | nur `:app` |

Damit widersprechen die überprüfbaren Metadaten einer behaupteten
`com.example.3dxagent`-App in Version `5.0.0` mit Code `50000`.

### 2.2 Nicht vorhandene Capacitor-/npm-Buildkette

Im Repository gibt es:

- kein Root-`package.json`;
- keine Capacitor-Konfiguration;
- kein `npm run android:apk` oder `npm run android:apk:release`;
- kein Verzeichnis `android-app/gradlew`;
- kein `android-app/gradle/wrapper/`;
- kein Git-getracktes APK/AAB;
- kein GitHub-Release mit APK;
- keine `.jks`-/Keystore-Datei und keine Release-Signing-Konfiguration.

Das einzige `package.json` gehört zum separaten
`web-visualizer/`. Befehle wie `npm install`, `npx cap sync android` und
`npm run android:apk:release` an der Repository-Wurzel sind deshalb für diesen
Quellbaum nicht anwendbar.

### 2.3 APK-Build in dieser Prüfung

Ein Android-Build konnte nicht als Testevidenz erzeugt werden:

- Java/JDK, Android SDK, `gradle` und `adb` sind im Workspace nicht installiert;
- das Projekt liefert selbst keinen Gradle Wrapper mit;
- Versuche, JDK, Gradle und Android Command-Line Tools nachzuladen, scheiterten an
  den in dieser Umgebung nicht erreichbaren externen Distribution-Endpunkten;
- ein Signing-Schlüssel mit geklärter organisatorischer Eigentümerschaft liegt
  nicht vor.

Das ist **kein Nachweis eines Buildfehlers im Quellcode**, aber ebenso wenig ein
Nachweis, dass der Quellcode kompiliert. Ein reproduzierbarer CI- oder lokaler
Build bleibt ein offenes Gate.

---

## 3. Ausgeführte Prüfungen

| Bereich | Ausgeführte Prüfung | Ergebnis | Reichweite |
|---|---|---|---|
| Edge-Agent | isolierte Python-Abhängigkeiten, `PYTHONPATH=/home/user/.cache/edge-agent-py python3 -m pytest -q` | **48 passed in 0.80 s** | bestehende Tests plus 30 reine Alarm-Reducer-/SQLite-Recovery-Tests; keine Hardware- oder Gateway-E2E-Prüfung |
| Web-Visualizer | `npm ci --ignore-scripts` | erfolgreich, 0 gemeldete npm-Auditschwachstellen | Dependency-Auflösung des Webmoduls |
| Web-Visualizer | Syntaxcheck von `server.js` und browserseitigem ES-Modul | erfolgreich | Syntax, kein Browser-E2E |
| Web-Visualizer | Start und HTTP-GET `/` | **HTTP 200** | lokaler Start/Static-Serving; kein Gateway-E2E |
| JSON-Verträge | strikte Draft-2020-12-Kompilierung der sieben Schemas mit AJV und `ajv-formats` | erfolgreich | Schemaform, nicht Laufzeitintegration |
| Android | Quell-/Manifest-/Gradle-Audit | Befunde in Abschnitt 4 | kein Compiler- oder Gerätetest |
| Firmware | Quell-/Builddatei-Audit | Befunde in Abschnitt 5 | kein Zephyr-Compile, Flash- oder Funk-Test |

Die 18 bestandenen Python-Tests dürfen nicht zu „alle Komponenten getestet“
verallgemeinert werden. Unter `android-app/app/src` existieren weder JVM-Unit-Test-
noch `androidTest`-Quellen. Für die Firmware existieren ebenfalls keine im
Repository gefundenen automatisierten Tests.

---

## 4. Android-App: statische Integrationsbefunde

### 4.1 Activity, Berechtigungen und Lebenszyklus

`MainActivity` startet Hardwarezugriffe unmittelbar nach dem asynchronen
Berechtigungsdialog. Es gibt keinen Callback, der nach Zustimmung die übersprungenen
Initialisierungen verlässlich erneut ausführt. Insbesondere:

- `BleTokenManager.startScan()` prüft nur `ACCESS_FINE_LOCATION`, nicht den
  notwendigen Laufzeitstatus von `BLUETOOTH_SCAN`;
- ein beim Erststart wegen fehlender Berechtigung abgebrochener BLE-Scan wird nach
  der Zustimmung nicht erneut gestartet;
- Sensoraufnahme, Netzwerk und periodische Loops sind an die Activity gebunden und
  werden in `onDestroy()` beendet;
- es gibt keinen Foreground Service, Worker, Boot-Receiver oder anderen
  implementierten dauerhaften Alarm-Lifecycle;
- UWB erhält nur einen Callback; `startRanging(...)` wird in der App nirgends
  aufgerufen;
- `PipelineOrchestrator` wird instanziiert, aber aus `MainActivity` nicht
  ausgeführt;
- der EKF der Activity erhält Updates, aber in diesem Aufrufpfad keinen
  `predict()`-Schritt.

Damit ist weder ein permanenter Hintergrundalarm noch eine vollständig verdrahtete
Live-Sensorpipeline vorhanden.

### 4.2 USB-Serial, LiDAR und mmWave

`SerialManager` ist ein Prototypadapter mit offenen Produktionsgates:

- Es fehlt der Android-USB-Permission-Flow mit `UsbManager.requestPermission(...)`,
  Receiver, Grant-/Deny-Behandlung und Wiederanlauf. `openDevice(...)` kann ohne
  Grant nur `null` liefern.
- Geräte werden nur nach Vendor-ID unterschieden. Produkt, Interface, Endpoint und
  Protokollversion werden nicht als Enrollment-/Capability-Vertrag geprüft.
- Der mmWave-Parser `parseMmwaveData(...)` gibt immer eine leere Liste zurück und
  ist im Quelltext ausdrücklich als Platzhalter markiert.
- Die LiDAR-Kommandos und der als 5-Byte-Punktfolge angenommene Parser sind gegen
  konkrete Modell-, Scanmodus- und Fragmentierungs-Fixtures zu validieren. Der
  Code besitzt keinen Stream-Reassembly-/Resynchronisationsnachweis.
- Jeder Aufruf von `initDevices()` startet einen weiteren endlosen Watchdog.
  Ein Watchdog ruft bei fehlendem Gerät erneut `initDevices()` auf, wodurch weitere
  Watchdogs entstehen können. Das ist kein begrenzter Recovery-Zustandsautomat.
- Disconnect, partieller Frame, fehlerhafte Länge, Backpressure, USB-Reattach und
  konkurrierende Initialisierung sind ungetestet.

Folglich ist „LiDAR vollständig“ nicht belegt; „mmWave vollständig“ wird bereits
durch den immer-leeren Parser widerlegt.

### 4.3 BLE-App/Firmware-Vertrag

Zwischen `BleTokenManager.kt` und `ble-token-firmware/src/main.c` besteht ein
konkreter Payload-Vertragsfehler:

- Die Firmware übergibt Manufacturer Data mit 2-Byte-Company-ID plus drei
  Little-Endian-`int16`-Werten und Batterie.
- Android indexiert Manufacturer Data bereits über die Company-ID. Der von
  `getManufacturerSpecificData(COMPANY_ID)` gelieferte Wert ist der zugehörige
  Payload, nicht erneut ein 9-Byte-Paket mit Company-ID.
- Die App verwirft Werte kleiner als neun Byte und liest jeweils nur ein einzelnes
  Byte an den Positionen 2, 4 und 6 statt je zwei Little-Endian-Bytes. Der
  gezeigte Producer-/Consumer-Vertrag kann so keine korrekten Beschleunigungswerte
  liefern.
- Die Firmware ruft in der 200-ms-Schleife wiederholt `bt_le_adv_start(...)` auf,
  statt laufende Advertising-Daten mit dem vorgesehenen Update-/Stop-Start-Pfad
  zu aktualisieren. Nach dem ersten Start ist ein „already advertising“-Fehler zu
  erwarten; eine erfolgreiche fortlaufende Nutzdatenaktualisierung ist nicht
  belegt.
- Der Batteriewert der Firmware ist ausdrücklich ein fester Platzhalter `100`.
- Die Android-App verwendet keinen engen `ScanFilter`, keine stabile gebundene
  Asset-ID, keine Background-Discovery-Strategie und keine Messqualitätsmetadaten.

Der BLE-Pfad benötigt zuerst einen versionierten Bytevertrag und Golden-Frame-Tests
auf beiden Seiten. Danach folgen Funk-, Reconnect-, Identifier-, Akku- und
Interferenztests.

### 4.4 UWB und behauptete Micro-Doppler-Erfassung

`UwbManager` erzeugt keine rohe UWB-Phase. Die Methode `extractPhase(...)`
projiziert eine bereits berechnete Distanz modulo einer angenommenen Wellenlänge
auf einen Winkel. Das ist ausdrücklich eine vereinfachte Modellierung und kein
Nachweis eines Rohphasen- oder Micro-Doppler-Datenkanals.

Zusätzlich fehlen:

- Capability-Check des konkreten CT45P;
- nachgewiesene CT45P-UWB-Hardware;
- Provisioning/Session-Schlüssel und Peer-Lifecycle;
- Startaufruf aus der App;
- NLOS-/Clock-/Ranging-Qualität;
- Fixtures und Hardwaremessungen gegen ein konkretes UWB-Modul.

Eine AndroidX-UWB-Abhängigkeit fügt einem Gerät keine UWB-Hardware hinzu. Dieser
Pfad darf erst nach bestandenem Hardware-Gate als verfügbar angezeigt werden.

### 4.5 Fusion, Position und Visualisierung

Mehrere vorhandene Algorithmen sind nützliche Ausgangspunkte, aber noch keine
validierte Sensorfusion:

- Die Activity verwendet den **ersten LiDAR-Punkt** als Positionsmessung für den
  EKF. Ein Punkt auf einer Oberfläche ist ohne Registrierung/SLAM nicht die
  globale Geräteposition.
- mmWave liefert wegen des leeren Parsers keine Targets in diesen Pfad.
- UWB wird nicht gestartet.
- Der in `MapRenderer` aus einer BLE-MAC-Adresse gehashte Winkel ist keine
  physische Richtung. RSSI plus erfundener Winkel ist keine Triangulation.
- Die Persistenzfelder `covLidar` und `covMmwave` werden mit Elementen der
  Zustandskovarianz befüllt; die Namen belegen keine getrennte, kalibrierte
  Messunsicherheit je Sensor.
- Batterie `85`, Temperatur `45`, Device-ID `CT45P-01` und
  `scatteringDetected=false` sind in der Activity fest verdrahtet.
- `LiveSensorPipeline`, `LocalApiServer` und `LocalWebSocketServer` haben außerhalb
  ihrer eigenen Dateien keine produktive Verdrahtung.
- Die Activity-Layoutdatei enthält nur einen gewöhnlichen `FrameLayout` als
  `nav_host_fragment`; die aktuelle Activity richtet keinen NavController ein.
  Damit ist die Existenz von Fragmenten noch kein nachgewiesener navigierbarer UI-Pfad.

Die behauptete Genauigkeit kann daraus nicht abgeleitet werden. Abnahme erfordert
Ground Truth, Kalibrierungsrevisionen, Zeitbasis, gemeinsame Koordinatenrahmen,
Datenassoziation, Unsicherheitsfortpflanzung und Messreihen je Umgebung.

### 4.6 Persistenz, Netzwerk und Security

Der aktuelle Android-Prototyp erfüllt keine produktive Security- oder
Auditarchitektur:

- Gateway-Adressen sind als `http://192.168.1.100:8080` und
  `ws://192.168.1.100:8080/...` fest verdrahtet;
- es gibt keine belegte Server-/Clientauthentisierung, Zertifikatprüfung,
  Command-Signatur oder rollenbasierte Autorisierung;
- lokale Server binden ohne gezeigte Authentisierung/TLS und setzen teilweise
  `Access-Control-Allow-Origin: *`;
- das Manifest erlaubt App-Backups;
- Room verwendet `fallbackToDestructiveMigration()`, was keine belastbare
  Audit-/Upgrade-Strategie ist;
- die Activity löscht räumliche Datensätze nach sieben Tagen; dies ist weder die
  behauptete einjährige Alarmaufbewahrung noch eine abgenommene Retention Policy;
- ein Alarm-Eventjournal, Policy-Revisionen, Evidence-Snapshots und eine
  transaktionale Delivery-Outbox sind im Runtime-Code nicht vorhanden.

„AES-256“, „sichere Authentifizierung“, „DGUV-konformer Audit-Log“ oder
„produktionssicher“ sind daher keine nachgewiesenen Eigenschaften dieses Stands.

### 4.7 Der vorgeschlagene Ersatz für `MainActivity`

Der vorgelegte monolithische Activity-Ausschnitt wurde bewusst nicht übernommen.
Er passt nicht zu den vorhandenen Verträgen:

- Layout-IDs wie `tvStatus`, `tvPosition`, `tvVoxels` und `tvMode` existieren in
  `activity_main.xml` nicht;
- `EvaluationAgent.evaluate(...)` erwartet vier konkrete Pipelineobjekte und keine
  Liste von Punkten plus `SmartMeshIntegrator`;
- referenzierte Objekte wie `reconstructor` und `mapper` sind nicht definiert;
- der Ausschnitt würde parallele Sensor-, Pipeline-, Offline-, UI- und Serverrollen
  weiter in eine Activity konzentrieren;
- er implementiert weiterhin weder Gateway-Autorität noch Alarmzustandsautomat,
  Outbox, Recovery oder Security.

Eine Ersetzung hätte deshalb den Stand verschlechtert und keinen kompilierbaren
Produktionspfad erzeugt.

---

## 5. Firmware-Readiness

Die nRF52840-Firmware besteht derzeit im Wesentlichen aus einer einzelnen
`main.c` und Zephyr-Grundkonfiguration. Neben dem BLE-Vertragsfehler aus Abschnitt
4.3 fehlen für eine Produktionsfreigabe mindestens:

- reproduzierbarer Zephyr-SDK-/Board-Build mit gelockten Versionen;
- Devicetree-Overlay und Board-/Pin-/Bus-Nachweis für den BMI270;
- Batteriespannungsmessung statt Festwert;
- versioniertes Advertising-Format, Sequenz, Geräteidentität und Integritätsschutz;
- kontrollierte Advertising-Aktualisierung und Energiemodi;
- Boot-/Brownout-/Sensorfehler-Recovery;
- Golden-Frame-, Unit-, HIL-, Reichweiten- und Akkulaufzeittests;
- signiertes Firmwareartefakt und Update-/Rollback-Verfahren.

Das Verzeichnis heißt `ble-token-firmware/`; eine behauptete Unterstruktur
`ble-token-firmware/nrf52840/` existiert in diesem Repository nicht.

---

## 6. Prüfung der zusätzlichen Dart-Algorithmen

Die Dart-Ausschnitte gehören nicht zu diesem nativen Android-Repository und wurden
nicht als zweite App-Laufzeit übernommen. Unabhängig davon enthalten sie
Compile- und Semantikfehler:

### 6.1 RSSI und UWB

- Eine Klasse wird innerhalb von `BleDistanceEstimator` deklariert; Dart kennt
  keine solchen verschachtelten Klassendeklarationen.
- Ein neuer Kalman-Filter je Messfenster startet bei RSSI `0` und verwirft seinen
  Zustand nach jedem Aufruf. Das ist weder korrekt initialisiert noch eine
  kontinuierliche Filterung.
- `measurementWindow` wird nicht verwendet.
- Die als `confidence` bezeichnete Heuristik ist weder kalibrierte
  Wahrscheinlichkeit noch ein Konfidenzintervall; Intervallgrenzen fehlen.
- Kalibrierungen leben nur in einer statischen In-memory-Map und besitzen keine
  Hardware-, Standort-, Antennen-, Zeit- oder Revisionsbindung.
- Ein ganzzahliger `tofNanoseconds` mit pauschaler Division durch zwei klärt nicht,
  ob die Eingabe One-Way- oder Round-Trip-ToF ist. Auflösung, Bias, NLOS,
  Antennenverzögerung und Protokollqualität fehlen.

### 6.2 „SensorFusion“

- Die Typen `BleDistanceResult` und `DistanceResult` sind nicht konsistent.
- Ein gewichteter Mittelwert heterogener Reichweiten ist keine Fusion, solange
  Assetzuordnung, Bezugspunkt, Koordinatenrahmen, Messzeit und Kovarianz nicht
  übereinstimmen.
- LiDAR-, BLE-, UWB- und Wi-Fi-Distanzen können unterschiedliche Objekte oder
  Referenzen beschreiben.
- Feste Gewichte und gemittelte Confidence erhöhen die Sicherheit auch dann,
  wenn Quellen systematisch falsch oder korreliert sind.
- `_accuracyWeights` wird nicht verwendet; Distanzabweichung ersetzt kein
  probabilistisches Innovations-/Gatingmodell.

### 6.3 Bewegung und Alarmbedingungen

- Bei Annäherung ist `distanceDiff` und damit `speed` negativ. Der Code fordert
  gleichzeitig `speed > 0.1` und `distanceDiff < 0`; `isApproaching` kann daher
  nie wahr werden. Auch `isMoving = speed > 0.5` ignoriert schnelle negative
  Bewegung.
- Das Verfahren misst radiale Distanzänderung, nicht generell die Bewegung des
  Assets.
- Dwell-Methoden sind als Stubs implementiert: die Startzeit ist immer `null`,
  Reset ist leer. Eine konfigurierte Mindestdauer kann deshalb nicht korrekt
  erfüllt werden.
- Die Alarmprüfung verwendet einen konstanten Distanzwert `0`; echte
  Sensorfusion ist nicht angeschlossen.
- `activeFrom` und `activeTo` sind als Listen deklariert, werden aber wie einzelne
  `TimeOfDay`-Werte über `.hour` und `.minute` verwendet.
- Geofence-Flags für Enter/Exit werden nicht als Übergangsautomat ausgewertet;
  GPS-Position des Handhelds und Position eines externen Assets werden vermischt.

### 6.4 Geofence, Hintergrund und Audit

- Ein `Timer.periodic` ist kein persistenter Android-Hintergrundmechanismus.
- Initialzustand, Accuracy, Hysterese, stale Position, Zeitzone/DST und
  überlappende asynchrone Checks sind nicht robust behandelt.
- Ein veränder- und löschbarer Isar-Datensatz mit einjährigem Cleanup ist kein
  unveränderliches Auditjournal und kein Compliance-Nachweis.
- Policy-Revision, Evidence, Actor, vertrauenswürdige Zeit, Integrität,
  Zugriffsschutz, Exportverifikation und Zustell-Outbox fehlen.
- DGUV Vorschrift 3 wird nicht durch einen Boolean für kritische Ereignisse oder
  eine feste Retention-Frist erfüllt. Rechtliche/organisatorische Anforderungen
  müssen separat mit Verantwortlichen und Prüfnachweisen festgelegt werden.

Die übernommenen fachlichen Anforderungen und die belastbare Zielumsetzung stehen
in [Dauerhafter Hintergrund-Abstandsalarm](BACKGROUND_DISTANCE_ALARM.md).

---

## 7. Readiness-Matrix

Legende: **ja** = nachgewiesen, **teilweise** = begrenzte Evidenz,
**nein** = fehlt oder widerlegt, **blockiert** = in dieser Umgebung nicht ausführbar.

| Komponente | Source | Build | automatisierte Tests | E2E integriert | reale Hardware | Security/Recovery | Release |
|---|---:|---:|---:|---:|---:|---:|---:|
| Android-App | ja | blockiert | Tests vorhanden, nicht ausgeführt | nein | nein | teilweise gehärtet | nein |
| USB/LiDAR | parser- und lifecycle-seitig vorhanden | blockiert | JVM-Tests vorhanden, nicht ausgeführt | nein | nein | Foreground-Gate im Quellcode | nein |
| mmWave | Datenparser vorhanden; CLI-Profil bewusst deaktiviert | blockiert | JVM-Tests vorhanden, nicht ausgeführt | nein | nein | unvollständig | nein |
| BLE Android | versionierter Bytevertrag vorhanden | blockiert | JVM-Tests vorhanden, nicht ausgeführt | source-level mit Firmware abgestimmt | nein | teilweise | nein |
| BLE-Firmware | versionierter Bytevertrag vorhanden | blockiert | nein | source-level mit Android abgestimmt | nein | nein | nein |
| UWB | Android-Lokalpfad entfernt; Gateway-Modell unkalibriert | blockiert | nein | nicht gestartet | nein | nein | nein |
| Kotlin-Fusion/Pipeline | Altpfad teilweise, Produktionsaktivität bereinigt | blockiert | nein | nein | nein | nein | nein |
| Hintergrundalarm | Reducer + SQLite-Repository vorhanden | Python import-/testbar | 30 bestanden | noch nicht an Mess-/API-Pfad angebunden | nein | Restart/CAS/Outbox unit-getestet | nein |
| Edge-Agent Bestand | ja | Python import-/testbar | 48 bestanden | teilweise | nein | unvollständig | nein |
| Web-Visualizer | ja | npm-Auflösung erfolgreich | Syntax + HTTP-Smoke | Gateway-E2E nein | – | unvollständig | nein |
| JSON-Verträge | ja | 7 strikt validiert | Golden-Beispiele | Runtime noch nicht | – | Entwurf | nein |
| APK/AAB | nein | nein | nein | nein | nein | keine Signatur | nein |

---

## 8. Behauptungsprüfung

| Behauptung | Auditentscheidung | Begründung |
|---|---|---|
| „v5.0.0“ | **nicht belegt/widersprochen** | Android- und Edge-Metadaten nennen 2.0.0; kein v5-Tag oder Release |
| „APK erfolgreich gebaut“ | **nicht belegt** | kein Wrapper, CI-Nachweis oder Artefakt |
| „APK signiert“ | **nicht belegt** | keine Signing-Konfiguration, Signaturprüfung oder Artefakt |
| „downloadbereit“ | **falsch für dieses Repository** | kein GitHub-Release/Downloadartefakt; Beispiel-URLs sind kein Release |
| „alle Komponenten getestet“ | **falsch** | nur 18 begrenzte Python-Tests; keine Android-/Firmwaretests |
| „mmWave vollständig“ | **falsch** | Parser gibt immer leere Liste zurück |
| „UWB Micro-Doppler vollständig“ | **falsch** | kein Startpfad; Phase nur aus Distanz modelliert; kein Hardwarebeleg |
| „BLE-Token vollständig“ | **falsch** | Producer-/Consumer-Layout fehlerhaft; Batterie Platzhalter |
| „keine Simulation/Mocks“ | **kein Qualitätsnachweis** | Quelltext enthält vereinfachte Modelle, heuristische/Platzhalterpfade und Hardcodes |
| „permanenter Alarm“ | **nicht implementiert** | weder Gateway-Engine noch nativer dauerhafter Fallback vorhanden |
| „DGUV-konform“ | **nicht belegt** | technisches und organisatorisches Audit-/Prüfkonzept fehlt |
| „produktionsbereit“ | **abgelehnt** | Build, Security, Recovery, Hardware, E2E und Release-Gates offen |

Mocks und simulierte Fehler sind in Tests nicht generell zu verbieten. Produktionscode
darf keine unmarkierten Mockdaten als Messwahrheit ausgeben; deterministische Fakes,
Protokollfixtures und Fault Injection sind dagegen notwendig, bevor reale Hardwaretests
einen Release ergänzen.

---

## 9. Verbindlicher Weg zu einem signierten Release

### Gate R0 – Produkt- und Signieridentität

- endgültige Application ID, Versionsstrategie und Upgradepfad entscheiden;
- Eigentümer des Release-Schlüssels beziehungsweise Play-/MDM-Signing festlegen;
- vorhandene installierte App und erforderliche Signaturkontinuität klären;
- keine Passwörter oder privaten Schlüssel im Repository speichern.

**Exit:** dokumentierte Identität und durch die Organisation kontrollierter
Signing-Prozess.

### Gate R1 – Reproduzierbarer Build

- vertrauenswürdigen Gradle Wrapper hinzufügen und dessen Distribution-Hash pinnen;
- JDK-/Android-SDK-Versionen in CI pinnen;
- `assembleDebug`, Unit-Tests, Lint und `assembleRelease` als CI-Jobs ausführen;
- Dependency- und SBOM-Prüfung ergänzen;
- Release darf nur aus sauberem Commit entstehen.

**Exit:** reproduzierbares unsigned/release-signed CI-Artefakt mit Provenance.

### Gate R2 – Architektur und Verträge

- Activity auf Navigation/Composition Root reduzieren;
- CT45P-Control-Plane und Gateway-Data-Plane tatsächlich trennen;
- die JSON-Verträge in beide Laufzeiten integrieren;
- Capability Negotiation für alle optionalen Sensorpfade implementieren.

**Exit:** keine UI behauptet einen nicht vorhandenen Sensor; Producer und Consumer
bestehen Contract-Tests.

### Gate R3 – Hardwareadapter

- USB-Permission-/Reconnect-Zustandsautomat;
- vollständiger mmWave-TLV-Parser mit Herstellerfixtures;
- LiDAR-Streaming/Reassembly gegen aufgezeichnete und reale Frames;
- BLE-Bytevertrag und Advertising-Update korrigieren;
- UWB nur bei nachgewiesener Hardware/externem Adapter freigeben.

**Exit:** Unit-, Contract-, HIL- und Dauerlauftests je Adapter bestanden.

### Gate R4 – Autoritative Alarmengine

Die Phasen A–D aus
[BACKGROUND_DISTANCE_ALARM.md](BACKGROUND_DISTANCE_ALARM.md) umsetzen:
qualitätsbewusste Evidence, deterministischer Reducer, Dwell/Hysterese/Data Loss,
transaktionale Events/Outbox, Recovery, revisionierte Commands, CT45P-Projektion und
klar markierter optionaler Fallback. Der reine Reducer und das isolierte
SQLite-Repository decken Evidence-Admissibilität, Unsicherheitsgrenzen, Dwell,
Hysterese, Data Loss, CAS, Cursor, Restart-Rebase und Outbox-Leases inzwischen mit
Unit-Tests ab. Event-Contract-Rendering, Messpfad-, API-, Dispatcher- und
Asset-Projektionsintegration fehlen weiterhin.

**Exit:** Crash-, Restart-, Duplikat-, Reihenfolge-, Netzwerk- und Clock-Tests
bestehen ohne verlorene fachliche Events.

### Gate R5 – Security und Betrieb

- TLS/mTLS oder gleichwertig abgenommenen Transport;
- Geräte-/Operatoridentität, Rollen und signierte Commands;
- Secret Provisioning, Rotation und Revocation;
- verschlüsselte/minimierte Datenhaltung nach Threat Model;
- Metriken, Logs, Alarmierung und Runbooks;
- Datenschutz-, Retention- und Compliance-Abnahme.

**Exit:** Threat Model, Penetrationstestbefunde und Betriebsabnahme dokumentiert.

### Gate R6 – CT45P- und Feldabnahme

- konkrete CT45P-Variante/Android-Build inventarisieren;
- Permission-, Doze-, Reboot-, Update-, MDM-, DND- und Force-stop-Verhalten testen;
- Sensoren in den vorgesehenen Umgebungen gegen Ground Truth kalibrieren;
- Fehlalarm-/Miss-Rate, Latenz, Akku, Thermik und Recovery messen;
- kritische Funktion nicht ausschließlich an Android-Notification koppeln.

**Exit:** signierte Abnahmeprotokolle mit Serien-/Firmware-/Kalibrierungsbezug.

### Gate R7 – Release und Signaturverifikation

Erst danach:

1. Release-Build im kontrollierten CI signieren;
2. Signatur mit `apksigner verify --verbose --print-certs` prüfen;
3. APK/AAB-Hash und Build-Provenance veröffentlichen;
4. Upgrade und frische Installation auf verwalteten CT45P testen;
5. Rollback-/Revocation-Verfahren prüfen;
6. Git-Tag und Release Notes auf exakt denselben Commit beziehen.

**Exit:** installierbares, organisationssigniertes Artefakt mit Prüfsumme,
Provenance, Testbericht und Rollbackplan.

---

## 10. Schlussentscheidung

Aus diesem Stand darf **kein „finales signed APK“ als fertiges Produkt erzeugt oder
beworben** werden. Eine beliebige neu erzeugte lokale Signatur wäre ohne geklärte
Schlüsseleigentümerschaft, Upgradepfad und Abnahme kein valider Produktionsrelease.
Ebenso wäre ein Debug-APK kein Ersatz.

Das unmittelbar belastbare Ergebnis dieser Prüfung ist:

- die Python-Bestandstests sind mit 18/18 erfolgreich;
- der Web-Visualizer löst Dependencies auf, besteht Syntaxprüfung und liefert lokal
  HTTP 200;
- die Architektur- und Alarmverträge sind dokumentiert;
- konkrete Android-/Firmware-/Protokollfehler und Releaseblocker sind identifiziert;
- der Produktions-, Hardware- und Signing-Nachweis bleibt offen.

Die nächste Implementierungsstufe muss mit R0/R1 und dem gatewayseitigen Alarmkern
beginnen, nicht mit einem ungetesteten monolithischen Activity-Austausch oder einer
parallelen Flutter-/Capacitor-Migration.
