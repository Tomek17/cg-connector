"""Kopie zapasowe baz SQLite CG-connector (tylko biblioteka standardowa Pythona).

snapshot: spójna kopia "online" każdej bazy z folderu danych (API backup SQLite, to samo
co `sqlite3 baza ".backup plik"`), PRAGMA integrity_check kopii i rotacja. Uruchamiane
w kontenerze cg-server (ten sam użytkownik co serwer), patrz backup.sh.

Rotacja dotyczy wyłącznie katalogów o nazwie RRRR-MM-DD_GGMMSS (tworzonych przez ten
skrypt); wiek liczony jest z nazwy, nie z daty modyfikacji. Najnowsza kopia zostaje zawsze.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import sqlite3
import sys
from datetime import datetime, timedelta
from pathlib import Path

STAMP_FORMAT = "%Y-%m-%d_%H%M%S"
STAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}_\d{6}$")
DB_SUFFIXES = {".sqlite", ".sqlite3", ".db"}


def parse_stamp(name: str) -> datetime | None:
    if not STAMP_RE.fullmatch(name):
        return None
    try:
        return datetime.strptime(name, STAMP_FORMAT)
    except ValueError:
        return None


def snapshot(data_dir: Path, stamp: str) -> list[Path]:
    """Kopiuje bazy z data_dir do data_dir/backups/<stamp>/. Zwraca utworzone pliki."""
    dbs = sorted(p for p in data_dir.iterdir() if p.is_file() and p.suffix in DB_SUFFIXES)
    if not dbs:
        return []
    target = data_dir / "backups" / stamp
    target.mkdir(parents=True, exist_ok=False)
    copies = []
    for db in dbs:
        dst_path = target / db.name
        src = sqlite3.connect(db)
        dst = sqlite3.connect(dst_path)
        try:
            src.backup(dst)
            (result,) = dst.execute("PRAGMA integrity_check").fetchone()
        finally:
            dst.close()
            src.close()
        if result != "ok":
            raise RuntimeError(f"kopia {db.name} nie przeszła integrity_check: {result}")
        copies.append(dst_path)
    return copies


def prune(root: Path, keep_days: int, now: datetime) -> list[str]:
    """Usuwa z root katalogi kopii starsze niż keep_days (poza najnowszym)."""
    if not root.is_dir():
        return []
    dated = sorted(
        (ts, p)
        for p in root.iterdir()
        if (ts := parse_stamp(p.name)) is not None and p.is_dir() and not p.is_symlink()
    )
    cutoff = now - timedelta(days=keep_days)
    removed = []
    for ts, path in dated[:-1]:
        if ts < cutoff:
            shutil.rmtree(path)
            removed.append(path.name)
    return removed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_snap = sub.add_parser("snapshot")
    p_snap.add_argument("--data", type=Path, required=True)
    p_snap.add_argument("--stamp", required=True)
    p_snap.add_argument("--keep-days", type=int, default=14)
    args = parser.parse_args(argv)

    if parse_stamp(args.stamp) is None:
        parser.error(f"nieprawidłowy znacznik kopii: {args.stamp!r}")
    if args.keep_days < 1:
        parser.error("--keep-days musi być >= 1")
    # Kopie (katalogi i pliki) dostępne tylko dla właściciela.
    os.umask(0o077)
    now = datetime.now()

    copies = snapshot(args.data, args.stamp)
    if not copies:
        print("Brak baz SQLite w folderze danych - nic do skopiowania.")
    for c in copies:
        print(f"OK: {c.name} -> backups/{args.stamp}/{c.name} (integrity_check: ok)")
    root = args.data / "backups"

    for name in prune(root, args.keep_days, now):
        print(f"Usunięto starą kopię: {root / name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
