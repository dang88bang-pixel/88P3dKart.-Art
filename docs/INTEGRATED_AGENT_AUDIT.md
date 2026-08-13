# Integrierter Agent: Repository-Audit und belastbare Zielarchitektur

**Stand:** 2026-08-14  
**Geprüfter Branch:** `arena/019ffc03-88p3dkart-art`  
**Geprüfter Implementierungsstand:** `fa3af61`

## Entscheidung

Der vorgeschlagene „3dxAgent 5.1.0-Agent“ wird **nicht unverändert übernommen**.
Die eingefügten Ausschnitte sind weder mit den vorhandenen Kotlin-Verträgen
kompatibel noch ein Nachweis für TinyLLaMA, Cloud-LLMs, Voice Input,
Safety-Interlocks oder ein Auditjournal.

Ein integrierter Assistent kann später als Bedien- und Planungsoberfläche der
CT45P-Control-Plane sinnvoll sein. Er darf aber:

- keine Messwerte erfinden;
- keine Gateway-Autorität ersetzen;
- keine privilegierten Befehle lokal autorisieren;
- keinen Tool-Erfolg melden, bevor der reale Adapter beziehungsweise das Gateway
  ihn bestätigt hat;
- keine Cloud-Zugangsdaten im APK enthalten;
- keinen unbeschränkten Sensor-, Datei- oder Netzwerkzugriff erhalten.

Die bestehende Zielverteilung bleibt daher unverändert:

1. **CT45P-Control-Plane:** Darstellung, explizite Operator-Intents,
   Acknowledge/Snooze, lokale Statusprojektion und klar markierter Degraded State.
2. **Gateway-Data-Plane:** kalibrierte Evidence, Sensorfusion, autoritativer
   Alarm-Reducer, persistente Revisionen, Audit-Events, Outbox und RBAC.
3. **Optionaler Assistent:** übersetzt natürliche Sprache in einen begrenzten,
   überprüfbaren Aktionsplan. Er ist weder Alarmengine noch Sicherheitsinstanz.

## 1. Konkrete Compile- und Vertragsfehler der Vorlage

| Stelle | Repository-Befund | Folge |
|---|---|---|
| `IntentType` | Deklariert `START_SCAN`, verwendet aber mehrfach `SCAN_DEVICES`. | Unresolved reference; Quelltext kompiliert nicht. |
| Hilfe-Regel | Regex `(hilfe|help|?|support|info)` enthält ein ungeschütztes `?`. | `PatternSyntaxException` bei Initialisierung. |
| `AgentChatFragment` | Greift auf private `serialManager`/`bleManager` und nicht vorhandene Activity-Felder `ekf`, `meshIntegrator`, `evaluationAgent`, `exactMapper`, `acquisitionService` zu. | Fragment kann nicht kompiliert oder zusammengesetzt werden. |
| `StatusTool` | Erwartet `DataAcquisitionService.getStats()`. Vorhanden sind nur `snapshot()` und `count()`. | Unresolved reference. |
| `DiagnosticsTool` | Erwartet `EvaluationAgent.getLatestReport()` und Reportfelder, die nicht existieren. | Unresolved references und falsches Domänenmodell. |
| `ExportTool` | Erwartet `ExactMapper.export(...)`. `ExactMapper` besitzt nur `map(...)` und `toTransform3D(...)`. | Kein realer Exportpfad. |
| Chat-UI | Layouts, Item-Layout, Bindings und RecyclerView-Abhängigkeit fehlen. | Keine generierbaren Bindings; kein UI-Build. |
| Adapter | Verwendet `View.VISIBLE/GONE` ohne passenden Import und `surface_variant`, das in `colors.xml` fehlt. | Weitere Compile-/Resourcefehler. |
| Quick Actions | Übergibt beispielsweise `get_status` wieder als natürliche Sprache; der Parser erkennt diesen String nicht sicher als Statusintent. | Angezeigte Aktion kann in `UNKNOWN` enden. |
| Szenario | `handleScenario` meldet immer „gestartet“, ohne API-Aufruf oder Bestätigung. | Falscher Produktionserfolg. Das Gateway verlangt für die vorhandene Szenariokonfiguration eine Admin-Identität. |
| Scan | `foundDevices` wird nie aus `tokenUpdates` befüllt. | Jeder angeblich erfolgreiche Scan liefert deterministisch null Geräte. |
| Version | Gradle-Metadaten bleiben `2.0.0`, Version Code `1`; es gibt keinen Tag oder Release `5.1.0-Agent`. | Die Versionsbehauptung ist nicht belegt. |

## 2. Nicht implementierte, aber als vollständig markierte Funktionen

