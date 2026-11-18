"""Scheduler: catch-up rule (pure function), job runner and job configuration."""

from datetime import UTC, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy import select

from cg_server.config import Settings
from cg_server.crypto import encrypt
from cg_server.db import open_db
from cg_server.garmin.fake import FakeGarminGateway, make_activity
from cg_server.garmin.gateway import GarminGateway
from cg_server.models import GarminCredentials, GarminStatus, SyncRun, SyncStatus, User
from cg_server.scheduler import SyncRunner, create_scheduler, needs_catch_up

WAW = ZoneInfo("Europe/Warsaw")


def waw(day: int, hour: int, minute: int = 0) -> datetime:
    return datetime(2026, 11, day, hour, minute, tzinfo=WAW)


def due(last_ok: datetime | None, now: datetime, last_attempt: datetime | None = None) -> bool:
    return needs_catch_up(last_ok, last_attempt or last_ok, now, tz=WAW, hour=14)


def test_never_synced_is_due() -> None:
    assert due(None, waw(18, 9))


def test_due_after_14_when_last_sync_was_before_todays_14() -> None:
    assert due(waw(18, 8), waw(18, 15))
    assert due(waw(17, 14, 5), waw(18, 14, 1))


def test_not_due_before_14_when_synced_within_24h() -> None:
    assert not due(waw(17, 14, 5), waw(18, 9))
    assert not due(waw(17, 14, 5), waw(18, 13, 59))


def test_not_due_after_todays_sync() -> None:
    assert not due(waw(18, 14, 0), waw(18, 20))


def test_due_when_older_than_24h_even_before_14() -> None:
    assert due(waw(17, 9), waw(18, 9, 30))


def test_failed_attempt_waits_an_hour_before_next_catch_up() -> None:
    last_ok = waw(17, 9)
    assert not needs_catch_up(last_ok, waw(18, 15), waw(18, 15, 30), tz=WAW, hour=14, failures=1)
    assert needs_catch_up(last_ok, waw(18, 15), waw(18, 16, 1), tz=WAW, hour=14, failures=1)


@pytest.mark.parametrize(
    ("failures", "wait_h"), [(1, 1), (2, 2), (3, 4), (4, 8), (5, 16), (6, 24), (10, 24)]
)
def test_backoff_doubles_after_each_failure_up_to_24h(failures: int, wait_h: int) -> None:
    last_ok, attempt = waw(10, 14, 30), waw(17, 15)
    just_before = attempt + timedelta(hours=wait_h) - timedelta(minutes=1)
    just_after = attempt + timedelta(hours=wait_h) + timedelta(minutes=1)
    kw: dict[str, Any] = {"tz": WAW, "hour": 14, "failures": failures}
    assert not needs_catch_up(last_ok, attempt, just_before, **kw)
    assert needs_catch_up(last_ok, attempt, just_after, **kw)


def test_naive_utc_datetimes_from_sqlite_are_accepted() -> None:
    last_ok = waw(18, 8).astimezone(UTC).replace(tzinfo=None)
    assert due(last_ok, waw(18, 15))


def test_dst_change_uses_local_14() -> None:
    # 2026-03-29: Poland switches to UTC+2. 14:00 local = 12:00 UTC.
    now = datetime(2026, 3, 29, 12, 30, tzinfo=UTC)
    assert due(datetime(2026, 3, 29, 11, 0, tzinfo=UTC), now)
    assert not due(datetime(2026, 3, 29, 12, 5, tzinfo=UTC), now)


# --- runner ----------------------------------------------------------------

TOKENS = '{"di_token": "fake-access-1"}'


def add_user(settings: Settings, name: str, status: GarminStatus = GarminStatus.OK) -> None:
    with open_db(settings)() as session:
        user = User(name=name)
        user.garmin_credentials = GarminCredentials(
            status=status, encrypted_tokens=encrypt(TOKENS, settings.encryption_key)
        )
        session.add(user)
        session.commit()


