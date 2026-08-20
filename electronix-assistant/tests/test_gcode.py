from app.gcode import SliceParams, generate_gcode


def test_marlin_has_heat_and_home():
    g = generate_gcode(SliceParams(dialect="marlin", layers=2))
    assert "G21" in g
    assert "G90" in g
    assert "M104" in g
    assert "G28" in g


def test_grbl_has_no_hotend():
    g = generate_gcode(SliceParams(dialect="grbl", layers=1))
    assert "G17" in g
    assert "M104" not in g
