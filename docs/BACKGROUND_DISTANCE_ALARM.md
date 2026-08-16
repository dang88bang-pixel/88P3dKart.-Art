# Dauerhafter Hintergrund-Abstandsalarm

> **Status:** Zielarchitektur und Implementierungsvertrag, keine Behauptung einer bereits
> produktionsreifen Laufzeitimplementierung. Maßgeblich sind der native Android/Kotlin-Client
> auf dem CT45P und der Linux-Gateway-Dienst in diesem Repository. Flutter und iOS gehören
> nicht zum aktuellen Zielsystem.

Diese Spezifikation konkretisiert die
[CT45P-Master-Architektur](CT45P_MASTER_ARCHITECTURE.md), die
[Alternativenanalyse](ALTERNATIVE_IMPLEMENTATIONS.md) und die
[Device-Management-Plattform](DEVICE_MANAGEMENT_PLATFORM.md) für einen dauerhaft
wirksamen Distanzalarm. Sie trennt vier Dinge, die in einfachen Beispielen oft vermischt
werden:

1. Messung und Qualität der Distanzschätzung,
2. deterministische Alarmentscheidung,
3. dauerhafte Alarm- und Audit-Daten,
4. Zustellung und Darstellung am CT45P.

Die zentrale Entscheidung lautet:

> **Der Linux-Gateway-Dienst ist die Autorität für Messwertqualität, Alarmzustand,
> Persistenz und Wiederanlauf. Der CT45P ist Control Plane und Zustellkanal.**

Ein CT45P-lokaler Alarm ist nur eine ausdrücklich gekennzeichnete, eingeschränkte
Fallback-Funktion. Er ersetzt die Gateway-Autorität nicht.

---

## 1. Ergebnis der Prüfung des vorgeschlagenen Flutter-Entwurfs

### 1.1 Nutzbare Produktanforderungen

Folgende Anforderungen werden übernommen:

- Alarm je Asset und nicht nur als globale App-Einstellung;
- konfigurierbare Schwelle, Haltezeit, Hysterese, Schweregrad und Zustellprofil;
- Warnung bei fehlender oder zu alter Messung;
- Quittieren und zeitlich begrenztes Stummschalten;
- Verlauf mit unveränderlichen Ereignissen;
- Offline-Anzeige des zuletzt bestätigten Gateway-Zustands;
- Wiederherstellung nach Prozess- oder Gateway-Neustart;
- Benachrichtigung, Ton und Vibration nach Plattform- und Nutzerregeln;
- energieabhängige Reduktion nichtkritischer UI-Aktualisierungen;
- Tests für Zustandsautomat, Persistenz, Idempotenz und Störungen.

### 1.2 Zu revidierende Ansätze

| Vorgeschlagener Ansatz | Problem | Zielansatz |
|---|---|---|
| Flutter/Dart als neue App-Basis | Das Repository enthält bereits eine native Kotlin/XML-App; eine zweite Laufzeit verdoppelt Berechtigungs-, Lifecycle- und Testaufwand. | Bestehende Android-App modularisieren und erweitern. |
| `Timer.periodic` alle zwei Sekunden | Der Timer existiert nur, solange Prozess und Isolate laufen. | Messwertgetriebene Auswertung im Gateway; kein Polling-Timer als Autorität. |
| Periodischer WorkManager für Sekunden-Takt | Periodische Arbeit ist ungenau und hat mindestens 15 Minuten Intervall. | WorkManager ausschließlich für deferrable Sync, Upload und Reparatur. |
| Rekursive One-shot-Worker | Keine Dauerlaufgarantie; kann gedrosselt, verzögert oder durch Restriktionen blockiert werden. | Ereignisverarbeitung im Gateway; begrenzter nativer Foreground-Fallback nur bei explizitem Betriebsmodus. |
| `null`-Distanz bedeutet sofort „verloren“ | Verwechselt „keine Information“ mit „Schwellwert verletzt“. | Eigener Datenverlustpfad mit Freshness-, Qualitäts- und Haltezeitregel. |
| Alarm bei einem einzelnen Wert | Ausreißer und Rauschen erzeugen Flattern. | Unsicherheit, Mindestkonfidenz, Dwell und Hysterese auswerten. |
| Auto-Deaktivierung nach maximaler Triggerzahl | Ein realer Gefahrenzustand kann still verschwinden. | Erkennung bleibt aktiv; nur Wiederholungszustellung wird gedrosselt. |
| Quittierung löscht Alarm | Operatorhandlung und physischer Zustand werden vermischt. | Quittierung ändert Aufmerksamkeit, nicht die Gefahrenbedingung. |
| Snooze als zukünftiger Messzeitpunkt | Domänenfremd und fehleranfällig. | Eigenes `snoozed_until`; Zustandsübergang wird auditiert. |
| `requiresBatteryNotLow` für kritische Erkennung | Unterdrückt Arbeit gerade bei niedriger Energie. | Kritische Erkennung bleibt gatewayseitig aktiv; CT45P meldet Zustell-Degradation. |
| Exact-Alarm-Berechtigung für Dauermonitoring | Exakte Alarme sind kein kontinuierlicher Sensor-Loop. | Nicht für Distanzabtastung verwenden. |
| iOS `BackgroundTasks` als permanenter Loop | Opportunistische Hintergrundfenster bieten keine Dauer- oder Sekunden-Garantie. | iOS ist außerhalb des CT45P-Zielumfangs; spätere iOS-Zustellung separat entwerfen. |

### 1.3 Konkreter statischer Befund am vorgelegten Code

Unabhängig von der falschen Runtime-Verteilung enthält der Ausschnitt konkrete
Funktions- und Testfehler. Die wichtigsten Befunde sind:

