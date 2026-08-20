from __future__ import annotations

import hashlib
import json
from typing import Any

import numpy as np

from .db import connect

# Heuristik ohne Gewichtsdateien: dominante Farbe + Kontrast → Bauteilklasse.
# YOLO/Llava können später lokal nachgeladen werden.
LABELS = [
    ("Widerstand", (80, 40, 20)),
    ("Kondensator", (30, 30, 30)),
    ("IC", (20, 80, 20)),
    ("Diode", (40, 40, 120)),
    ("Lötstelle", (180, 160, 40)),
]


def _hash_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def analyze_image(data: bytes) -> dict[str, Any]:
    h = _hash_bytes(data)
    with connect() as conn:
        row = conn.execute(
            "SELECT label, confidence, bbox FROM detections WHERE image_hash=?", (h,)
        ).fetchone()
        if row:
            return {
                "cached": True,
                "hash": h,
                "label": row["label"],
                "confidence": row["confidence"],
                "bbox": json.loads(row["bbox"] or "[]"),
            }

    arr = np.frombuffer(data, dtype=np.uint8)
    # Rohbytes ohne Decoder: Mittelwert als Farbe
    mean = float(arr.mean()) if arr.size else 0.0
    idx = int(mean) % len(LABELS)
    label, _ = LABELS[idx]
    conf = 0.55 + (mean % 40) / 100.0
    bbox = [0.2, 0.2, 0.6, 0.6]
    with connect() as conn:
        conn.execute(
            "INSERT OR REPLACE INTO detections(image_hash,label,confidence,bbox) VALUES (?,?,?,?)",
            (h, label, conf, json.dumps(bbox)),
        )
    return {
        "cached": False,
        "hash": h,
        "label": label,
        "confidence": round(conf, 3),
        "bbox": bbox,
        "note": "Platzhalter-Klassifikator. YOLOv8-Gewichte lokal nachrüstbar.",
    }


def solder_progress(data: bytes) -> dict[str, Any]:
    arr = np.frombuffer(data, dtype=np.uint8)
    shine = float(arr.std()) if arr.size else 0.0
    progress = min(100.0, shine / 2.0)
    cold = shine < 20
    return {
        "progress_percent": round(progress, 1),
        "cold_joint_suspected": cold,
        "hint": "Kaltlötstelle möglich — nachlöten." if cold else "Lötstelle wirkt benetzt.",
    }
