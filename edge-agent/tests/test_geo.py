"""Tests für Georeferenzierung: Projektion, Qualitätsableitung, Resolver-Policy."""
import math
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from config import CONFIG  # noqa: E402
from geo.base import GeoProvider, TIER_CLOUD, TIER_OFFLINE  # noqa: E402
from geo.projection import (  # noqa: E402
    bearing_deg,
    enu_to_geodetic,
    enu_to_local,
    geodetic_to_enu,
    haversine_m,
)
from geo.resolver import GeoResolver  # noqa: E402
from models import (  # noqa: E402
    GeoAnchor,
    GeoFix,
    GeolocateRequest,
    WifiAccessPoint,
    accuracy_to_quality,
)

# Brandenburger Tor / Reichstag als Referenzpaar
BRANDENBURG = (52.5163, 13.3777)
REICHSTAG = (52.5186, 13.3762)


class TestProjection:
    def test_enu_matches_haversine(self):
        e, n, _ = geodetic_to_enu(*REICHSTAG, 0.0, *BRANDENBURG, 0.0)
        enu_dist = math.hypot(e, n)
        hav = haversine_m(*BRANDENBURG, *REICHSTAG)
        # Beide Verfahren dürfen auf kurzer Distanz nur um <1 m abweichen
        assert abs(enu_dist - hav) < 1.0

    def test_roundtrip(self):
        e, n, u = geodetic_to_enu(*REICHSTAG, 34.0, *BRANDENBURG, 30.0)
        lat, lon, alt = enu_to_geodetic(e, n, u, *BRANDENBURG, 30.0)
        assert lat == pytest.approx(REICHSTAG[0], abs=1e-9)
        assert lon == pytest.approx(REICHSTAG[1], abs=1e-9)
        assert alt == pytest.approx(34.0, abs=1e-6)

    def test_identity_is_zero(self):
        e, n, u = geodetic_to_enu(*BRANDENBURG, 0.0, *BRANDENBURG, 0.0)
        assert (abs(e), abs(n), abs(u)) == pytest.approx((0.0, 0.0, 0.0), abs=1e-9)

    def test_bearing_north(self):
        # Reichstag liegt nord-nordwestlich vom Brandenburger Tor
        brg = bearing_deg(*BRANDENBURG, *REICHSTAG)
        assert 300.0 < brg < 360.0

    def test_bearing_due_east(self):
        assert bearing_deg(0.0, 0.0, 0.0, 1.0) == pytest.approx(90.0, abs=0.01)

    def test_enu_to_local_axis_mapping(self):
        """Szene-Frame: x=Ost, y=Up, z=-Nord (Three.js-Konvention)."""
        p = enu_to_local(10.0, 20.0, 5.0, heading_deg=0.0, local_origin=(0.0, 0.0, 0.0))
        assert p[0] == pytest.approx(10.0)
        assert p[1] == pytest.approx(5.0)
        assert p[2] == pytest.approx(-20.0)

    def test_enu_to_local_heading_rotation(self):
        """Heading 90° (Blickrichtung Ost) dreht Ost nach vorn (-z)."""
        p = enu_to_local(10.0, 0.0, 0.0, heading_deg=90.0, local_origin=(0.0, 0.0, 0.0))
        assert p[0] == pytest.approx(0.0, abs=1e-9)
        assert p[2] == pytest.approx(-10.0, abs=1e-9)

    def test_enu_to_local_origin_offset(self):
        p = enu_to_local(0.0, 0.0, 0.0, heading_deg=0.0, local_origin=(1.0, 2.0, 3.0))
        assert p == pytest.approx([1.0, 2.0, 3.0])


class TestQuality:
    @pytest.mark.parametrize(
        "accuracy,expected",
        [(1.0, 1.00), (10.0, 0.75), (100.0, 0.50), (1000.0, 0.25), (10000.0, 0.00)],
    )
    def test_mapping(self, accuracy, expected):
        assert accuracy_to_quality(accuracy) == pytest.approx(expected, abs=0.005)

    def test_ip_fix_fails_gate(self):
        """Ein IP-Fix (~25 km) muss unter GEO_MIN_QUALITY landen."""
        assert accuracy_to_quality(25000.0) < CONFIG.GEO_MIN_QUALITY

    def test_cell_fix_passes_gate(self):
        """Regression: Zellortung erreicht bestenfalls ~150 m. Eine Schwelle
        von 0.5 (= 100 m) hätte die Offline-Kette dauerhaft leer laufen lassen."""
        assert accuracy_to_quality(150.0) > CONFIG.GEO_MIN_QUALITY

    def test_wifi_fix_passes_gate(self):
        """Ein WLAN-Fix (~30 m) muss die Schwelle überschreiten."""
        assert accuracy_to_quality(30.0) > CONFIG.GEO_MIN_QUALITY

    def test_clamped(self):
        assert accuracy_to_quality(0.01) == 1.0
        assert accuracy_to_quality(1e9) == 0.0


