import numpy as np

from icp_merger import ICPMerger


def test_kabsch_recovers_known_rotation_translation():
    rng = np.random.default_rng(42)
    A = rng.standard_normal((50, 3))
    angle = 0.5
    R = np.array(
        [
            [np.cos(angle), -np.sin(angle), 0],
            [np.sin(angle), np.cos(angle), 0],
            [0, 0, 1],
        ]
    )
    t = np.array([1.0, 2.0, -3.0])
    B = (R @ A.T).T + t

    R_hat, t_hat = ICPMerger.kabsch_umeyama(A, B)
    assert np.allclose(R_hat, R, atol=1e-6)
    assert np.allclose(t_hat, t, atol=1e-6)


def test_icp_aligns_shifted_cloud():
    rng = np.random.default_rng(1)
    src = rng.standard_normal((100, 3))
    offset = np.array([5.0, -2.0, 1.0])
    tgt = src + offset

    aligned, R, t = ICPMerger.icp(src, tgt, max_iterations=50)
    assert np.linalg.norm(t - offset) < 1e-4
    assert np.allclose(aligned.mean(axis=0), tgt.mean(axis=0), atol=1e-3)
