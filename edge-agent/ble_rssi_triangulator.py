"""BLE-RSSI-Triangulation — Python-Port der Kotlin-Module
(`com.example.agent.triangulation.BleBeaconTriangulator` +
`BleRadioBackend`, docs/TRIANGULATION.md §4/§5).

**Alternative A zu Wi-Fi RTT** für die Edge-Agent-Seite: pro Anker
(Beacon mit bekannter Position) wird der RSSI über eine wählbare
Filterstrategie geglättet (EMA / Median / 1D-Kalman) und mit einem
individuell kalibrierbaren Path-Loss-Modell in eine Distanz umgerechnet.
Sobald ≥ 3 Anker frische Messwerte liefern, löst `solve_trilateration`
(edge-agent/trilateration.py, inkl. Reject-and-Resolve-Ausreißerbehandlung)
die Position.

Enthält:
- `BeaconAnchor` — Anker mit bekannter Position und eigenem Pfadmodell,
- `RssiSmoother`, `RssiMedianFilter`, `KalmanRssiAdapter` — Filterstrategien
  (Interface `RssiFilter`), formatgleich zur Kotlin-Variante,
- `BleScanBackend`-Protokoll + `CallbackBleBackend` (austauschbarer
  Scan-Kanal; Hardware-Backends wie Bluetooth-Dongle oder MQTT-Feed
  implementieren das Protokoll),
- `BleBeaconTriangulator` — Anker-Registry, Frischeprüfung (2 s),
  Distanzschätzung, Trilateration und `PositionEstimate`-Erzeugung.

Genauigkeit: typisch 3–8 m (stark multipath-abhängig) — dient als
Sekundärquelle neben Wi-Fi RTT und wird im `TriangulationService`
(EstimateGate, Mahalanobis, inverse Varianz) fusioniert.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Protocol

from trilateration import RssiKalmanFilter, rssi_to_distance, solve_trilateration

log = logging.getLogger("ble_rssi_triangulator")

# Frische-Schwelle für Anker-RSSI-Werte (2 s) — identisch zu Kotlin.
ANCHOR_FRESHNESS_MS = 2_000

# Basis-Unsicherheit der RSSI-Distanzschätzung in Metern.
BASE_ACCURACY_M = 3.0

# Plausibilitätsfenster für Distanzen (Filter gegen Fehlmessungen).
MIN_DISTANCE_M = 0.05
MAX_DISTANCE_M = 100.0

# Obergrenze der geschätzten Genauigkeit.
MAX_ACCURACY_M = 12.0


@dataclass
class BeaconAnchor:
    """Anker (Beacon) mit bekannter Position und eigenem Pfadmodell."""

    id: str
    mac: str
    x: float
    y: float
    z: float = 0.0
    # Log-Distance-Path-Loss-Modell (RSSI₀ bei 1 m, Exponent n).
    reference_rssi_dbm: float = -59.0
    path_loss_exponent: float = 2.8


@dataclass
class PositionEstimate:
    """Geschätzte Position aus der BLE-RSSI-Triangulation.

    Feldnamen/Struktur analog zur Kotlin-`PositionEstimate`-Klasse
    (Source `BLE_RSSI`), damit der Agent die Werte 1:1 in die
    WebSocket-/REST-Nachrichten übernehmen kann.
    """

    timestamp_ms: int
    source: str  # "BLE_RSSI"
    x: float
    y: float
    z: float
    accuracy_m: float
    confidence: float
    detail: str


class RssiFilter(Protocol):
    """Filterstrategie je Sender-MAC — glättet RSSI-Jitter vor der
    Distanzschätzung (Kotlin-Äquivalent: `RssiFilter`-Interface)."""

    def smooth(self, key: str, rssi_dbm: float) -> float:
        """Liefert den gefilterten RSSI-Wert für `key`."""
        ...

    def value(self, key: str) -> Optional[float]:
        """Zuletzt gefilterter Wert (oder None)."""
        ...

    def clear(self, key: str) -> None:
        ...


class RssiSmoother:
    """Exponentiell gleitender Mittelwert (EMA) — Standardstrategie.

    Kotlin-Äquivalent: `RssiSmoother(alpha = 0.6f)`.
    """

    def __init__(self, alpha: float = 0.6) -> None:
        self.alpha = alpha
        self._values: Dict[str, float] = {}

    def smooth(self, key: str, rssi_dbm: float) -> float:
        prev = self._values.get(key, rssi_dbm)
        nxt = self.alpha * rssi_dbm + (1.0 - self.alpha) * prev
        self._values[key] = nxt
        return nxt

    def value(self, key: str) -> Optional[float]:
        return self._values.get(key)

    def clear(self, key: str) -> None:
        self._values.pop(key, None)

    def clear_all(self) -> None:
        self._values.clear()


class RssiMedianFilter:
    """Gleitender Median-Filter je MAC — unterdrückt RSSI-Spikes
    (Multipath-Ausreißer); vgl. MDPI Sensors 2025, 25(9):2834.

    Kotlin-Äquivalent: `RssiMedianFilter(window = 5)`.
    """

    def __init__(self, window: int = 5) -> None:
        self.window = window
        self._buffers: Dict[str, List[float]] = {}

    def smooth(self, key: str, rssi_dbm: float) -> float:
        buf = self._buffers.setdefault(key, [])
        buf.append(rssi_dbm)
        if len(buf) > self.window:
            # Nur die letzten `window` Werte behalten (Fenster verschieben).
            del buf[: len(buf) - self.window]
        sorted_vals = sorted(buf)
        n = len(sorted_vals)
        if n % 2 == 1:
            return float(sorted_vals[n // 2])
        return (sorted_vals[n // 2 - 1] + sorted_vals[n // 2]) / 2.0

    def value(self, key: str) -> Optional[float]:
        buf = self._buffers.get(key)
        return float(buf[-1]) if buf else None

    def clear(self, key: str) -> None:
        self._buffers.pop(key, None)

    def clear_all(self) -> None:
        self._buffers.clear()


class KalmanRssiAdapter:
    """Adapter auf `trilateration.RssiKalmanFilter` (1D-Kalman je MAC,
    q = Prozess-, r = Messrauschen) — gleiche Numerik wie die
    Kotlin-Variante `RssiKalmanFilter`."""

    def __init__(self, q: float = 4.0, r: float = 16.0) -> None:
        self._kalman = RssiKalmanFilter(q=q, r=r)

    def smooth(self, key: str, rssi_dbm: float) -> float:
        return self._kalman.filter(key, rssi_dbm)

    def value(self, key: str) -> Optional[float]:
        return self._kalman.value(key)

    def clear(self, key: str) -> None:
        self._kalman.clear(key)


class BleScanBackend(Protocol):
    """Abstraktion des BLE-Funkkanals (Kotlin: `BleRadioBackend`).

    Der CT45P besitzt Bluetooth 5.1 plus eine optionale zweite
    BLE-Schnittstelle; auf der Edge-Agent-Seite kann der Scan-Kanal ein
    USB-Dongle, ein MQTT-BLE-Feed oder ein Simulator sein.
    """

    @property
    def available(self) -> bool:
        ...

    def start_scan(self, on_result: Callable[[str, float], None]) -> bool:
        """Startet den Scan; `on_result(mac_upper, rssi_dbm)` je Paket.

        Returns:
            True, wenn der Scan gestartet wurde.
        """
        ...

    def stop_scan(self) -> None:
        ...


class CallbackBleBackend:
    """Backend, das einen externen Scan-Kanal kapselt.

    Der Aufrufer liefert frische (mac, rssi)-Messungen über `on_scan_result`
    (z. B. aus einer MQTT-Bridge, einem asynchronen Scanner oder einem
    Simulator) — der Triangulator bleibt hardware-agnostisch.
    """

    def __init__(self, on_result: Optional[Callable[[str, float], None]] = None) -> None:
        self._on_result = on_result
        self._running = False

    @property
    def available(self) -> bool:
        return True

    def start_scan(self, on_result: Callable[[str, float], None]) -> bool:
        self._on_result = on_result
        self._running = True
        return True

    def stop_scan(self) -> None:
        self._running = False

    def on_scan_result(self, mac: str, rssi_dbm: float) -> None:
        """Externer Einspeisepunkt: leitet eine Messung an den Triangulator weiter."""
        if self._running and self._on_result is not None:
            self._on_result(mac.upper(), rssi_dbm)


class BleBeaconTriangulator:
    """BLE-RSSI-Triangulation über den dedizierten Scan-Kanal.

    Kotlin-Äquivalent: `BleBeaconTriangulator` (docs/TRIANGULATION.md §4).
    """

    def __init__(
        self,
        backend: Optional[BleScanBackend] = None,
        rssi_filter: RssiFilter = RssiSmoother(),
        on_estimate: Optional[Callable[[PositionEstimate], None]] = None,
    ) -> None:
        self._backend = backend
        self._rssi_filter = rssi_filter
        self._on_estimate = on_estimate
        self._anchors_by_mac: Dict[str, BeaconAnchor] = {}
        self._rssi_by_mac: Dict[str, float] = {}
        self._rssi_time_by_mac: Dict[str, int] = {}
        self._running = False
        self._last_estimate: Optional[PositionEstimate] = None

    # ─── Konfiguration ──────────────────────────────────────────────────

    def set_anchors(self, anchors: List[BeaconAnchor]) -> None:
        """Setzt die Anker-Konfiguration (ersetzt bestehende)."""
        self._anchors_by_mac = {a.mac.upper(): a for a in anchors}

    @property
    def anchors(self) -> List[BeaconAnchor]:
        return list(self._anchors_by_mac.values())

    @property
    def last_estimate(self) -> Optional[PositionEstimate]:
        return self._last_estimate

    # ─── Lebenszyklus ───────────────────────────────────────────────────

    def start(self) -> bool:
        """Startet den Scan über das Backend (falls vorhanden)."""
        if self._running:
            return True
        if self._backend is None:
            log.warning("Kein Scan-Backend konfiguriert — nur manuelle Einspeisung")
            self._running = True
            return True
        ok = self._backend.start_scan(self._handle_scan_result)
        self._running = ok
        log.info("BLE-Triangulation gestartet (Backend verfügbar: %s)", self._backend.available)
        return ok

    def stop(self) -> None:
        self._running = False
        if self._backend is not None:
            self._backend.stop_scan()

    # ─── Messwerte ──────────────────────────────────────────────────────

    def on_scan_result(self, mac: str, rssi_dbm: float) -> None:
        """Verarbeitet eine BLE-Scan-Messung (mac, rssi) — public Entry.

        Wird vom Backend-Callback oder direkt (MQTT-Feed, Simulator)
        aufgerufen. Unbekannte MACs werden ignoriert.
        """
        if not self._running:
            return
        self._handle_scan_result(mac.upper(), rssi_dbm)

    def _handle_scan_result(self, mac: str, rssi_dbm: float) -> None:
        if mac not in self._anchors_by_mac:
            return
        smoothed = self._rssi_filter.smooth(mac, rssi_dbm)
        self._rssi_by_mac[mac] = smoothed
        self._rssi_time_by_mac[mac] = int(time.time() * 1000)
        self._evaluate()

    def current_rssi(self) -> Dict[str, float]:
        """Aktuelle geglättete RSSI-Werte je Anker-MAC (für UI/Diagnose)."""
        return dict(self._rssi_by_mac)

    # ─── Auswertung ─────────────────────────────────────────────────────

    def _evaluate(self) -> None:
        """≥ 3 frische Anker → Distanzen → Trilateration → Estimate."""
        now = int(time.time() * 1000)
        fresh = {
            mac: anchor
            for mac, anchor in self._anchors_by_mac.items()
            if now - self._rssi_time_by_mac.get(mac, 0) <= ANCHOR_FRESHNESS_MS
        }
        if len(fresh) < 3:
            return

        distances: Dict[str, float] = {}
        for mac, anchor in fresh.items():
            rssi = self._rssi_by_mac.get(mac)
            if rssi is None:
                continue
            d = rssi_to_distance(
                rssi,
                reference_rssi_dbm=anchor.reference_rssi_dbm,
                path_loss_exponent=anchor.path_loss_exponent,
            )
            if d is not None and MIN_DISTANCE_M <= d <= MAX_DISTANCE_M:
                distances[anchor.id] = d
        if len(distances) < 3:
            return

        anchors = list(fresh.values())
        use_z = any(a.z != 0.0 for a in anchors)
        result = solve_trilateration(
            anchors=[{"id": a.id, "x": a.x, "y": a.y, "z": a.z} for a in anchors],
            distances=distances,
            use_z=use_z,
        )
        if result is None:
            return

        mean_distance = sum(distances.values()) / len(distances)
        estimate = PositionEstimate(
            timestamp_ms=now,
            source="BLE_RSSI",
            x=result["x"],
            y=result["y"],
            z=result["z"],
            accuracy_m=min(BASE_ACCURACY_M + 0.15 * mean_distance, MAX_ACCURACY_M),
            confidence=result.get("confidence", 0.0),
            detail=(
                f"BLE: {len(distances)} Anker, Ø-Distanz {mean_distance:.1f} m"
                + (f", {result['rejected_anchors']} Anker verworfen" if result.get("rejected_anchors") else "")
            ),
        )
        self._last_estimate = estimate
        if self._on_estimate is not None:
            try:
                self._on_estimate(estimate)
            except Exception:  # pragma: no cover — Abnehmer-Fehler isolieren
                log.exception("on_estimate-Callback fehlgeschlagen")


# ─── Selbsttest / Demo ───────────────────────────────────────────────────

def _demo() -> None:
    """Simulierte Anker + Messwerte → Position (4 Anker, 2D)."""

    anchors = [
        BeaconAnchor(id="A", mac="AA:BB:CC:00:00:01", x=0.0, y=0.0),
        BeaconAnchor(id="B", mac="AA:BB:CC:00:00:02", x=10.0, y=0.0),
        BeaconAnchor(id="C", mac="AA:BB:CC:00:00:03", x=10.0, y=10.0),
        BeaconAnchor(id="D", mac="AA:BB:CC:00:00:04", x=0.0, y=10.0),
    ]
    backend = CallbackBleBackend()
    tri = BleBeaconTriangulator(backend=backend, rssi_filter=RssiSmoother(alpha=0.6))
    tri.set_anchors(anchors)
    tri.start()

    # Wahrer Standort (3.5, 2.5) — RSSI aus inversem Path-Loss, leicht verrauscht.
    import random

    rng = random.Random(7)
    truth = (3.5, 2.5)
    for a in anchors:
        d = ((a.x - truth[0]) ** 2 + (a.y - truth[1]) ** 2) ** 0.5
        rssi = -59.0 - 10 * 2.8 * math.log10(max(d, 1e-3)) + rng.gauss(0, 2.0)
        backend.on_scan_result(a.mac, rssi)

    est = tri.last_estimate
    if est is None:
        print("Keine Schätzung (zu wenige frische Anker)")
        return
    err = ((est.x - truth[0]) ** 2 + (est.y - truth[1]) ** 2) ** 0.5
    print(
        f"Position: ({est.x:.2f}, {est.y:.2f}) — Fehler {err:.2f} m, "
        f"Konfidenz {est.confidence:.2f}, Genauigkeit ±{est.accuracy_m:.1f} m"
    )
    print(est.detail)


if __name__ == "__main__":
    import math

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    _demo()