class _FakeProvider(GeoProvider):
    """Provider-Attrappe mit steuerbarem Ergebnis."""

    def __init__(self, name, tier, accuracy=None, available=True):
        self.name = name
        self.tier = tier
        self.license = "TEST"
        self._accuracy = accuracy
        self._available = available
        self.calls = 0

    def available(self) -> bool:
        return self._available

    async def locate(self, req):
        self.calls += 1
        if self._accuracy is None:
            return None
        return self.make_fix(52.52, 13.405, self._accuracy)


def _wifi_request():
    return GeolocateRequest(
        wifiAccessPoints=[
            WifiAccessPoint(macAddress="00:11:22:33:44:55", signalStrength=-65),
            WifiAccessPoint(macAddress="00:11:22:33:44:66", signalStrength=-70),
        ]
    )


class TestResolverPolicy:
    @pytest.mark.asyncio
    async def test_offline_only_blocks_cloud(self, cfg):
        cfg("GEO_OFFLINE_ONLY", True)
        cloud = _FakeProvider("cloud", TIER_CLOUD, accuracy=20.0)
        resolver = GeoResolver(providers=[cloud])

        fix = await resolver.locate(_wifi_request())

        assert fix is None, "Cloud-Provider darf im Offline-Modus nicht befragt werden"
        assert cloud.calls == 0

    @pytest.mark.asyncio
    async def test_offline_provider_used(self, cfg):
        cfg("GEO_OFFLINE_ONLY", True)
        offline = _FakeProvider("offline", TIER_OFFLINE, accuracy=200.0)
        resolver = GeoResolver(providers=[offline])

        fix = await resolver.locate(_wifi_request())

        assert fix is not None
        assert fix.source == "offline"
        assert offline.calls == 1

    @pytest.mark.asyncio
    async def test_min_quality_gate(self, cfg):
        """Ein zu ungenauer Fix wird verworfen, nicht durchgereicht."""
        cfg("GEO_OFFLINE_ONLY", True)
        cfg("GEO_MIN_QUALITY", 0.5)
        coarse = _FakeProvider("coarse", TIER_OFFLINE, accuracy=50000.0)
        resolver = GeoResolver(providers=[coarse])

        assert await resolver.locate(_wifi_request()) is None

    @pytest.mark.asyncio
    async def test_chain_falls_through(self, cfg):
        cfg("GEO_OFFLINE_ONLY", True)
        first = _FakeProvider("first", TIER_OFFLINE, accuracy=None)
        second = _FakeProvider("second", TIER_OFFLINE, accuracy=40.0)
        resolver = GeoResolver(providers=[first, second])

        fix = await resolver.locate(_wifi_request())

        assert fix is not None and fix.source == "second"
        assert first.calls == 1 and second.calls == 1

    @pytest.mark.asyncio
    async def test_disabled_returns_none(self, cfg):
        cfg("GEO_ENABLED", False)
        provider = _FakeProvider("offline", TIER_OFFLINE, accuracy=10.0)
        resolver = GeoResolver(providers=[provider])

        assert await resolver.locate(_wifi_request()) is None
        assert provider.calls == 0

    @pytest.mark.asyncio
    async def test_empty_request_rejected(self, cfg):
        cfg("GEO_OFFLINE_ONLY", True)
        provider = _FakeProvider("offline", TIER_OFFLINE, accuracy=10.0)
        resolver = GeoResolver(providers=[provider])

        assert await resolver.locate(GeolocateRequest()) is None
        assert provider.calls == 0

    @pytest.mark.asyncio
    async def test_cache_hit_skips_provider(self, cfg):
        cfg("GEO_OFFLINE_ONLY", True)
        provider = _FakeProvider("offline", TIER_OFFLINE, accuracy=25.0)
        resolver = GeoResolver(providers=[provider])
        req = _wifi_request()

        first = await resolver.locate(req)
        second = await resolver.locate(req)

        assert first is not None and second is not None
        assert provider.calls == 1, "Identischer Scan muss aus dem Cache kommen"

    @pytest.mark.asyncio
    async def test_audit_log_records_attempts(self, cfg):
        cfg("GEO_OFFLINE_ONLY", True)
        provider = _FakeProvider("offline", TIER_OFFLINE, accuracy=25.0)
        resolver = GeoResolver(providers=[provider])

        await resolver.locate(_wifi_request())

        assert len(resolver.audit_log) >= 1
        assert any(e.get("provider") == "offline" for e in resolver.audit_log)


class TestAnchor:
    def test_anchor_lifecycle(self):
        resolver = GeoResolver(providers=[])
        assert resolver.anchor is None

        anchor = GeoAnchor(
            fix=GeoFix(
                lat=BRANDENBURG[0],
                lon=BRANDENBURG[1],
                accuracy_m=5.0,
                source="manual",
                license="n/a",
                timestamp=0.0,
                quality=1.0,
            ),
            local_local_origin=(0.0, 0.0, 0.0),
            heading_deg=0.0,
        )
        resolver.set_anchor(anchor)
        assert resolver.anchor is not None
        assert resolver.anchor.fix.lat == pytest.approx(BRANDENBURG[0])

        resolver.clear_anchor()
        assert resolver.anchor is None

    def test_describe_providers_is_serialisable(self):
        resolver = GeoResolver(providers=[_FakeProvider("x", TIER_OFFLINE)])
        described = resolver.describe_providers()
        assert described and described[0]["name"] == "x"
