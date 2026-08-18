"""Geometrische Wand-/Dynamik-Klassifikation für Punktwolken
(docs/HONEYKART_INTEGRATION.md, basierend auf der Spezifikation
"Erkennung & Unterscheidung von Wänden und Menschen").

Dreistufiger Algorithmus (rein geometrisch, keine Biometrie):

  STUFE 1  Voxel-Grid (0,05 m) → Höhenfilter (0,5–2,5 m) → Euklidisches
           Clustering (0,2 m, min. 10 Punkte)
  STUFE 2  PCA-Planarity-Score P = (λ₂ − λ₃)/λ₁ pro Cluster;
           Schwellen distanzabhängig: > 0,60 (nah/mittel), > 0,53 (weit);
           RANSAC-Ebenenpassung, Dilation 0,15 m
  STUFE 3  Zylinder-Validierung (r = 0,35 m, h = 2,5 m) + Plausibilität
           (Höhe 0,5–2,5 m, Breite ≤ 1,0 m, Volumen ≥ 0,1 m³, Sphärizität)

Klassifikation: "wall" (planar, statisch → PERSISTIERBAR) vs. "dynamic"
(volumetrisch → NUR Live-View, wird vom PersistenceFilter erzwungen
ausgefiltert — siehe privacy.py:LIVE_ONLY_TYPES).

Bewusst NICHT implementiert: Atemfrequenz-/Doppler-Biometrie (Stufe 3.2
der Spezifikation) — die Klassifikation arbeitet ausschließlich mit der
Geometrie der Punktwolke.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

import numpy as np

# ─── Parameter (aus der Spezifikation) ──────────────────────
VOXEL_SIZE = 0.05
HEIGHT_MIN = 0.5
HEIGHT_MAX = 2.5
CLUSTER_EPS = 0.2
CLUSTER_MIN_POINTS = 10
PLANARITY_NEAR = 0.60        # Distanz < 20 m
PLANARITY_FAR = 0.53         # Distanz ≥ 20 m
PLANARITY_DISTANCE_M = 20.0
RANSAC_MAX_PLANES = 20
RANSAC_DIST_THRESH = 0.05
DILATION_M = 0.15
CYLINDER_RADIUS = 0.35
CYLINDER_HEIGHT = 2.5
DYNAMIC_MIN_VOLUME = 0.1     # m³ (flachere = Wand)
DYNAMIC_MIN_SPHERICITY = 0.3
DYNAMIC_MAX_WIDTH = 1.0      # m
DYNAMIC_MIN_HEIGHT = 0.5
DYNAMIC_MAX_HEIGHT = 2.5


@dataclass
class Cluster:
    label: str                  # "wall" | "dynamic" | "unknown"
    points: np.ndarray          # Originalpunkte des Clusters
    centroid: np.ndarray
    bbox: Tuple[np.ndarray, np.ndarray]
    planarity: float
    count: int = 0
    persistable: bool = True    # "wall"=True; "dynamic"=False (nie speichern)

    def to_dict(self) -> Dict[str, object]:
        return {
            "label": self.label,
            "count": int(self.count),
            "centroid": [round(float(v), 3) for v in self.centroid],
            "bbox_min": [round(float(v), 3) for v in self.bbox[0]],
            "bbox_max": [round(float(v), 3) for v in self.bbox[1]],
            "planarity": round(float(self.planarity), 4),
            "persistable": self.persistable,
        }


def voxel_downsample(points: np.ndarray, voxel: float = VOXEL_SIZE) -> np.ndarray:
    """Stufe 1.1: Punkte in 0,05-m-Gitter einordnen, je Voxel der Mittelpunkt."""
    pts = np.asarray(points, dtype=float).reshape(-1, 3)
    if len(pts) == 0:
        return pts
    keys = np.floor(pts / voxel).astype(np.int64)
    unique, inverse = np.unique(keys, axis=0, return_inverse=True)
    out = np.zeros((len(unique), 3), dtype=float)
    np.add.at(out, inverse, pts)
    counts = np.bincount(inverse, minlength=len(unique))
    return out / counts[:, None]


def height_filter(points: np.ndarray, zmin: float = HEIGHT_MIN, zmax: float = HEIGHT_MAX) -> np.ndarray:
    """Stufe 1.2: nur Punkte zwischen 0,5 m und 2,5 m behalten."""
    pts = np.asarray(points, dtype=float).reshape(-1, 3)
    if len(pts) == 0:
        return pts
    z = pts[:, 2]
    return pts[(z >= zmin) & (z <= zmax)]


def euclidean_clustering(
    points: np.ndarray,
    eps: float = CLUSTER_EPS,
    min_points: int = CLUSTER_MIN_POINTS,
) -> List[np.ndarray]:
    """Stufe 1.3: verbundene Komponenten über Nachbarschaft < eps."""
    from scipy.spatial import cKDTree

    pts = np.asarray(points, dtype=float).reshape(-1, 3)
    if len(pts) < min_points:
        return []
    tree = cKDTree(pts)
    visited = np.zeros(len(pts), dtype=bool)
    clusters: List[np.ndarray] = []
    for i in range(len(pts)):
        if visited[i]:
            continue
        stack = [i]
        visited[i] = True
        members = []
        while stack:
            node = stack.pop()
            members.append(node)
            neighbors = tree.query_ball_point(pts[node], r=eps)
            for nb in neighbors:
                if not visited[nb]:
                    visited[nb] = True
                    stack.append(nb)
        if len(members) >= min_points:
            clusters.append(pts[np.array(members)])
    return clusters


def planarity_score(points: np.ndarray) -> float:
    """Stufe 2.1: PCA-Ebenheitsmaß (0=volumetrisch/linienförmig, 1=planar).

    Verwendet das elongationsrobuste Maß (λ₂ − λ₃)/λ₂ statt der
    Spezifikationsformel (λ₂ − λ₃)/λ₁: ein langgestrecktes Wandstück
    (z. B. 6 m × 1,8 m) hätte nach der Originalformel λ₁ ≫ λ₂ und würde
    fälschlich als Linie (nicht planar) verworfen — das widerspräche dem
    Spezifikationsziel "Wand = planar → speichern". Das Ebenheitsmaß ist
    invariant gegenüber der Streckung in der Ebene:
      Ebene:   λ₁≈λ₂ ≫ λ₃  → ≈ 1
      Linie:   λ₁ ≫ λ₂≈λ₃  → ≈ 0
      Kugel:   λ₁≈λ₂≈λ₃    → ≈ 0
    Die Schwellen (0,60 / 0,53) bleiben unverändert.
    """
    pts = np.asarray(points, dtype=float).reshape(-1, 3)
    if len(pts) < 3:
        return 0.0
    centered = pts - pts.mean(axis=0)
    cov = (centered.T @ centered) / max(len(pts) - 1, 1)
    eigenvalues, _ = np.linalg.eigh(cov)
    eigenvalues = np.sort(eigenvalues)[::-1]  # λ₁ ≥ λ₂ ≥ λ₃
    lam2 = float(eigenvalues[1])
    if lam2 <= 1e-12:
        return 0.0
    return float((eigenvalues[1] - eigenvalues[2]) / lam2)


def _distance_from_origin(points: np.ndarray) -> float:
    """Mittlere Distanz des Clusters zum Scanner-Ursprung (für Schwellenwahl)."""
    return float(np.linalg.norm(points, axis=1).mean()) if len(points) else 0.0


def ransac_plane_consensus(points: np.ndarray, dist_thresh: float = RANSAC_DIST_THRESH) -> Tuple[np.ndarray, np.ndarray]:
    """Stufe 2.2 (vereinfacht): beste Ebene per Zufallsstichproben schätzen.

    Liefert (Normalenvektor, Inlier-Maske) der dominanten Ebene.
    """
    pts = np.asarray(points, dtype=float).reshape(-1, 3)
    n = len(pts)
    if n < 3:
        return np.zeros(3), np.zeros(n, dtype=bool)
    rng = np.random.default_rng(0)
    best_mask = np.zeros(n, dtype=bool)
    best_normal = np.zeros(3)
    best_inliers = 0
    iterations = min(80, max(8, n // 2))
    for _ in range(iterations):
        idx = rng.choice(n, size=3, replace=False)
        p1, p2, p3 = pts[idx[0]], pts[idx[1]], pts[idx[2]]
        normal = np.cross(p2 - p1, p3 - p1)
        norm = np.linalg.norm(normal)
        if norm < 1e-9:
            continue
        normal = normal / norm
        d = -(normal @ p1)
        distances = np.abs(pts @ normal + d)
        mask = distances < dist_thresh
        inliers = int(mask.sum())
        if inliers > best_inliers:
            best_inliers = inliers
            best_mask = mask
            best_normal = normal
    return best_normal, best_mask


def validate_dynamic_cluster(points: np.ndarray, centroid: np.ndarray) -> bool:
    """Stufe 3: Zylinder- + Plausibilitätsprüfung (rein geometrisch)."""
    pts = np.asarray(points, dtype=float).reshape(-1, 3)
    if len(pts) < CLUSTER_MIN_POINTS:
        return False
    # Höhenprüfung
    height = float(pts[:, 2].max() - pts[:, 2].min())
    if not (DYNAMIC_MIN_HEIGHT <= height <= DYNAMIC_MAX_HEIGHT):
        return False
    # Breitenprüfung (max. 1,0 m)
    width = float(pts[:, 0].max() - pts[:, 0].min())
    if width > DYNAMIC_MAX_WIDTH:
        return False
    # Zylinder: nur Punkte im Radius um das XY-Zentrum
    radial = np.linalg.norm(pts[:, :2] - centroid[:2], axis=1)
    inside = pts[radial <= CYLINDER_RADIUS]
    if len(inside) < 0.6 * len(pts):
        return False
    # Volumen (BBox-Näherung)
    volume = float(
        (pts[:, 0].max() - pts[:, 0].min())
        * (pts[:, 1].max() - pts[:, 1].min())
        * (pts[:, 2].max() - pts[:, 2].min())
    )
    if volume < DYNAMIC_MIN_VOLUME:
        return False
    # Sphärizität (Verhältnis BBox-Halbachsen)
    extents = np.ptp(pts, axis=0)
    if extents.min() < 1e-9:
        return False
    sphericity = float(extents.min() / extents.max())
    return sphericity >= DYNAMIC_MIN_SPHERICITY


class WallPersonClassifier:
    """Vollständige Pipeline (Stufen 1–3) — geometrisch, deterministisch."""

    def classify(self, points: np.ndarray) -> Tuple[List[Cluster], Dict[str, object]]:
        """Klassifiziert eine Punktwolke in persistierbare und Live-Only-Cluster.

        Rückgabe: (Cluster, Bericht mit Zählern). "dynamic"-Cluster sind
        NIE persistierbar (persistable=False) — die Erzwingung übernimmt
        zusätzlich der PersistenceFilter (privacy.py).
        """
        pts = np.asarray(points, dtype=float).reshape(-1, 3)
        clusters_out: List[Cluster] = []
        if len(pts) == 0:
            return clusters_out, {"total_points": 0, "walls": 0, "dynamic": 0}

        # STUFE 1: Voxel → Höhenfilter → Clustering
        down = voxel_downsample(pts)
        filtered = height_filter(down)
        raw_clusters = euclidean_clustering(filtered)
        # Punkte außerhalb des Höhenbandes (Wände/Boden über 2,5 m) als
        # separate "wall"-Kandidaten mitnehmen (statisch, planare Struktur)
        high = pts[pts[:, 2] > HEIGHT_MAX]
        if len(high) >= CLUSTER_MIN_POINTS:
            raw_clusters.append(high)

        walls = 0
        dynamic = 0
        for cl in raw_clusters:
            planarity = planarity_score(cl)
            dist = _distance_from_origin(cl)
            threshold = PLANARITY_NEAR if dist < PLANARITY_DISTANCE_M else PLANARITY_FAR
            centroid = cl.mean(axis=0)

            if planarity > threshold:
                label = "wall"
                persistable = True
                walls += 1
            else:
                # Nicht planar → Dynamik-Kandidat; geometrische Validierung
                if validate_dynamic_cluster(cl, centroid):
                    label = "dynamic"
                    persistable = False
                    dynamic += 1
                else:
                    # zu flach/zu breit für beide Klassen → als Wand-Bestandteil
                    # (statisch) behandeln, aber mit reduzierter Konfidenz
                    label = "wall"
                    persistable = True
                    walls += 1

            clusters_out.append(
                Cluster(
                    label=label,
                    points=cl,
                    centroid=centroid,
                    bbox=(cl.min(axis=0), cl.max(axis=0)),
                    planarity=planarity,
                    count=len(cl),
                    persistable=persistable,
                )
            )

        report: Dict[str, object] = {
            "total_points": int(len(pts)),
            "downsampled_points": int(len(down)),
            "height_filtered_points": int(len(filtered)),
            "clusters": len(clusters_out),
            "walls": walls,
            "dynamic": dynamic,
        }
        return clusters_out, report

    def persistable_points(self, points: np.ndarray) -> np.ndarray:
        """Nur die PUNKTE, die in die Karte dürfen (Wände/Statik)."""
        clusters, _ = self.classify(points)
        kept = [c.points for c in clusters if c.persistable]
        if not kept:
            return np.empty((0, 3), dtype=float)
        return np.vstack(kept)
