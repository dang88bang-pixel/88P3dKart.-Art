"""Taktisches Map-/Szenario-Management — Python-Kern (docs/TACTICAL.md).

Portierung der sinnvollen Kernlogik aus den v9.0–9.3-Spezifikationen:
- modulare Szenario-Komposition mit Abhängigkeitsauflösung,
- Map-Versionierung mit Delta-Kette (Rekonstruktion aus Basis + Deltas),
- Szenario-/Export-Kompression (zlib),
- Annotation-Templates (20+ Icons, Layer, Typen).

Die Room-Persistenz läuft auf dem Gerät über die bestehende AppDatabase
(SpatialDao-Muster); die KI-Szenariogenerierung bleibt LLM-Client-Interface
(Roadmap, analog AURA „Grounding Lite").
"""

from __future__ import annotations

import time
import zlib
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

# ─── Modulare Szenario-Komposition ────────────────────────────────────────


@dataclass
class ScenarioModule:
    id: str
    name: str = ""
    module_type: str = "terrain"  # terrain|buildings|personnel|vehicles|hazards|...
    config: Dict[str, Any] = field(default_factory=dict)
    dependencies: List[str] = field(default_factory=list)


class ScenarioComposer:
    """Baut Szenario-Konfigurationen aus Modulen (Abhängigkeitsauflösung)."""

    def __init__(self, modules: List[ScenarioModule]) -> None:
        self._modules: Dict[str, ScenarioModule] = {m.id: m for m in modules}

    def available_ids(self) -> List[str]:
        return list(self._modules)

    def build(self, selected: List[str]) -> Dict[str, Any]:
        """Löst die Abhängigkeits-Hülle auf (DFS, topologisch) und merged die Konfigurationen."""
        if len(set(selected)) != len(selected):
            raise ValueError("Doppelte Modul-IDs in der Auswahl")
        order: List[str] = []
        visiting: List[str] = []
        visited: set = set()

        def visit(module_id: str) -> None:
            module = self._modules.get(module_id)
            if module is None:
                raise KeyError(f"Modul {module_id} nicht gefunden")
            if module_id in visited:
                return
            if module_id in visiting:
                cycle = visiting[visiting.index(module_id):] + [module_id]
                raise ValueError(f"Zyklische Abhängigkeit: {' -> '.join(cycle)}")
            visiting.append(module_id)
            for dep in module.dependencies:
                visit(dep)
            visiting.pop()
            visited.add(module_id)
            order.append(module_id)

        for module_id in selected:
            visit(module_id)

        merged: Dict[str, Any] = {}
        for module_id in order:
            module = self._modules[module_id]
            merged[module.module_type] = dict(module.config)
        return {"modules": order, "config": merged}


# ─── Map-Versionierung (Delta-Kette) ──────────────────────────────────────


@dataclass
class MapVersion:
    version: int
    parent: Optional[int]
    created_ms: int
    snapshot: Dict[str, Any]  # nur für die Basis-Version
    delta: Optional[Dict[str, Any]] = None  # {"upsert": {...}, "remove": [...]}


class MapVersioning:
    """Versionskette: Basis-Snapshot + Deltas → Rekonstruktion jeder Version."""

    def __init__(self) -> None:
        self._versions: Dict[int, MapVersion] = {}

    @property
    def latest_version(self) -> int:
        return max(self._versions) if self._versions else 0

    def create(self, snapshot: Dict[str, Any]) -> MapVersion:
        if self._versions:
            raise ValueError("Basis existiert bereits — commit() verwenden")
        version = MapVersion(
            version=1,
            parent=None,
            created_ms=int(time.time() * 1000),
            snapshot=self._clone(snapshot),
        )
        self._versions[1] = version
        return version

    def commit(self, snapshot: Dict[str, Any]) -> MapVersion:
        """Neue Version: speichert nur das Delta zur Vorgängerversion."""
        if not self._versions:
            return self.create(snapshot)
        prev = self._versions[self.latest_version]
        prev_state = self.reconstruct(prev.version)
        delta = self._diff(prev_state, snapshot)
        version = MapVersion(
            version=prev.version + 1,
            parent=prev.version,
            created_ms=int(time.time() * 1000),
            snapshot={},
            delta=delta,
        )
        self._versions[version.version] = version
        return version

    def reconstruct(self, version: Optional[int] = None) -> Dict[str, Any]:
        """Basis + Delta-Kette anwenden — ergibt den Zustand der Version."""
        if not self._versions:
            raise ValueError("Keine Versionen vorhanden")
        target = version if version is not None else self.latest_version
        if target not in self._versions:
            raise KeyError(f"Version {target} existiert nicht")

        chain: List[MapVersion] = []
        cur: Optional[int] = target
        while cur is not None:
            chain.append(self._versions[cur])
            cur = self._versions[cur].parent
        chain.reverse()

        state = self._clone(chain[0].snapshot)
        for entry in chain[1:]:
            if entry.delta:
                for key, value in (entry.delta.get("upsert") or {}).items():
                    state[key] = self._clone(value)
                for key in entry.delta.get("remove", []):
                    state.pop(key, None)
        return state

    @staticmethod
    def _diff(old: Dict[str, Any], new: Dict[str, Any]) -> Dict[str, Any]:
        upsert = {k: v for k, v in new.items() if k not in old or old[k] != v}
        remove = [k for k in old if k not in new]
        return {"upsert": upsert, "remove": remove}

    @staticmethod
    def _clone(value: Any) -> Any:
        if isinstance(value, dict):
            return {k: MapVersioning._clone(v) for k, v in value.items()}
        if isinstance(value, list):
            return [MapVersioning._clone(v) for v in value]
        return value


# ─── Kompression ──────────────────────────────────────────────────────────


