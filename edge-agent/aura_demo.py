"""Aura-Demo-Feeder — synthetische RF-/RTI-Daten an den Edge-Agent senden.

Ohne echte SDR-Hardware erzeugt dieser Feeder ein selbstkonsistentes
12-Link-RTI-Szenario (Dämpfungs-„Blob" bei (0.75, 0.75, 0.5), identisch zu
tests/test_rti.py) sowie eine Heatmap-Rasteraufnahme. Der Edge-Agent löst die
RTI und broadcastet Voxel + Zellen an alle Web-Visualizer-Clients
(docs/AURA.md §8.1).

Verwendung:

    .venv/bin/python aura_demo.py                 # einmalig senden
    .venv/bin/python aura_demo.py --loop 15       # alle 15 s (Live-Demo)
    .venv/bin/python aura_demo.py --base-url http://<edge-agent>:8080
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.request

import numpy as np

from rti_solver import Link, RtiSolver

BOUNDS_MIN = (-5.0, -5.0, 0.0)
BOUNDS_MAX = (5.0, 5.0, 1.0)
VOXEL_SIZE = 0.5
BLOB = np.array([0.75, 0.75, 0.5])
SIGMA = 1.2
AMPLITUDE_DB = 10.0


def _link_geometry():
    """12 Links: 6 Parallelen (x/y = −2, 0, 2) + 6 Diagonale (z = 0,5 m)."""
    z = 0.5
    links = [((-5.0, y, z), (5.0, y, z)) for y in (-2.0, 0.0, 2.0)]
    links += [((x, -5.0, z), (x, 5.0, z)) for x in (-2.0, 0.0, 2.0)]
    links += [
        ((-4.0, -4.0, z), (4.0, 4.0, z)),
        ((4.0, -4.0, z), (-4.0, 4.0, z)),
        ((-5.0, -2.5, z), (5.0, 2.5, z)),
        ((5.0, -2.5, z), (-5.0, 2.5, z)),
        ((-5.0, 2.5, z), (5.0, -2.5, z)),
        ((-2.5, -5.0, z), (2.5, 5.0, z)),
    ]
    return links


def _true_field(v):
    d = v - BLOB
    return float(AMPLITUDE_DB * np.exp(-np.dot(d, d) / (2.0 * SIGMA * SIGMA)))


def build_rti_payload():
    """Synthetisiert konsistente Dämpfungsmesswerte für die 12 Links."""
    solver = RtiSolver(
        bounds_min=BOUNDS_MIN,
        bounds_max=BOUNDS_MAX,
        voxel_size=VOXEL_SIZE,
        ellipse_width=0.5,
        regularization=0.05,
    )
    geometry = _link_geometry()
    for tx, rx in geometry:
        solver.add_link(Link(tx=tx, rx=rx, attenuation_db=0.0))
    weights = solver.build_weights()

    links = []
    for row, (tx, rx) in zip(weights, geometry):
        y = float(
            row
            @ np.array(
                [_true_field(solver.voxel_center(i)) for i in range(solver.voxel_count)]
            )
        )
        links.append({"tx": list(tx), "rx": list(rx), "attenuation_db": round(y, 4)})

    return {
        "device_id": "CT45P-01",
        "bounds_min": list(BOUNDS_MIN),
        "bounds_max": list(BOUNDS_MAX),
        "voxel_size": VOXEL_SIZE,
        "ellipse_width": 0.5,
        "regularization": 0.05,
        "method": "tikhonov",
        "links": links,
    }


def build_heatmap_payload():
    """Raster-Scan über den Raum; Empfangsleistung nimmt mit Abstand zum Blob ab."""
    rng = np.random.default_rng(int(time.time() * 1000) % (2**32))
    samples = []
    step = 0.5
    for x in np.arange(-4.5, 5.0, step):
        for y in np.arange(-4.5, 5.0, step):
            d = np.linalg.norm(np.array([x, y, 0.5]) - BLOB)
            # Freiraumdämpfungs-Modell mit Rauschanteil
            dbm = -40.0 - 14.0 * np.log10(max(d, 0.1) + 1.0)
            dbm += float(rng.normal(0.0, 0.6))
            samples.append(
                {
                    "x": float(x),
                    "y": float(y),
                    "z": 0.0,
                    "dbm": round(float(np.clip(dbm, -88.0, -30.0)), 2),
                    "frequency_hz": 433.92e6,
                }
            )
    return {"device_id": "CT45P-01", "cell_size_m": 0.5, "samples": samples}


def post(base_url: str, path: str, payload: dict):
    req = urllib.request.Request(
        f"{base_url}{path}",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    return body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument(
        "--loop",
        type=float,
        default=0.0,
        help="Wiederholung alle N Sekunden (0 = einmalig senden)",
    )
    args = parser.parse_args()

    while True:
        rti = post(args.base_url, "/api/v1/aura/rti", build_rti_payload())
        heat = post(args.base_url, "/api/v1/aura/heatmap", build_heatmap_payload())
        print(
            f"[{time.strftime('%H:%M:%S')}] RTI: {rti['voxel_count']} Voxel, "
            f"{rti['link_count']} Links | Heatmap: {len(heat['cells'])} Zellen"
        )
        if args.loop <= 0:
            break
        time.sleep(args.loop)


if __name__ == "__main__":
    main()
