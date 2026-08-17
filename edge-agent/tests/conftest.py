"""Gemeinsame Test-Fixtures."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from config import CONFIG  # noqa: E402


@pytest.fixture
def cfg():
    """Setzt Felder auf dem eingefrorenen CONFIG-Objekt und stellt sie zurück.

    ``Config`` ist ein ``@dataclass(frozen=True)``; monkeypatch.setattr scheitert
    daran mit FrozenInstanceError. Der Umweg über ``object.__setattr__`` ist hier
    ausschliesslich Testcode — im Produktivpfad bleibt die Konfiguration
    unveränderlich.
    """
    originals = {}

    def _set(name: str, value):
        if name not in originals:
            originals[name] = getattr(CONFIG, name)
        object.__setattr__(CONFIG, name, value)

    yield _set

    for name, value in originals.items():
        object.__setattr__(CONFIG, name, value)
