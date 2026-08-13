"""v2.0.0 — Sensor-/Netzwerkdaten-Evaluierungspipeline.

Pipeline:
    Sensor-/Netzwerkdaten → Analyse → Mesh → 3D-Umgebung → Exakte Abbildung → Evaluierungsagent

Jede Stufe ist eine eigenständige, testbare Klasse. Die Orchestrierung übernimmt
`DataPipeline`.
"""
import logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Tuple

import numpy as np
from scipy.spatial import ConvexHull, Delaunay

logger = logging.getLogger(__name__)


# ──────────────────────────────────────────────────────────────────────────
# 1. Daten-Erfassung
# ──────────────────────────────────────────────────────────────────────────
@dataclass
class SensorDataPoint:
    timestamp: float
    source: str
    x: float
    y: float
    z: float
    quality: float = 1.0  # 0..1


class DataAcquisition:
    """Erfassung von Sensor-/Netzwerkdaten mit Qualitätsbewertung."""

    def __init__(self, max_buffer_size: int = 10000):
        self.max_buffer_size = max_buffer_size
        self.buffer: List[SensorDataPoint] = []

    def ingest(self, points: List[float], source: str = "lidar",
               quality: float = 1.0, timestamp: float | None = None) -> int:
        """Nimmt flache Koordinaten [x1,y1,z1, ...] auf und legt sie im Buffer ab."""
        import time as _time

        ts = timestamp if timestamp is not None else _time.time()
        arr = np.asarray(points, dtype=float)
        arr = arr.reshape(-1, 3)
        for row in arr:
            self.buffer.append(
                SensorDataPoint(ts, source, float(row[0]), float(row[1]), float(row[2]), quality)
            )
        if len(self.buffer) > self.max_buffer_size:
            self.buffer = self.buffer[-self.max_buffer_size:]
        return len(self.buffer)

    def to_array(self) -> np.ndarray:
        if not self.buffer:
            return np.empty((0, 3), dtype=float)
        return np.array([[p.x, p.y, p.z] for p in self.buffer], dtype=float)

    @property
    def count(self) -> int:
        return len(self.buffer)


# ──────────────────────────────────────────────────────────────────────────
# 2. Datenanalyse & Interpretation
# ──────────────────────────────────────────────────────────────────────────
@dataclass
class InterpretedObject:
    kind: str          # "floor", "wall", "person", "unknown"
    points: np.ndarray
    centroid: np.ndarray
    bbox: Tuple[np.ndarray, np.ndarray]  # (min, max)


class DataInterpreter:
    """Interpretiert Punktwolken: Segmentierung in Boden/Wand/Personen."""

    def interpret(self, points: np.ndarray) -> List[InterpretedObject]:
        points = np.asarray(points, dtype=float).reshape(-1, 3)
        if len(points) == 0:
            return []

        z = points[:, 2]
        zmin, zmax = float(z.min()), float(z.max())

        # Heuristische Segmentierung über die Z-Höhe:
        #  - Boden: unterste ~15 % der Höhenspanne
        #  - Personen/Objekte: mittlerer Bereich, kompakte Cluster (hier: alles Übrige)
        #  - Wand/Decke: oberste ~15 %
        if zmax - zmin < 1e-6:
            bands = [("floor", points)]
        else:
            bands = []
            bands.append(("floor", points[z < zmin + 0.15 * (zmax - zmin)]))
            bands.append(("wall", points[z > zmax - 0.15 * (zmax - zmin)]))
            mid = points[(z >= zmin + 0.15 * (zmax - zmin)) & (z <= zmax - 0.15 * (zmax - zmin))]
            if len(mid):
                bands.append(("person", mid))

        objects: List[InterpretedObject] = []
        for kind, pts in bands:
            if len(pts) == 0:
                continue
            centroid = pts.mean(axis=0)
            bbox = (pts.min(axis=0), pts.max(axis=0))
            objects.append(InterpretedObject(kind, pts, centroid, bbox))
        return objects


# ──────────────────────────────────────────────────────────────────────────
# 3. Mesh-Generierung
# ──────────────────────────────────────────────────────────────────────────
@dataclass
class Mesh:
    vertices: np.ndarray
    faces: np.ndarray  # (F, 3) Indizes


class MeshGenerator:
    """Erzeugt ein Dreiecks-Mesh aus einer Punktwolke (2D-Delaunay auf XY)."""

    def generate(self, points: np.ndarray) -> Mesh:
        points = np.asarray(points, dtype=float).reshape(-1, 3)
        if len(points) < 3:
            return Mesh(points, np.empty((0, 3), dtype=int))

        xy = points[:, :2]
        # Deduplizieren für eine stabile Triangulation
        _, unique_idx = np.unique(xy, axis=0, return_index=True)
        unique_idx = np.sort(unique_idx)
        xy_u = xy[unique_idx]

        if len(xy_u) < 3:
            return Mesh(points[unique_idx], np.empty((0, 3), dtype=int))

        try:
            tri = Delaunay(xy_u)
        except Exception as e:  # degenerierte Punktmenge
            logger.warning("Delaunay fehlgeschlagen: %s", e)
            return Mesh(points[unique_idx], np.empty((0, 3), dtype=int))

        return Mesh(points[unique_idx], tri.simplices.astype(int))


# ──────────────────────────────────────────────────────────────────────────
# 4. 3D-Umgebungsrekonstruktion
# ──────────────────────────────────────────────────────────────────────────
@dataclass
class Environment:
    bounds: Tuple[np.ndarray, np.ndarray]
    objects: List[InterpretedObject]
    volume: float
    floor_area: float


