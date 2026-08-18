"""EDM-Lebenszyklus eigener Geräte: Zustände, Audit, legitimer Reset-Weg."""
import pytest

from edm import EdmRegistry


def test_lifecycle_happy_path():
    reg = EdmRegistry()
    reg.upsert("CT45P-01", actor="admin-1", serial="S123", model="CT45P-X0N")
    assert reg.get("CT45P-01").state == "ENROLLED"

    reg.set_state("CT45P-01", "PROVISIONED", "admin-1", "Gerät eingerichtet")
    assert reg.get("CT45P-01").state == "PROVISIONED"

    reg.request_reset("CT45P-01", "admin-1", "Mitarbeiter ausgeschieden")
    assert reg.get("CT45P-01").state == "RESET_PENDING"

    reg.confirm_reset("CT45P-01", "admin-1", "Provisioning Mode durchgeführt")
    assert reg.get("CT45P-01").state == "RESET"

    # Nach Reset wieder aufnehmen
    reg.set_state("CT45P-01", "ENROLLED", "admin-1", "Neu ausgegeben")
    assert reg.get("CT45P-01").state == "ENROLLED"


def test_invalid_transitions_rejected():
    reg = EdmRegistry()
    reg.upsert("CT45P-01", actor="admin-1")
    # Direkt-Reset ohne RESET_PENDING ist verboten (nur legitimer EDM-Weg)
    with pytest.raises(PermissionError):
        reg.set_state("CT45P-01", "RESET", "admin-1", "direkt")
    # Überspringen von ENROLLED → RESET_PENDING verboten
    with pytest.raises(PermissionError):
        reg.request_reset("CT45P-01", "admin-1", "übersprungen")
    # Unbekannter Zustand
    with pytest.raises(ValueError):
        reg.set_state("CT45P-01", "GEHACKT", "admin-1", "x")
    # reason ist Pflicht
    with pytest.raises(ValueError):
        reg.set_state("CT45P-01", "PROVISIONED", "admin-1", "   ")


def test_confirm_reset_requires_pending():
    reg = EdmRegistry()
    reg.upsert("CT45P-01", actor="admin-1")
    reg.set_state("CT45P-01", "PROVISIONED", "admin-1", "eingerichtet")
    with pytest.raises(PermissionError):
        reg.confirm_reset("CT45P-01", "admin-1", "zu früh")


def test_audit_trail_complete():
    reg = EdmRegistry()
    reg.upsert("CT45P-01", actor="admin-1")
    reg.set_state("CT45P-01", "PROVISIONED", "admin-1", "eingerichtet")
    reg.request_reset("CT45P-01", "admin-1", "ausgeschieden")
    reg.confirm_reset("CT45P-01", "admin-1", "durchgeführt")
    audit = reg.list_audit()
    actions = [e.action for e in audit]
    assert actions == ["REGISTER", "SET_STATE", "SET_STATE", "SET_STATE"]
    assert all(e.actor == "admin-1" for e in audit)
    states = [(e.from_state, e.to_state) for e in audit if e.action == "SET_STATE"]
    assert states == [
        ("ENROLLED", "PROVISIONED"),
        ("PROVISIONED", "RESET_PENDING"),
        ("RESET_PENDING", "RESET"),
    ]


def test_unknown_device_and_stats():
    reg = EdmRegistry()
    with pytest.raises(KeyError):
        reg.set_state("unbekannt", "PROVISIONED", "admin-1", "x")
    assert reg.stats() == {}
    reg.upsert("a", actor="admin-1")
    reg.upsert("b", actor="admin-1")
    reg.set_state("a", "PROVISIONED", "admin-1", "ok")
    assert reg.stats() == {"ENROLLED": 1, "PROVISIONED": 1}
