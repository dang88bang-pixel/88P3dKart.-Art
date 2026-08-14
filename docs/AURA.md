# 📡 Projekt Aura — Elektromagnetische Umgebung in 3D

> **Version:** v0.1.0 · **Status:** Konzept + Kernmodule implementiert (siehe [Umsetzungsstatus](#umsetzungsstatus))
>
> Die Erfassung und Visualisierung der elektromagnetischen Umgebung in einer
> dreidimensionalen, photorealistischen Darstellung stellt eine der komplexesten
> Herausforderungen der modernen Hochfrequenztechnik (HF) und mobilen
> Softwareentwicklung dar. **Projekt Aura** schließt diese Lücke durch die
> Synthese von **Software Defined Radio (SDR)**, **Radio-Tomographischer
> Bildgebung (RTI)** und modernsten Geospatial-APIs.

Das zentrale Nervensystem von Aura besteht aus einem hochperformanten
Tunnel-Link zwischen zwei mobilen Endgeräten, der den Austausch von Inphase- und
Quadratur-Daten (IQ-Daten) mit minimaler Latenz ermöglicht. Dieses Dokument
identifiziert die optimalen technologischen Komponenten, definiert die
Netzwerkarchitektur und stellt den Implementierungsleitfaden für die Integration
in das Google-Maps-Ökosystem sowie in die 3dxAgent-Plattform dieses Repositories
bereit.

---

## 1. Strategische Auswahl der Google Maps Platform APIs

Für eine immersive 3D-Visualisierung von Funkquellen müssen die verwendeten APIs
sowohl geografische Präzision als auch grafische Leistungsfähigkeit unterstützen.
Die Entscheidung hängt von der benötigten Immersionstiefe und der Kontrolle über
die Rendering-Pipeline ab.

| API / SDK | Relevanz für Aura | Technische Begründung |
| :--- | :--- | :--- |
| **Maps 3D SDK für Android (Experimental)** | Primär | Photorealistische 3D-Gebäude, Gelände und Landmarks direkt in der nativen App. Unterstützt 3D-Marker und glTF-Modelle für SDR-Hardware. |
| **Geolocation API** | Sekundär | Position über Funkmasten und WLAN-Knoten, wenn GPS in Tunneln oder Gebäuden versagt. Essentiell für die Verortung der SDR-Knoten. |
| **Maps Datasets API** | Tertiär | Serverseitiges Management großer geospatialer Datensätze; reduziert die Client-Last bei komplexen RF-Heatmaps. |
| **Maps Grounding Lite** | Ergänzend | Verbindet KI-gestützte Analyse-Agents mit aktuellen Standortdaten, um Anomalien im Funkraum intelligent zu klassifizieren. |
| **Routes API** | Operativ | Berechnet optimierte Bewegungspfade für die mobilen Scanner-Einheiten, um die Abdeckung für die Tomographie zu maximieren. |

Das Maps 3D SDK bietet durch seine experimentelle Preview-Phase Zugang zu einer
Rendering-Engine jenseits der klassischen 2D-Vektorkarte. Für Aura ist das
entscheidend, da Funkwellen sich dreidimensional ausbreiten und ihre Intensität
oft von der Gebäudegeometrie beeinflusst wird. Durch die Integration von
3D-Modellen via glTF können physische SDR-Empfänger (z. B. Nooelec RTL-SDR v5)
räumlich korrekt platziert werden.

> **Hinweis zur Umsetzung in diesem Repository:** Die Android-App ist bewusst
> ohne Google-Maps-Abhängigkeit gebaut (sie baut auf dem eigenen
> OpenGL/Three.js-Renderer auf). Die Aura-Kernmodule liefern die Datenmodelle
> (extrudierte Heatmap-Zellen, RTI-Voxel, Kamerapose), die 1:1 in das Maps-3D-SDK
> übernommen werden können — siehe [Umsetzungsstatus](#umsetzungsstatus).

## 2. Implementierung der 3D-Visualisierungsebene

- **Lebenszyklus der `Map3DView`:** Das SDK unterstützt nur eine aktive Instanz
  pro Layout. Die Architektur muss Rendering-Konflikte vermeiden (Single-Instance,
  Lifecycle-Bindung an `Activity`/`Fragment`).
- **Volumetrische Heatmap:** Die Funkintensität wird über **extrudierte Polygone**
  dargestellt, wobei die Extrusionshöhe direkt proportional zur gemessenen
  Signalstärke ist. Hindernisse (Wände, Reflexionsflächen) werden dadurch
  sichtbar.
- **Performance:** Draco-Kompression für alle glTF/GLB-Modelle ist zwingend
  (Zielgröße < 5 MB), um Ruckler bei Kamerabewegung zu verhindern.
  Marker-Kollisionseigenschaften so konfigurieren, dass bei hoher Signaldichte
  nur die relevantesten Peaks angezeigt werden.

> Die vollständige UI/UX-Spezifikation (Bildschirmaufbau, HUD, Panels,
> Aktionskatalog, Kamera-Modi inkl. „Röntgenblick", Gesten, Farb-/Legendensystem)
> liegt in [`UI_UX_PLAN.md`](UI_UX_PLAN.md).

## 3. Netzwerkarchitektur für die SDR-Tunnelkommunikation

Ein Nooelec RTL-SDR v5 mit typischer Abtastrate $2{,}4\ \text{MS/s}$ und
8-Bit-Auflösung pro Sample erzeugt einen Datenstrom von etwa
$38{,}4\ \text{Mbit/s}$ (I + Q). Das Übertragungsprotokoll muss Verschlüsselung
ohne signifikanten Overhead bieten.

### 3.1 WireGuard als Backbone für den Datentunnel

WireGuard bietet gegenüber OpenVPN signifikant geringere Latenz und eine
effizientere Ressourcennutzung (Kernel-Space bzw. hochoptimierte
Go-Implementierung). Für die Android-Integration dient die Bibliothek
`com.wireguard.android:tunnel` (Einbindung in das `VpnService`-Framework).

| Eigenschaft | Begründung für Aura |
| :--- | :--- |
| **ChaCha20-Poly1305** | AEAD-Verschlüsselung mit minimalem CPU-Overhead auf Mobilgeräten |
| **UDP als Basistransport** | Eliminiert das „TCP-Meltdown"-Problem (TCP-über-TCP) bei Echtzeit-SDR-Daten |
| **PersistentKeepalive = 25 s** | Stabiler Tunnel auch bei kurzzeitigen Funkunterbrechungen im mobilen Einsatz |

### 3.2 Konfigurations-Blueprint für den Tunnel-Link

Point-to-Point-Ansatz: ein Smartphone agiert als Hotspot, oder beide Geräte sind
in einem lokalen Ad-hoc-Netzwerk verbunden. Eine feste MTU verhindert
Fragmentierung, die bei SDR-Streams zu massiven Paketverlusten führt.

**Smartphone A (Leitstelle):**

```ini
[Interface]
PrivateKey = <Generierter_Privatkey_A>
Address = 10.0.0.1/32
ListenPort = 51820
MTU = 1420

[Peer]
PublicKey = <Publickey_B>
AllowedIPs = 10.0.0.2/32
Endpoint = 192.168.43.2:51820
PersistentKeepalive = 25
```

**Smartphone B (Scanner-Knoten)** spiegelt die Konfiguration mit der Adresse
`10.0.0.2`. Die Generierung der Schlüsselpaare (Curve25519, RFC 7748) und der
INI-Konfigurationen übernimmt
[`WireGuardConfig.kt`](../android-app/app/src/main/java/com/example/agent/aura/WireGuardConfig.kt).

### 3.3 Hochperformanter Transport von IQ-Daten via UDP

Da UDP keine Zustellung garantiert, muss die Anwendungsebene Paketverluste
kompensieren. Das Aura-Datagramm besitzt einen 12-Byte-Header:

| Feld | Größe | Inhalt |
| :--- | :--- | :--- |
| Sequenznummer | 4 Byte | Fortlaufender Zähler zur Erkennung von Lücken im Datenstrom |
| Zeitstempel | 8 Byte | Systemzeit in **Mikrosekunden** zum Erfassungszeitpunkt — essentiell für die Phasen-Synchronisation bei der Tomographie |

Bei einer MTU von 1420 Byte und 12 Byte Header verbleiben **1408 Byte Payload**.
Bei 8-Bit-Samples (1 Byte I, 1 Byte Q) entspricht das **704 IQ-Paaren pro
Paket** — minimaler Overhead, unter der kritischen Fragmentierungsgrenze der
meisten WLAN-Netzwerke. Implementierung:
[`IqDatagram.kt`](../android-app/app/src/main/java/com/example/agent/aura/IqDatagram.kt).

### 3.4 Implementierung in Kotlin unter Nutzung von Coroutines

Netzwerkoperationen laufen im `Dispatchers.IO`-Pool, rechenintensive
Signalverarbeitung (FFT, RTI) auf `Dispatchers.Default`. Der Empfänger nutzt
eine `DatagramSocket` in einer Endlosschleife innerhalb einer Coroutine:

```kotlin
val socket = DatagramSocket(50000)
val buffer = ByteArray(1500)
val packet = DatagramPacket(buffer, buffer.size)

scope.launch(Dispatchers.IO) {
    while (isActive) {
        socket.receive(packet)
        // Schnelle Extraktion des Headers und Weitergabe an den Verarbeitungs-Kanal
        processIncomingData(packet.data.copyOf(packet.length))
    }
}
```

Ein `Channel` mit `BufferOverflow.DROP_OLDEST` stellt sicher, dass die App bei
kurzzeitigen Lastspitzen stets die aktuellsten Funkdaten verarbeitet — eine
flüssige 3D-Visualisierung bleibt gewährleistet. Implementierung:
[`IqTunnelReceiver.kt`](../android-app/app/src/main/java/com/example/agent/aura/IqTunnelReceiver.kt).

## 4. Radio-Tomographische Bildgebung (RTI) und Signalverarbeitung

Das Herzstück der Aura-Plattform ist die Erkennung von Objekten und Bewegungen
hinter Wänden: Die Signaldämpfung wird analysiert, die auftritt, wenn ein Objekt
die Sichtlinie (Line-of-Sight) zwischen einem der vier Sender und den zwei
RTL-SDR-Empfängern kreuzt.

### 4.1 Mathematische Grundlagen der Tomographie

Die Dämpfung $y$ auf einem Link zwischen Sender $i$ und Empfänger $j$ lässt sich
als Integral über das Raumverlustfeld $\phi$ beschreiben:

$$y_{i,j} = \int_{L_{i,j}} \phi(x, y, z)\, ds + n$$

In der diskreten Umsetzung wird der Raum in Voxel unterteilt. Jede Messung
ergibt eine lineare Gleichung. Hunderte Messlinien (während der Bewegung der
Smartphones) ergeben ein überbestimmtes Gleichungssystem, das mittels
**Backprojection** oder **Tikhonov-Regularisierung** gelöst wird:

$$\min_{\phi}\ \|A\phi - y\|_2^2 + \lambda \|\phi\|_2^2 + \gamma\,\phi^T L \phi$$

Der optionale Glättungsterm $\gamma$ (diskreter Graph-Laplacian L über die
6-Nachbarschaft der Voxel) reduziert Rausch-Artefakte in dünn abgedeckten
Regionen (Differenzoperator-Ansatz nach SPIE 8753 — Details in
[VERBESSERUNGEN.md](VERBESSERUNGEN.md)).

Das Ergebnis ist eine 3D-Rekonstruktion der Dämpfungswerte, die als
halbtransparente Voxel visualisiert wird. Implementierung:
[`RtiSolver.kt`](../android-app/app/src/main/java/com/example/agent/aura/RtiSolver.kt)
(Kotlin) und [`rti_solver.py`](../edge-agent/rti_solver.py) (Python-Port für den
Edge-Agent).

### 4.2 Cross-Korrelation und Phasenanalyse

Zur präzisen Distanzmessung vergleicht Aura das Empfangssignal mit einem
bekannten Referenzsignal (Chirp oder PN-Sequenz). Die Kreuzkorrelation im
Frequenzbereich bestimmt Laufzeit und Multipath-Effekte:

$$R(\tau) = \mathcal{F}^{-1}\left\{\ \mathcal{F}\{S_{\text{rx}}\} \cdot
\mathcal{F}\{S_{\text{ref}}\}^*\ \right\}$$

Spitzen in der Korrelationsfunktion deuten auf direkte Pfade und Reflexionen
hin. Durch die Integration in das 3D-Modell unterscheidet Aura statische
Strukturen (Wände) von dynamischen Objekten (Personen). Implementierung:
[`CrossCorrelator.kt`](../android-app/app/src/main/java/com/example/agent/aura/CrossCorrelator.kt).

### 4.3 Sensorfusion und räumliche Kalibrierung

Damit die 3D-Funkkarte mit der realen Welt übereinstimmt, müssen die
IMU-Sensoren des Smartphones präzise mit dem geografischen Koordinatensystem
synchronisiert werden. Das Google Maps 3D SDK nutzt ein rechtshändiges
Koordinatensystem (Z-Achse zum Himmel); Android-Sensoren liefern Daten im
lokalen Gerätesystem, die über Rotationsmatrizen transformiert werden
(`Sensor.TYPE_ROTATION_VECTOR` → absolute Ausrichtung relativ zum magnetischen
Nordpol).

| Parameter | Sensor-Quelle | Maps-3D-Entsprechung |
| :--- | :--- | :--- |
| Azimut (Yaw) | Magnetometer + Accel | Kamera-Heading (0° = Nord) |
| Pitch | Accelerometer | Kamera-Tilt (0° = Draufsicht) |
| Roll | Gyroscope | Kamera-Roll (Rotation um Sichtachse) |

Die Werte werden in Echtzeit an den `CameraController` der Maps-Instanz
übergeben — Grundlage für den **„Röntgenblick"-Modus**, bei dem der Nutzer das
Smartphone gegen eine Wand hält und RTI-Objekte positionsgetreu eingeblendet
bekommt. Implementierung:
[`GeoPoseMapper.kt`](../android-app/app/src/main/java/com/example/agent/aura/GeoPoseMapper.kt).

## 5. Das Gatekeeper-Modul: Sicherheit und Netzwerkanalyse

Der Gatekeeper fungiert als digitaler Schutzschild und überwacht mit der
SDR-Hardware das gesamte Spektrum auf Anomalien.

- **Netzwerkintegrität:** Analyse des Datenverkehrs innerhalb des
  WireGuard-Tunnels (via `VpnService`). IP-Zugriffe, Port-Scans und
  DNS-Anfragen werden in Echtzeit validiert; verdächtige Verbindungen
  (Tracking-Frameworks, unbekannte Steuer-Server) werden blockiert, bevor sie
  die physische Netzwerkschnittstelle verlassen.
- **RF-Anomalieerkennung:** Kontinuierlicher Scan der Bänder um **433 MHz** und
  **868 MHz** (Smart-Home-Sensoren, Alarmanlagen — häufig unverschlüsselt).
  Aura klassifiziert die Signale und warnt vor unautorisierten Sendern; die
  Position wird durch Triangulation auf der 3D-Karte visualisiert.

Implementierung:
[`Gatekeeper.kt`](../android-app/app/src/main/java/com/example/agent/aura/Gatekeeper.kt),
[`RfBandClassifier.kt`](../android-app/app/src/main/java/com/example/agent/aura/RfBandClassifier.kt).

## 6. Zusatzmodule: DAB+, Smart Tags und KI-Integration

| Modul | Funktion | Technologie |
| :--- | :--- | :--- |
| **DAB+ Dekodierung** | Digitalradio-Empfang aus SDR-Rohdaten; Senderstandorte als 3D-Türme (Transparenz = Signalqualität) | libwelle, libdab |
| **Smart Tag Tracking** | Verfolgung von AirTags/Samsung Tags/Tile via BLE und UWB; Live-Geschwindigkeit aus Positionsänderungen als 3D-Vektorgrafik im Scanner-Modul (RC-Leistungsanalyse) | [`TagVelocityTracker.kt`](../android-app/app/src/main/java/com/example/agent/aura/TagVelocityTracker.kt) |
| **KI-Signalklassifizierung** | Unbekannte Signale + Standortkontext („Flughafennähe", „Industriegebiet") → LLM-Einschätzung via Maps Grounding Lite → verständliche Handlungsempfehlungen | Maps Grounding Lite |

## 7. Hardware-Stabilisierung und operative Empfehlungen

| Komponente | Anforderung | Grund |
| :--- | :--- | :--- |
| USB-C Hub | Powered (extern 5 V) | Vermeidung von USB-Buseinbrüchen bei 2× SDR-Last |
| SDR-Empfänger | Nooelec v5 (TCXO) | Thermische Stabilität, geringer Frequenzdrift für die Tomographie |
| Smartphone-CPU | Octa-Core (min. 2,4 GHz) | Echtzeit-FFT und 3D-Rendering benötigen hohe Single-Core-Last |
| Netzwerk | Wi-Fi 6 oder USB-Ethernet | Minimierung des Jitters für den WireGuard-Tunnel |

Ein passiver USB-C-Adapter reicht für den stabilen Betrieb zweier RTL-SDRs nicht
aus: instabile Spannung führt zu Phasenrauschen und macht die RTI-Ergebnisse
unbrauchbar.

---

## 8. Umsetzungsstatus in diesem Repository

Die Aura-Kernmodule sind als Kotlin-Paket `com.example.agent.aura` in der
Android-App sowie als Python-Port im Edge-Agent implementiert und über die
bestehende Datenpipeline, den WebSocket-Kanal und den Web-Visualizer integriert:

| Spezifikations-Komponente | Implementierung | Status |
| :--- | :--- | :--- |
| Aura-Datagramm (12-Byte-Header, MTU 1420, 704 IQ-Paare) | `aura/IqDatagram.kt` | ✅ |
| UDP-Empfang, Coroutines, DROP_OLDEST | `aura/IqTunnelReceiver.kt` | ✅ |
| WireGuard-Schlüssel (RFC 7748) + INI-Konfigurationen | `aura/WireGuardConfig.kt` | ✅ |
| FFT / Spektren | `aura/Fft.kt` | ✅ |
| Cross-Korrelation & Multipath | `aura/CrossCorrelator.kt` | ✅ |
| RTI (Voxel, Ellipsen-Gewichtung, Tikhonov/Backprojection) | `aura/RtiSolver.kt` + `edge-agent/rti_solver.py` | ✅ |
| RF-Bandklassifikation (433/868 MHz) | `aura/RfBandClassifier.kt` | ✅ |
| Gatekeeper (RF-Anomalien + Paketinspektion) | `aura/Gatekeeper.kt` | ✅ |
| Smart-Tag-Geschwindigkeit | `aura/TagVelocityTracker.kt` | ✅ |
| IMU → Maps-Kamerapose (Röntgenblick) | `aura/GeoPoseMapper.kt` | ✅ |
| Heatmap-Extrusion | `aura/RfHeatmapBuilder.kt` | ✅ |
| Pipeline-Integration | `aura/AuraIntegrator.kt` + `LiveSensorPipeline` | ✅ |
| REST-Endpunkte Edge-Agent | `POST /api/v1/aura/rti`, `POST /api/v1/aura/classify` | ✅ |
| 3D-Visualisierung RF-Feld | `web-visualizer/public/main.js` (Voxel-/Heatmap-Layer) | ✅ |
| Google Maps 3D SDK (Android, Experimental) | Datenmodelle vorbereitet; SDK-Integration folgt in Phase „Maps-3D-Preview" (eigenes App-Flavour wegen Preview-Lizenz) | ⏳ |
| SDR-USB-Treiber (RTL-SDR via USB Host) | `IqSource`-Schnittstelle vorbereitet; Treibermodul folgt (libusb-Portierung) | ⏳ |
| WireGuard-Android-Bibliothek (`com.wireguard.android:tunnel`) | Konfigurations-Blueprint vorhanden; VPN-Einbindung folgt mit Flavour `aura-vpn` | ⏳ |
| DAB+ (libwelle/libdab) | Geplant | ⏳ |
| Maps Grounding Lite | Geplant | ⏳ |

### 8.1 Datenfluss

```text
RTL-SDR v5 (2,4 MS/s)          Smartphone B (Scanner)
        │                                │ IQ-Datagramme (UDP, 12-Byte-Header)
        ▼                                ▼
USB-Host (⏳)  ───────────────►  WireGuard-Tunnel (10.0.0.1 ⇄ 10.0.0.2, MTU 1420)
        │                                │
        └────────►  IqTunnelReceiver (Channel, DROP_OLDEST)
                                        │
                       ┌────────────────┼─────────────────┐
                       ▼                ▼                 ▼
                 CrossCorrelator   RfBandClassifier   Gatekeeper
                 (Laufzeit/RTI-    (433/868 MHz)      (Anomalien,
                  Dämpfung)                            Paketinspektion)
                       │
                       ▼
                 RtiSolver (Voxel, Tikhonov) ──► RTI-Voxel
                       │
                       ▼
        LiveSensorPipeline ──► Edge-Agent (REST/WebSocket)
                                        │
                                        ▼
                              Web-Visualizer (Three.js: Voxel-/Heatmap-Layer)
```

### 8.2 Rechtlicher Hinweis

Der Empfang der ISM-Bänder 433 MHz und 868 MHz ist in Deutschland
frequenzrechtlich zulässig; die Entschlüsselung oder das Stören fremder
Funkkommunikation nicht. Das Gatekeeper-Modul dient ausschließlich der
passiven Spektrumsbeobachtung und der Absicherung des eigenen Geräts.
