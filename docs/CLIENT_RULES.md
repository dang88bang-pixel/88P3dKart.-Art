# 📡 Regelwerk für Client-Geräte (v4.4.0-ClientRules)

Spezifikation zur Anbindung, Authentifizierung, Signalauswertung und Integration
beliebiger externer Geräte in die 3dxAgent-Plattform.

## Client-Klassifizierung

| Typ | Beschreibung | Daten | Frequenz |
|-----|--------------|-------|----------|
| `MASTER` | CT45P mit voller Sensorik | Alle | 20 Hz |
| `RELAY` | Smartphone (nur BLE/IMU) | RSSI, IMU | 5 Hz |
| `SENSOR` | IoT-Sensor (Umwelt) | Temp, Feuchte, Luft | 1 Hz |
| `GATEWAY` | Drahtlos-Gateway (Wi-Fi/LoRa/Zigbee) | Netzwerk | 0.1 Hz |
| `WEARABLE` | Smartwatch/Tracker | IMU, Bio | 10 Hz |
| `TOKEN` | nRF52840 + BMI270 | RSSI, IMU, Batterie | 1 Hz |

## Verbindung & Authentifizierung

| Protokoll | Port | Verschlüsselung |
|-----------|------|-----------------|
| REST (HTTPS) | 8081 | TLS 1.2+ |
| WebSocket (WSS) | 8080 | TLS 1.2+ |
| MQTT (MQTTS) | 1883 | TLS 1.2+ |
| BLE | – | Secure Pairing |

Mechanismen: API-Key, JWT, mTLS, BLE-Pairing.

## Signalqualität

```
Q_total = 0.4·Q_snr + 0.3·Q_conf + 0.2·Q_latency + 0.1·Q_dup
```

Signale mit `Q < 0.5` werden verworfen. Semantische Interpretation: LiDAR → Geometrie,
mmWave → Person, BLE → Beacon, IMU → Bewegung, Umwelt → Kontext.

## Implementierung (Kotlin, `com.example.agent.network`)

- `ClientModels.kt` — `ClientType`, `SensorType`, `ClientCapabilities`, `ClientRegistration`, `ClientSignal`
- `ClientRegistry.kt` — Registrierung & Signale
- `ClientConnectionManager.kt` — WebSocket/MQTT-Sessions
- `ClientHealthEvaluator.kt` — Health-Score (Qualität 40 %, Verbindung 30 %, Batterie 20 %, Latenz 10 %)
- `ClientRecoveryManager.kt` — Backoff-Recovery (1s, 2s, 4s, 8s, 30s)
- `pipeline/SignalInterpreter.kt` — Qualitätsbewertung + Semantik
- `pipeline/DataIntegrator.kt` — EKF-/Mesh-/Semantik-Integration
- `sensors/NetworkDataCollector.kt` — Wi-Fi-/Mobilfunk-Daten

> **Hinweis:** Die REST-Endpunkte für Clients sind im `offline/LocalApiServer.kt`
> (JDK-`ServerSocket`) umgesetzt; die im Spezifikationsdokument verwendeten
> Spring-`@RestController`-Annotationen laufen nicht auf Android und wurden daher
> bewusst ersetzt.
