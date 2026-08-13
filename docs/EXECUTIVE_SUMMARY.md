# 📌 Executive Summary

Die **3dxAgent-Plattform** setzt das Industrie-Smartphone **Honeywell CT45P** als
mobile Control Plane für Bedienung, Enrollment und signierte Benutzerintentionen
eines modularen 3D-Kartierungs- und Lageerkennungssystems ein. Ein robustes
Linux-Gateway verarbeitet Daten externer LiDAR-, mmWave-, BLE- und gegebenenfalls
UWB-Module als autoritative Data Plane. Hohe Ortungsgenauigkeit und Erkennung durch
Hindernisse sind validierungspflichtige Systemziele mit zusätzlicher
Infrastruktur, keine zugesicherten CT45P-Eigenschaften.

Die **v2.0.0-DataPipeline** enthält quellseitige Prototypen einer
**Sensor-/Netzwerkdaten-Evaluierungspipeline** (Erfassung → Analyse → Mesh →
3D-Umgebung → Exakte Abbildung → Evaluierungsagent). Die Klassen sind noch nicht
als vollständig gebauter, hardwarevalidierter Ende-zu-Ende-Pfad nachgewiesen; siehe
[Release-Readiness-Audit](RELEASE_READINESS_AUDIT.md).

## Die 5 Einsatzszenarien

1. **Taktische Einsatzbesprechung** — 3D-Scan ohne Baupläne, Avatare, GLTF-Export.
2. **Gefahren- & Evakuierungssimulation** — Rauch, ABM und experimentelle Auswertung extern gelieferter UWB-Rohdaten.
3. **Architektur & Bestandsanalyse** — LiDAR-SLAM, IFC-Export für BIM.
4. **Temporäre Szenarien** — BLE-Token-Personenströme, ICP-Map-Merging.
5. **Forschung & Lehre** — versionierte, wiederholbare 3D-Datensätze.

## Nutzen für Stakeholder

- **Architekten:** schnelle, kostengünstige Bestandserfassung.
- **Behörden:** realistische, datenschutzkonforme Einsatzvorbereitung.
- **Universitäten:** wiederholbare Ground-Truth-Datensätze für KI/ML.
- **Bauunternehmen:** effiziente Baustellenlogistik & Umleitungsplanung.