def compress_json(obj: Any) -> bytes:
    """Szenario/Export-Daten komprimieren (zlib — auf dem Gerät Deflater)."""
    import json

    return zlib.compress(json.dumps(obj, ensure_ascii=False).encode("utf-8"), level=6)


def decompress_json(data: bytes) -> Any:
    import json

    return json.loads(zlib.decompress(data).decode("utf-8"))


# ─── Annotation-Templates ─────────────────────────────────────────────────


@dataclass
class AnnotationTemplate:
    id: str
    name: str
    icon_id: str
    layer: str  # TACTICAL|LOGISTICS|MEDICAL|HAZARD|COMMUNICATION|...
    annotation_type: str  # WAYPOINT|DANGER|ENTRY_POINT|...
    description: str = ""
    color: int = 0xFFCC00


ANNOTATION_TEMPLATES: List[AnnotationTemplate] = [
    # Taktik
    AnnotationTemplate("tactical_entry", "Eingang", "entry", "TACTICAL", "ENTRY_POINT", "Taktischer Eingangspunkt", 0x00FF88),
    AnnotationTemplate("tactical_exit", "Ausgang", "exit", "TACTICAL", "EXIT_POINT", "Taktischer Ausgangspunkt", 0xFF4444),
    AnnotationTemplate("tactical_command", "Führungsstelle", "command", "TACTICAL", "COMMAND_POST", "Taktische Führungsstelle", 0x4488FF),
    AnnotationTemplate("tactical_observation", "Beobachtungsposten", "observation", "TACTICAL", "OBSERVATION", "Beobachtungsposten", 0x44FF44),
    AnnotationTemplate("waypoint", "Wegpunkt", "waypoint", "TACTICAL", "WAYPOINT", "Wegpunkt", 0xFFFF00),
    AnnotationTemplate("checkpoint", "Checkpoint", "checkpoint", "TACTICAL", "CHECKPOINT", "Kontrollpunkt", 0xFF8800),
    # Logistik
    AnnotationTemplate("logistics_resupply", "Nachschub", "resupply", "LOGISTICS", "RESUPPLY", "Nachschubpunkt", 0xFFAA00),
    AnnotationTemplate("logistics_fuel", "Tankstelle", "fuel", "LOGISTICS", "FUEL_POINT", "Tankstelle", 0xFF6600),
    AnnotationTemplate("logistics_vehicle", "Fahrzeug", "vehicle", "LOGISTICS", "VEHICLE", "Fahrzeug", 0x0088FF),
    # Medizin
    AnnotationTemplate("medical_point", "Sanitätsstelle", "medical", "MEDICAL", "MEDICAL_POINT", "Sanitätsstelle", 0xFF4444),
    AnnotationTemplate("casualty", "Verletzter", "casualty", "MEDICAL", "CASUALTY", "Verletzten-Sammelpunkt", 0xFF2222),
    AnnotationTemplate("shelter", "Schutzraum", "shelter", "MEDICAL", "SHELTER", "Schutzraum", 0x44AAFF),
    # Gefahren
    AnnotationTemplate("hazard_danger", "Gefahrenzone", "danger", "HAZARD", "DANGER", "Gefahrenzone", 0xFF0000),
    AnnotationTemplate("hazard_roadblock", "Sperrung", "roadblock", "HAZARD", "ROADBLOCK", "Straßensperrung", 0xAA4444),
    AnnotationTemplate("hazard_chemical", "Gefahrstoff", "hazard", "HAZARD", "HAZARD", "Gefahrstoff", 0xFF8800),
    # Kommunikation
    AnnotationTemplate("comm_network", "Netzwerkknoten", "network", "COMMUNICATION", "COMMUNICATION", "Netzwerkknoten", 0x00FFCC),
    AnnotationTemplate("comm_radio", "Funkstelle", "communication", "COMMUNICATION", "COMMUNICATION", "Funkstelle", 0x44FF44),
    # Gebäude
    AnnotationTemplate("building", "Gebäude", "building", "OVERVIEW", "CUSTOM", "Gebäude", 0x8888FF),
    AnnotationTemplate("stairs", "Treppe", "stairs", "OVERVIEW", "CUSTOM", "Treppenhaus", 0xAAAAAA),
    AnnotationTemplate("door", "Tür", "door", "OVERVIEW", "CUSTOM", "Tür", 0xCCCC88),
    AnnotationTemplate("window", "Fenster", "window", "OVERVIEW", "CUSTOM", "Fenster", 0x88CCFF),
    AnnotationTemplate("custom", "Benutzerdefiniert", "custom", "OVERVIEW", "CUSTOM", "Benutzerdefiniert", 0xCCCCCC),
]

_TEMPLATES_BY_ID = {t.id: t for t in ANNOTATION_TEMPLATES}


def get_template(template_id: str) -> AnnotationTemplate:
    try:
        return _TEMPLATES_BY_ID[template_id]
    except KeyError as exc:
        raise KeyError(f"Template {template_id} nicht gefunden") from exc


def annotation_from_template(
    template_id: str,
    map_id: str,
    position: Dict[str, float],
    title: Optional[str] = None,
) -> Dict[str, Any]:
    """Erstellt eine Annotation aus einem Template."""
    template = get_template(template_id)
    return {
        "id": f"{int(time.time() * 1000)}_{template_id}",
        "map_id": map_id,
        "type": template.annotation_type,
        "icon_id": template.icon_id,
        "title": title or template.name,
        "description": template.description,
        "color": template.color,
        "layer": template.layer,
        "x": float(position.get("x", 0.0)),
        "y": float(position.get("y", 0.0)),
        "z": float(position.get("z", 0.0)),
        "created_at": int(time.time() * 1000),
        "created_by": "system",
    }
