import numpy as np

from pipeline import DataPipeline


def _room_points(n=400, rng=None):
    rng = rng or np.random.default_rng(0)
    pts = []
    # Boden + Wände eines Raumes (Höhen variieren)
    for _ in range(n):
        x = rng.uniform(-5, 5)
        y = rng.uniform(-5, 5)
        z = rng.choice([0.0, 2.5, rng.uniform(0.1, 2.4)])
        pts.append([x, y, z])
    return np.asarray(pts, dtype=float).flatten().tolist()


def test_pipeline_runs_end_to_end():
    pipeline = DataPipeline()
    result = pipeline.run(_room_points())
    assert result["status"] == "ready"
    assert result["num_points"] > 0
    assert result["num_mesh_faces"] > 0
    assert result["num_objects"] >= 1
    assert 0.0 <= result["confidence"] <= 1.0
    assert "transform" in result
    assert result["evaluation"]["floor_area_m2"] > 0


def test_pipeline_empty_input():
    pipeline = DataPipeline()
    result = pipeline.run([])
    assert result["status"] == "empty"
    assert result["num_points"] == 0
