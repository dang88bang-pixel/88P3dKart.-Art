"""Tests für den Aura RTI-Solver (Python-Port, docs/AURA.md §4.1).

Szenario: Dämpfungs-„Blob" (Person/Objekt) in einem 10 m × 10 m × 1 m Raum,
12 Messlinien (6 Parallelen + 6 Diagonale), Ellipsenbreite 0,5 m. Messwerte
werden mit demselben Gewichtungsmodell synthetisiert (selbstkonsistentes
System y = A·φ).

Erwartung: Tikhonov lokalisiert den Blob präzise; Backprojection trägt das
Signal (positive Korrelation mit dem wahren Feld, Blob-Region über den
Ecken-Regionen) — ihr globales Argmax liegt bekanntermaßen in dünn
abgedeckten Randregionen (RTI-Literatur, Wilson & Patwari).
"""

import numpy as np

from rti_solver import Link, RfSample, RtiSolver, build_heatmap

BOUNDS_MIN = (-5.0, -5.0, 0.0)
BOUNDS_MAX = (5.0, 5.0, 1.0)
VOXEL_SIZE = 0.5
BLOB = np.array([0.75, 0.75, 0.5])
SIGMA = 1.2
AMPLITUDE_DB = 10.0
ELLIPSE_WIDTH = 0.5


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


def _create_solver(regularization=0.05):
    solver = RtiSolver(
        bounds_min=BOUNDS_MIN,
        bounds_max=BOUNDS_MAX,
        voxel_size=VOXEL_SIZE,
        ellipse_width=ELLIPSE_WIDTH,
        regularization=regularization,
    )
    geometry = _link_geometry()
    for tx, rx in geometry:
        solver.add_link(Link(tx=tx, rx=rx, attenuation_db=0.0))
    weights = solver.build_weights()
    solver.clear_links()
    for row, (tx, rx) in zip(weights, geometry):
        y = float(
            row
            @ np.array([_true_field(solver.voxel_center(i)) for i in range(solver.voxel_count)])
        )
        solver.add_link(Link(tx=tx, rx=rx, attenuation_db=y))
    return solver


def _distance(a, b):
    return float(np.linalg.norm(np.asarray(a) - np.asarray(b)))


def test_tikhonov_localizes_blob():
    solver = _create_solver()
    field = solver.solve()
    assert len(field) == 20 * 20 * 2
    argmax = max(field, key=lambda v: v.attenuation)
    err = _distance((argmax.x, argmax.y, argmax.z), BLOB)
    assert err <= 1.5 * VOXEL_SIZE, f"Rekonstruktion zu ungenau: Fehler {err:.2f} m"


def test_backprojection_carries_localization_signal():
    solver = _create_solver()
    field = solver.solve_backprojection()
    bp = np.array([v.attenuation for v in field])
    true = np.array([_true_field(solver.voxel_center(i)) for i in range(solver.voxel_count)])

    # Positive Korrelation mit dem wahren Feld
    r = np.corrcoef(bp, true)[0, 1]
    assert r > 0.2, f"Korrelation zu gering: r={r:.3f}"

    # Blob-Region (r = 2 m) muss über den Ecken-Regionen liegen
    def region_mean(center, radius=2.0):
        vals = [
            a
            for i, a in enumerate(bp)
            if np.linalg.norm(solver.voxel_center(i) - center) <= radius
        ]
        return float(np.mean(vals))

    near_blob = region_mean(BLOB)
    near_corner_1 = region_mean(np.array([4.5, 4.5, 0.5]))
    near_corner_2 = region_mean(np.array([-4.5, 4.5, 0.5]))
    assert near_blob > near_corner_1
    assert near_blob > near_corner_2


def test_locate_peaks_returns_limited_separated_maxima():
    solver = _create_solver()
    field = solver.solve()
    peaks = solver.locate_peaks(field, top_k=4, min_separation_voxels=2)
    assert 0 < len(peaks) <= 4
    err = _distance((peaks[0].x, peaks[0].y, peaks[0].z), BLOB)
    assert err <= 2.5 * VOXEL_SIZE


def _total_variation(field):
    """Nachbarschafts-Variation des Felds (Glättungsmaß, Grid 20×20×2)."""
    values = np.array([v.attenuation for v in field]).reshape(2, 20, 20)
    diff = (
        np.abs(np.diff(values, axis=0)).sum()
        + np.abs(np.diff(values, axis=1)).sum()
        + np.abs(np.diff(values, axis=2)).sum()
    )
    return float(diff)


def test_tikhonov_smoothing_reduces_noise_while_keeping_blob():
    """Glättungs-Regularisierung (Graph-Laplacian) reduziert die Variation
    des rekonstruierten Felds, ohne die Blob-Lokalisierung zu verlieren."""
    solver_plain = _create_solver()
    field_plain = solver_plain.solve()

    solver_smooth = _create_solver()
    solver_smooth.smoothing = 2.0
    field_smooth = solver_smooth.solve()

    tv_plain = _total_variation(field_plain)
    tv_smooth = _total_variation(field_smooth)
    assert tv_smooth <= tv_plain + 1e-9, f"Glättung erhöht die Variation ({tv_smooth} > {tv_plain})"

    argmax = max(field_smooth, key=lambda v: v.attenuation)
    err = _distance((argmax.x, argmax.y, argmax.z), BLOB)
    assert err <= 2.0 * VOXEL_SIZE, f"Glättung verschiebt den Blob: {err:.2f} m"


def test_empty_solver_returns_empty_field():
    solver = RtiSolver(BOUNDS_MIN, BOUNDS_MAX, VOXEL_SIZE)
    assert solver.solve() == []
    assert solver.solve_backprojection() == []


def test_heatmap_aggregates_and_normalizes():
    samples = [
        RfSample(0, 0.3, 0.3, 0.0, -50.0, 433.92e6),
        RfSample(0, 0.7, 0.6, 0.0, -40.0, 433.92e6),
        RfSample(0, 2.3, 2.3, 0.0, -90.0, 433.92e6),
    ]
    cells = build_heatmap(samples, cell_size_m=1.0, max_height_m=12.0)
    assert len(cells) == 2
    cell_a = next(c for c in cells if c.center_x == 0.5 and c.center_y == 0.5)
    assert abs(cell_a.dbm - (-45.0)) < 1e-3
    assert abs(cell_a.height_m - 9.0) < 1e-3  # (−45+90)/60 · 12
    cell_b = next(c for c in cells if c.center_x == 2.5 and c.center_y == 2.5)
    assert abs(cell_b.height_m - 0.0) < 1e-3


def test_heatmap_empty_input():
    assert build_heatmap([]) == []


def test_voxel_grid_rejects_huge_grids():
    try:
        RtiSolver(BOUNDS_MIN, BOUNDS_MAX, voxel_size=0.001)
        assert False, "sollte ValueError werfen"
    except ValueError:
        pass
