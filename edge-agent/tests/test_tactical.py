"""Tests für das taktische Map-/Szenario-Management (docs/TACTICAL.md)."""

import pytest

from tactical import (
    ANNOTATION_TEMPLATES,
    MapVersioning,
    ScenarioComposer,
    ScenarioModule,
    annotation_from_template,
    compress_json,
    decompress_json,
    get_template,
)


def test_composer_resolves_dependency_closure():
    composer = ScenarioComposer(
        [
            ScenarioModule("urban_terrain", "Urbane Umgebung", "terrain", {"density": 0.8}),
            ScenarioModule("civilian_population", "Zivilbevölkerung", "personnel",
                           {"density": 0.3}, dependencies=["urban_terrain"]),
            ScenarioModule("hostile_forces", "Feindliche Kräfte", "personnel",
                           {"count": 6}, dependencies=["urban_terrain"]),
        ]
    )
    result = composer.build(["hostile_forces"])
    # Abhängigkeit wird automatisch mit aufgelöst und vor dem Modul einsortiert
    assert result["modules"][0] == "urban_terrain"
    assert result["modules"][1] == "hostile_forces"
    assert result["config"]["terrain"]["density"] == 0.8
    assert result["config"]["personnel"]["count"] == 6


def test_composer_rejects_missing_module():
    composer = ScenarioComposer([ScenarioModule("a", "A", "terrain")])
    with pytest.raises(KeyError):
        composer.build(["unbekannt"])


def test_composer_detects_cycles():
    composer = ScenarioComposer(
        [
            ScenarioModule("a", "A", "terrain", dependencies=["b"]),
            ScenarioModule("b", "B", "terrain", dependencies=["a"]),
        ]
    )
    with pytest.raises(ValueError, match="Zyklische"):
        composer.build(["a"])


def test_composer_rejects_duplicates():
    composer = ScenarioComposer([ScenarioModule("a", "A", "terrain")])
    with pytest.raises(ValueError, match="Doppelte"):
        composer.build(["a", "a"])


def test_map_versioning_delta_chain_reconstructs():
    versioning = MapVersioning()
    versioning.create({"voxel_1": [0, 0, 0], "voxel_2": [1, 0, 0]})
    versioning.commit({"voxel_1": [0, 0, 0], "voxel_2": [2, 0, 0], "voxel_3": [3, 0, 0]})
    versioning.commit({"voxel_3": [3, 0, 0]})  # v2 entfernt

    latest = versioning.reconstruct()
    assert latest == {"voxel_3": [3, 0, 0]}
    v1 = versioning.reconstruct(1)
    assert v1 == {"voxel_1": [0, 0, 0], "voxel_2": [1, 0, 0]}
    v2 = versioning.reconstruct(2)
    assert v2 == {"voxel_1": [0, 0, 0], "voxel_2": [2, 0, 0], "voxel_3": [3, 0, 0]}
    assert versioning.latest_version == 3


def test_map_versioning_rejects_unknown_version():
    versioning = MapVersioning()
    versioning.create({"a": 1})
    with pytest.raises(KeyError):
        versioning.reconstruct(42)


def test_compress_roundtrip():
    payload = {"name": "Evakuierung", "modules": ["a", "b"], "params": {"persons": 50}}
    assert decompress_json(compress_json(payload)) == payload
    assert len(compress_json(payload)) > 0


def test_templates_cover_20_icons():
    assert len(ANNOTATION_TEMPLATES) >= 20
    template = get_template("tactical_entry")
    assert template.layer == "TACTICAL"
    with pytest.raises(KeyError):
        get_template("gibt_es_nicht")


def test_annotation_from_template():
    annotation = annotation_from_template(
        "medical_point", "map-1", {"x": 1.5, "y": 2.5, "z": 0.0}
    )
    assert annotation["map_id"] == "map-1"
    assert annotation["icon_id"] == "medical"
    assert annotation["layer"] == "MEDICAL"
    assert annotation["x"] == 1.5
    assert annotation["title"] == "Sanitätsstelle"
