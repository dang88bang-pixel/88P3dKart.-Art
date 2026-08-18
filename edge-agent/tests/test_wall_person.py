"""Wand-/Dynamik-Klassifikation (3-Stufen-Pipeline, rein geometrisch)."""
import numpy as np
import pytest

from wall_person_classifier import (
    WallPersonClassifier,
    euclidean_clustering,
    height_filter,
    planarity_score,
    voxel_downsample,
)


def _wall_points(n=600, seed=1):
    """Vertikale, planare Wand (y ≈ 2.0, x/z variieren)."""
    rng = np.random.default_rng(seed)
    x = rng.uniform(-3, 3, n)
    z = rng.uniform(0.6, 2.4, n)
    y = 2.0 + rng.normal(0, 0.02, n)
    return np.column_stack([x, y, z])


def _blob_points(center=(0.0, 0.0, 1.2), n=400, seed=2):
    """Kompaktes, volumetrisches Objekt (Dynamik-Kandidat, ~0.3 m Radius)."""
    rng = np.random.default_rng(seed)
    pts = rng.normal(0, 0.12, (n, 3))
    pts[:, 0] += center[0]
    pts[:, 1] += center[1]
    pts[:, 2] = np.clip(pts[:, 2] + center[2], 0.6, 2.4)
    return pts


def test_voxel_downsample_reduces_point_count():
    # Dichte, kompakte Wand: 6000 Punkte auf engem Bereich → Voxel-Kollisionen
    rng = np.random.default_rng(9)
    pts = np.column_stack([
        rng.uniform(-1, 1, 6000),
        2.0 + rng.normal(0, 0.005, 6000),
        rng.uniform(0.8, 2.0, 6000),
    ])
    down = voxel_downsample(pts)
    assert len(down) < len(pts) * 0.5
    # Mittelpunkt je Voxel: Wertebereich bleibt erhalten
    assert down[:, 1].max() <= pts[:, 1].max() + 1e-9


def test_height_filter_bounds():
    pts = np.array([[0, 0, 0.0], [0, 0, 0.4], [0, 0, 1.5], [0, 0, 2.4], [0, 0, 3.0]])
    out = height_filter(pts)
    assert np.all((out[:, 2] >= 0.5) & (out[:, 2] <= 2.5))
    assert len(out) == 2


def test_clustering_separates_blobs():
    a = _blob_points(center=(-1.5, 0, 1.2))
    b = _blob_points(center=(1.5, 0, 1.2))
    clusters = euclidean_clustering(np.vstack([a, b]))
    assert len(clusters) == 2


def test_planarity_wall_high_blob_low():
    assert planarity_score(_wall_points()) > 0.6
    assert planarity_score(_blob_points()) < 0.6


def test_classify_wall_is_persistable():
    clusters, report = WallPersonClassifier().classify(_wall_points())
    assert report["walls"] >= 1
    walls = [c for c in clusters if c.label == "wall"]
    assert walls and all(c.persistable for c in walls)


def test_classify_dynamic_is_live_only():
    clusters, report = WallPersonClassifier().classify(_blob_points())
    assert report["dynamic"] >= 1
    dynamic = [c for c in clusters if c.label == "dynamic"]
    assert dynamic and all(not c.persistable for c in dynamic)
    # BBox plausibel: kompakt, innerhalb der Zylinder-/Größen-Grenzen
    bbox = dynamic[0].bbox
    assert bbox[1][2] - bbox[0][2] <= 2.5 + 1e-6


def test_mixed_scene_separates_wall_and_dynamic():
    scene = np.vstack([_wall_points(), _blob_points(center=(0.5, -1.0, 1.2))])
    clusters, report = WallPersonClassifier().classify(scene)
    labels = {c.label for c in clusters}
    assert "wall" in labels and "dynamic" in labels
    assert report["walls"] >= 1 and report["dynamic"] >= 1


def test_persistable_points_exclude_dynamic():
    scene = np.vstack([_wall_points(), _blob_points(center=(0.5, -1.0, 1.2))])
    kept = WallPersonClassifier().persistable_points(scene)
    # Dynamik-Punkte liegen um y≈−1.0 bei x≈0.5; Wand bei y≈2.0
    assert len(kept) < len(scene)
    assert np.all(kept[:, 1] > 0.5)  # Blob entfernt


def test_classify_empty_input():
    clusters, report = WallPersonClassifier().classify(np.empty((0, 3)))
    assert clusters == [] and report["total_points"] == 0
