#!/usr/bin/env python3
"""Baut den lokalen Offline-Zellbestand für geo/offline_cell.py.

Die Datenbank wird bewusst NICHT im Repository mitgeliefert:

* der weltweite OpenCelliD-Export ist mehrere Gigabyte gross
* CC BY-SA 4.0 ist ein Share-Alike-Lizenz — eine Weitergabe im Repository
  würde Pflichten auf das Gesamtprojekt ausdehnen
* jeder Betreiber soll den Bestand auf sein Einsatzgebiet zuschneiden

Ablauf
------
1. Kostenlosen API-Token auf https://opencellid.org/ registrieren.
2. CSV-Export ziehen (``--download`` oder manuell) — pro Land mit ``--mcc``.
3. Dieses Skript erzeugt daraus eine indizierte SQLite-Datei.

Beispiele
---------
    # Nur Deutschland (MCC 262), Datei bereits heruntergeladen:
    python scripts/fetch_geo_data.py --csv 262.csv.gz --out data/opencellid.sqlite

    # Direkt herunterladen (Token nötig):
    export OPENCELLID_TOKEN=...
    python scripts/fetch_geo_data.py --download --mcc 262 --out data/opencellid.sqlite

CSV-Spalten laut OpenCelliD-Exportformat:
    radio, mcc, net, area, cell, unit, lon, lat, range, samples,
    changeable, created, updated, averageSignal

Achtung ``lon`` steht VOR ``lat`` — eine klassische Fehlerquelle.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import io
import os
import sqlite3
import sys
import urllib.request
from pathlib import Path
from typing import Iterable, Iterator, TextIO

DOWNLOAD_URL = "https://opencellid.org/ocid/downloads?token={token}&type=mcc&file={mcc}.csv.gz"

SCHEMA = """
CREATE TABLE IF NOT EXISTS cells (
    mcc     INTEGER NOT NULL,
    mnc     INTEGER NOT NULL,
    lac     INTEGER NOT NULL,
    cid     INTEGER NOT NULL,
    lat     REAL    NOT NULL,
    lon     REAL    NOT NULL,
    range_m REAL,
    samples INTEGER,
    PRIMARY KEY (mcc, mnc, lac, cid)
);
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT
);
"""

ATTRIBUTION = (
    "OpenCelliD, lizenziert unter CC BY-SA 4.0. "
    "Bei Weitergabe abgeleiteter Daten gilt die Share-Alike-Pflicht."
)


def open_csv(path: str) -> TextIO:
    if path.endswith(".gz"):
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8")
    return open(path, "r", encoding="utf-8")


def download(mcc: int, dest: Path) -> Path:
    token = os.getenv("OPENCELLID_TOKEN")
    if not token:
        sys.exit(
            "OPENCELLID_TOKEN ist nicht gesetzt.\n"
            "Kostenlosen Token unter https://opencellid.org/ anfordern."
        )
    url = DOWNLOAD_URL.format(token=token, mcc=mcc)
    print(f"Lade MCC {mcc} …")
    # Token nicht mit ausgeben
    urllib.request.urlretrieve(url, dest)
    print(f"  -> {dest} ({dest.stat().st_size / 1e6:.1f} MB)")
    return dest


def rows(handle: TextIO, mcc_filter: Iterable[int] | None) -> Iterator[tuple]:
    reader = csv.reader(handle)
    header = next(reader, None)
    # Manche Exporte kommen ohne Kopfzeile
    if header and header[0].strip().lower() not in {"radio", "radio_type"}:
        handle.seek(0)
        reader = csv.reader(handle)

    allowed = set(mcc_filter) if mcc_filter else None
    skipped = 0
    for row in reader:
        if len(row) < 10:
            skipped += 1
            continue
        try:
            mcc = int(row[1])
            mnc = int(row[2])
            lac = int(row[3])
            cid = int(row[4])
            lon = float(row[6])   # Reihenfolge beachten: lon vor lat
            lat = float(row[7])
            rng = float(row[8]) if row[8] else None
            samples = int(row[9]) if row[9] else None
        except (ValueError, IndexError):
            skipped += 1
            continue

        if allowed and mcc not in allowed:
            continue
        if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0):
            skipped += 1
            continue
        if lat == 0.0 and lon == 0.0:
            skipped += 1
            continue

        yield (mcc, mnc, lac, cid, lat, lon, rng, samples)

    if skipped:
        print(f"  {skipped} unbrauchbare Zeilen übersprungen")


def build(csv_path: str, out_path: Path, mcc_filter: Iterable[int] | None) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(out_path)
    try:
        conn.executescript(SCHEMA)
        conn.execute("PRAGMA journal_mode=OFF")
        conn.execute("PRAGMA synchronous=OFF")

        count = 0
        batch = []
        with open_csv(csv_path) as handle:
            for record in rows(handle, mcc_filter):
                batch.append(record)
                if len(batch) >= 20000:
                    conn.executemany(
                        "INSERT OR REPLACE INTO cells VALUES (?,?,?,?,?,?,?,?)", batch
                    )
                    count += len(batch)
                    batch.clear()
                    print(f"  {count:,} Zellen …", end="\r")
        if batch:
            conn.executemany(
                "INSERT OR REPLACE INTO cells VALUES (?,?,?,?,?,?,?,?)", batch
            )
            count += len(batch)

        conn.execute(
            "INSERT OR REPLACE INTO meta VALUES ('source', 'OpenCelliD')"
        )
        conn.execute(
            "INSERT OR REPLACE INTO meta VALUES ('license', 'CC BY-SA 4.0')"
        )
        conn.execute(
            "INSERT OR REPLACE INTO meta VALUES ('attribution', ?)", (ATTRIBUTION,)
        )
        conn.commit()
        conn.execute("VACUUM")
        conn.commit()

        size_mb = out_path.stat().st_size / 1e6
        print(f"\nFertig: {count:,} Zellen in {out_path} ({size_mb:.1f} MB)")
        print(f"\n{ATTRIBUTION}")
        print("Die Namensnennung muss in der Oberfläche sichtbar sein — "
              "siehe docs/LICENSES.md.")
    finally:
        conn.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--csv", help="Pfad zum OpenCelliD-CSV (.csv oder .csv.gz)")
    parser.add_argument("--download", action="store_true",
                        help="CSV herunterladen (braucht OPENCELLID_TOKEN)")
    parser.add_argument("--mcc", type=int, action="append",
                        help="Auf MCC begrenzen, mehrfach angebbar (DE=262, AT=232, CH=228)")
    parser.add_argument("--out", default="data/opencellid.sqlite",
                        help="Zieldatei (Vorgabe: data/opencellid.sqlite)")
    args = parser.parse_args()

    out_path = Path(args.out)

    if args.download:
        if not args.mcc:
            sys.exit("--download benötigt mindestens ein --mcc")
        tmp = out_path.parent / f"{args.mcc[0]}.csv.gz"
        tmp.parent.mkdir(parents=True, exist_ok=True)
        csv_path = str(download(args.mcc[0], tmp))
    elif args.csv:
        csv_path = args.csv
    else:
        sys.exit("Entweder --csv oder --download angeben. Hilfe: --help")

    build(csv_path, out_path, args.mcc)


if __name__ == "__main__":
    main()
