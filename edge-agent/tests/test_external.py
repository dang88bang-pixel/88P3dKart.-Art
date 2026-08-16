"""Tests für externe Tracking-Feeds: Protobuf-Decoder, GTFS-RT, Manager."""
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from config import CONFIG  # noqa: E402
from external import protobuf_lite as pb  # noqa: E402
from external.base import ExternalSource  # noqa: E402
from external.gtfs_rt import OCCUPANCY_LABELS, parse_feed  # noqa: E402
from external.manager import ExternalEntityManager, latency_quality  # noqa: E402
from geo.resolver import GeoResolver  # noqa: E402
from models import ExternalEntity, GeoAnchor, GeoFix  # noqa: E402

BERLIN = (52.5163, 13.3777)


# ─── Fixture-Bau: echte GTFS-RT-Wire-Bytes ohne protobuf-Abhängigkeit ───
def build_position(lat: float, lon: float, bearing=None, speed=None) -> bytes:
    out = pb.encode_float(1, lat) + pb.encode_float(2, lon)
    if bearing is not None:
        out += pb.encode_float(3, bearing)
    if speed is not None:
        out += pb.encode_float(5, speed)
    return out


def build_vehicle_position(
    lat, lon, *, trip_id=None, route_id=None, vehicle_id=None,
    label=None, ts=None, stop_id=None, occupancy=None, position=True,
) -> bytes:
    out = b""
    if trip_id or route_id:
        trip = b""
        if trip_id:
            trip += pb.encode_string(1, trip_id)
        if route_id:
            trip += pb.encode_string(5, route_id)
        out += pb.encode_submessage(1, trip)
    if position:
        out += pb.encode_submessage(2, build_position(lat, lon))
    if ts is not None:
        out += pb.encode_uint(5, int(ts))
    if stop_id:
        out += pb.encode_string(7, stop_id)
    if vehicle_id or label:
        desc = b""
        if vehicle_id:
            desc += pb.encode_string(1, vehicle_id)
        if label:
            desc += pb.encode_string(2, label)
        out += pb.encode_submessage(8, desc)
    if occupancy is not None:
        out += pb.encode_uint(9, occupancy)
    return out


def build_feed(vehicles, header_ts=None) -> bytes:
    header = pb.encode_string(1, "2.0")
    if header_ts is not None:
        header += pb.encode_uint(3, int(header_ts))
    out = pb.encode_submessage(1, header)
    for entity_id, vp in vehicles:
        entity = pb.encode_string(1, entity_id) + pb.encode_submessage(4, vp)
        out += pb.encode_submessage(2, entity)
    return out


class TestProtobufLite:
    def test_varint_roundtrip(self):
        msg = pb.parse_message(pb.encode_uint(1, 300) + pb.encode_uint(2, 0))
        assert pb.get_uint(msg, 1) == 300
        assert pb.get_uint(msg, 2) == 0

    def test_string_roundtrip(self):
        msg = pb.parse_message(pb.encode_string(3, "Straßenbahn M10"))
        assert pb.get_string(msg, 3) == "Straßenbahn M10"

    def test_float_roundtrip(self):
        msg = pb.parse_message(pb.encode_float(1, 52.5163))
        assert pb.get_float(msg, 1) == pytest.approx(52.5163, abs=1e-5)

    def test_nested_submessage(self):
        inner = pb.encode_string(1, "abc")
        msg = pb.parse_message(pb.encode_submessage(7, inner))
        sub = pb.get_submessage(msg, 7)
        assert sub is not None and pb.get_string(sub, 1) == "abc"

    def test_repeated_submessages(self):
        payload = pb.encode_submessage(2, pb.encode_string(1, "a"))
        payload += pb.encode_submessage(2, pb.encode_string(1, "b"))
        msg = pb.parse_message(payload)
        subs = pb.get_repeated_submessages(msg, 2)
        assert [pb.get_string(s, 1) for s in subs] == ["a", "b"]

    def test_missing_field_returns_none(self):
        msg = pb.parse_message(pb.encode_uint(1, 5))
        assert pb.get_string(msg, 99) is None
        assert pb.get_uint(msg, 99) is None

    def test_truncated_input_raises(self):
        with pytest.raises(pb.ProtobufError):
            pb.parse_message(pb.encode_string(1, "hello")[:-2])

    def test_empty_message(self):
        assert pb.parse_message(b"") == {}