| Stelle | Befund | Auswirkung |
|---|---|---|
| `AlarmConfigDialog._isEnabled` | Der Wert wird initialisiert, aber im Dialog durch kein Bedienelement geändert. Der Button mit Text „Aktivieren“ speichert deshalb einen zuvor deaktivierten Alarm erneut deaktiviert. | Der primäre Aktivierungsfluss funktioniert für neue beziehungsweise deaktivierte Alarme nicht. |
| `StoredAlarm`/`saveAlarm` | Bei jeder Konfiguration wird ein Objekt mit neuer Auto-ID geschrieben; `deviceId` ist nicht als eindeutig modelliert und es gibt kein Upsert anhand einer stabilen Policy-ID. | Doppelte Regeln und ein nichtdeterministisches `findFirst()` sind möglich. |
| `_backgroundAlarm` | Die Einstellung lebt nur im Widget-State und wird nicht in Policy oder Storage gespeichert. | Nach Prozessneustart ist die gewählte Betriebsart nicht rekonstruierbar. |
| `copyWith` und `updateAlarmState` | Nullable Felder verwenden `value ?? oldValue` beziehungsweise werden nur bei `!= null` gesetzt. Ein explizites Löschen von `triggeredAt` ist damit nicht möglich. | Auto-Reset und Dismiss hinterlassen alte Fristen; „null“, „unverändert“ und „löschen“ sind nicht unterscheidbar. |
| WorkManager-Callback | Isar, Notification-Plugin und Messadapter werden im separaten Worker-Isolate nicht initialisiert; statische Main-Isolate-Singletons sind dort keine belastbare Initialisierung. | Der Hintergrundcheck kann bereits beim Zugriff auf `AlarmStorage.isar` scheitern. |
| Foreground-Pfad | Ein Prozess-`Timer.periodic` und rekursiv geplante One-shot-Arbeit ergeben keinen permanenten Zwei-Sekunden-Service. | Prozessende, Doze, Quoten und Hintergrundstartgrenzen brechen die behauptete Echtzeitüberwachung. |
| Periodischer Task | 15-Minuten-WorkManager mit `requiresBatteryNotLow=true` ist der einzige planmäßig wiederkehrende persistente Ersatzpfad. | Reaktionszeit ist für einen Sekundenalarm ungeeignet und Arbeit kann bei niedrigem Akku unterdrückt werden. |
| `_performBackgroundCheck` | Ein einzelnes `null` wird sofort als „Gerät verloren“ behandelt; Alter, Qualität, Confidence, Unsicherheit, Hysterese und Dwell fehlen. | Funklücken und Ausreißer können Fehlalarme oder falsche Entwarnungen erzeugen. |
| Nebenläufigkeit | Timer, periodischer Worker und One-shot-Worker können denselben Alarm ohne Lock, Revision oder Deduplication Key prüfen. | Doppelte Trigger, verlorene Updates und widersprüchliche Trigger-Zähler sind möglich. |
| Persistenz/Zustellung | Statusänderung, Trigger-Zähler, Audit und Notification liegen nicht in einer atomaren Transaktion mit Outbox. | Ein Crash kann einen aktiven Alarm ohne Zustellung oder eine Zustellung ohne nachvollziehbares Event hinterlassen. |
| Notification-Aktionen | Beim Anzeigen wird kein belastbarer Asset-/Event-Payload übergeben; das Action-Setup bleibt in `main` ein Kommentar. `dismiss` versucht zudem, den physischen Zustand zu löschen. | Snooze/Dismiss/Open sind nicht end-to-end implementiert und vermischen Aufmerksamkeit mit Bedingung. |
| Snooze | `triggeredAt` wird auf einen zukünftigen Zeitpunkt gesetzt und zugleich für Cooldown und Triggerzeit verwendet. | Historie und Fristsemantik werden verfälscht; eine eigene `snoozed_until`-Frist fehlt. |
| Max-Trigger-Logik | Die Grenze wird vor dem Inkrement geprüft. Bei `maxTriggerCount=3` deaktiviert erst der vierte Aufruf; `DEVICE_LOST` prüft die Grenze gar nicht. | Implementierung und angegebener Test widersprechen einander; eine Gefahrenregel kann außerdem still deaktiviert werden. |
| Batteriemodi | `_setScanParameters` ist nur ein Log-Stub; bei niedrigem Akku wird Monitoring nichtkritischer Regeln beendet. | Die behauptete adaptive Scansteuerung existiert nicht, während Erkennungsabdeckung unbemerkt sinkt. |
| iOS-Registrierung | Es wird kein erster `BGAppRefreshTaskRequest` eingereicht; spätere Ausführung wäre ohnehin opportunistisch. Ein für den Callback laufendes Flutter-Engine-/Plugin-Bootstrap ist nicht gezeigt. | Der gezeigte Pfad startet nicht zuverlässig und kann keine permanente Überwachung garantieren. |
| Fehlerpfad | `e as Exception` scheitert für andere Dart-Fehlerobjekte; die gekürzte Fehlermeldung setzt mindestens 50 Zeichen voraus und transienter Fehler deaktiviert die Regel. | Der Error-Handler kann selbst fehlschlagen oder die Überwachung nach einer vorübergehenden Störung abschalten. |
| „Audit-Log“ | `_logAlarm` schreibt nur `debugPrint`; es gibt kein append-only Journal, keine Policy-/Evidence-Referenz und keine Aufbewahrung. | Die Audit-Behauptung ist nicht erfüllt. |
| Tests | Die Tests initialisieren weder Isar noch Plugin-Fakes, speichern den Alarm nicht und prüfen anschließend das stale Setup-Objekt. Cooldown verhindert zudem die behaupteten drei unmittelbaren Trigger. | Die Beispiele sind keine ausführbaren Nachweise für die Implementierung. |

#### Zusätzlicher Befund zu Distanz-, Fusions-, Bewegungs- und Geofence-Ausschnitten

Die später vorgelegten Beispiele schließen die oben genannten Lücken nicht. Sie enthalten
zusätzlich folgende statisch erkennbare Probleme:

- `RssiKalmanFilter` wird innerhalb von `BleDistanceEstimator` deklariert; Dart
  unterstützt keine solchen verschachtelten Klassendeklarationen. Ein neuer Filter je
  Messfenster startet außerdem bei RSSI `0` und verliert danach seinen gesamten Zustand.
- `measurementWindow` wird nicht verwendet. Die als `confidence` bezeichnete Heuristik
  liefert weder eine kalibrierte Wahrscheinlichkeit noch ein Konfidenzintervall mit
  Grenzen. Die Kalibrierung lebt nur in einer statischen In-memory-Map ohne Geräte-,
  Standort-, Zeit- oder Revisionsbezug.
- `BleDistanceResult` und `DistanceResult` sind nicht konsistent typisiert. Ein fester
  gewichteter Mittelwert aus BLE, UWB, Wi-Fi und LiDAR ist keine Sensorfusion, solange
  Asset, Bezugspunkt, Koordinatenrahmen, Messzeit und Kovarianz nicht identisch
  beziehungsweise transformiert sind.
- Bei Annäherung ist `distanceDiff` und damit `speed` negativ. Gleichzeitig fordert der
  Code für `isApproaching` ein positives `speed`; dieser Zustand kann daher nie wahr
  werden. Das Verfahren misst außerdem nur radiale Distanzänderung, nicht allgemeine
  Bewegung.
- Die Dwell-Helfer liefern immer `null` beziehungsweise tun beim Reset nichts, während
  die Alarmprüfung mit einem konstanten Distanzwert `0` arbeitet. Damit sind weder reale
  Messung noch Haltezeit angeschlossen.
- `activeFrom`/`activeTo` sind Listen, werden aber als einzelne `TimeOfDay`-Objekte über
  `.hour` und `.minute` verwendet. Geofence-Enter/-Exit-Flags werden nicht als
  Übergangsautomat ausgewertet.
- Der Geofence-Timer verfolgt die Handheld-GPS-Position, nicht automatisch die Position
  des gebundenen Assets. Initialzustand, Accuracy, Hysterese, stale Position,
  Zeitzone/DST und überlappende asynchrone Checks sind nicht belastbar behandelt.
- Ein löschbarer Isar-Datensatz mit pauschaler Einjahresbereinigung ist kein append-only
  Auditjournal. Policy-Revision, Evidence, Actor, vertrauenswürdige Zeit, Integrität,
  Zugriffsschutz, Exportverifikation und Zustell-Outbox fehlen. Daraus folgt insbesondere
  kein DGUV-Vorschrift-3-Nachweis.

Weitere Teile sind nur angekündigt: `DeviceDistanceService`, BLE-/UWB-/Wi-Fi-Messadapter,
Notification-Action-Routing, Reboot-Recovery, Datenbankmigrationen und echte
Scanparameter fehlen. Auch die angefragten Exact-Alarm- und Akku-Ausnahmeberechtigungen
stellen diese Komponenten nicht bereit. Der Ausschnitt ist damit weder vollständig noch
als zusammenhängender Produktionspfad testbar. Die vollständige Releaseprüfung des
aktuellen Kotlin-, Gateway- und Firmwarestands steht im
[Release-Readiness-Audit](RELEASE_READINESS_AUDIT.md).

### 1.4 Abgelehnte Produktionsaussagen

Der vorgelegte Code darf nicht als „permanent“ oder „production ready“ bezeichnet werden,
solange mindestens folgende Fälle nicht nachweisbar beherrscht sind:

- App-Prozess beendet, Android-Gerät neu gestartet oder Paket aktualisiert;
- App durch Nutzer oder MDM force-stopped;
- Gateway, Netzwerk oder CT45P nicht erreichbar;
- Messquelle liefert veraltete, unkalibrierte oder widersprüchliche Werte;
- doppelte, verspätete oder in anderer Reihenfolge eintreffende Ereignisse;
- Datenbankfehler zwischen Zustandsübergang und Benachrichtigung;
- verweigerte Benachrichtigungs-, Bluetooth- oder Foreground-Service-Berechtigung;
- Doze, Akkuoptimierung, Android-Hintergrundstartgrenzen und Herstellerpolitik;
- Uhrsprung, Neustart der monotonen Uhr oder falsche Gerätezeit;
- gleichzeitige Policy-Änderung und Alarmübergang.

