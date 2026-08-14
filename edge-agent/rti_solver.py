"""Aura RTI-Solver — Python-Port des Kotlin-Moduls `aura/RtiSolver.kt`.

Radio-Tomographische Bildgebung (RTI): Rekonstruktion des Raumverlustfelds
phi aus Dämpfungsmessungen y_i entlang Messlinien (Sender → Empfänger):

    y = A·phi + n

Gelöst per Tikhonov-Regularisierung (matrixfrei über scipy.sparse CG) oder
Backprojection (Echtzeit-Vorschau). Datenmodell und Gewichtungsmodell
(normalisiertes Ellipsen-Modell) sind identisch zur Kotlin-Implementierung,
sodass CT45P-App und Edge-Agent austauschbar rechnen.

Siehe docs/AURA.md §4.1.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

import numpy as np

logger = logging.getLogger(__name__)

try:
    from scipy.sparse import eye as sparse_eye
    from scipy.sparse import lil_matrix
    from scipy.sparse.linalg import cg as sparse_cg

    _HAS_SCIPY = True
except ImportError:  # pragma: no cover
    _HAS_SCIPY = False


@dataclass
class Link:
    """Eine Messlinie zwischen Sender und Empfänger inkl. gemessener Dämpfung."""

    tx: Tuple[float, float, float]
    rx: Tuple[float, float, float]
    attenuation_db: float

    @property
    def length(self) -> float:
        tx, rx = np.asarray(self.tx), np.asarray(self.rx)
        return float(np.linalg.norm(tx - rx))


@dataclass
class Voxel:
    """Rekonstruiertes Voxel des Dämpfungsfelds."""

    index: int
    x: float
    y: float
    z: float
    attenuation: float
    weight: float = 1.0


class RtiSolver:
    """Voxel-basierte RTI-Rekonstruktion (Tikhonov + Backprojection)."""

    MAX_CG_ITERATIONS = 500
    CG_TOLERANCE = 1e-6

    def __init__(
        self,
        bounds_min: Tuple[float, float, float],
        bounds_max: Tuple[float, float, float],
        voxel_size: float,
        ellipse_width: float = 0.05,
        regularization: float = 0.1,
    ):
        if voxel_size <= 0:
            raise ValueError("voxel_size muss > 0 sein")
        self.bounds_min = np.asarray(bounds_min, dtype=float)
        self.bounds_max = np.asarray(bounds_max, dtype=float)
        self.voxel_size = float(voxel_size)
        self.ellipse_width = float(ellipse_width)
        self.regularization = float(regularization)

        self.nx, self.ny, self.nz = tuple(
            max(1, int(np.ceil((self.bounds_max[i] - self.bounds_min[i]) / voxel_size)))
            for i in range(3)
        )
        if self.nx * self.ny * self.nz > 500_000:
            raise ValueError("Voxelgitter zu groß (> 500_000 Voxel)")
        self.links: List[Link] = []

    @property
    def voxel_count(self) -> int:
        return self.nx * self.ny * self.nz

    @property
    def link_count(self) -> int:
        return len(self.links)

    def add_link(self, link: Link) -> None:
        self.links.append(link)

    def clear_links(self) -> None:
        self.links.clear()

    def voxel_center(self, index: int) -> np.ndarray:
        """Voxel-Mittelpunkt (Weltkoordinaten) für einen linearen Index."""
        iz = index // (self.nx * self.ny)
        rem = index % (self.nx * self.ny)
        iy = rem // self.nx
        ix = rem % self.nx
        return self.bounds_min + (np.array([ix, iy, iz], dtype=float) + 0.5) * self.voxel_size

    def _voxel_grid(self) -> np.ndarray:
        """Zentren aller Voxel als (n, 3)-Array."""
        n = self.voxel_count
        centers = np.empty((n, 3), dtype=float)
        for i in range(n):
            centers[i] = self.voxel_center(i)
        return centers

    # ── Gewichtungsmodell ─────────────────────────────────────────────

    def build_weights(self) -> np.ndarray:
        """Normalisiertes Ellipsen-Gewichtungsmodell als (m, n)-Sparse-Matrix.

        w_i,j(v) = 1 / sqrt(d_tx(v) + d_rx(v))  falls d_tx + d_rx < d_link + lambda_w
        w_i,j(v) = 0                           sonst
        Anschließend zeilennormiert.
        """
        if not self.links:
            return np.zeros((0, self.voxel_count))
        centers = self._voxel_grid()
        matrix = lil_matrix((self.link_count, self.voxel_count), dtype=float)

        for i, link in enumerate(self.links):
            tx = np.asarray(link.tx, dtype=float)
            rx = np.asarray(link.rx, dtype=float)
            d_link = float(np.linalg.norm(tx - rx))
            d_tx = np.linalg.norm(centers - tx, axis=1)
            d_rx = np.linalg.norm(centers - rx, axis=1)
            inside = (d_tx + d_rx) < (d_link + self.ellipse_width)
            if not np.any(inside):
                continue
            w = np.zeros_like(d_tx)
            w[inside] = 1.0 / np.sqrt(d_tx[inside] + d_rx[inside])
            w /= w.sum()
            idx = np.nonzero(inside)[0]
            matrix[i, idx] = w[idx]
        return matrix.tocsr()

    # ── Löser ────────────────────────────────────────────────────────

    def solve(self) -> List[Voxel]:
        """Tikhonov-Lösung: min ||A·phi − y||² + λ·||phi||² (CG, matrixfrei)."""
        if not self.links:
            return []
        a = self.build_weights()
        y = np.asarray([l.attenuation_db for l in self.links], dtype=float)
        b = a.T @ y

        if _HAS_SCIPY and self.voxel_count <= 20_000:
            phi, _info = sparse_cg(
                a.T @ a + self.regularization * sparse_eye(self.voxel_count, format="csr"),
                b,
                tol=self.CG_TOLERANCE,
                atol=0,
                maxiter=self.MAX_CG_ITERATIONS,
            )
        else:
            phi = self._conjugate_gradient_matrix_free(a, b)

        return self._build_field(np.asarray(phi, dtype=float))

    def solve_backprojection(self) -> List[Voxel]:
        """Backprojection: phi_v = Σ_i w_i,v·y_i / Σ_i w_i,v (Echtzeit-Vorschau)."""
        if not self.links:
            return []
        a = self.build_weights()
        y = np.asarray([l.attenuation_db for l in self.links], dtype=float)
        field = np.asarray(a.T @ y).ravel()
        weight_sum = np.asarray(a.T @ np.ones(self.link_count)).ravel()
        mask = weight_sum > 0
        field[mask] /= weight_sum[mask]
        return self._build_field(field)

    def _conjugate_gradient_matrix_free(self, a, b: np.ndarray) -> np.ndarray:
        """Matrixfreies CG für (AᵀA + λI)·x = b — für große Voxelgitter."""
        n = self.voxel_count
        x = np.zeros(n)
        r = b.copy()
        p = r.copy()
        rs_old = float(r @ r)
        tol_sq = self.CG_TOLERANCE ** 2 * max(1.0, rs_old)
        for _ in range(self.MAX_CG_ITERATIONS):
            ap = a.T @ (a @ p) + self.regularization * p
            alpha = rs_old / float(p @ ap)
            x += alpha * p
            r -= alpha * ap
            rs_new = float(r @ r)
            if rs_new <= tol_sq:
                break
            p = r + (rs_new / rs_old) * p
            rs_old = rs_new
        return x

    def _build_field(self, field: np.ndarray) -> List[Voxel]:
        field = np.asarray(field, dtype=float).ravel()
        result: List[Voxel] = []
        for i in range(self.voxel_count):
            c = self.voxel_center(i)
            result.append(
                Voxel(
                    index=i,
                    x=float(c[0]),
                    y=float(c[1]),
                    z=float(c[2]),
                    attenuation=float(field[i]),
                )
            )
        return result

    def locate_peaks(
        self,
        field: List[Voxel],
        top_k: int = 8,
        min_separation_voxels: int = 2,
    ) -> List[Voxel]:
        """Lokale Maxima des Dämpfungsfelds (Objekt-/Personen-Kandidaten)."""
        if not field:
            return []
        threshold = max(v.attenuation for v in field) * 0.3
        candidates = sorted(
            (v for v in field if v.attenuation >= threshold),
            key=lambda v: v.attenuation,
            reverse=True,
        )
        peaks: List[Voxel] = []
        sep = min_separation_voxels * self.voxel_size
        for candidate in candidates:
            c = self.voxel_center(candidate.index)
            too_close = any(
                np.max(np.abs(c - self.voxel_center(p.index))) < sep for p in peaks
            )
            if not too_close:
                peaks.append(candidate)
                if len(peaks) >= top_k:
                    break
        return peaks


@dataclass
class RfSample:
    """Eine RF-Leistungsmessung mit Position (Heatmap-Basis)."""

    timestamp_ms: int
    x: float
    y: float
    z: float
    dbm: float
    frequency_hz: float
    sample_count: int = 1


@dataclass
class ExtrudedCell:
    """Extrudierte Heatmap-Zelle (Höhe ∝ Signalstärke)."""

    center_x: float
    center_y: float
    base_z: float
    height_m: float
    dbm: float
    cell_size_m: float
    sample_count: int


def build_heatmap(
    samples: List[RfSample],
    cell_size_m: float = 1.0,
    min_dbm: float = -90.0,
    max_dbm: float = -30.0,
    max_height_m: float = 12.0,
) -> List[ExtrudedCell]:
    """Aggregiert RF-Samples zu einem Bodenraster (Mittelwert je Zelle)."""
    if not samples:
        return []
    cells: dict = {}
    for s in samples:
        key = (int(np.floor(s.x / cell_size_m)), int(np.floor(s.y / cell_size_m)))
        cells.setdefault(key, []).append(s)

    result: List[ExtrudedCell] = []
    for (cx, cy), cell_samples in cells.items():
        dbm = float(np.mean([s.dbm for s in cell_samples]))
        normalized = (min(max(dbm, min_dbm), max_dbm) - min_dbm) / (max_dbm - min_dbm)
        result.append(
            ExtrudedCell(
                center_x=(cx + 0.5) * cell_size_m,
                center_y=(cy + 0.5) * cell_size_m,
                base_z=min(s.z for s in cell_samples),
                height_m=normalized * max_height_m,
                dbm=dbm,
                cell_size_m=cell_size_m,
                sample_count=sum(s.sample_count for s in cell_samples),
            )
        )
    return result
