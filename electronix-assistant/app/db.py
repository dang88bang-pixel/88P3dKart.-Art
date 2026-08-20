from __future__ import annotations

import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).resolve().parent.parent / "data" / "exa.sqlite"


def connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with connect() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS detections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                image_hash TEXT UNIQUE,
                label TEXT NOT NULL,
                confidence REAL NOT NULL,
                bbox TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            );
            CREATE TABLE IF NOT EXISTS materials (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT UNIQUE NOT NULL,
                e_modulus_gpa REAL,
                density REAL,
                yield_mpa REAL,
                thermal_k REAL,
                heat_capacity REAL
            );
            CREATE TABLE IF NOT EXISTS components (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                mpn TEXT UNIQUE NOT NULL,
                kind TEXT,
                footprint TEXT,
                pins INTEGER
            );
            CREATE TABLE IF NOT EXISTS projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                snapshot INTEGER DEFAULT 1,
                payload TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            );
            """
        )
        count = conn.execute("SELECT COUNT(*) FROM materials").fetchone()[0]
        if count == 0:
            conn.executemany(
                "INSERT INTO materials(name,e_modulus_gpa,density,yield_mpa,thermal_k,heat_capacity) VALUES (?,?,?,?,?,?)",
                [
                    ("FR4", 22.0, 1850, 70, 0.3, 880),
                    ("Kupfer", 110.0, 8960, 70, 385, 385),
                    ("Aluminium 6061", 69.0, 2700, 276, 167, 896),
                    ("PLA", 3.5, 1240, 50, 0.13, 1800),
                    ("PETG", 2.1, 1270, 50, 0.21, 1200),
                    ("ABS", 2.3, 1040, 40, 0.17, 1400),
                    ("Stahl 1.4301", 193.0, 8000, 230, 15, 500),
                ],
            )
        if conn.execute("SELECT COUNT(*) FROM components").fetchone()[0] == 0:
            conn.executemany(
                "INSERT INTO components(mpn,kind,footprint,pins) VALUES (?,?,?,?)",
                [
                    ("1N4148", "Diode", "DO-35", 2),
                    ("LM7805", "Regulator", "TO-220", 3),
                    ("ATmega328P", "MCU", "TQFP-32", 32),
                    ("0603-10k", "Resistor", "0603", 2),
                    ("0805-100n", "Capacitor", "0805", 2),
                ],
            )
