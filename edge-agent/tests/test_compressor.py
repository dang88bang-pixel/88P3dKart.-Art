import numpy as np

from pointcloud_compressor import PointCloudCompressor


def test_roundtrip():
    rng = np.random.default_rng(7)
    pts = rng.standard_normal((1000, 3)).astype(np.float32)
    blob = PointCloudCompressor.compress(pts)
    out = PointCloudCompressor.decompress(blob)
    assert out.shape == pts.shape
    assert np.allclose(out, pts)


def test_empty():
    blob = PointCloudCompressor.compress(np.empty((0, 3)))
    out = PointCloudCompressor.decompress(blob)
    assert out.shape == (0, 3)