### 2.1 Lokales Modell

Im Repository fehlen für TinyLLaMA insbesondere:

- Modell- und Tokenizer-Asset mit Hash und Lizenznachweis;
- eine Android-kompatible Inference-Runtime;
- Quantisierungs-, RAM-, Storage-, Latenz- und Thermikbudget;
- Prompt-/Output-Grenzen und deterministische Tool-Call-Grammatik;
- Tests gegen Prompt Injection, unzulässige Toolnamen und fehlerhafte Parameter;
- CT45P-Gerätemessungen.

Die regelbasierte `when`-Logik ist kein TinyLLaMA- oder KI-Fallback.

### 2.2 Cloud-Agent

Es existiert kein Claude-, GPT- oder Llama-Cloudadapter. Vor einer Einführung
müssen mindestens geklärt sein:

- Gateway-vermittelter Zugriff statt Provider-Secret im APK;
- Provider-Allowlist, Datenklassifikation und Redaction;
- EU-/Kunden-Datenregion, Aufbewahrung und Auftragsverarbeitung;
- kurze Timeouts, Quoten, Circuit Breaker und Offlineverhalten;
- signierte beziehungsweise serverseitig neu validierte Toolpläne;
- Schutz gegen Prompt Injection aus Sensordaten, Dateinamen und Gatewaytexten.

Ein Cloudmodell darf nur einen **Vorschlag** liefern. Die App beziehungsweise das
Gateway muss den Vorschlag gegen ein statisches Toolschema und die aktuelle Rolle
neu validieren.

### 2.3 Safety und Audit

Die Vorlage enthält keine Klasse und keinen Aufrufpfad für den behaupteten
`SafetyInterlock`. Ebenfalls fehlen:

- Rollen-/Capability-Prüfung pro Tool;
- explizite Bestätigung für mutierende oder disruptive Aktionen;
- Idempotency Key und Command-Revision;
- Begrenzung von Dauer, Ergebnisgröße und Parallelität;
- Abbruch-/Rollbacksemantik;
- unveränderliches Audit-Event mit Actor, Session, Tool, Parametern, Ergebnis und
  Gatewayrevision.

Das vorhandene Gateway auditiert Alarm-Policy- und Alarmzustandsübergänge. Dieses
fachliche Journal darf nicht durch einen frei formulierten Chatverlauf ersetzt
werden.

## 3. Sicherheitsprobleme im vorgeschlagenen Toolpfad

1. Scan-Dauer und Mesh-Auflösung werden nicht robust begrenzt.
2. Ein Mesh-Tool kann bis zu zehntausende vollständige Punktobjekte in eine
   Chatnachricht kopieren und damit Speicher/IPC/UI überlasten.
3. `Exception.message` und absolute Dateipfade werden direkt in die UI gegeben.
4. `connectionStatus = "Connected"` ist ein erfundener Status.
5. Tool-Erfolg basiert teilweise nur darauf, dass keine Exception auftrat.
6. Sensorstart und -stopp umgehen Activity-Lifecycle und Runtime-Permissions.
7. Eine einfache Keyword-Erkennung kann destruktive und lesende Intents
   verwechseln; Negationen werden nicht behandelt.
8. Es gibt keine Replay-, Doppel-Tap-, Parallelitäts- oder Cancellation-Semantik.
9. Der mutable Tool-Registry-/Konversationszustand ist weder dauerhaft noch als
   Thread-Safety-Vertrag definiert.
10. Chatinhalt wird in HTML umgewandelt; nicht vertrauenswürdiger Text benötigt
    eine strikt begrenzte Renderer-Pipeline statt allgemeiner HTML-Interpretation.

## 4. Empfohlener Agentenvertrag

Jedes Tool benötigt ein statisches, versioniertes Manifest:

```text
ToolManifest
  id                  stabiler Bezeichner
  contractVersion     Schema-/Semantikversion
  mode                READ | COMMAND
  executionAuthority  CT45P | GATEWAY
  requiredRole        DEVICE | OPERATOR | ADMIN
  confirmation        NONE | OPERATOR | TWO_PERSON
  timeoutMs           harte Obergrenze
  maxResultBytes      harte Obergrenze
  inputSchema         strikt; unbekannte Felder verboten
  outputSchema        strikt; unbekannte Felder verboten
  idempotent          explizit
  auditCategory       verpflichtend
```

Der Ausführungspfad lautet:

```text
Benutzereingabe
  -> begrenzter Parser/Modellvorschlag
  -> strikt validierter ToolPlan
  -> Capability- und Rollenprüfung
  -> sichtbare Bestätigung bei COMMAND
  -> Ausführung durch fest registrierten Adapter
  -> fachliche Gateway-Bestätigung
  -> strukturiertes Audit-Event
  -> begrenzte, lokalisierte UI-Projektion
```

