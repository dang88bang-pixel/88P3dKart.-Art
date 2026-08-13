"""ICP-Map-Merging (Kabsch-Umeyama) für Punktwolken mehrerer CT45P."""
import logging

import numpy as np
from scipy.spatial import KDTree

logger = logging.getLogger(__name__)


class ICPMerger:
    """Führt zwei Punktwolken mit Iterative Closest Point zusammen."""

    @staticmethod
    def kabsch_umeyama(A: np.ndarray, B: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """Optimale Rotation R und Translation t mit A → B (SVD / Kabsch-Umeyama)."""
        A = np.asarray(A, dtype=float)
        B = np.asarray(B, dtype=float)
        assert A.shape == B.shape, "Punktmengen müssen dieselbe Form haben"

        mu_A = A.mean(axis=0)
        mu_B = B.mean(axis=0)

        AA = A - mu_A
        BB = B - mu_B

        H = AA.T @ BB
        U, _, Vt = np.linalg.svd(H)
        V = Vt.T
        R = V @ U.T

        # Reflexion vermeiden
        if np.linalg.det(R) < 0:
            V[:, -1] *= -1
            R = V @ U.T

        t = mu_B - R @ mu_A
        return R, t

    @staticmethod
    def icp(
        source: np.ndarray,
        target: np.ndarray,
        max_iterations: int = 50,
        tolerance: float = 1e-6,
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """Passt `source` an `target` an.

        Rückgabe: (transformierte Punktwolke, kumulierte Rotation, kumulierte Translation).
        """
        src = np.asarray(source, dtype=float).copy()
        tgt = np.asarray(target, dtype=float)
        if src.ndim == 1:
            src = src.reshape(-1, 3)
        if tgt.ndim == 1:
            tgt = tgt.reshape(-1, 3)

        R_total = np.eye(3)
        t_total = np.zeros(3)
        prev_error = 0.0

        tree = KDTree(tgt)
        for _ in range(max_iterations):
            distances, indices = tree.query(src)
            matched = tgt[indices]

            R, t = ICPMerger.kabsch_umeyama(src, matched)
            src = (R @ src.T).T + t

            R_total = R @ R_total
            t_total = R @ t_total + t

            mean_error = float(np.mean(distances))
            if abs(prev_error - mean_error) < tolerance:
                break
            prev_error = mean_error

        return src, R_total, t_total
