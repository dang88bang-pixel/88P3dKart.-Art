from __future__ import annotations

from .db import connect


def simulate(material: str, power_w: float, area_cm2: float, ambient_c: float = 25.0) -> dict:
    with connect() as conn:
        row = conn.execute(
            "SELECT * FROM materials WHERE name LIKE ? LIMIT 1", (f"%{material}%",)
        ).fetchone()
    if not row:
        k = 0.3
        name = material
    else:
        k = row["thermal_k"] or 0.3
        name = row["name"]
    area_m2 = max(area_cm2, 0.1) * 1e-4
    # grobes stationäres Modell: ΔT ≈ P * L / (k A), L=1.6mm PCB
    thickness = 0.0016
    delta = (power_w * thickness) / (k * area_m2)
    tmax = ambient_c + delta
    hotspot = tmax > 85
    return {
        "material": name,
        "thermal_k": k,
        "t_ambient_c": ambient_c,
        "t_max_c": round(tmax, 2),
        "hotspot": hotspot,
        "note": "Vereinfachtes 1D-Modell. CalculiX/OpenFOAM optional lokal anbindbar.",
    }
