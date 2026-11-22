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


def test_sync_tables_exist(settings: Settings) -> None:
    engine = create_db_engine(settings)
    upgrade_db(engine)
    with engine.connect() as conn:
        tables = set(inspect(conn).get_table_names())
    assert {"activities", "activity_details", "vo2max", "sync_runs"} <= tables


def test_garmin_activity_id_unique_per_user(settings: Settings) -> None:
    from datetime import UTC, datetime

    from cg_server.models import Activity, ActivityKind

    def activity(user: User) -> Activity:
        return Activity(
            user=user,
            garmin_activity_id=42,
            kind=ActivityKind.RUNNING,
            type_key="running",
            start_time_utc=datetime(2026, 11, 1, 6, tzinfo=UTC),
            start_time_local=datetime(2026, 11, 1, 7),
        )

    sessions = open_db(settings)
    with sessions() as session:
        tomek, brat = User(name="tomek"), User(name="brat")
        # The same Garmin id for two different users is fine...
        session.add_all([activity(tomek), activity(brat)])
        session.commit()
        # ...but not twice for one user.
        session.add(activity(tomek))
        with pytest.raises(IntegrityError):
            session.commit()


def test_workout_client_id_unique_per_user(settings: Settings) -> None:
    from cg_server.models import Workout, WorkoutOrigin, WorkoutSport

    def workout(user: User) -> Workout:
        return Workout(
            user=user,
            client_id="7f9c1f8e-2b7a-4a51-9a51-3c1f0e7b2d11",
            name="x",
            sport=WorkoutSport.RUNNING,
            definition_json={},
            created_by=WorkoutOrigin.APP,
        )

    sessions = open_db(settings)
    with sessions() as session:
        tomek, brat = User(name="tomek"), User(name="brat")
        session.add_all([workout(tomek), workout(brat)])
        session.commit()
        session.add(workout(tomek))
        with pytest.raises(IntegrityError):
            session.commit()


def test_workout_migration_downgrades(settings: Settings) -> None:
    from alembic import command
    from alembic.config import Config

    engine = create_db_engine(settings)
    upgrade_db(engine)
    config = Config()
    config.set_main_option("script_location", "cg_server:migrations")
    with engine.begin() as conn:
        config.attributes["connection"] = conn
        command.downgrade(config, "0004")
    with engine.connect() as conn:
        tables = set(inspect(conn).get_table_names())
    assert "workouts" not in tables and "scheduled_workouts" not in tables
    upgrade_db(engine)


def test_migration_0006_keeps_scheduled_workouts(settings: Settings) -> None:
    """Rebuilding `workouts` must not cascade-delete scheduled workouts (bug in 0006 v1)."""
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import text

    engine = create_db_engine(settings)
    config = Config()
    config.set_main_option("script_location", "cg_server:migrations")

    def migrate(rev: str, *, down: bool = False) -> None:
        with engine.begin() as conn:
            config.attributes["connection"] = conn
            (command.downgrade if down else command.upgrade)(config, rev)

    migrate("0005")
    with engine.begin() as conn:
        conn.execute(text("INSERT INTO users(id, name, created_at) VALUES (1, 't', '2030-01-01')"))
        conn.execute(
            text(
                "INSERT INTO workouts(id, user_id, client_id, name, sport, definition_json,"
                " created_by, created_at) VALUES (1, 1, 'c', 'W', 'running', '{}', 'app',"
                " '2030-01-01')"
            )
        )
        conn.execute(
            text(
                "INSERT INTO scheduled_workouts(id, user_id, workout_id, date, status, attempts,"
                " created_at, updated_at) VALUES (1, 1, 1, '2030-01-07', 'sent', 1,"
                " '2030-01-01', '2030-01-01')"
            )
        )
    migrate("0006")
    with engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM scheduled_workouts")).scalar() == 1
    migrate("0005", down=True)
    with engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM scheduled_workouts")).scalar() == 1


def test_backup_is_made_before_a_schema_change(settings: Settings) -> None:
    from alembic import command
    from alembic.config import Config

    engine = create_db_engine(settings)
    upgrade_db(engine)
    backups = settings.data_dir / "backups"
    assert not backups.exists()  # new database: nothing to keep
    config = Config()
    config.set_main_option("script_location", "cg_server:migrations")
    with engine.begin() as conn:
        config.attributes["connection"] = conn
        command.downgrade(config, "0005")
    upgrade_db(engine)
    (folder,) = backups.iterdir()
    assert folder.name.startswith("przed-aktualizacja-0005-")
    assert (folder / "cg.sqlite").stat().st_size > 0
    upgrade_db(engine)  # already current: no new copy
    assert len(list(backups.iterdir())) == 1
