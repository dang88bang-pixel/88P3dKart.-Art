"""Datenschutz-Schicht (docs/FARBKODIERUNG.md, docs/SIGNAL_POSITIONING.md).

Erzwingt die Speicherregeln der Plattform:

- Personen/Tiere (semantische Typen person/animal/moving_person) werden
  NIE persistiert oder exportiert — nur transiente Live-View-Anzeige.
- Geräte-Metadaten werden vor Persistenz/Export anonymisiert:
  MAC/UUID/User-IDs werden gehasht, Positionen auf 0,1 m granularisiert,
  verbotene Metadaten-Schlüssel werden entfernt.
- Alle Filter-Entscheidungen sind deterministisch und testbar.
"""
from __future__ import annotations

import hashlib
import math
from typing import Any, Dict, List

# Semantische Typen, die NIE gespeichert werden dürfen (nur Live-View).
LIVE_ONLY_TYPES = frozenset({"person", "animal", "moving_person"})

# Metadaten-Schlüssel, die vor Persistenz entfernt werden.
STRIP_KEYS = frozenset({"mac", "uuid", "user_id", "user", "owner_name", "phone", "email"})

# Positions-Granularität in Metern (0,1 m = 10 cm).
POSITION_GRANULARITY = 0.1


def anonymize_identifier(value: str, prefix: str = "ANON") -> str:
    """Hash eines Identifikators (z. B. MAC): deterministisch, nicht umkehrbar."""
    digest = hashlib.sha256(str(value).encode("utf-8")).hexdigest()
    return f"{prefix}_{digest[:16]}"


def granularize(value: float, granularity: float = POSITION_GRANULARITY) -> float:
    """Position auf die Granularitätsstufe runden (Datensparsamkeit)."""
    step = float(granularity)
    return float(math.floor(round(value / step, 9)) * step)


def strip_metadata(metadata: Dict[str, Any]) -> Dict[str, Any]:
    """Entfernt personenbeziehende Schlüssel aus Metadaten (rekursiv flach)."""
    return {str(k): v for k, v in (metadata or {}).items() if str(k).lower() not in STRIP_KEYS}


class PersistenceFilter:
    """Erzwingender Filter zwischen Live-Pipeline und Speicher/Export."""

    def filter_objects(self, objects: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Entfernt Live-Only-Objekte (Personen/Tiere) vollständig.

        Rückgabe enthält zusätzlich einen Zähler, wie viele Objekte
        aussortiert wurden (für Audit/Auswertung, ohne deren Daten).
        """
        kept = []
        removed = 0
        for obj in objects or []:
            kind = str(obj.get("kind") or obj.get("type") or "").lower()
            if kind in LIVE_ONLY_TYPES:
                removed += 1
                continue
            kept.append(obj)
        return kept, removed

    def filter_device(self, device: Dict[str, Any]) -> Dict[str, Any]:
        """Anonymisiert ein Geräte-Objekt: ID-Hash + Metadaten-Strip."""
        out = dict(device)
        if out.get("id") and not str(out["id"]).startswith("ANON_"):
            out["id"] = anonymize_identifier(str(out["id"]))
        for field in ("mac", "address"):
            if out.get(field):
                out[field] = anonymize_identifier(str(out[field]))
        out["metadata"] = strip_metadata(out.get("metadata") or {})
        return out

    def sanitize_position(self, x: float, y: float, z: float) -> tuple:
        return granularize(x), granularize(y), granularize(z)

    def audit(self, objects: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Liefert einen Audit-Eintrag über die Filterung (ohne Objektdaten)."""
        kinds = {}
        for obj in objects or []:
            kind = str(obj.get("kind") or obj.get("type") or "unknown").lower()
            kinds[kind] = kinds.get(kind, 0) + 1
        return {
            "total_objects": len(objects or []),
            "live_only_removed": sum(v for k, v in kinds.items() if k in LIVE_ONLY_TYPES),
            "persisted_kinds": {k: v for k, v in kinds.items() if k not in LIVE_ONLY_TYPES},
        }
