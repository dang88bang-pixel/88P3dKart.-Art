"""Adaptiver 6-DOF Extended Kalman Filter (NumPy)."""
from dataclasses import dataclass

import numpy as np


@dataclass
class EkfState:
    x: float = 0.0
    y: float = 0.0
    z: float = 0.0
    vx: float = 0.0
    vy: float = 0.0
    vz: float = 0.0


class AdaptiveEKF:
    """6-DOF EKF mit adaptivem Messrauschen.

    Zustand:  [x, y, z, vx, vy, vz]
    Prädiktion: konstantes Geschwindigkeitsmodell.
    Update: Positionsmessungen von LiDAR und mmWave (H = I auf Positionsteil).
    """

    def __init__(self, dt: float = 0.05):
        self.dt = dt
        self.n = 6
        self.m = 3

        self.x = np.zeros(6)
        self.P = np.diag([1.0, 1.0, 1.0, 0.25, 0.25, 0.25])

        # Prozessrauschen Q (konstant)
        self.Q = np.diag([0.01, 0.01, 0.01, 0.1, 0.1, 0.1])

        # Messrauschen (adaptiv)
        self.R_lidar = np.eye(3) * 0.1
        self.R_mmwave = np.eye(3) * 0.3
        self.scattering_scale = 1.0

    def adapt_to_environment(self, scattering_detected: bool, thermal_c: float) -> None:
        """Passt das LiDAR-Rauschen dynamisch an (Rauch/Staub → mmWave übernimmt)."""
        self.scattering_scale = 1000.0 if scattering_detected else 1.0

        # Thermisches Rauschen: +5 % pro °C über 60 °C
        thermal_factor = 1.0 + max(0.0, (thermal_c - 60.0) * 0.05)
        lidar_noise = 0.1 * self.scattering_scale * thermal_factor
        self.R_lidar = np.eye(3) * lidar_noise
        # mmWave bleibt stabil
        self.R_mmwave = np.eye(3) * 0.3

    def predict(self) -> None:
        """Prädiktion mit konstantem Geschwindigkeitsmodell."""
        F = np.eye(6)
        for i in range(3):
            F[i, i + 3] = self.dt
        self.x = F @ self.x
        self.P = F @ self.P @ F.T + self.Q

    def update_lidar(self, z: np.ndarray) -> None:
        """LiDAR-Messupdate (3D-Position)."""
        self._update(np.asarray(z, dtype=float)[:3], self.R_lidar)

    def update_mmwave(self, z: np.ndarray) -> None:
        """mmWave-Messupdate (3D-Position)."""
        self._update(np.asarray(z, dtype=float)[:3], self.R_mmwave)

    def _update(self, z: np.ndarray, R: np.ndarray) -> None:
        """Generischer Kalman-Update auf den Positionsteil (3 Messwerte)."""
        # H projiziert den Zustand auf die Position (nur x,y,z gemessen)
        H = np.zeros((3, 6))
        H[0, 0] = H[1, 1] = H[2, 2] = 1.0

        y = z - H @ self.x          # Innovation
        S = H @ self.P @ H.T + R    # Innovationskovarianz
        K = self.P @ H.T @ np.linalg.inv(S)  # Kalman-Gain (3x3)

        self.x = self.x + K @ y
        I = np.eye(6)
        self.P = (I - K @ H) @ self.P

    def get_state(self) -> EkfState:
        return EkfState(
            x=float(self.x[0]), y=float(self.x[1]), z=float(self.x[2]),
            vx=float(self.x[3]), vy=float(self.x[4]), vz=float(self.x[5]),
        )

    def get_kalman_gain_lidar(self) -> float:
        """Kalman-Gain der LiDAR-Messung (0-1, Indikator für Vertrauen)."""
        p = self.P[0, 0]
        r = self.R_lidar[0, 0]
        return p / (p + r) if (p + r) > 0 else 0.0