def runner_for(settings: Settings) -> SyncRunner:
    created: list[FakeGarminGateway] = []

    def factory() -> GarminGateway:
        fake = FakeGarminGateway(activities=[make_activity(1, datetime(2026, 11, 17, 7))])
        created.append(fake)
        return fake

    runner = SyncRunner(
        open_db(settings), settings.model_copy(update={"sync_request_delay_s": 0}), factory
    )
    runner.created = created  # type: ignore[attr-defined]
    return runner


def runs(settings: Settings) -> list[tuple[str, SyncStatus]]:
    with open_db(settings)() as session:
        rows = session.execute(
            select(User.name, SyncRun.status).join(SyncRun, SyncRun.user_id == User.id)
        ).all()
        return [(name, status) for name, status in rows]


def test_catch_up_syncs_only_due_users(settings: Settings) -> None:
    add_user(settings, "tomek")
    add_user(settings, "brat", GarminStatus.NEEDS_LOGIN)
    with open_db(settings)() as session:
        session.add(User(name="bez-garmina"))
        session.commit()
    runner = runner_for(settings)
    now = datetime(2026, 11, 18, 14, 30, tzinfo=WAW)

    runner.catch_up(now=now)
    assert runs(settings) == [("tomek", SyncStatus.OK)]

    runner.catch_up(now=now + timedelta(minutes=15))  # already synced today
    assert runs(settings) == [("tomek", SyncStatus.OK)]


def test_catch_up_skips_user_in_429_pause(settings: Settings) -> None:
    add_user(settings, "tomek", GarminStatus.RATE_LIMITED)
    with open_db(settings)() as session:
        user = session.scalars(select(User)).one()
        assert user.garmin_credentials is not None
        user.garmin_credentials.rate_limited_at = datetime.now(UTC) - timedelta(hours=1)
        session.commit()
    runner = runner_for(settings)

    runner.catch_up(now=datetime.now(UTC))

    assert runs(settings) == []
    assert runner.created == []  # type: ignore[attr-defined]


def test_daily_job_and_wake_up_catch_up_do_not_sync_twice(settings: Settings) -> None:
    add_user(settings, "tomek")
    add_user(settings, "brat")
    runner = runner_for(settings)
    woke_up = datetime(2026, 11, 18, 14, 10, tzinfo=WAW)

    runner.daily(now=woke_up)  # misfired 14:00 job, run after wake-up
    runner.catch_up(now=woke_up)  # the 15-min check fires at the same moment

    assert sorted(runs(settings)) == [("brat", SyncStatus.OK), ("tomek", SyncStatus.OK)]
    assert len(runner.created) == 2  # type: ignore[attr-defined]


def test_last_ok_counts_from_finish_time(settings: Settings) -> None:
    add_user(settings, "tomek")
    with open_db(settings)() as session:
        user = session.scalars(select(User)).one()
        # Started before 14:00, finished after: today's sync is done.
        session.add(
            SyncRun(
                user_id=user.id,
                status=SyncStatus.OK,
                started_at=datetime(2026, 11, 18, 13, 58, tzinfo=WAW).astimezone(UTC),
                finished_at=datetime(2026, 11, 18, 14, 2, tzinfo=WAW).astimezone(UTC),
            )
        )
        session.commit()
    runner = runner_for(settings)

    runner.catch_up(now=datetime(2026, 11, 18, 14, 30, tzinfo=WAW))

    assert len(runs(settings)) == 1


def test_run_stopped_by_request_limit_counts_as_todays_sync(settings: Settings) -> None:
    add_user(settings, "tomek")
    with open_db(settings)() as session:
        user = session.scalars(select(User)).one()
        session.add(
            SyncRun(
                user_id=user.id,
                status=SyncStatus.PARTIAL,
                request_limit_reached=True,
                started_at=datetime(2026, 11, 18, 14, 0, tzinfo=WAW).astimezone(UTC),
                finished_at=datetime(2026, 11, 18, 14, 20, tzinfo=WAW).astimezone(UTC),
            )
        )
        session.commit()
    runner = runner_for(settings)

    runner.catch_up(now=datetime(2026, 11, 18, 18, 0, tzinfo=WAW))

    assert len(runs(settings)) == 1


