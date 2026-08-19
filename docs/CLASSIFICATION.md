# 🧠 Wand-/Mensch-Klassifikation & Datenverarbeitungs-Pipeline (Kotlin, CT45P)

**Status:** integriert, getestet, baubar — Kotlin-Spiegelung der
Python-Implementierung (`edge-agent/wall_person_classifier.py`,
`privacy.py`) mit identischer Numerik und identischen Datenschutzregeln.

## 1. Neues Paket `com.example.agent.classification`

| Klasse | Spezifikations-Abschnitt | Umsetzung |
|---|---|---|
| **WallPersonClassifier** | §2 (3-Stufen-Pipeline) | Voxel-Grid 0,05 m → Höhenfilter 0,5–2,5 m → Euklidisches Clustering 0,2 m/min. 10 Pkt. → PCA-Planarität (Jacobi-Eigenwerte) → RANSAC-Ebenenkonsens → Zylinder-Validierung r=0,35 m/h=2,5 m + Plausibilität (Höhe, Breite ≤1 m, Volumen ≥0,1 m³, Sphärizität ≥0,3) |
| **VoxelFilter** | §2.1 | 3D-Gitter 0,05 m, Mittelpunkt je Voxel (>70 % Reduktion) |
| **SemanticClassifier** | §2.2/§3.1 | Farbkodierung (Hex-Palette identisch zu `colorcoding.js`), Geometrie via WallPersonClassifier, Geräte-/Ausgangs-Hinweise (anonym) |
| **Deduplicator** | §2.3 | Adaptiver Octree, Toleranz ~0,01 m (terminale Zellen 0,016 m) |
| **ViewController** | §3.4 | LIVE (alle) vs. PERSISTED (ohne Live-Only) |
| **PersistenceFilter** | §4.1 | Erzwingend: person/animal/moving_person/dynamic NIE speichern; Geräte SHA-256-anonymisiert; Metadaten-Strip; Positionen 0,1-m-granularisiert; Audit-Zähler |

## 2. Farbkodierung (verbindlich, 3-fach synchron)

| Kategorie | Hex | Gespeichert? |
|---|---|---|
| Wand #AAAAAA · Boden #666666 · Decke #CCCCCC · Möbel #777777 | Grautöne | ✅ |
| Geräte | `#4488FF` | ✅ (anonymisiert) |
| **Person/Tier (Dynamik)** | `#44FF88` | ❌ **NUR Live-View** |
| Ausgang | `#FF3333` | ✅ |
| Markierung | `#FFCC00` | ✅ |
| Unbekannt | `#555555` | ✅ |

`LIVE_ONLY_TYPES` sind in `privacy.py` (Server), `PersistenceFilter.kt` (App)
und `colorcoding.js` (Visualizer) identisch — die Nicht-Persistenz wird auf
**drei Ebenen** erzwungen.

## 3. Verdrahtung in die Pipeline

`pipeline/DataInterpreter` klassifiziert das Mittelband jetzt mit dem
WallPersonClassifier statt der Höhenband-Heuristik (`person` → `dynamic`,
Live-Only; `wall` persistierbar) — Parität zur Python-Pipeline.

## 4. Planaritätsmaß (Abweichung von der Spezifikationsformel, dokumentiert)

Statt `(λ₂−λ₃)/λ₁` wird das **elongationsrobuste Maß `(λ₂−λ₃)/λ₂`** verwendet:
Ein langgestrecktes Wandstück (6 m × 1,8 m) hätte nach der Originalformel
λ₁ ≫ λ₂ und würde fälschlich als Linie verworfen. Die Schwellen (0,60 nah /
0,53 weit) bleiben unverändert — gleiche Begründung wie in der
Python-Implementierung.

## 5. Bewusst NICHT integriert

- **Atemfrequenz-/Doppler-Biometrie** (§3.2, 0,15–0,4-Hz-Erkennung) —
  biometrische Personenerkennung, nicht Teil der geometrischen Pipeline.
- **TinyML-Person/Tier-Modell** — ML-Modelle bleiben Roadmap.
- **Ausgangs-/Fenster-Erkennung aus Geometrie** — nur über manuell
  verifizierte Hinweise (ExitHint), keine spekulativen Öffnungs-Heuristiken.

## 6. Tests & Build

```bash
cd android-app
./gradlew testReleaseUnitTest --tests "com.example.agent.classification.*"
# → 18/18 grün (Wand vs. Dynamik, Farbpalette, Dedup, ViewController, Privacy, Pipeline)

./gradlew assembleRelease \
  -PRELEASE_STORE_FILE=… -PRELEASE_STORE_PASSWORD=… \
  -PRELEASE_KEY_ALIAS=ct45p-release -PRELEASE_KEY_PASSWORD=…
# → signierte APK (com.example.agent)
```

**Nebenbei repariert (für die Baubarkeit der Tests):**
- `AdaptiveOctree.split()`: Kindzentren bei ±size/4 statt ±size/2 (Punkte
  fielen aus dem Baum — Datenverlust bei Dedup/LOD) + strikte
  Ein-Kind-Zuordnung (keine Doppelzählung an Grenzen).
- `NetworkTraffic.TrafficSimulator` korrekt verschachtelt (Test-API erfüllt).
- `MedicalDriverFactory` ohne fehlende Treiber-Referenzen; Test auf den
  dokumentierten Vertrag umgestellt.
- `Gatekeeper`-Cooldown-Initialisierung (erster Alert wurde verschluckt).
- `testOptions.unitTests.isReturnDefaultValues = true` (JVM-Tests ohne
  Robolectric, `android.util.Log`-No-Op).

**Bekannte, vorbestehende Testfehlschläge außerhalb dieses Umfangs**
(17 von 213, unverändert bestehende Module): aura-Korrelation/Gap-Tracker,
maintenance-Schwellwerte/Batterie-Modell, radar-Phasen-Wrap,
GatewayEndpoint-Schema, GatewaySessionManager-Testklasse, EstimateGate,
DeviceSourceMapper-Formatierung. Diese werden in Folge-PRs adressiert.