class TestGtfsRtParsing:
    def test_single_vehicle(self):
        now = time.time()
        feed = build_feed(
            [("e1", build_vehicle_position(
                *BERLIN, trip_id="t1", route_id="M10",
                vehicle_id="v1", label="Wagen 3", ts=now, occupancy=2))],
            header_ts=now,
        )
        header_ts, entities = parse_feed(feed)

        assert header_ts == pytest.approx(int(now))
        assert len(entities) == 1
        e = entities[0]
        assert e.entity_type == "vehicle"
        assert e.lat == pytest.approx(BERLIN[0], abs=1e-4)
        assert e.lon == pytest.approx(BERLIN[1], abs=1e-4)
        assert e.metadata["route_id"] == "M10"
        assert e.metadata["trip_id"] == "t1"
        assert e.metadata["vehicle_label"] == "Wagen 3"
        assert e.metadata["occupancy_status"] == OCCUPANCY_LABELS[2]

    def test_id_never_marked_stable(self):
        """Feed-IDs sind agenturintern und rotieren — nie als stabil melden."""
        feed = build_feed([("e1", build_vehicle_position(*BERLIN, vehicle_id="v1"))])
        _, entities = parse_feed(feed)
        assert entities[0].id_is_stable is False

    def test_id_fallback_chain(self):
        feed = build_feed([
            ("ent-a", build_vehicle_position(*BERLIN, vehicle_id="veh-1", trip_id="tr-1")),
            ("ent-b", build_vehicle_position(*BERLIN, trip_id="tr-2")),
            ("ent-c", build_vehicle_position(*BERLIN)),
        ])
        _, entities = parse_feed(feed)
        ids = [e.entity_id for e in entities]
        assert "veh-1" in ids[0]
        assert "tr-2" in ids[1]
        assert "ent-c" in ids[2]

    def test_vehicle_without_position_dropped(self):
        feed = build_feed([("e1", build_vehicle_position(*BERLIN, position=False))])
        _, entities = parse_feed(feed)
        assert entities == []

    def test_null_island_dropped(self):
        """0/0 ist der klassische Platzhalter defekter Feeds."""
        feed = build_feed([("e1", build_vehicle_position(0.0, 0.0))])
        _, entities = parse_feed(feed)
        assert entities == []

    def test_out_of_range_dropped(self):
        feed = build_feed([("e1", build_vehicle_position(95.0, 200.0))])
        _, entities = parse_feed(feed)
        assert entities == []

    def test_non_vehicle_entity_ignored(self):
        """FeedEntity mit trip_update (Feld 3) statt vehicle (Feld 4)."""
        entity = pb.encode_string(1, "e1") + pb.encode_submessage(
            3, pb.encode_string(1, "trip")
        )
        feed = pb.encode_submessage(1, pb.encode_string(1, "2.0"))
        feed += pb.encode_submessage(2, entity)
        _, entities = parse_feed(feed)
        assert entities == []

    def test_license_attached(self):
        feed = build_feed([("e1", build_vehicle_position(*BERLIN))])
        _, entities = parse_feed(feed)
        assert entities[0].license == CONFIG.GTFS_RT_LICENSE

    def test_multiple_vehicles(self):
        feed = build_feed([
            (f"e{i}", build_vehicle_position(52.51 + i * 0.001, 13.37, vehicle_id=f"v{i}"))
            for i in range(5)
        ])
        _, entities = parse_feed(feed)
        assert len(entities) == 5

    def test_empty_feed(self):
        header_ts, entities = parse_feed(build_feed([], header_ts=1700000000))
        assert entities == []
        assert header_ts == 1700000000


class TestLatencyQuality:
    def test_fresh_is_one(self):
        assert latency_quality(0.0, 120.0) == pytest.approx(1.0)

    def test_at_limit_is_zero(self):
        assert latency_quality(120.0, 120.0) == pytest.approx(0.0)

    def test_half_way(self):
        assert latency_quality(60.0, 120.0) == pytest.approx(0.5)

    def test_beyond_limit_clamped(self):
        assert latency_quality(999.0, 120.0) == 0.0


class _StubSource(ExternalSource):
    name = "stub"
    entity_type = "vehicle"
    license = "TEST"

    def __init__(self, entities):
        super().__init__()
        self._entities = entities
        self.last_success = time.time()

    async def fetch(self):
        return self._entities


def _entity(lat, lon, *, eid="x", age=0.0, etype="vehicle"):
    now = time.time()
    return ExternalEntity(
        entity_id=eid,
        entity_type=etype,
        source="stub",
        license="TEST",
        lat=lat,
        lon=lon,
        timestamp=now - age,
        received_at=now,
    )


def _anchored_resolver():
    resolver = GeoResolver(providers=[])
    resolver.set_anchor(
        GeoAnchor(
            fix=GeoFix(
                lat=BERLIN[0], lon=BERLIN[1], accuracy_m=5.0,
                source="manual", license="n/a", timestamp=time.time(), quality=1.0,
            ),
            local_origin=[0.0, 0.0, 0.0],
            heading_deg=0.0,
        )
    )
    return resolver


