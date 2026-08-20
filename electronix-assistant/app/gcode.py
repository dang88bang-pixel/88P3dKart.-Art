from __future__ import annotations

from dataclasses import dataclass


@dataclass
class SliceParams:
    dialect: str = "marlin"  # marlin | grbl
    material_temp: int = 210
    bed_temp: int = 60
    feed: int = 1500
    layer_h: float = 0.2
    layers: int = 5
    size_mm: float = 20.0


def generate_gcode(params: SliceParams) -> str:
    """Herstellerunabhängiger Marlin/GRBL-G-Code (kein Snapmaker, kein ESP32)."""
    lines = [
        "; EXA universal G-code",
        f"; dialect={params.dialect}",
        "G21 ; mm",
        "G90 ; absolute",
    ]
    if params.dialect == "marlin":
        lines += [
            "G28 ; home",
            f"M104 S{params.material_temp}",
            f"M140 S{params.bed_temp}",
            "M109 S" + str(params.material_temp),
            "M190 S" + str(params.bed_temp),
        ]
    else:
        lines += [
            "G17",
            "G94",
            "$H ; GRBL home (falls unterstützt)",
        ]
    lines.append(f"G1 Z5 F{params.feed}")
    half = params.size_mm / 2
    z = 0.0
    for i in range(params.layers):
        z = round((i + 1) * params.layer_h, 3)
        lines.append(f"; layer {i + 1}")
        lines.append(f"G1 Z{z} F{params.feed}")
        lines.append(f"G1 X{-half} Y{-half} F{params.feed}")
        lines.append(f"G1 X{half} Y{-half}")
        lines.append(f"G1 X{half} Y{half}")
        lines.append(f"G1 X{-half} Y{half}")
        lines.append(f"G1 X{-half} Y{-half}")
    if params.dialect == "marlin":
        lines += ["M104 S0", "M140 S0", "M84"]
    else:
        lines += ["M5", "G0 Z10"]
    lines.append("M2")
    return "\n".join(lines) + "\n"
