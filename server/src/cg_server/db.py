"""SQLite database: engine, Alembic migrations, sessions."""

from pathlib import Path
from typing import Any

from alembic import command
from alembic.config import Config
from sqlalchemy import Engine, create_engine, event
from sqlalchemy.orm import Session, sessionmaker

from cg_server.config import Settings

MIGRATIONS = "cg_server:migrations"


def db_path(settings: Settings) -> Path:
    return settings.data_dir / "cg.sqlite"


def create_db_engine(settings: Settings) -> Engine:
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    engine = create_engine(f"sqlite:///{db_path(settings)}")

    @event.listens_for(engine, "connect")
    def _enable_foreign_keys(dbapi_conn: Any, _record: Any) -> None:
        dbapi_conn.execute("PRAGMA foreign_keys=ON")

    return engine


def upgrade_db(engine: Engine) -> None:
    config = Config()
    config.set_main_option("script_location", MIGRATIONS)
    with engine.begin() as conn:
        config.attributes["connection"] = conn
        command.upgrade(config, "head")


def open_db(settings: Settings) -> sessionmaker[Session]:
    """Create the engine, migrate the schema to the newest version, return a session factory."""
    engine = create_db_engine(settings)
    upgrade_db(engine)
    return sessionmaker(engine, expire_on_commit=False)