class EnvironmentReconstructor:
    """Rekonstruiert die 3D-Umgebung (Grenzen, Volumen, Bodenfläche)."""

    def reconstruct(self, points: np.ndarray, objects: List[InterpretedObject]) -> Environment:
        points = np.asarray(points, dtype=float).reshape(-1, 3)
        if len(points) == 0:
            zero = np.zeros(3)
            return Environment((zero, zero), objects, 0.0, 0.0)

        lo = points.min(axis=0)
        hi = points.max(axis=0)
        volume = float(np.prod(np.maximum(hi - lo, 1e-9)))

        # Bodenfläche über 2D-Hülle der untersten Punkte
        floor_area = 0.0
        zmin = float(points[:, 2].min())
        floor_pts = points[np.abs(points[:, 2] - zmin) < 0.05 * max(1.0, hi[2] - lo[2])]
        if len(floor_pts) >= 3:
            try:
                hull = ConvexHull(floor_pts[:, :2])
                floor_area = float(hull.area)
            except Exception:
                floor_area = 0.0

        return Environment((lo, hi), objects, volume, floor_area)


# ──────────────────────────────────────────────────────────────────────────
# 5. Exakte Abbildung
# ──────────────────────────────────────────────────────────────────────────
@dataclass
class ExactMapping:
    transform: np.ndarray  # 4x4 homogen
    aligned_points: np.ndarray
    residual: float


class ExactMapper:
    """Exakte Abbildung: zentriert die Punktwolke auf den Weltursprung (Referenz-Frame)."""

    def map(self, points: np.ndarray) -> ExactMapping:
        points = np.asarray(points, dtype=float).reshape(-1, 3)
        if len(points) == 0:
            return ExactMapping(np.eye(4), points, 0.0)

        centroid = points.mean(axis=0)
        T = np.eye(4)
        T[:3, 3] = -centroid
        aligned = (T[:3, :3] @ points.T).T + T[:3, 3]
        residual = float(np.linalg.norm(aligned.mean(axis=0)))
        return ExactMapping(T, aligned, residual)


# ──────────────────────────────────────────────────────────────────────────
# 6. Evaluierungsagent
# ──────────────────────────────────────────────────────────────────────────
class EvaluationAgent:
    """Bewertet die Qualität der rekonstruierten Umgebung."""

    def evaluate(
        self,
        points: np.ndarray,
        mesh: Mesh,
        environment: Environment,
        mapping: ExactMapping,
    ) -> Dict[str, Any]:
        points = np.asarray(points, dtype=float).reshape(-1, 3)
        n = len(points)

        coverage = 1.0 if n > 0 else 0.0
        density = float(n) if environment.floor_area > 0 else 0.0
        if environment.floor_area > 0:
            density = float(n) / environment.floor_area  # Punkte pro m²

        # Vertrauen: Kombination aus Abdeckung, Dichte und Mapping-Residuum
        residual_score = 1.0 / (1.0 + abs(mapping.residual))
        mesh_score = 1.0 if len(mesh.faces) > 0 else 0.5
        confidence = float(np.clip(0.4 * coverage + 0.3 * residual_score + 0.3 * mesh_score, 0.0, 1.0))

        return {
            "num_points": n,
            "num_faces": int(len(mesh.faces)),
            "num_objects": len(environment.objects),
            "coverage": round(coverage, 4),
            "density_pts_per_m2": round(density, 2),
            "volume_m3": round(environment.volume, 4),
            "floor_area_m2": round(environment.floor_area, 4),
            "mapping_residual": round(mapping.residual, 6),
            "confidence": round(confidence, 4),
            "status": "ready" if n > 0 else "empty",
        }


# ──────────────────────────────────────────────────────────────────────────
# Orchestrierung
# ──────────────────────────────────────────────────────────────────────────
class DataPipeline:
    """Verkettet die 6 Stufen und liefert ein Gesamtergebnis."""

    def __init__(self):
        self.acquisition = DataAcquisition()
        self.interpreter = DataInterpreter()
        self.mesh_generator = MeshGenerator()
        self.reconstructor = EnvironmentReconstructor()
        self.mapper = ExactMapper()
        self.evaluator = EvaluationAgent()

    def run(self, points: List[float], source: str = "lidar",
            quality: float = 1.0, metadata: Dict[str, Any] | None = None) -> Dict[str, Any]:
        metadata = metadata or {}

        # 1. Erfassung
        self.acquisition.ingest(points, source=source, quality=quality)
        arr = self.acquisition.to_array()

        # 2. Interpretation
        objects = self.interpreter.interpret(arr)

        # 3. Mesh
        mesh = self.mesh_generator.generate(arr)

        # 4. Umgebung
        environment = self.reconstructor.reconstruct(arr, objects)

        # 5. Exakte Abbildung
        mapping = self.mapper.map(arr)

        # 6. Evaluation
        evaluation = self.evaluator.evaluate(arr, mesh, environment, mapping)

        return {
            "status": evaluation["status"],
            "num_points": evaluation["num_points"],
            "num_mesh_vertices": int(len(mesh.vertices)),
            "num_mesh_faces": evaluation["num_faces"],
            "num_objects": evaluation["num_objects"],
            "objects": [o.kind for o in objects],
            "confidence": evaluation["confidence"],
            "transform": {
                "offset_x": float(mapping.transform[0, 3]),
                "offset_y": float(mapping.transform[1, 3]),
                "offset_z": float(mapping.transform[2, 3]),
                "pitch": 0.0,
                "roll": 0.0,
                "yaw": 0.0,
                "scale": 1.0,
            },
            "evaluation": evaluation,
            "metadata": metadata,
        }
