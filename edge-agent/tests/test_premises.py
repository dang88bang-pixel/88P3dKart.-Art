"""Betriebsgelände-Sicherheit: passive Fremdgeräte-Erkennung + Störungs-Detektion."""
import time

import pytest

from premises_security import PremisesSecurity, SensorReport


def test_observe_classifies_own_infra_unknown():
    ps = PremisesSecurity()
    ps.register_own("veh-1")
    ps.register_infra("ap:factory-1")

    own = ps.observe("veh-1", kind="fleet", name="E-Bike 1")
    assert own.status == "own"
    infra = ps.observe("ap:factory-1", kind="accessory", name="Werk-AP")
    assert infra.status == "infra"
    unknown = ps.observe("aa:bb:cc:dd:ee:99", kind="accessory", name="Fremdgerät")
    assert unknown.status == "unknown"
    assert unknown.reason and "Fremdgerät" in unknown.reason


def test_observe_updates_existing():
    ps = PremisesSecurity()
    ps.observe("x-1", "accessory", "X")
    ps.register_own("x-1")
    updated = ps.observe("x-1", "accessory", "X", rssi=-50)
    assert updated.status == "own"
    assert updated.rssi == -50
    # nur EIN Eintrag
    assert len(ps.overview()["unknown"]) == 0
    assert ps.overview()["observed"] == 1


def test_evaluate_returns_new_unknown_only():
    ps = PremisesSecurity()
    ps.observe("foreign-1", "accessory", "F1")
    first = ps.evaluate(own_ids=set(), infra_ids=set())
    assert len(first) == 0  # war schon unknown
    second = ps.evaluate(own_ids={"foreign-1"}, infra_ids=set())
    assert second == []  # jetzt own, kein neuer Alert
    assert ps.overview()["counts"]["unknown"] == 0


def test_sensor_reports_bounded():
    ps = PremisesSecurity()
    for i in range(250):
        ps.add_sensor_report(SensorReport("dev-1", "magnetometer", float(i), "uT"))
    overview = ps.overview()
    assert len(overview["sensor_reports"]) == 50  # letzte 50


def test_drop_detection_passive():
    ps = PremisesSecurity()
    assert ps.check_own_device_drop(10) is None       # Baseline
    assert ps.check_own_device_drop(10) is None       # stabil
    alert = ps.check_own_device_drop(3)               # -70 % → Alert
    assert alert is not None
    assert alert["type"] == "own_device_drop"
    assert alert["dropped"] == 7
    # Schwelle: erst ab mindestens 3 Geräten
    assert ps.check_own_device_drop(8) is None        # Anstieg → kein Alert
