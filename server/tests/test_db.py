import pytest
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from sqlalchemy import inspect
from sqlalchemy.exc import IntegrityError

from cg_server.config import Settings
from cg_server.db import create_db_engine, db_path, open_db, upgrade_db
from cg_server.models import Base, GarminCredentials, GarminStatus, User


def test_database_file_lives_in_data_dir(settings: Settings) -> None:
    open_db(settings)
    assert (settings.data_dir / "cg.sqlite").is_file()


def test_migration_matches_models(settings: Settings) -> None:
    engine = create_db_engine(settings)
    upgrade_db(engine)
    with engine.connect() as conn:
        assert set(inspect(conn).get_table_names()) >= {"users", "garmin_credentials"}
        diff = compare_metadata(MigrationContext.configure(conn), Base.metadata)
    assert diff == []


def test_upgrade_is_idempotent(settings: Settings) -> None:
    open_db(settings)
    open_db(settings)


def test_user_name_is_unique(settings: Settings) -> None:
    sessions = open_db(settings)
    with sessions() as session:
        session.add_all([User(name="tomek"), User(name="tomek")])
        with pytest.raises(IntegrityError):
            session.commit()


def test_credentials_belong_to_user(settings: Settings) -> None:
    sessions = open_db(settings)
    with sessions() as session:
        user = User(name="brat")
        user.garmin_credentials = GarminCredentials(status=GarminStatus.NEEDS_LOGIN)
        session.add(user)
        session.commit()
        assert user.created_at is not None
        assert user.garmin_credentials.updated_at is not None
        assert user.garmin_credentials.encrypted_tokens is None


def test_data_dir_and_database_are_private(settings: Settings) -> None:
    open_db(settings)
    assert (settings.data_dir.stat().st_mode & 0o777) == 0o700
    assert (db_path(settings).stat().st_mode & 0o777) == 0o600


def test_existing_database_permissions_are_tightened(settings: Settings) -> None:
    settings.data_dir.mkdir(parents=True)
    db_path(settings).touch(mode=0o644)
    db_path(settings).chmod(0o644)
    open_db(settings)
    assert (db_path(settings).stat().st_mode & 0o777) == 0o600
