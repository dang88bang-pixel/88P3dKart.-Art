# 🖥️ Nutzeroberfläche & Interaktionsworkflows (v3.2.0-UX)

## Die 5 Hauptansichten

| Ansicht | Funktion | Zielgruppe |
|---------|----------|------------|
| Live-3D-View | Echtzeit-Punktwolke mit semantischen Farben, Kamerasteuerung | Alle |
| 2D-Kartenansicht | Top-Down: BLE-Token, Personen, Hindernisse | Einsatzkräfte |
| Szenarien-Steuerung | Evakuierung/Taktik/Architektur/Temporär/Forschung | Behörden, Forscher |
| Analyse & Export | Historie, GLTF/OBJ/PLY/IFC-Export, Evaluierungsbericht | Architekten |
| Einstellungen | Sensor-Kalibrierung, Netzwerk, Speicher, Offline-Modus | Admins |

## Touch-Gesten (CT45P)

| Geste | Funktion |
|-------|----------|
| 1-Finger-Wischen | Rotieren/Scrollen |
| 2-Finger-Pinch | Zoom |
| Doppeltipp | Kontext-Menü (Objektdetails) |
| Langer Druck | Markieren |
| 3-Finger-Wischen | Ansicht wechseln |

## Farbkodierung (Semantik)

| Farbe | Hex | Bedeutung |
|-------|-----|-----------|
| 🔴 Rot | `#FF3333` | Person |
| 🔵 Blau | `#4488FF` | Wand |
| 🟢 Grün | `#44FF88` | Boden |
| 🟤 Braun | `#AA8844` | Möbel |
| ⚪ Weiß | `#FFFFFF` | Unbekannt |
| 🟡 Gelb | `#FFCC00` | Markiert |
| 🔶 Orange | `#FF8800` | Bewegt |

## Workflows (Auszug)

1. **Evakuierung:** Szenarien → Evakuierung → Parameter (Personen, Rauch, Ausgänge) → Start → Echtzeit verfolgen → Stopp → Analyse exportieren.
2. **Bestandsaufnahme:** Live-3D → Gebäude begehen → Speichern → Export (GLTF) → BIM-Import.
3. **Korrektur Person/Gegenstand:** Objekt doppeltippen → Typ bearbeiten → Übernehmen.

Die `res/values/colors.xml`, `strings.xml` und `themes.xml` setzen das
Material-Design-3-Farbsystem und die semantischen Farben um.