---

## 2. Was „dauerhaft“ in diesem System bedeutet

„Dauerhaft“ darf nicht bedeuten, dass ein Mobilprozess unendlich lebt. Es bezeichnet eine
Systemeigenschaft mit klaren Betriebsgrenzen.

### 2.1 Garantierte Eigenschaften des Zielsystems

Bei gesundem Gateway und mindestens einer für die Policy geeigneten Messquelle gilt:

- jede akzeptierte Beobachtung wird höchstens einmal pro Policy-Revision wirksam
  verarbeitet oder anhand ihrer Sequenz als Duplikat verworfen;
- der Alarmzustand überlebt den Gateway-Prozessneustart;
- ein Zustandsübergang wird atomar zusammen mit einem Outbox-Eintrag gespeichert;
- Zustellung kann wiederholt werden, ohne denselben Zustandsübergang neu zu erzeugen;
- CT45P-Verbindungsverlust stoppt die Erkennung nicht;
- fehlende Messbarkeit wird als eigener Zustand sichtbar und nicht als erfundene Distanz;
- Policy-Änderungen sind revisioniert und optimistisch gegen verlorene Updates geschützt;
- Quittieren, Snooze und Deaktivieren sind authentisierte, idempotente Kommandos.

### 2.2 Nicht zugesicherte Eigenschaften

Ohne weitere Infrastruktur werden nicht zugesichert:

- Benachrichtigung am CT45P nach Force-stop oder bei ausgeschaltetem Gerät;
- Sekundengenauigkeit unter beliebigen Android-Energiespar- und Netzwerkzuständen;
- Distanzgenauigkeit ohne kalibrierte, beobachtbare Messgeometrie;
- sicherheitsgerichteter Betrieb nach einer funktionalen Sicherheitsnorm;
- Weiterbetrieb bei gleichzeitigem Ausfall von Gateway und lokaler Fallback-Messung.

Für kritische Betriebsfälle ist deshalb zusätzlich ein gatewaynaher Aktor vorzusehen,
z. B. Signalleuchte, Summer, Maschinenfreigabe oder Leitstandintegration. Eine
Android-Benachrichtigung allein ist kein fehlertoleranter Sicherheitskanal.

### 2.3 Betriebsmodi

| Modus | Erkennung | Persistenz | CT45P-Anzeige | Erwartete Aussage |
|---|---|---|---|---|
| Normal | Gateway | Gateway | live | autoritativ |
| CT45P offline | Gateway | Gateway | letzter Stand + offline | Erkennung läuft, Zustellung verzögert |
| Messquelle gestört | Gateway | Gateway | `DATA_LOSS` | Distanz unbekannt |
| Gateway gestört | keine autoritative Erkennung | letzter persistierter Stand | „Gateway nicht verfügbar“ | keine falsche Normalmeldung |
| Lokaler Fallback aktiv | CT45P, eingeschränkte BLE-Regel | lokal + spätere Synchronisierung | sichtbarer Foreground-Modus | degradiert, nicht gleichwertig |
| CT45P force-stopped | Gateway | Gateway | keine CT45P-Zustellung | lokale App kann sich nicht selbst garantieren |

---

## 3. Zielarchitektur

```text
Messquellen                    Linux-Gateway                    CT45P
BLE/UWB/USB/weitere  -->  Normalisierung + Kalibrierung  -->  Asset-Projektion
                               |                                  |
                               v                                  v
                         Qualitätsbewertung                 Alarm-/Historien-UI
                               |                                  |
                               v                                  |
                       Alarm-Zustandsautomat                      |
                               |                                  |
                      +--------+---------+                        |
                      |                  |                        |
                 SQL-Transaktion   Event-/Delivery-Outbox  -->  Benachrichtigung
                      ^                                           |
                      |                                           |
                      +------ signierte Command Intents <---------+
                             ACK / SNOOZE / POLICY UPDATE
```

### 3.1 Gateway Data Plane

Der Gateway-Dienst besitzt:

- Treiber und Adapter für reale Messquellen;
- Normalisierung in den
  [`sensor-envelope`](contracts/sensor-envelope.schema.json)-Vertrag;
- Kalibrierungs- und Qualitätsmodell;
- revisionierte
  [`asset-state`](contracts/asset-state.schema.json)-Projektionen;
- Policy-Repository und Alarmzustandsautomat;
- unveränderliches Ereignisjournal;
- transaktionale Zustell-Outbox;
- Deduplication Store für Beobachtungen und Kommandos;
- Recovery- und Reconciliation-Loop;
- Streaming-API und Snapshot-API für den CT45P.

### 3.2 CT45P Control Plane

Die native Android-App besitzt:

- Gerätebindung und Operatorsitzung;
- lokale Room-Projektion, aber keine zweite Autorität;
- Policy-Editor mit serverseitig bestätigter Revision;
- Alarmübersicht, Verlauf und Degradationsanzeigen;
- Erzeugung signierter
  [`command-intent`](contracts/command-intent.schema.json)-Nachrichten;
- ACK-/Snooze-Aktionen mit „pending/accepted/rejected“-Status;
- Notification Channels und Deep Links;
- optionalen, bewusst aktivierten nativen BLE-Fallback.

### 3.3 Trennung von Erkennung und Zustellung

Eine Alarmbedingung kann aktiv sein, obwohl noch keine Benachrichtigung zugestellt wurde.
Umgekehrt darf ein Zustell-Retry keinen neuen Alarm triggern. Daher existieren getrennte
IDs und Zustände:

- `event_id`: unveränderlicher fachlicher Übergang;
- `deduplication_key`: fachliche Idempotenz;
- `delivery_id`: ein Zustellauftrag für einen Kanal und Empfänger;
- `attempt`: einzelner Sendeversuch;
- `delivered_at`: technische Bestätigung, nicht Quittierung;
- `acknowledged_at`: Operatorhandlung, nicht physische Entwarnung.

---

## 4. Verträge

Diese Spezifikation ergänzt die vorhandenen Verträge um:

- [`alarm-policy.schema.json`](contracts/alarm-policy.schema.json): revisionierte,
  gatewayseitig auszuwertende Regel;
- [`alarm-runtime.schema.json`](contracts/alarm-runtime.schema.json): jüngste
  revisionierte Alarmprojektion mit expliziter Gateway- oder lokaler
  Fallback-Autorität;
- [`alarm-event.schema.json`](contracts/alarm-event.schema.json): unveränderlicher
  Zustandsübergang mit Evidence-Snapshot, Policy-Revision und Integritätsstatus.

### 4.1 AlarmPolicy

Wesentliche Felder:

| Feld | Bedeutung |
|---|---|
| `policy_id`, `revision` | stabile Identität und monoton steigende Konfigurationsversion |
| `asset_id` | fachliche Asset-ID, keine wechselnde BLE-Adresse |
| `metric` | Distanzbezug, Zone, Geofence oder Verbindungsverlust |
| `threshold_m`, `trigger_direction` | physische Regel; bei Nicht-Distanzmetriken nicht erfinden |
| `decision_mode` | mögliche oder bestätigte Schwellwertverletzung unter Unsicherheit |
| `minimum_confidence`, `maximum_age_ms` | Zulässigkeit einer Schätzung |
| `dwell_ms`, `clear_dwell_ms` | Haltezeiten für Auslösung und Entwarnung |
| `data_loss_dwell_ms`, `recovery_dwell_ms` | Haltezeiten für Messausfall und stabile Wiederkehr |
| `hysteresis_m` | getrennte Trigger-/Clear-Grenze gegen Flattern |
| `cooldown_ms` | begrenzt Wiederholungszustellung, nie die Erkennung |
| `data_loss_behavior` | explizite Reaktion auf fehlende Beobachtbarkeit |
| `delivery_profile_id` | Verweis auf Kanäle; keine Detektionssemantik |