Freitext darf niemals Klassenname, URL, Dateipfad oder beliebigen JSON-RPC-Code
bestimmen.

## 5. Sinnvolle erste Tools

Die erste Stufe sollte ausschließlich reale, überwiegend lesende Projektionen
verwenden:

| Tool | Autorität | Freigabe | Reale Quelle |
|---|---|---|---|
| `show_connection_state` | CT45P | Device | authentifizierter WebSocketstatus |
| `show_latest_alarm` | Gateway-Projektion | Device | `AlarmUiState`/Alarm-Event |
| `show_latest_fusion_state` | Gateway-Projektion | Device | validiertes `EkfState`-Envelope |
| `show_sensor_capabilities` | CT45P/Gateway | Device | noch zu implementierender Capability-Vertrag |
| `acknowledge_alarm` | Gateway | Operator | vorhandener authentifizierter REST-Pfad, explizite Bestätigung |
| `snooze_alarm` | Gateway | Operator | vorhandener REST-Pfad, begrenzte Dauer, explizite Bestätigung |

Nicht in Stufe 1 aufzunehmen sind:

- Szenario-Start mit Device-Credentials;
- Fault-Code-Löschung ohne konkreten Adaptervertrag;
- beliebiger Datei-/Mesh-Export;
- lokale Änderung autoritativer Alarm-Policies;
- Cloudmodell-gesteuerte Sensor- oder Sicherheitsaktionen.

## 6. Implementierungsreihenfolge und Exit-Kriterien

### A. Deterministischer Parser als reine Bibliothek

- kleiner, abgeschlossener Intentkatalog;
- Unicode-/Längenbegrenzung;
- Negations- und Mehrdeutigkeitsfälle führen zu Rückfrage, nicht Ausführung;
- keine Mutation bei Confidence-Heuristik;
- JVM-Tests für Deutsch/Englisch, Grenzwerte und adversariale Eingaben.

**Exit:** Parser kompiliert und alle Fixtures bestehen; kein Tool wurde ausgeführt.

### B. Toolplan und Safety-Interlock

- strikte Datentypen statt `Map<String, Any>`;
- Capability/RBAC/Confirmation/Timeout/Result-Limit;
- ein Ausführungsjob pro Session; explizites Cancel;
- strukturierte, redigierte Fehler.

**Exit:** unzulässige Tools/Parameter/Rollen werden nachweislich vor Adapteraufruf
abgewiesen.

### C. Read-only UI

- bestehende native Kotlin/XML-Navigation erweitern;
- keine Flutter-/Riverpod-/BLoC-Parallellaufzeit;
- Lifecycle-aware Flow-Collection;
- Status immer mit Quelle, Revision und Freshness anzeigen.

**Exit:** Instrumentation-Test zeigt reale Projektion und klaren Degraded State.

### D. Mutierende Gateway-Commands

- nur vorhandene autorisierte REST-Verträge;
- sichtbare Zielressource und Auswirkung;
- Idempotency/CAS und Gateway-Audit;
- Erfolg erst nach Gatewayantwort beziehungsweise korreliertem Event.

**Exit:** Replay-, Timeout-, 401/403-, Konflikt- und Doppel-Tap-Tests bestehen.

### E. Optionales lokales oder Cloudmodell

Erst nach A–D kann ein Modell Text in denselben validierten `ToolPlan` übersetzen.
Das Modell erhält keine zusätzliche Capability.

**Exit:** Modell-, Datenschutz-, Security-, Ressourcen- und Hardwaregates sind
separat dokumentiert und bestanden.

## 7. Evidenzstatus

Zum Prüfzeitpunkt gilt:

- Gateway-Testlauf: **82 bestanden**;
- Android Agent-Quelltext aus der Vorlage: **nicht übernommen und nicht gebaut**;
- TinyLLaMA/Cloud/Voice: **nicht vorhanden**;
- Safety-Interlock für generische Tools: **nicht vorhanden**;
- signiertes APK: **nicht vorhanden**;
- Branch-Push mit sicherer Gateway-/Alarm-/Android-Control-Plane:
  **`fa3af61` auf `arena/019ffc03-88p3dkart-art`**.

Damit ist die korrekte Statusaussage nicht „App komplett“, sondern:

> Eine gehärtete Gateway-Alarmbasis und eine native Android-Control-Plane sind im
> Quellstand integriert. Ein generischer KI-Agent bleibt ein separat zu
> implementierendes und zu validierendes Feature.
