"""Minimaler Protobuf-Wire-Format-Decoder (nur Lesen).

Bewusst ohne die Bibliothek ``protobuf``/``gtfs-realtime-bindings``:

* Der Edge-Agent läuft auf einem Handheld (CT45P). Jede vermiedene native
  Abhängigkeit spart Image-Grösse und Build-Aufwand für ARM.
* Der Decoder ist schemafrei — er liefert rohe Feldnummern. Die Zuordnung
  zu GTFS-RT-Feldern liegt in gtfs_rt.py und ist damit sichtbar und testbar.
* Unbekannte Felder werden übersprungen statt zu scheitern; reale Feeds
  enthalten regelmässig Hersteller-Erweiterungen.

Unterstützt die vier in GTFS-RT vorkommenden Wire-Types:
    0 varint | 1 fixed64 | 2 length-delimited | 5 fixed32
"""
from __future__ import annotations

import struct
from typing import Dict, Iterator, List, Tuple

WIRE_VARINT = 0
WIRE_FIXED64 = 1
WIRE_LENGTH = 2
WIRE_FIXED32 = 5


class ProtobufError(ValueError):
    """Ungültiges oder abgeschnittenes Protobuf-Payload."""


def _read_varint(buf: bytes, pos: int) -> Tuple[int, int]:
    result = 0
    shift = 0
    while True:
        if pos >= len(buf):
            raise ProtobufError("Varint reicht über das Pufferende hinaus")
        if shift > 63:
            raise ProtobufError("Varint zu lang (>10 Bytes)")
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7


def iter_fields(buf: bytes) -> Iterator[Tuple[int, int, object]]:
    """Iteriert (field_number, wire_type, raw_value) über eine Nachricht."""
    pos = 0
    end = len(buf)
    while pos < end:
        key, pos = _read_varint(buf, pos)
        field_no = key >> 3
        wire = key & 0x07
        if field_no == 0:
            raise ProtobufError("Feldnummer 0 ist unzulässig")

        if wire == WIRE_VARINT:
            value, pos = _read_varint(buf, pos)
            yield field_no, wire, value
        elif wire == WIRE_FIXED64:
            if pos + 8 > end:
                raise ProtobufError("fixed64 abgeschnitten")
            yield field_no, wire, buf[pos : pos + 8]
            pos += 8
        elif wire == WIRE_LENGTH:
            length, pos = _read_varint(buf, pos)
            if pos + length > end:
                raise ProtobufError("length-delimited abgeschnitten")
            yield field_no, wire, buf[pos : pos + length]
            pos += length
        elif wire == WIRE_FIXED32:
            if pos + 4 > end:
                raise ProtobufError("fixed32 abgeschnitten")
            yield field_no, wire, buf[pos : pos + 4]
            pos += 4
        elif wire in (3, 4):  # veraltete Groups
            raise ProtobufError("Protobuf-Groups werden nicht unterstützt")
        else:
            raise ProtobufError(f"Unbekannter Wire-Type {wire}")


def parse_message(buf: bytes) -> Dict[int, List[object]]:
    """Parst eine Nachricht zu {field_number: [werte...]}.

    Wiederholte Felder erscheinen als Liste mehrerer Einträge; skalare
    Felder als Liste der Länge 1 (letzter Eintrag gewinnt nach Protobuf-
    Semantik).
    """
    out: Dict[int, List[object]] = {}
    for field_no, _wire, value in iter_fields(buf):
        out.setdefault(field_no, []).append(value)
    return out


# ─── Typisierte Zugriffshelfer ──────────────────────────────────
def get_bytes(msg: Dict[int, List[object]], field: int) -> bytes | None:
    vals = msg.get(field)
    if not vals:
        return None
    val = vals[-1]
    return val if isinstance(val, bytes) else None


def get_string(msg: Dict[int, List[object]], field: int) -> str | None:
    raw = get_bytes(msg, field)
    if raw is None:
        return None
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("utf-8", errors="replace")


def get_submessage(msg: Dict[int, List[object]], field: int) -> Dict[int, List[object]] | None:
    raw = get_bytes(msg, field)
    if raw is None:
        return None
    try:
        return parse_message(raw)
    except ProtobufError:
        return None


def get_repeated_submessages(
    msg: Dict[int, List[object]], field: int
) -> List[Dict[int, List[object]]]:
    out: List[Dict[int, List[object]]] = []
    for val in msg.get(field, []):
        if not isinstance(val, bytes):
            continue
        try:
            out.append(parse_message(val))
        except ProtobufError:
            continue
    return out


def get_uint(msg: Dict[int, List[object]], field: int) -> int | None:
    vals = msg.get(field)
    if not vals:
        return None
    val = vals[-1]
    return val if isinstance(val, int) else None


def get_float(msg: Dict[int, List[object]], field: int) -> float | None:
    """Liest ein `float` (fixed32) — in GTFS-RT sind Koordinaten float."""
    vals = msg.get(field)
    if not vals:
        return None
    val = vals[-1]
    if isinstance(val, bytes) and len(val) == 4:
        return struct.unpack("<f", val)[0]
    if isinstance(val, bytes) and len(val) == 8:
        return struct.unpack("<d", val)[0]
    if isinstance(val, int):
        return float(val)
    return None


def get_double(msg: Dict[int, List[object]], field: int) -> float | None:
    vals = msg.get(field)
    if not vals:
        return None
    val = vals[-1]
    if isinstance(val, bytes) and len(val) == 8:
        return struct.unpack("<d", val)[0]
    return get_float(msg, field)


# ─── Encoder (nur für Tests / Fixtures) ─────────────────────────
def encode_varint(value: int) -> bytes:
    out = bytearray()
    while True:
        bits = value & 0x7F
        value >>= 7
        if value:
            out.append(bits | 0x80)
        else:
            out.append(bits)
            return bytes(out)


def encode_key(field_no: int, wire: int) -> bytes:
    return encode_varint((field_no << 3) | wire)


def encode_string(field_no: int, value: str) -> bytes:
    raw = value.encode("utf-8")
    return encode_key(field_no, WIRE_LENGTH) + encode_varint(len(raw)) + raw


def encode_submessage(field_no: int, payload: bytes) -> bytes:
    return encode_key(field_no, WIRE_LENGTH) + encode_varint(len(payload)) + payload


def encode_uint(field_no: int, value: int) -> bytes:
    return encode_key(field_no, WIRE_VARINT) + encode_varint(value)


def encode_float(field_no: int, value: float) -> bytes:
    return encode_key(field_no, WIRE_FIXED32) + struct.pack("<f", value)