Ein Policy-Update wird mit `expected_revision` über einen Command Intent gesendet. Der
Gateway-Dienst akzeptiert es nur, wenn Revision, Operatorrecht, Gültigkeitsfenster,
Signatur und Idempotenzschlüssel passen. Die Bestätigung enthält die neue Revision und
den autoritativen Policy-Snapshot.

### 4.2 AlarmRuntime

Die Runtime-Projektion ist der jüngste serverseitig bestätigte Zustand für UI,
Snapshot und Recovery. Sie enthält:

- `authority` und `authority_id`, damit `GATEWAY_AUTHORITATIVE` und
  `CT45P_LOCAL_DEGRADED` nie zusammenfallen;
- Boot-, State-, Policy- und Asset-Revision;
- Bedingungs- und Aufmerksamkeitszustand samt Beginn;
- explizite Trigger-, Clear-, Data-loss-, Recovery-, Snooze- und nächste
  Evaluationsfrist;
- letzte Evidence, Event-ID und aktive Korrelation.

Sie ist veränderlich und kann aus Policy-Historie plus Events rekonstruiert werden. Ein
CT45P überschreibt eine gatewayautoritative Projektion nicht mit seiner lokalen Runtime;
die UI zeigt beide bei Konflikt getrennt.

### 4.3 AlarmEvent

Ein Event enthält mindestens:

- explizite Autorität sowie Boot-, Policy-, Asset- und Korrelationsidentität;
- vorherigen und neuen Zustand;
- UTC-Zeit für Austausch sowie monotone Zeit innerhalb eines Boot-Zyklus;
- die bei der Entscheidung verwendete Evidence, nicht nur den letzten UI-Wert;
- Severity, maschinenlesbaren Reason Code und Policy-Snapshot-Hash;
- bei `POLICY_UPDATED` zusätzlich vorherige Policy-Revision und vorherigen Snapshot-Hash;
- Operatoridentität bei ACK, Snooze oder Policy-Aktion;
- Integritäts-/Verifikationsstatus;
- einen stabilen Deduplication Key.

Events werden nicht nachträglich überschrieben. Korrekturen sind neue Ereignisse. Lokal
entstandene Fallback-Events bleiben ausdrücklich `CT45P_LOCAL_DEGRADED`; ihre spätere
Übertragung macht sie nicht rückwirkend gatewayautoritativ.

### 4.4 Orthogonale Zustandsdimensionen

Eine einzelne `isActive`-Boolean reicht nicht. Intern sind mindestens zwei Dimensionen
zu führen:

**Bedingung**

- `NORMAL`
- `PENDING_TRIGGER`
- `ACTIVE`
- `PENDING_CLEAR`
- `DATA_LOSS`
- `DISABLED`
- `ERROR`

**Aufmerksamkeit/Zustellung**

- `NONE`
- `UNACKNOWLEDGED`
- `ACKNOWLEDGED`
- `SNOOZED`

Damit bleibt beispielsweise `condition=ACTIVE`, wenn ein Operator quittiert. Snooze
setzt nur die Aufmerksamkeit bis `snoozed_until` aus. Ein neuer, höher priorisierter
Alarm oder ein Policy-gesteuerter Eskalationsschritt darf Snooze übersteuern.

---

## 5. Messwert- und Qualitätsmodell

### 5.1 Eine Zahl ist keine ausreichende Evidence

Die Alarmengine wertet niemals nur `distance < threshold` oder
`distance > threshold` aus. Eine verwertbare Distanzschätzung benötigt mindestens:

- Status (`VALID`, `LOW_CONFIDENCE`, `STALE`, `UNOBSERVABLE`, ...);
- Wert in Metern;
- Beobachtungszeit und beim Entscheiden berechnetes Alter;
- Confidence;
- Standardabweichung oder Konfidenzintervall;
- Messmethode und Quellen;
- Kalibrierungsreferenz;
- Qualitätsflags wie NLOS, unkalibriert oder unsichere Uhr;
- Quellsequenz beziehungsweise Measurement-ID.

BLE-RSSI ist dabei eine grobe, umgebungsabhängige Näherung. Es darf ohne standort- und
hardwarebezogene Kalibrierung nicht als präzise Meterwahrheit dargestellt werden.

### 5.2 Zulässigkeitsprüfung

Vor jeder Schwellwertentscheidung prüft die Engine in dieser Reihenfolge:

1. Gehört die Evidence zum erwarteten Asset und Bezugspunkt?
2. Ist ihre Quellsequenz neuer als die zuletzt verarbeitete Sequenz?
3. Ist die Signatur beziehungsweise Transportauthentizität ausreichend?
4. Ist der Status für die Policy verwertbar?
5. Ist `age_ms <= maximum_age_ms`?
6. Ist `confidence >= minimum_confidence`?
7. Ist eine passende Kalibrierung aktiv und nicht abgelaufen?
8. Ist das benötigte Unsicherheitsmaß vorhanden?

Scheitert die Zulässigkeitsprüfung, wird nicht mit einem alten Wert weiterentschieden.
Stattdessen läuft die Datenverlustlogik.

### 5.3 Unsicherheitsbewusste Schwellwerte

Für eine obere Distanzgrenze `T` sind zwei ausdrücklich wählbare Modi sinnvoll:

- `POSSIBLE_BREACH`: Triggerprädikat `upper_95_m > T`. Frühe, empfindliche Warnung;
- `CONFIRMED_BREACH`: Triggerprädikat `lower_95_m > T`. Weniger Fehlalarme, aber später.

Für die Entwarnung einer oberen Grenze wird konservativ geprüft:

```text
upper_95_m < T - hysteresis_m
```

Für eine untere Grenze werden die Ungleichungen gespiegelt. Fehlt trotz gültiger Policy
ein gefordertes Konfidenzintervall, ist die Schätzung für diese Entscheidung nicht
verwertbar; die Engine darf nicht still auf einen Punktwert zurückfallen.

### 5.4 Dwell und Hysterese

Ein Triggerprädikat muss über `dwell_ms` erfüllt bleiben. Kurze Gegenbeobachtungen setzen
nur dann zurück, wenn sie selbst gültig und ausreichend frisch sind. Entwarnung benötigt
`clear_dwell_ms` und die Clear-Grenze. Dadurch entstehen keine Notification-Stürme an
der Schwelle.

Haltezeiten werden mit einer monotonen Uhr ausgewertet. UTC dient Anzeige und Austausch.
Nach Gateway-Neustart wird ein neuer `boot_id` angelegt; Recovery verwendet persistierte
UTC-Anker konservativ und löst fällige Zustände nicht rückwirkend mehrfach aus.

### 5.5 Datenverlust ist ein eigener Alarmgrund

Folgende Fälle können `DATA_LOSS` auslösen:

- noch nie eine geeignete Beobachtung erhalten;
- letzte Beobachtung älter als `maximum_age_ms`;
- Confidence dauerhaft unter Minimum;
- Quelle oder Gateway-Adapter offline;
- Kalibrierung ungültig;
- widersprüchliche Quellen oder unzureichende Geometrie.

`data_loss_behavior` bestimmt:

- `SEPARATE_ALARM`: eigener sichtbarer technischer Alarm;
- `FAIL_CLOSED`: konservativer kritischer Zustand für Prozesse, die Unkenntnis nicht
  tolerieren;
- `WARN_ONLY`: Warnung ohne Gleichsetzung mit der Distanzverletzung.

Keine Variante darf „keine Daten“ als normale, sichere Distanz darstellen.

---

## 6. Deterministischer Zustandsautomat

### 6.1 Fachliche Übergänge

