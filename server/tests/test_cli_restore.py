"""CLI: restore-scheduled puts back scheduled workouts missing from the database."""

import sqlite3
from pathlib import Path

import pytest

from cg_server.cli import main
from cg_server.config import Settings
from cg_server.db import db_path, open_db

ROWS = [(1, "2030-01-07", "sent"), (2, "2030-01-09", "pending")]


@pytest.fixture
def setup(settings: Settings, monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> Path:
    monkeypatch.setenv("CG_DATA_DIR", str(settings.data_dir))
    monkeypatch.setenv("CG_ENCRYPTION_KEY", settings.encryption_key)
    open_db(settings)
    with sqlite3.connect(db_path(settings)) as con:
        con.execute("INSERT INTO users(id, name, created_at) VALUES (1, 'tomek', '2030-01-01')")
        con.execute(
            "INSERT INTO workouts(id, user_id, client_id, name, sport, definition_json,"
            " created_by, created_at) VALUES (1, 1, 'c1', 'Tempo', 'running', '{}', 'app',"
            " '2030-01-01')"
        )
        for row_id, day, status in ROWS:
            con.execute(
                "INSERT INTO scheduled_workouts(id, user_id, workout_id, date, status, attempts,"
                " created_at, updated_at) VALUES (?, 1, 1, ?, ?, 0, '2030-01-01', '2030-01-01')",
                (row_id, day, status),
            )
    backup = tmp_path / "backup.sqlite"
    with sqlite3.connect(db_path(settings)) as src, sqlite3.connect(backup) as dst:
        src.backup(dst)
    with sqlite3.connect(db_path(settings)) as con:
        con.execute("DELETE FROM scheduled_workouts WHERE id = 1")
    return backup


def count(settings: Settings) -> int:
    with sqlite3.connect(db_path(settings)) as con:
        value: int = con.execute("SELECT count(*) FROM scheduled_workouts").fetchone()[0]
    return value


def test_dry_run_changes_nothing(
    setup: Path, settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    assert main(["restore-scheduled", str(setup), "--dry-run"]) == 0
    out = capsys.readouterr().out
    assert "2030-01-07  Tempo  (sent)" in out
    assert "Do przywrócenia: 1" in out
    assert count(settings) == 1


def test_restores_only_missing_rows_and_is_repeatable(
    setup: Path, settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    assert main(["restore-scheduled", str(setup)]) == 0
    assert "Przywrócono zaplanowanych treningów: 1." in capsys.readouterr().out
    assert count(settings) == 2
    assert main(["restore-scheduled", str(setup)]) == 0
    assert "Przywrócono zaplanowanych treningów: 0." in capsys.readouterr().out
    assert count(settings) == 2


def test_missing_backup_file(setup: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["restore-scheduled", str(setup.with_name("nope.sqlite"))]) == 1
    assert "Nie ma pliku kopii" in capsys.readouterr().err
