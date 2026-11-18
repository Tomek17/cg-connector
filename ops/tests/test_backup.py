"""Testy kopii zapasowych na syntetycznej bazie SQLite (bez Dockera i bez danych osobowych).

Uruchomienie:  python3 -m unittest discover -s ops/tests -v
"""

from __future__ import annotations

import os
import sqlite3
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest
from datetime import datetime
from pathlib import Path

OPS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(OPS / "wsl"))

import cg_backup  # noqa: E402


def make_db(path: Path, rows: int = 3) -> None:
    con = sqlite3.connect(path)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("CREATE TABLE activities (id INTEGER PRIMARY KEY, name TEXT)")
    con.executemany(
        "INSERT INTO activities (name) VALUES (?)", [(f"trening {i}",) for i in range(rows)]
    )
    con.commit()
    con.close()


def count_rows(path: Path) -> int:
    con = sqlite3.connect(path)
    try:
        return con.execute("SELECT count(*) FROM activities").fetchone()[0]
    finally:
        con.close()


class SnapshotTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.data = Path(self.tmp.name)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_kopia_zawiera_dane_takze_z_niezapisanego_wal(self) -> None:
        db = self.data / "cg.sqlite"
        make_db(db, rows=2)
        # Otwarte połączenie z zapisem w WAL (bez checkpointu) = serwer w trakcie pracy.
        live = sqlite3.connect(db)
        live.execute("PRAGMA wal_autocheckpoint=0")
        live.execute("INSERT INTO activities (name) VALUES ('nowy')")
        live.commit()
        self.assertTrue((self.data / "cg.sqlite-wal").stat().st_size > 0)

        copies = cg_backup.snapshot(self.data, "2026-11-18_143000")
        live.close()

        self.assertEqual([c.name for c in copies], ["cg.sqlite"])
        copy = self.data / "backups" / "2026-11-18_143000" / "cg.sqlite"
        self.assertEqual(count_rows(copy), 3)
        self.assertFalse(copy.with_name("cg.sqlite-wal").exists())

    def test_brak_baz_nie_tworzy_katalogu(self) -> None:
        (self.data / "notatka.txt").write_text("x")
        self.assertEqual(cg_backup.snapshot(self.data, "2026-11-18_143000"), [])
        self.assertFalse((self.data / "backups").exists())


    def test_nieudana_kopia_jest_usuwana(self) -> None:
        db = self.data / "cg.sqlite"
        make_db(db, rows=2000)
        # Uszkadzamy stronę danych (nie nagłówek): kopia stron ją powieli.
        with open(db, "r+b") as f:
            f.seek(4096 * 2 + 100)
            f.write(b"\xff" * 2000)
        with self.assertRaises((RuntimeError, sqlite3.DatabaseError)):
            cg_backup.snapshot(self.data, "2026-11-18_143000")
        self.assertEqual(list((self.data / "backups").iterdir()), [])

    def test_uprawnienia_kopii_przez_main(self) -> None:
        make_db(self.data / "cg.sqlite")
        old_umask = os.umask(0o022)
        try:
            rc = cg_backup.main(
                ["snapshot", "--data", str(self.data), "--stamp", "2026-11-18_143000"]
            )
        finally:
            os.umask(old_umask)
        self.assertEqual(rc, 0)
        target = self.data / "backups" / "2026-11-18_143000"
        self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE((target / "cg.sqlite").stat().st_mode), 0o600)


class PruneTest(unittest.TestCase):
    def test_rotacja_po_nazwie_zostawia_najnowsza_i_obce_pliki(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in ["2026-10-01_143000", "2026-11-04_143000", "2026-11-05_143000"]:
                (root / name).mkdir()
            (root / "moje-notatki").mkdir()
            (root / "2026-01-01_000000.txt").write_text("nie kopia")

            removed = cg_backup.prune(root, 14, now=datetime(2026, 11, 18, 15, 0))

            self.assertEqual(sorted(removed), ["2026-10-01_143000", "2026-11-04_143000"])
            left = sorted(p.name for p in root.iterdir())
            self.assertEqual(
                left, ["2026-01-01_000000.txt", "2026-11-05_143000", "moje-notatki"]
            )

    def test_nigdy_nie_usuwa_jedynej_kopii(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "2020-01-01_000000").mkdir()
            self.assertEqual(cg_backup.prune(root, 14, now=datetime(2026, 11, 18)), [])


class BackupShTest(unittest.TestCase):
    """backup.sh z atrapą `docker` (uruchamia skrypt lokalnie zamiast w kontenerze)."""

    def test_kopia_i_rotacja(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_p = Path(tmp)
            server, data, bin_dir = (tmp_p / n for n in ("server", "data", "bin"))
            for d in (server, data, bin_dir):
                d.mkdir()
            (server / "compose.yaml").write_text("services: {}\n")
            (server / ".env").write_text(f'CG_HOST_DATA_DIR="{data}"\n')
            make_db(data / "cg.sqlite", rows=5)
            # Stare kopie do usunięcia przez rotację.
            for old in ("2020-01-01_000000", "2026-01-01_000000"):
                (data / "backups" / old).mkdir(parents=True)

            docker = bin_dir / "docker"
            docker.write_text(
                textwrap.dedent(
                    f"""\
                    #!/usr/bin/env bash
                    # Atrapa: bierze argumenty po "python -", /data -> {data}.
                    while [[ $# -gt 0 && "$1" != python ]]; do shift; done
                    shift 2
                    args=("${{@//\\/data/{data}}}")
                    exec {sys.executable} - "${{args[@]}}"
                    """
                )
            )
            docker.chmod(docker.stat().st_mode | stat.S_IXUSR)

            env = dict(
                os.environ,
                PATH=f"{bin_dir}:{os.environ['PATH']}",
                CG_SERVER_DIR=str(server),
                CG_BACKUP_RETENTION_DAYS="14",
            )
            out = subprocess.run(
                ["bash", str(OPS / "wsl" / "backup.sh")],
                env=env,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
            self.assertIn("integrity_check: ok", out.stdout)

            local = list((data / "backups").iterdir())
            self.assertEqual(len(local), 1, "stare kopie powinny zostać usunięte")
            self.assertEqual(count_rows(local[0] / "cg.sqlite"), 5)

    def test_zla_retencja(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            for name in ("compose.yaml", ".env"):
                (Path(tmp) / name).write_text("")
            out = subprocess.run(
                ["bash", str(OPS / "wsl" / "backup.sh")],
                env=dict(os.environ, CG_SERVER_DIR=tmp, CG_BACKUP_RETENTION_DAYS="0"),
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("CG_BACKUP_RETENTION_DAYS", out.stderr)


if __name__ == "__main__":
    unittest.main()