| Von | Bedingung | Nach | Ereignis |
|---|---|---|---|
| `DISABLED` | Policy aktiviert | `NORMAL` oder `DATA_LOSS` | `POLICY_ENABLED` + Reconciliation |
| `NORMAL` | gültiges Triggerprädikat beginnt | `PENDING_TRIGGER` | `PENDING_STARTED` |
| `PENDING_TRIGGER` | Prädikat vor Dwell nicht mehr erfüllt | `NORMAL` | `PENDING_CANCELED` |
| `PENDING_TRIGGER` | Dwell erfüllt | `ACTIVE` | `TRIGGERED` |
| `ACTIVE` | Clear-Prädikat beginnt | `PENDING_CLEAR` | `CLEAR_PENDING_STARTED` |
| `PENDING_CLEAR` | Clear-Prädikat bricht ab | `ACTIVE` | `CLEAR_PENDING_CANCELED` |
| `PENDING_CLEAR` | Clear-Dwell erfüllt | `NORMAL` | `CLEARED` |
| jeder auswertbare Zustand | Daten unzulässig bis Data-loss-Dwell | `DATA_LOSS` | `DATA_LOSS_STARTED` |
| `DATA_LOSS` | gültige Evidence stabil verfügbar | Re-Evaluation | `DATA_LOSS_CLEARED` plus ggf. `TRIGGERED` |
| jeder Zustand | Policy deaktiviert | `DISABLED` | `POLICY_DISABLED` |
| jeder Zustand | nicht behebbarer Evaluationsfehler | `ERROR` | `EVALUATION_ERROR` |
| `ERROR` | Evaluator wieder konsistent | Re-Evaluation | `EVALUATION_RECOVERED` |

Die Re-Evaluation nach `DATA_LOSS` darf nicht blind `NORMAL` annehmen: Die erste wieder
gültige Evidence kann bereits eine Distanzverletzung belegen.

### 6.2 Operatorübergänge

- **ACK:** setzt Aufmerksamkeit von `UNACKNOWLEDGED` auf `ACKNOWLEDGED`; Bedingung bleibt.
- **SNOOZE:** setzt `SNOOZED` mit explizitem Endzeitpunkt; Bedingung und Evidence laufen weiter.
- **Snooze-Ablauf:** stellt abhängig vom noch aktiven Zustand die Aufmerksamkeit wieder her.
- **Disable:** ist ein Policy-Kommando mit Berechtigungsprüfung, Grund und Audit-Event; kein
  schneller Ersatz für ACK.
- **Clear:** wird ausschließlich durch gültige Evidence und Policylogik erzeugt, nicht durch
  eine UI-Schaltfläche.

### 6.3 Ereignisgetriebener Evaluator

```text
onObservation(observation):
    validate and normalize observation
    persist raw/normalized reference according to retention policy
    for each enabled policy affected by observation:
        begin transaction
        lock policy runtime row
        reject duplicate or out-of-order source sequence
        derive fresh quality-bearing evidence
        reduce(previous_runtime, policy_revision, evidence, monotonic_now)
        if runtime changed:
            append immutable alarm_event
            update runtime and asset projection revision
            if event type is delivery-eligible:
                append one delivery_outbox row per eligible channel
        persist last processed source sequence
        commit
```

Zusätzlich prüft ein kleiner Gateway-Scheduler nur zeitabhängige Fristen: Dwell,
Freshness, Snooze-Ablauf und Retry-Leases. Dieser Scheduler ist nicht von Android abhängig
und arbeitet nach Neustart alle überfälligen Datensätze idempotent ab.

### 6.4 Policy-Revision während eines laufenden Alarms

Ein Update ersetzt nie still die Semantik eines bereits gespeicherten Events:

1. neue Policy-Revision und alter Runtime-Snapshot werden in derselben serialisierten
   Verarbeitung geladen;
2. laufende Trigger-/Clear-/Data-loss-Fristen werden standardmäßig verworfen und unter
   der neuen Revision neu begonnen, sofern die Policy nicht ausdrücklich eine kompatible
   Fortsetzung erlaubt;
3. die letzte Evidence wird erneut auf Alter und Qualität geprüft;
4. `POLICY_UPDATED` dokumentiert Actor, alte/neue Revision und Snapshot-Hash;
5. ein aktiver physischer Zustand wird nicht allein durch die Konfigurationsmutation als
   „gemessen entwarnt“ dargestellt;
6. nach Re-Evaluation entsteht gegebenenfalls ein separates `CLEARED`, `TRIGGERED` oder
   `DATA_LOSS_STARTED` mit neuer State Revision.

Eine Deaktivierung ist ein explizites privilegiertes Ereignis. Eine Aktivierung beginnt
mit der Prüfung frischer Evidence und darf ohne verwertbare Messung nicht `NORMAL`
annehmen. Vergangene Zeiträume werden nachträglich weder künstlich getriggert noch
überschrieben.

---

## 7. Persistenz, Outbox und Wiederanlauf

### 7.1 Minimales Gateway-Datenmodell

| Tabelle | Zweck |
|---|---|
| `alarm_policy` | aktueller Snapshot pro `policy_id`, Revision und Aktivstatus |
| `alarm_policy_history` | unveränderliche Policy-Versionen |
| `alarm_runtime` | aktueller Bedingungs-/Aufmerksamkeitszustand und Fristanker |
| `alarm_event` | append-only Ereignisjournal |
| `observation_cursor` | letzte akzeptierte Sequenz je Quelle/Asset |
| `command_dedup` | Ergebnis je Command-Idempotenzschlüssel |
| `delivery_outbox` | transaktional erzeugte Zustellaufträge |
| `delivery_attempt` | technische Versuche und Fehlerklassifikation |

SQLite mit WAL ist für einen einzelnen Edge-Prozess ein praktikabler Start. Mehrere
aktive Gateway-Instanzen benötigen eine Datenbank beziehungsweise einen Konsens- oder
Lease-Mechanismus, der konkurrierende Alarmautoritäten verhindert.

### 7.2 Atomare Schreibregel

In derselben Datenbanktransaktion werden gespeichert:

1. neue Runtime-Revision,
2. unveränderliches AlarmEvent,
3. aktualisierte Asset-Projektion,
4. Outbox-Aufträge,
5. verarbeitete Quellsequenz.

„Datenbank speichern, danach direkt Notification senden“ ist nicht crash-sicher: Ein
Absturz zwischen beiden Schritten erzeugt entweder verlorene Zustellung oder Duplikate.
Der Outbox-Worker beansprucht Einträge per Lease, sendet idempotent und protokolliert das
Ergebnis getrennt.

### 7.3 Recovery

Beim Start des Gateways:

1. Datenbankmigration mit Vorwärts-/Rollback-Plan ausführen;
2. unvollständige Leases nach Ablauf zurücksetzen;
3. Runtime und letzte Policy-Revision laden;
4. überfällige Freshness-, Dwell- und Snooze-Fristen reconciliieren;
5. Outbox weiter abarbeiten;
6. vollständigen Snapshot mit neuer Stream-Epoch bereitstellen;
7. erst danach Readiness melden.

Der CT45P verwirft bei neuer Stream-Epoch seine Annahme einer lückenlosen Sequenz, lädt
einen Snapshot und setzt erst anschließend Delta-Verarbeitung fort.

### 7.4 Aufbewahrung und Audit

- Runtime-Datensätze sind veränderlich und nicht das Auditlog.
- Events sind append-only und erhalten serverseitige Reihenfolge.
- Aufbewahrungsfristen unterscheiden Rohmessungen, Evidence-Snapshots und Alarmereignisse.
- PII und Standortdaten werden minimiert und rollenbasiert geschützt.
- Export enthält Policy-Revision, Zeitzone, Gateway-/Boot-ID und Integritätsstatus.
- Ein einfacher Boolean `verified=true` ist kein kryptografischer Beweis; Signatur- oder
  Hashketten sind nur dann zu behaupten, wenn sie tatsächlich implementiert und geprüft sind.

---

## 8. Android-Implementierung auf dem CT45P

### 8.1 Bestehenden Stack verwenden

