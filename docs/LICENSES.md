# Lizenzen und Namensnennung externer Datenquellen

Dieses Dokument listet alle Fremddaten auf, die der 3dxAgent zur Laufzeit
beziehen kann, samt der daraus folgenden Pflichten. Es ist bewusst getrennt
von der Lizenz des Quellcodes: Datenlizenzen greifen erst beim Betrieb, und
sie treffen den Betreiber, nicht den Entwickler.

**Regel im Projekt:** Jede Quelle liefert ihre Lizenz als Feld mit
(`GeoFix.license`, `ExternalEntity.license`). Wo Namensnennung Pflicht ist,
muss der Text in der Oberfläche sichtbar sein — nicht nur im Quelltext.

---

## 1. Geolokalisierungs-Provider

| Quelle | Lizenz/Bedingungen | Namensnennung | Weitergabe | Standard |
|---|---|---|---|---|
| **OpenCelliD** (offline) | CC BY-SA 4.0 | **Pflicht** | Share-Alike | aktiv |
| **beaconDB** | AGPL-3.0 (Dienst), Daten gemeinfrei gestellt | empfohlen | frei | aktiv |
| **Ichnaea (self-hosted)** | Apache-2.0 (Software) | – | eigene Daten | inaktiv |
| **Combain** | kommerzieller Vertrag | nach Vertrag | verboten | inaktiv |
| **Google Geolocation** | Google Maps Platform ToS | Pflicht | **verboten** | inaktiv |

### OpenCelliD — CC BY-SA 4.0

Der Bestand wird nicht mitgeliefert, sondern lokal mit
`scripts/fetch_geo_data.py` erzeugt. Anzuzeigender Hinweis:

> Zelldaten: OpenCelliD-Mitwirkende (CC BY-SA 4.0)

Share-Alike heisst: Wer eine *angepasste Fassung der Datenbank* weitergibt,
muss sie ebenfalls unter CC BY-SA 4.0 stellen. Nur Positionen daraus
abzuleiten und anzuzeigen löst diese Pflicht nicht aus.

### beaconDB

Ichnaea-kompatibler Ersatz für den zum 10.04.2024 endgültig abgeschalteten
Mozilla Location Service. Der Betreiber bezeichnet den Dienst ausdrücklich
als **experimentell** — keine Verfügbarkeits- oder Genauigkeitszusage. Nicht
als alleinige Ortungsquelle für zeitkritische Abläufe einplanen.

### Google Geolocation API

Drei Bedingungen mit direkter Auswirkung auf die Umsetzung:

1. **Cache-Grenze 30 Tage** für `lat`/`lng`. Im Code umgesetzt über
   `GeoFix.ttl_days` → `geo_fixes.expires_at` → `purge_expired_geo()`.
2. **"No Use With any Map"** — Ergebnisse dürfen auf keiner Karte ausser
   Google Maps dargestellt werden. Die Three.js-Szene des Projekts ist keine
   Karte im Sinne der Klausel, eine eingeblendete OSM-Kachelkarte wäre es.
3. Der Freibetrag von 200 USD/Monat entfiel zum **01.03.2025**; das aktuelle
   Kontingent sind 10.000 Essentials-Aufrufe pro Monat.

### Nicht aufgenommen

* **WiGLE** — nur nicht-kommerzielle Nutzung, Weitergabe untersagt, gleitende
  Abfragelimits. Für einen Einsatzagenten nicht tragfähig.
* **MaxMind GeoLite2** — seit 30.12.2019 keine Creative-Commons-Lizenz mehr,
  sondern EULA mit 30-Tage-Aktualisierungspflicht; City-Daten sind seit 2025
  für sieben Länder gesperrt. IP-Ortung fällt ohnehin durch das
  Qualitätsgatter (`GEO_MIN_QUALITY`).

---

## 2. Externe Tracking-Feeds

### GTFS-Realtime (aktiv umgesetzt)

Das *Format* (Protobuf-Schema, Apache-2.0) ist frei. Die *Daten* jedes
einzelnen Feeds haben eine eigene Lizenz — es gibt keine
GTFS-RT-Sammellizenz. Deshalb sind `GTFS_RT_LICENSE` und
`GTFS_RT_ATTRIBUTION` Konfigurationswerte, die der Betreiber pro Feed setzt;
Vorgabe ist `unknown`, was in der Oberfläche als ungeklärt erscheint.

Verbreitete Fälle im deutschsprachigen Raum:

| Anbieter | Lizenz |
|---|---|
| DELFI / Deutschlandweite Sollfahrplandaten | CC BY 4.0 |
| MobiData BW | CC BY 4.0 bzw. DL-DE/BY-2.0 |
| VBB | CC BY 4.0 |
| Wiener Linien | CC BY 4.0 |

`TransitFeeds` als Verzeichnis wird im **Dezember 2025** abgeschaltet;
Nachfolger ist die **Mobility Database**.

### Zweckbindung — der eigentliche Prüfpunkt

Eine offene Lizenz erlaubt die *Nutzung* der Daten. Sie ersetzt keine
Rechtsgrundlage für einen abweichenden *Zweck*. Fahrplandaten werden zur
Fahrgastinformation veröffentlicht; sie in ein behördliches Lagebild zu
überführen ist eine andere Verarbeitung und kann eine eigene Grundlage
verlangen. Deshalb steht `EXT_ENABLED` standardmässig auf `false`.

Zweiter Punkt, der leicht übersehen wird: Eine Bounding-Box-Abfrage teilt dem
Feed-Betreiber mit, wo sich der Agent gerade befindet. Bei einem verdeckten
Einsatz ist das ein Informationsabfluss.

### Bewertete, aber nicht umgesetzte Quellen

| Quelle | Grund |
|---|---|
| **OpenSky Network** | nur Forschung/nicht-kommerziell; Kontingent 400–8.000 Credits/Tag, Zitierpflicht |
| **GBFS** | `vehicle_id` rotiert vorschriftsgemäss nach jeder Miete; Fahrzeuge in aktiver Miete fehlen im Feed — kein Tracking möglich |
| **aisstream.io** | Beta ohne Verfügbarkeitszusage, keine Browser-Verbindungen |
| **Entur** | NLOD, Header `ET-Client-Name` verpflichtend — nur für Norwegen relevant |
| **TfL** | kein Live-Positionsfeed, 50/500 Anfragen pro Minute |
| **HSL HFP** | nur Helsinki; die Doku rät aus Standortdatenschutzgründen zu den verschlüsselten Ports 8883/443 |
| **DATEX II** | ein Standard, kein Dienst — es gibt keinen Endpunkt zum Anbinden |

---

## 3. Nachvollziehbarkeit im Betrieb

| Zweck | Endpunkt/Ort |
|---|---|
| Welcher Provider ist aktiv, mit welcher Lizenz | `GET /api/v1/geo/providers` |
| Welcher Provider wurde wann mit welchem Ergebnis befragt | `GET /api/v1/geo/audit` |
| Lizenz und Zustand jedes Feeds | `GET /api/v1/external/sources` |
| Ablauf lizenzbeschränkter Positionen | `geo_fixes.expires_at`, `purge_expired_geo()` |

Der Audit-Ringpuffer fasst 200 Einträge und dient als Grundlage für das
Verarbeitungsverzeichnis nach Art. 30 DSGVO. Er wird bewusst nur im
Arbeitsspeicher gehalten — er soll den Einsatz nicht überdauern.