def test_consecutive_failures_back_off(settings: Settings) -> None:
    add_user(settings, "tomek")
    with open_db(settings)() as session:
        user = session.scalars(select(User)).one()
        for hour in (14, 15, 17):  # three failures: next try 4 h after the last
            session.add(
                SyncRun(
                    user_id=user.id,
                    status=SyncStatus.FAILED,
                    started_at=datetime(2026, 11, 18, hour, 0, tzinfo=WAW).astimezone(UTC),
                    finished_at=datetime(2026, 11, 18, hour, 1, tzinfo=WAW).astimezone(UTC),
                )
            )
        session.commit()
    runner = runner_for(settings)

    runner.catch_up(now=datetime(2026, 11, 18, 20, 0, tzinfo=WAW))
    assert len(runs(settings)) == 3
    runner.catch_up(now=datetime(2026, 11, 18, 21, 1, tzinfo=WAW))
    assert len(runs(settings)) == 4


def test_one_user_failure_does_not_stop_others(settings: Settings) -> None:
    add_user(settings, "brat")
    add_user(settings, "tomek")
    calls: list[str] = []

    def factory() -> GarminGateway:
        fake = FakeGarminGateway(activities=[])
        if not calls:
            fake.on_call = lambda *_: (_ for _ in ()).throw(RuntimeError("x"))
        calls.append("x")
        return fake

    runner = SyncRunner(
        open_db(settings), settings.model_copy(update={"sync_request_delay_s": 0}), factory
    )
    runner.daily(now=datetime(2026, 11, 18, 14, 0, tzinfo=WAW))

    statuses = sorted(status for _, status in runs(settings))
    assert statuses == [SyncStatus.FAILED, SyncStatus.OK]


def test_scheduler_jobs(settings: Settings) -> None:
    runner = runner_for(settings)
    scheduler = create_scheduler(runner, settings)
    scheduler.start(paused=True)  # job defaults are applied when the scheduler starts
    try:
        jobs: dict[str, Any] = {job.id: job for job in scheduler.get_jobs()}
    finally:
        scheduler.shutdown(wait=False)

    assert set(jobs) == {"daily_sync", "catch_up"}
    assert jobs["daily_sync"].func == runner.daily
    daily = jobs["daily_sync"]
    assert str(daily.trigger.timezone) == "Europe/Warsaw"
    assert "hour='14'" in str(daily.trigger)
    assert jobs["catch_up"].trigger.interval == timedelta(minutes=15)
    for job in jobs.values():
        assert job.coalesce is True
        assert job.max_instances == 1
        assert job.misfire_grace_time is not None and job.misfire_grace_time >= 600
    # Catch-up also runs right after start.
    assert jobs["catch_up"].next_run_time is not None


@pytest.mark.parametrize("enabled", [False, True])
def test_app_lifespan_starts_scheduler_only_when_enabled(
    settings: Settings, monkeypatch: pytest.MonkeyPatch, enabled: bool
) -> None:
    from fastapi.testclient import TestClient

    from cg_server import scheduler as scheduler_module
    from cg_server.app import create_app

    monkeypatch.setenv("CG_DATA_DIR", str(settings.data_dir))
    monkeypatch.setenv("CG_ENCRYPTION_KEY", settings.encryption_key)
    monkeypatch.setenv("CG_SCHEDULER_ENABLED", str(enabled).lower())
    monkeypatch.setattr(scheduler_module, "RealGarminGateway", FakeGarminGateway)
    started: list[bool] = []
    original = scheduler_module.start_scheduler

    def spy(*args: Any, **kwargs: Any) -> Any:
        sched = original(*args, **kwargs)
        started.append(sched is not None and sched.running)
        return sched

    monkeypatch.setattr(scheduler_module, "start_scheduler", spy)

    with TestClient(create_app()) as client:
        assert client.get("/health").status_code == 200

    assert started == ([True] if enabled else [])