Das Repository nutzt Kotlin, XML/ViewBinding, Room, Coroutines und AndroidX-Komponenten.
Die Alarmfunktion wird deshalb nicht als parallele Flutter-App hinzugefügt. Empfohlene
Module beziehungsweise Pakete:

```text
android-app/app/src/main/java/com/example/agent/
  alarm/api/            Gateway DTOs und Stream-Protokoll
  alarm/domain/         Projektionen und UI-Mapping, keine zweite Engine
  alarm/data/           Room-Cache, Repository, Command-Outbox
  alarm/notification/   Channels, Renderer, Deep Links, Actions
  alarm/ui/             Liste, Detail, Policy-Editor, Verlauf
  alarm/fallback/       optionaler nativer BLE-Fallback
```

Die aktuelle `MainActivity` darf nicht dauerhaft weitere Sensor- und Alarmverantwortung
aufnehmen. Sie wird schrittweise auf Navigation und Composition Root reduziert.

### 8.2 Room-Cache

Lokal gespeichert werden:

- letzter bestätigter Asset-/Alarm-Snapshot mit Gateway-Revision;
- Policy-Snapshots und Pending-Command-Status;
- begrenzter Eventverlauf;
- CT45P-lokale Notification-Zuordnung;
- Fallback-Events mit eigenem Ursprung und noch nicht synchronisiertem Status.

Der Cache zeigt `lastConfirmedAt` und Verbindungszustand. Ein alter Cache darf nicht wie
ein Live-Zustand aussehen. Nullable Felder brauchen in Kotlin/SQL explizite
„setzen/löschen/unverändert“-Semantik; ein gewöhnliches `copy`-Default darf Löschen nicht
unmöglich machen.

### 8.3 Stream und Synchronisierung

- App im Vordergrund: authentisierter WebSocket/SSE-ähnlicher Delta-Stream plus Snapshot.
- App im Hintergrund: server-/gatewayseitige Push-Integration, sofern im Deployment
  vorhanden; ansonsten keine erfundene Echtzeitgarantie.
- Wiederverbindung: Exponential Backoff mit Jitter und Obergrenze.
- Lücke in Revisionen: Snapshot statt blindem Anwenden weiterer Deltas.
- Pending Command: lokal sichtbar, aber nicht als akzeptierte Policy darstellen.
- WorkManager: nur für aufschiebbare Synchronisierung, Telemetrie-Upload und Reparatur;
  nicht für Sekunden-Abstandsmessung.

### 8.4 Notifications

Mindestens folgende Channels sind sinnvoll:

- `alarm_critical`
- `alarm_warning`
- `alarm_data_loss`
- `alarm_service_status` für sichtbaren Fallback-Foreground-Betrieb

Channel-Bedeutung und Importance werden versioniert, weil Nutzer Channel-Einstellungen
nach Erstellung kontrollieren. Die App prüft Berechtigungs- und Channel-Status und zeigt
eine klare Degradation, statt erfolgreichen Alarmton anzunehmen.

Notification-Aktionen:

- öffnen Asset-/Alarmdetail;
- ACK als authentisierter Command Intent;
- Snooze nur mit erlaubten Zeitwerten;
- kein „Clear“-Button;
- Disable nur hinter expliziter Bestätigung, Berechtigung und Begründung.

Ton, Vibration, DND-Bypass, Full-Screen Intents und Lockscreen-Inhalte werden nicht durch
Codekommentare garantiert. Sie müssen zu Android-Version, Gerätemanagement, Nutzerwahl,
Berechtigung und Datenschutz passen.

### 8.5 Reboot, Update, Prozessende und Force-stop

| Fall | Verhalten |
|---|---|
| App-Prozessende | Gateway-Erkennung unverändert; lokale UI beim nächsten Start resynchronisieren |
| CT45P-Reboot | Gateway unverändert; App/MDM stellt erlaubte Konnektivität wieder her |
| Paketupdate | DB-Migration, Snapshot-Reconciliation, keine Policy-Neuanlage |
| Force-stop | Android kann App-Empfang und Jobs blockieren; Gateway bleibt Autorität |
| Netzwerkverlust | lokaler Stand als stale; Gateway entscheidet weiter |
| Gateway-Neustart | persistierte Runtime und Outbox reconciliieren |

Ein `BOOT_COMPLETED`-Receiver allein erzeugt keine Permanenz und umgeht Force-stop nicht.

---

## 9. Optionaler nativer CT45P-BLE-Fallback

### 9.1 Zulässiger Zweck

Der Fallback darf nur eine enge, lokal messbare Regel bedienen, zum Beispiel „bekanntes
BLE-Tag seit definierter Zeit nicht gesehen“ oder eine kalibrierte grobe RSSI-Nähe. Er
ist kein Ersatz für gatewayseitige Sensorfusion, Zonenlogik oder UWB, wenn der CT45P die
entsprechende Hardware und Geometrie nicht besitzt.

### 9.2 Aktivierungsvoraussetzungen

- Feature Flag und administrativ freigegebene Policy;
- explizite Nutzer-/Operatoraktion aus sichtbarer Activity, soweit Android dies verlangt;
- deklarierte und gewährte Bluetooth-/gegebenenfalls Standortberechtigungen;
- dokumentierter Foreground-Service-Typ passend zur realen Nutzung und Android-Version;
- permanente Statusnotification während kontinuierlicher Operation;
- MDM-/Akkuoptimierungsprofil für verwaltete Geräte;
- Capability Check des konkreten CT45P-Builds;
- klare UI-Kennzeichnung `LOCAL_DEGRADED`, niemals „Gateway autoritativ“.

### 9.3 Technische Form

Für Hintergrundentdeckung ist ein gefilterter BLE-Scan mit `PendingIntent` geeigneter als
ein Dart-Timer, weil Scan-Treffer an die App zugestellt werden können, ohne dass ihr
Prozess permanent lebt. Das ist dennoch keine feste Sekunden-SLA. Für eine fortlaufende
Verbindung oder enges Scannen kann ein zulässiger nativer Foreground Service notwendig
sein; Android-Hintergrundstart- und Service-Type-Regeln bleiben einzuhalten.

Fallback-Regeln:

- ausschließlich stabile gebundene Asset-ID, nicht rohe wechselnde MAC-Adresse;
- Scan-Filter so eng wie möglich;
- RSSI glätten und gerätespezifisch kalibrieren;
- keine Meteranzeige ohne validiertes Modell;
- eigenes Freshness-/Dwell-/Hysteresemodell;
- lokale Ereignisse mit `authority=CT45P_LOCAL_DEGRADED` und eigener Sequenz;
- bei Wiederverbindung an Gateway hochladen, aber keine Gateway-Historie überschreiben;
- Konflikte als beide Perspektiven darstellen, nicht still zusammenführen.

### 9.4 Grenzen

Der lokale Fallback ist nicht zuverlässig verfügbar, wenn:

- Gerät ausgeschaltet, Bluetooth deaktiviert oder Berechtigung entzogen ist;
- App force-stopped wurde;
- OS/MDM den Service nicht starten lässt;
- Funksicht durch Körper, Metall, Interferenz oder Tag-Batterie beeinträchtigt ist;
- wechselnde Identifikatoren nicht über ein sicheres Enrollment aufgelöst werden können.

Deshalb ist sein Health-Status selbst Teil der UI und Telemetrie.

---

## 10. Energie- und Laststrategie

### 10.1 Gateway

- Erkennung wird durch neue Evidence und Fristen getrieben, nicht durch blindes
  Zwei-Sekunden-Polling aller Assets.
- Policy-zu-Asset-Index verhindert vollständige Tabellenscans.
- Fällige Timer liegen in einer Priority Queue, persistente Fristen in der Datenbank.
- Nichtkritische Historienverdichtung läuft getrennt von der Alarmtransaktion.
- Gateway-Health, Datenträger, Temperatur und Spannungsversorgung werden überwacht.

### 10.2 CT45P

