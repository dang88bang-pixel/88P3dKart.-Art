import numpy as np

from ekf_fusion import AdaptiveEKF


def test_ekf_converges_to_constant_position():
    ekf = AdaptiveEKF(dt=0.05)
    true_pos = np.array([2.0, -1.0, 3.0])
    for _ in range(200):
        ekf.predict()
        ekf.update_lidar(true_pos)
    state = ekf.get_state()
    assert np.allclose([state.x, state.y, state.z], true_pos, atol=0.05)


def test_adaptive_noise_reduces_lidar_confidence():
    ekf = AdaptiveEKF(dt=0.05)
    ekf.adapt_to_environment(scattering_detected=False, thermal_c=45.0)
    normal_gain = ekf.get_kalman_gain_lidar()

    ekf.adapt_to_environment(scattering_detected=True, thermal_c=45.0)
    scattered_gain = ekf.get_kalman_gain_lidar()

    assert scattered_gain < normal_gain


def test_mmwave_update_runs():
    ekf = AdaptiveEKF(dt=0.05)
    ekf.predict()
    ekf.update_mmwave(np.array([1.0, 2.0, 3.0]))
    state = ekf.get_state()
    assert state.x != 0.0
