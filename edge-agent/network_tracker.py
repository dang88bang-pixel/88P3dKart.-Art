"""Live-Netzwerk-Geräte-Tracker — Python-Kern (docs/TACTICAL.md).

Portierung der Change-/Anomalie-Erkennung aus der v9.1/9.3-Spezifikation
(LiveNetworkScanner.detectChanges, NetworkAnalyzer.detectAnomalies) —
als reiner, testbarer Kern. Das Scannen selbst übernehmen auf dem Gerät die
bestehenden Module (WifiRttTriangulator, BleBeaconTriangulator,
NetworkDataCollector).
"""

from __future__ import annotations

from collections import deque
from typing import Any, Dict, List, Optional

DEFAULT_SIGNAL_CHANGE_DBM = 10.0
DEFAULT_ANOMALY_DEVIATION_DBM = 20.0
DEFAULT_HISTORY_CAP = 1000


class DeviceTracker:
    """Erkennt hinzugekommene/verschwundene Geräte, Signalsprünge und Anomalien."""

    def __init__(
        self,
        signal_change_threshold_dbm: float = DEFAULT_SIGNAL_CHANGE_DBM,
        anomaly_deviation_dbm: float = DEFAULT_ANOMALY_DEVIATION_DBM,
        anomaly_window: int = 10,
        history_cap: int = DEFAULT_HISTORY_CAP,
    ) -> None:
        self.signal_change_threshold_dbm = signal_change_threshold_dbm
        self.anomaly_deviation_dbm = anomaly_deviation_dbm
        self.anomaly_window = anomaly_window
        self.history_cap = history_cap
        self._devices: Dict[str, Dict[str, Any]] = {}
        self._history: Dict[str, deque] = {}

    def update(self, devices: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Verarbeitet einen Scan-Zyklus; Rückgabe: added/removed/signal_changes/anomalies."""
        devices = [dict(d) for d in devices]
        by_id = {str(d["id"]): d for d in devices}

        added = [
            d for device_id, d in by_id.items() if device_id not in self._devices
        ]
        removed = [
            self._devices[device_id]
            for device_id in list(self._devices)
            if device_id not in by_id
        ]

        signal_changes: List[Dict[str, Any]] = []
        for device_id, device in by_id.items():
            cached = self._devices.get(device_id)
            if cached is not None:
                diff = abs(float(cached.get("rssi", 0.0)) - float(device.get("rssi", 0.0)))
                if diff > self.signal_change_threshold_dbm:
                    signal_changes.append(
                        {
                            "id": device_id,
                            "old_rssi": cached.get("rssi"),
                            "new_rssi": device.get("rssi"),
                            "diff": diff,
                        }
                    )

        anomalies: List[Dict[str, Any]] = []
        for device_id, device in by_id.items():
            history = self._history.setdefault(device_id, deque(maxlen=self.anomaly_window))
            history.append(float(device.get("rssi", 0.0)))
            if len(history) >= self.anomaly_window:
                avg = sum(history) / len(history)
                deviation = abs(float(device.get("rssi", 0.0)) - avg)
                if deviation > self.anomaly_deviation_dbm:
                    anomalies.append(
                        {
                            "id": device_id,
                            "avg_rssi": round(avg, 1),
                            "current_rssi": device.get("rssi"),
                            "deviation": round(deviation, 1),
                            "severity": "high" if deviation > 1.5 * self.anomaly_deviation_dbm else "medium",
                        }
                    )

        # Cache + Historie aktualisieren
        self._devices = by_id
        for device_id in list(self._history):
            if device_id not in by_id and len(self._history) > self.history_cap:
                self._history.pop(device_id, None)

        return {
            "added": added,
            "removed": removed,
            "signal_changes": signal_changes,
            "anomalies": anomalies,
            "device_count": len(devices),
        }

    def known_devices(self) -> Dict[str, Dict[str, Any]]:
        return dict(self._devices)

    def clear(self) -> None:
        self._devices.clear()
        self._history.clear()