Bei niedrigem Akku dürfen reduziert werden:

- Karten-Animationsrate;
- nichtkritische Telemetrie;
- Historien-Prefetch;
- dekorative Hintergrundaktualisierung.

Nicht automatisch reduziert werden:

- Anzeige bereits aktiver kritischer Alarme;
- ACK-/Snooze-Kommandos;
- Gateway-Erkennung;
- erforderliche Statusnotification eines bewusst gestarteten Fallback-Service.

Ein niedriger Akku ist selbst eine Degradation und darf nicht still das Abschalten der
Alarmfunktion bewirken.

---

## 11. Sicherheit und Berechtigungen

### 11.1 Identität

- Asset-ID ist eine provisionierte fachliche Identität, keine MAC-Adresse und kein
  Anzeigename.
- CT45P-Operator und CT45P-Gerät werden getrennt authentisiert.
- Private Schlüssel liegen soweit verfügbar nicht exportierbar im Android Keystore;
  Hardware-/StrongBox-Unterstützung wird zur Laufzeit geprüft, nicht vorausgesetzt.
- Gateway prüft Zertifikatsstatus, Rollen und Command-Signatur.

### 11.2 Command-Schutz

Jeder mutierende Befehl enthält:

- Command-ID und Idempotenzschlüssel;
- Actor-, Device- und Session-Kontext;
- Zielressource;
- `expected_revision`;
- Erstellungs- und Ablaufzeit;
- Nonce;
- kanonisch signierten Payload.

Der Gateway-Dienst speichert Erfolg oder Ablehnung pro Idempotenzschlüssel. Ein Retry
liefert dasselbe Ergebnis und führt die Aktion nicht erneut aus.

### 11.3 Rollen

Beispiel:

| Aktion | Operator | Supervisor | Administrator |
|---|---:|---:|---:|
| Alarm ansehen | ja | ja | ja |
| ACK | ja | ja | ja |
| begrenzter Snooze | policyabhängig | ja | ja |
| Schwelle ändern | nein | ja | ja |
| kritische Policy deaktivieren | nein | policyabhängig | ja |
| Enrollment/Trust ändern | nein | nein | ja |

Die tatsächlichen Rechte werden serverseitig geprüft. UI-Ausblendung allein ist keine
Autorisierung.

---

## 12. API- und Sequenzentwurf

### 12.1 Snapshot

```http
GET /v1/alarm-policies?asset_id={assetId}
GET /v1/alarms/snapshot?after_revision={revision}
GET /v1/alarm-events?asset_id={assetId}&cursor={cursor}
```

Antworten enthalten Gateway-ID, Stream-Epoch, höchste Revision und Serverzeit. Pagination
verwendet undurchsichtige Cursor statt Client-Uhrzeit.

### 12.2 Mutationen

```http
POST /v1/commands
Content-Type: application/json

{ command-intent gemäß Schema }
```

Beispielaktionen:

- `alarm.policy.create`
- `alarm.policy.update`
- `alarm.policy.enable`
- `alarm.policy.disable`
- `alarm.acknowledge`
- `alarm.snooze`

HTTP-Annahme bedeutet nur Transportannahme. Das fachliche Command-Ergebnis enthält
`ACCEPTED`, `REJECTED`, `CONFLICT` oder `EXPIRED` sowie die autoritative Revision.

### 12.3 Zustellfluss

```text
Gateway reducer
  -> DB transaction: runtime + event + outbox
  -> outbox dispatcher
  -> deployment delivery adapter
  -> CT45P notification receiver
  -> local Room insert/dedup
  -> Android notification
  -> operator ACK
  -> signed command intent
  -> gateway command transaction
  -> ACKNOWLEDGED event + stream delta
```

Wiederholte Zustellung derselben `event_id` aktualisiert höchstens die lokale
Zustellinformation; sie erzeugt keinen zweiten fachlichen Alarm.

---

## 13. Repository-grounded Implementierungsplan

### Phase A – Verträge und Gateway-Grundlage

1. Die drei neuen Alarm-JSON-Schemas mit Draft-2020-12-Validator und Golden Samples prüfen.
2. Gateway-Datenbank um Policy, Runtime, Event, Cursor und Outbox migrieren.
3. Reinen Alarm-Reducer ohne I/O implementieren.
4. Freshness-Scheduler und transaktionalen Repository-Layer ergänzen.
5. Snapshot-, Event- und Command-Endpunkte bereitstellen.

**Exit:** Ein Gateway-Neustart verliert weder aktiven Zustand noch ausstehende Zustellung;
doppelte Evidence und Commands bleiben ohne doppelte Wirkung.

### Phase B – Native CT45P Control Plane

1. Alarm-DTOs, Room-Tabellen und Repository ergänzen.
2. Vollsnapshot plus revisionsbasierte Delta-Synchronisierung implementieren.
3. Alarm-Liste, Detail, Verlauf und Policy-Editor integrieren.
4. Notification Channels, Deep Links, ACK und Snooze hinzufügen.
5. Permission-/Channel-/Connectivity-Degradation sichtbar machen.

**Exit:** Die UI unterscheidet Live, stale, data loss, pending command und aktive
Gateway-Autorität; kein deaktivierter Alarm wird durch einen UI-State-Bug als aktiviert
gespeichert oder umgekehrt.

### Phase C – Delivery-Härtung

1. Deployment-spezifischen Zustelladapter wählen und dessen Offlinegrenzen dokumentieren.
2. Retry, Backoff, Dead-letter-Status und Metriken ergänzen.
3. MDM-, DND-, Notification- und Akkuprofile auf realem CT45P testen.
4. Gatewaynahen kritischen Aktor integrieren, falls die Risikobewertung ihn verlangt.

**Exit:** Gemessene End-to-End-Latenzen und Ausfallmodi sind dokumentiert; keine
unbelegte „immer“-Aussage bleibt.

### Phase D – Optionaler lokaler Fallback

1. Nur nach Bedarf und Hardwaretest implementieren.
2. Native BLE APIs und passenden Foreground-Lifecycle verwenden.
3. Eigenes lokales Eventjournal und Konfliktanzeige ergänzen.
4. Funk-, Reboot-, Force-stop-, Berechtigungs- und Akkutests durchführen.

**Exit:** Fallback wird im Produkt und in Telemetrie eindeutig als degradiert erkannt und
kann die gatewayseitige Historie nicht verfälschen.

---

## 14. Teststrategie

### 14.1 Reiner Reducer

Tabellengetriebene Tests prüfen:

- Trigger oberhalb und unterhalb einer Schwelle;
- `POSSIBLE_BREACH` gegen `CONFIRMED_BREACH`;
- Dwell-Abbruch kurz vor Frist;
- Hysterese und Clear-Dwell;
- Low Confidence, stale, NLOS und fehlende Unsicherheit;
- Datenverlust und Wiederkehr direkt in aktive Verletzung;
- ACK bei weiterhin aktiver Bedingung;
- Snooze-Ablauf;
- Policy-Disable und Re-Enable;
- Policy-Revision während `PENDING_TRIGGER`;
- monotone Zeit und neuer Boot-Zyklus.

Tests verwenden eine injizierte Clock und reine Value Objects. Sie dürfen weder echte
Notifications noch Audio, Vibration oder Plugin-Singletons starten.

### 14.2 Persistenz und Idempotenz

- Crash vor/nach jedem Transaktionsschritt;
- Outbox-Send erfolgreich, Bestätigung vor Crash nicht gespeichert;
- doppelter Event-/Command-Key;
- verspätete und out-of-order Observation;
- konkurrierende ACK-/Snooze-Kommandos;
- Migration mit aktivem Alarm;
- Wiederanlauf mit abgelaufener Dwell-/Snooze-Frist;
- nullable Feld setzen, löschen und unverändert lassen.

Nach Mutationen werden Datenbankobjekte neu geladen. Tests dürfen nicht gegen stale
In-memory-Objekte behaupten, Persistenz geprüft zu haben.

