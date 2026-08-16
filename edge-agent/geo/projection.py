"""WGS84 <-> ENU <-> lokaler Agent-Frame.

Das ist die Koordinatenbrücke aus docs/API_INTEGRATION_REVIEW.md (Blocker A).
Ohne sie ist keine externe lat/lon-Entität in der lokalen metrischen Szene
platzierbar.

Verwendet wird die geodätische ENU-Transformation auf dem WGS84-Ellipsoid
(keine Kugelnäherung), weil der Höhenfehler einer Kugelnäherung in
mitteleuropäischen Breiten bereits bei wenigen Kilometern Distanz spürbar ist.

Konventionen des Agent-Frames (three.js-kompatibel, siehe web-visualizer):
    x -> Ost      (nach Anwendung von heading_deg)
    y -> Hoch     (Up)
    z -> Süd      (= -Nord; three.js ist rechtshändig mit y=oben)
"""
from __future__ import annotations

import math
from typing import Tuple

# WGS84-Ellipsoidparameter
_A = 6378137.0  # große Halbachse [m]
_F = 1.0 / 298.257223563  # Abplattung
_E2 = _F * (2.0 - _F)  # erste numerische Exzentrizität zum Quadrat


def _n_radius(sin_lat: float) -> float:
    """Querkrümmungshalbmesser N(phi)."""
    return _A / math.sqrt(1.0 - _E2 * sin_lat * sin_lat)


def geodetic_to_ecef(lat: float, lon: float, alt: float = 0.0) -> Tuple[float, float, float]:
    """WGS84 (Grad, Grad, Meter) -> geozentrisch kartesisch (Meter)."""
    lat_r = math.radians(lat)
    lon_r = math.radians(lon)
    sin_lat, cos_lat = math.sin(lat_r), math.cos(lat_r)
    sin_lon, cos_lon = math.sin(lon_r), math.cos(lon_r)

    n = _n_radius(sin_lat)
    x = (n + alt) * cos_lat * cos_lon
    y = (n + alt) * cos_lat * sin_lon
    z = (n * (1.0 - _E2) + alt) * sin_lat
    return x, y, z


def ecef_to_enu(
    x: float,
    y: float,
    z: float,
    ref_lat: float,
    ref_lon: float,
    ref_alt: float = 0.0,
) -> Tuple[float, float, float]:
    """ECEF -> lokales East/North/Up relativ zum Referenzpunkt."""
    ref_x, ref_y, ref_z = geodetic_to_ecef(ref_lat, ref_lon, ref_alt)
    dx, dy, dz = x - ref_x, y - ref_y, z - ref_z

    lat_r = math.radians(ref_lat)
    lon_r = math.radians(ref_lon)
    sin_lat, cos_lat = math.sin(lat_r), math.cos(lat_r)
    sin_lon, cos_lon = math.sin(lon_r), math.cos(lon_r)

    east = -sin_lon * dx + cos_lon * dy
    north = -sin_lat * cos_lon * dx - sin_lat * sin_lon * dy + cos_lat * dz
    up = cos_lat * cos_lon * dx + cos_lat * sin_lon * dy + sin_lat * dz
    return east, north, up


def geodetic_to_enu(
    lat: float,
    lon: float,
    alt: float,
    ref_lat: float,
    ref_lon: float,
    ref_alt: float = 0.0,
) -> Tuple[float, float, float]:
    """WGS84 -> ENU relativ zu einem Referenzpunkt."""
    x, y, z = geodetic_to_ecef(lat, lon, alt)
    return ecef_to_enu(x, y, z, ref_lat, ref_lon, ref_alt)


def enu_to_geodetic(
    east: float,
    north: float,
    up: float,
    ref_lat: float,
    ref_lon: float,
    ref_alt: float = 0.0,
) -> Tuple[float, float, float]:
    """ENU -> WGS84 (Rückrichtung, iterationsfrei über ECEF)."""
    lat_r = math.radians(ref_lat)
    lon_r = math.radians(ref_lon)
    sin_lat, cos_lat = math.sin(lat_r), math.cos(lat_r)
    sin_lon, cos_lon = math.sin(lon_r), math.cos(lon_r)

    dx = -sin_lon * east - sin_lat * cos_lon * north + cos_lat * cos_lon * up
    dy = cos_lon * east - sin_lat * sin_lon * north + cos_lat * sin_lon * up
    dz = cos_lat * north + sin_lat * up

    ref_x, ref_y, ref_z = geodetic_to_ecef(ref_lat, ref_lon, ref_alt)
    x, y, z = ref_x + dx, ref_y + dy, ref_z + dz

    # Bowring-Verfahren (konvergiert für terrestrische Höhen in einem Schritt)
    lon_out = math.atan2(y, x)
    p = math.hypot(x, y)
    if p < 1e-9:
        lat_out = math.copysign(math.pi / 2.0, z)
        alt_out = abs(z) - _A * math.sqrt(1.0 - _E2)
        return math.degrees(lat_out), math.degrees(lon_out), alt_out

    lat_out = math.atan2(z, p * (1.0 - _E2))
    for _ in range(5):
        sin_l = math.sin(lat_out)
        n = _n_radius(sin_l)
        alt_out = p / math.cos(lat_out) - n
        lat_new = math.atan2(z, p * (1.0 - _E2 * n / (n + alt_out)))
        if abs(lat_new - lat_out) < 1e-12:
            lat_out = lat_new
            break
        lat_out = lat_new

    sin_l = math.sin(lat_out)
    n = _n_radius(sin_l)
    alt_out = p / math.cos(lat_out) - n
    return math.degrees(lat_out), math.degrees(lon_out), alt_out


def enu_to_local(
    east: float,
    north: float,
    up: float,
    heading_deg: float | None = None,
    local_origin: Tuple[float, float, float] = (0.0, 0.0, 0.0),
) -> Tuple[float, float, float]:
    """ENU -> Agent-Frame (x=rechts, y=hoch, z=hinten; three.js-Konvention).

    ``heading_deg`` ist die Kompassrichtung (0=Nord, 90=Ost), in die die
    lokale +x-Achse zeigt. Ohne Heading wird +x = Ost angenommen.
    """
    if heading_deg:
        # Drehung des ENU-Systems um die Up-Achse in den lokalen Frame
        a = math.radians(heading_deg)
        cos_a, sin_a = math.cos(a), math.sin(a)
        e_rot = east * cos_a - north * sin_a
        n_rot = east * sin_a + north * cos_a
    else:
        e_rot, n_rot = east, north

    x = e_rot + local_origin[0]
    y = up + local_origin[1]
    z = -n_rot + local_origin[2]  # three.js: -z zeigt nach vorn/Nord
    return x, y, z


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Grosskreisdistanz [m]. Für Radiusfilter ausreichend und schnell."""
    r = 6371008.8  # mittlerer Erdradius
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Rechtweisende Peilung von Punkt 1 nach Punkt 2 [0..360)."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0