class TestManager:
    def test_projection_sets_local_and_distance(self):
        mgr = ExternalEntityManager(
            _anchored_resolver(), sources=[_StubSource([_entity(52.5186, 13.3762)])]
        )
        entities = mgr.collect()
        assert len(entities) == 1
        e = entities[0]
        assert e.distance_m == pytest.approx(280.0, abs=15.0)
        assert e.local is not None and len(e.local) == 3
        assert "bearing_from_anchor" in e.metadata

    def test_no_anchor_means_no_local(self):
        mgr = ExternalEntityManager(
            GeoResolver(providers=[]), sources=[_StubSource([_entity(*BERLIN)])]
        )
        e = mgr.collect()[0]
        assert e.local is None
        assert e.distance_m is None

    def test_radius_filter(self, cfg):
        cfg("EXT_RADIUS_M", 500.0)
        mgr = ExternalEntityManager(
            _anchored_resolver(),
            sources=[_StubSource([
                _entity(52.5186, 13.3762, eid="near"),   # ~280 m
                _entity(52.6000, 13.3777, eid="far"),    # ~9 km
            ])],
        )
        ids = [e.entity_id for e in mgr.collect()]
        assert ids == ["near"]

    def test_staleness_flag(self, cfg):
        cfg("EXT_MAX_AGE_S", 120.0)
        cfg("EXT_MIN_QUALITY", 0.0)
        mgr = ExternalEntityManager(
            _anchored_resolver(),
            sources=[_StubSource([_entity(*BERLIN, eid="old", age=300.0)])],
        )
        e = mgr.collect()[0]
        assert e.stale is True
        assert e.quality == 0.0

    def test_min_quality_drops_old_entity(self, cfg):
        cfg("EXT_MAX_AGE_S", 120.0)
        cfg("EXT_MIN_QUALITY", 0.3)
        mgr = ExternalEntityManager(
            _anchored_resolver(),
            sources=[_StubSource([
                _entity(*BERLIN, eid="fresh", age=5.0),
                _entity(52.5164, 13.3778, eid="old", age=110.0),
            ])],
        )
        assert [e.entity_id for e in mgr.collect()] == ["fresh"]

    def test_dedup_prefers_newer(self, cfg):
        cfg("EXT_MIN_QUALITY", 0.0)
        mgr = ExternalEntityManager(
            _anchored_resolver(),
            sources=[_StubSource([
                _entity(52.51630, 13.37770, eid="stale-copy", age=60.0),
                _entity(52.516301, 13.377701, eid="fresh-copy", age=1.0),
            ])],
        )
        collected = mgr.collect()
        assert len(collected) == 1
        assert collected[0].entity_id == "fresh-copy"

    def test_different_types_not_deduped(self, cfg):
        cfg("EXT_MIN_QUALITY", 0.0)
        mgr = ExternalEntityManager(
            _anchored_resolver(),
            sources=[_StubSource([
                _entity(*BERLIN, eid="bus", etype="vehicle"),
                _entity(*BERLIN, eid="scooter", etype="micromobility"),
            ])],
        )
        assert len(mgr.collect()) == 2

    def test_sorted_by_distance(self, cfg):
        cfg("EXT_RADIUS_M", 20000.0)
        mgr = ExternalEntityManager(
            _anchored_resolver(),
            sources=[_StubSource([
                _entity(52.5600, 13.3777, eid="far"),
                _entity(52.5186, 13.3762, eid="near"),
            ])],
        )
        assert [e.entity_id for e in mgr.collect()] == ["near", "far"]

    def test_max_entities_cap(self, cfg):
        cfg("EXT_MAX_ENTITIES", 3)
        cfg("EXT_RADIUS_M", 50000.0)
        entities = [
            _entity(52.52 + i * 0.001, 13.40 + i * 0.001, eid=f"e{i}") for i in range(10)
        ]
        mgr = ExternalEntityManager(_anchored_resolver(), sources=[_StubSource(entities)])
        assert len(mgr.collect()) == 3

    def test_snapshot_shape(self):
        mgr = ExternalEntityManager(
            _anchored_resolver(), sources=[_StubSource([_entity(*BERLIN)])]
        )
        snap = mgr.snapshot()
        assert snap.anchor_set is True
        assert snap.count == len(snap.entities)
        assert "stub" in snap.sources

    def test_status_reports_license(self):
        mgr = ExternalEntityManager(_anchored_resolver(), sources=[_StubSource([])])
        st = mgr.status()[0]
        assert st.license == "TEST"
        assert st.name == "stub"

    def test_start_noop_when_disabled(self, cfg):
        cfg("EXT_ENABLED", False)
        mgr = ExternalEntityManager(_anchored_resolver(), sources=[_StubSource([])])
        mgr.start()
        assert mgr._tasks == []


class TestSourceHealth:
    @pytest.mark.asyncio
    async def test_poll_keeps_last_state_on_error(self):
        class Flaky(_StubSource):
            def __init__(self):
                super().__init__([_entity(*BERLIN, eid="cached")])
                self.fail = False

            async def fetch(self):
                if self.fail:
                    raise RuntimeError("Feed weg")
                return self._entities

        src = Flaky()
        await src.poll()
        src.fail = True
        result = await src.poll()

        assert [e.entity_id for e in result] == ["cached"]
        assert src.consecutive_errors == 1
        assert src.last_error is not None

    @pytest.mark.asyncio
    async def test_unhealthy_after_three_errors(self):
        class Broken(_StubSource):
            def __init__(self):
                super().__init__([])
                self.last_success = None

            async def fetch(self):
                raise RuntimeError("nope")

        src = Broken()
        for _ in range(3):
            await src.poll()
        assert src.healthy is False