### 14.3 Android

- Notification Permission erlaubt/verweigert;
- Channel deaktiviert oder Importance geändert;
- Deep Link aus beendetem Prozess;
- ACK-Retry offline/online;
- Room-Migration und Paketupdate;
- Reboot, Doze und Akkuoptimierung auf realem CT45P;
- Bluetooth/Standortberechtigung nur für den optionalen Fallback;
- Foreground-Service-Start aus allen erlaubten und gesperrten Zuständen;
- MDM/Kiosk-Konfiguration.

Robolectric-/Instrumentierungstests kapseln Android-Plugins hinter Interfaces. Eine
Unit-Test-Datenbank und Fake-Notifier ersetzen reale Nebenwirkungen.

### 14.4 Fault Injection und Systemtests

- Gateway-Prozess `SIGKILL` während Triggertransaktion;
- Netzwerkpartition CT45P↔Gateway;
- Messquelle friert mit altem Zeitstempel ein;
- Uhr springt vor/zurück;
- Tag wechselt Identifier;
- Datenbank voll oder schreibgeschützt;
- Tausende gleichzeitige Policy-Fristen;
- Zustelladapter liefert Timeout nach erfolgreicher Annahme;
- Gateway und CT45P widersprechen im Fallback-Modus.

### 14.5 Messbare Abnahmekriterien

Vor Produktion werden für konkrete Hardware und Deployment festgelegt und gemessen:

- maximale akzeptierte Observation-to-decision-Latenz;
- Decision-to-durable-event-Latenz;
- Event-to-CT45P-Zustellperzentile im Normalbetrieb;
- Data-loss-Erkennungszeit;
- Recovery-Zeit nach Gateway-Neustart;
- Duplikatquote fachlicher Events: Ziel null;
- verlorene Event-/Outbox-Einträge bei Crash: Ziel null;
- Akkuverbrauch des optionalen Fallbacks pro Schicht;
- Fehlalarm- und Miss-Rate je Messmethode und Umgebung.

Grenzwerte werden aus Risikobewertung und Feldmessung abgeleitet, nicht aus einem
Beispielintervall von zwei Sekunden.

---

## 15. Observability und Betrieb

### 15.1 Metriken

- `alarm_evaluations_total{result,metric}`
- `alarm_transitions_total{event_type,severity}`
- `alarm_evidence_age_ms`
- `alarm_active{severity}`
- `alarm_data_loss_active`
- `alarm_outbox_pending`
- `alarm_delivery_latency_ms{channel}`
- `alarm_delivery_failures_total{class}`
- `alarm_command_conflicts_total{action}`
- `alarm_recovery_duration_ms`

Asset-ID und Operator-ID gehören nicht als unbeschränkte Metric Labels in ein
zeitreihenbasiertes Monitoring. Detailkorrelation erfolgt über strukturierte Logs und
zugriffsgeschützte Events.

### 15.2 Health und Alerts

Der Betrieb alarmiert separat auf:

- keine frischen Beobachtungen trotz erwarteter Quelle;
- Outbox-Rückstau;
- Datenbank-/Datenträgerfehler;
- unbekannte Policy-Version;
- hohe Clock-Uncertainty;
- Gateway-Split-Brain;
- CT45P-Zustellkanal global gestört;
- ungewöhnlich viele Policy-Deaktivierungen.

### 15.3 Runbooks

Runbooks müssen mindestens beantworten:

- Wie wird ein `DATA_LOSS` untersucht?
- Wie wird ein festhängender Outbox-Eintrag sicher erneut zugestellt?
- Wie wird ein Gateway ersetzt, ohne zwei Autoritäten zu erzeugen?
- Wie werden Policy-Revision und Signatur geprüft?
- Wie wird ein verlorenes CT45P gesperrt?
- Welche Aussage darf der Operator bei stale/offline sehen?

---

## 16. Aktueller Repository-Stand und konkrete Lücken

Zum Zeitpunkt dieser Spezifikation gilt:

- Es existiert keine produktive Alarmengine im Gateway.
- Die Android-App ist nativ; Flutter-/iOS-Code wäre ein unverbundener zweiter Stack.
- Die aktuelle Android-Komposition ist stark in `MainActivity` gebündelt.
- Die bestehende Registry ist in-memory und noch keine revisionierte Asset-Autorität.
- Die bisherige BLE-Kartendarstellung kann aus einer MAC-Ableitung keine physische
  Richtung oder belastbare Distanz erzeugen.
- Room ist vorhanden, aber das hier definierte Alarm-/Outbox-Modell noch nicht.
- Android-Manifest und Build enthalten noch nicht alle Komponenten für Notifications,
  Command-Sync oder einen optionalen Foreground-Fallback.
- Der Edge-Agent enthält noch nicht die hier spezifizierten Policy-, Runtime-, Event- und
  Outbox-Tabellen.

Die neuen Schemas und dieses Dokument sind daher der Implementierungsvertrag, nicht der
Nachweis, dass die Funktion bereits läuft.

---

## 17. Offizielle Plattformreferenzen

- [WorkManager: wiederkehrende Arbeit](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/recurring-work) — periodische Arbeit ist ungenau; Mindestintervall 15 Minuten.
- [PeriodicWorkRequest](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest) — Ausführung kann durch Doze, Optimierungen und Constraints verzögert werden.
- [BLE im Hintergrund](https://developer.android.com/develop/connectivity/bluetooth/ble/background) — `PendingIntent`-Scans, Companion APIs und Grenzen von Prozess/Verbindung.
- [BluetoothLeScanner](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner) — Scan-Ergebnisse über `PendingIntent`.
- [Lang laufende Worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) — Foreground-Service-Mechanik und neuere Quoten-/Typanforderungen.
- [Android 14 Foreground-Service-Typen](https://developer.android.com/about/versions/14/changes/fgs-types-required) — Typ, Berechtigung und Laufzeitvoraussetzung müssen zur Nutzung passen.
- [Exact Alarms](https://developer.android.com/develop/background-work/services/alarms) — für präzise, nutzerrelevante Zeitpunkte, nicht als kontinuierlicher Sensortakt.
- [Bluetooth-Berechtigungen](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) — Laufzeitberechtigungen ab Android 12 und Standortbezug.
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore) — nicht exportierbare Schlüssel und hardwareabhängige Sicherheitsmerkmale.
- [Apple: iOS Background Execution Limits](https://developer.apple.com/forums/thread/685525) — keine allgemeine kontinuierliche oder garantiert periodische Hintergrundausführung.

---

## 18. Definition of Done

Die Funktion ist erst dann als produktionsreif zu bezeichnen, wenn:

- [ ] Gateway-Alarmentscheidungen allein aus versionierter Policy und gespeicherter
      Evidence reproduzierbar sind;
- [ ] Qualitäts-, Freshness-, Unsicherheits-, Dwell-, Hysterese- und Data-loss-Regeln
      implementiert und getestet sind;
- [ ] Runtime, Event und Outbox atomar persistiert werden;
- [ ] Neustart-, Duplikat-, Reihenfolge- und Netzwerkpartitionsfälle bestanden sind;
- [ ] ACK, Snooze, Disable und Update authentisiert, autorisiert, revisioniert und
      idempotent sind;
- [ ] CT45P zwischen Live, stale, offline, data loss und lokal degradiert unterscheidet;
- [ ] Notification-/Permission-Degradation sichtbar und gemessen ist;
- [ ] kritische Zustellung nicht ausschließlich von einem Android-Prozess abhängt;
- [ ] reale CT45P- und Funkmessungen die dokumentierten Latenz-/Energiegrenzen erfüllen;
- [ ] Datenschutz, Aufbewahrung, MDM und Betriebsrunbooks abgenommen sind;
- [ ] Dokumentation keine Hardware-, Genauigkeits- oder Permanenzgarantie behauptet, die
      nicht durch Tests und Deployment belegt ist.
