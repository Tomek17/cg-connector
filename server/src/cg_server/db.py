"""SQLite database: engine, Alembic migrations, sessions."""

import sqlite3
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from alembic import command
from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import Engine, create_engine, event
from sqlalchemy.orm import Session, sessionmaker

from cg_server.config import Settings

MIGRATIONS = "cg_server:migrations"


def db_path(settings: Settings) -> Path:
    return settings.data_dir / "cg.sqlite"


def create_db_engine(settings: Settings) -> Engine:
    # The database holds encrypted Garmin tokens: owner-only access.
    settings.data_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = db_path(settings)
    path.touch(mode=0o600, exist_ok=True)
    path.chmod(0o600)
    engine = create_engine(f"sqlite:///{path}")

    @event.listens_for(engine, "connect")
    def _enable_foreign_keys(dbapi_conn: Any, _record: Any) -> None:
        dbapi_conn.execute("PRAGMA foreign_keys=ON")

    return engine


def backup_before_migration(engine: Engine, config: Config) -> Path | None:
    """Copy the database next to the daily backups before a schema change.

    Only when the database already has data at an older revision; the copy goes to
    `<data_dir>/backups/przed-aktualizacja-<revision>-<time>/cg.sqlite` (the daily backup
    rotation leaves such folders alone). Restoring: docs/instalacja-serwera.md."""
    path = engine.url.database
    if not path or path == ":memory:":
        return None
    with engine.connect() as conn:
        current = MigrationContext.configure(conn).get_current_revision()
    if current is None or current == ScriptDirectory.from_config(config).get_current_head():
        return None
    stamp = datetime.now(UTC).strftime("%Y-%m-%d_%H%M%S")
    target = Path(path).parent / "backups" / f"przed-aktualizacja-{current}-{stamp}"
    target.mkdir(mode=0o700, parents=True, exist_ok=True)
    copy = target / "cg.sqlite"
    src, dst = sqlite3.connect(path), sqlite3.connect(copy)
    try:
        src.backup(dst)
    finally:
        dst.close()
        src.close()
    copy.chmod(0o600)
    return copy


def upgrade_db(engine: Engine) -> None:
    config = Config()
    config.set_main_option("script_location", MIGRATIONS)
    backup_before_migration(engine, config)
    with engine.begin() as conn:
        config.attributes["connection"] = conn
        command.upgrade(config, "head")


def open_db(settings: Settings) -> sessionmaker[Session]:
    """Create the engine, migrate the schema to the newest version, return a session factory."""
    engine = create_db_engine(settings)
    upgrade_db(engine)
    return sessionmaker(engine, expire_on_commit=False)
