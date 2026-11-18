"""Workouts service (create/schedule/list/delete) and the outbox worker, with the fake Garmin."""

import uuid
from collections.abc import Callable, Iterator
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
from garminconnect import GarminConnectConnectionError
from sqlalchemy import select
from sqlalchemy.orm import Session, sessionmaker

from cg_server import workouts
from cg_server.config import Settings
from cg_server.crypto import decrypt, encrypt
from cg_server.db import open_db
from cg_server.garmin.fake import FakeGarminGateway
from cg_server.garmin.gateway import GarminRateLimited
from cg_server.models import (
    GarminCredentials,
    GarminStatus,
    ScheduledWorkout,
    ScheduleStatus,
    User,
    Workout,
    WorkoutOrigin,
)
from cg_server.outbox import MAX_ATTEMPTS, OutboxRunner, process_user_outbox, retry_delay
from cg_server.synclock import SyncBusy, sync_lock
from cg_server.workout_definition import DefinitionError

NOW = datetime(2026, 11, 18, 10, 0, tzinfo=UTC)
TODAY = date(2026, 11, 18)
DAY = date(2026, 11, 20)
RUN = {
    "sport": "running",
    "name": "Easy",
    "steps": [{"type": "interval", "seconds": 1800, "target": {"type": "hr_zone", "zone": 2}}],
}


@pytest.fixture
def sessions(settings: Settings) -> sessionmaker[Session]:
    sessions = open_db(settings)
    with sessions() as session:
        for name in ("tomek", "brat"):
            user = User(name=name)
            user.garmin_credentials = GarminCredentials(
                status=GarminStatus.OK,
                encrypted_tokens=encrypt('{"t": 1}', settings.encryption_key),
            )
            session.add(user)
        session.commit()
    return sessions


@pytest.fixture
def session(sessions: sessionmaker[Session]) -> Iterator[Session]:
    with sessions() as session:
        yield session


def user(session: Session, name: str = "tomek") -> User:
    return session.scalars(select(User).where(User.name == name)).one()


def schedule(session: Session, name: str = "tomek", day: date = DAY) -> ScheduledWorkout:
    workout, _ = workouts.create_workout(
        session, user(session, name), RUN, origin=WorkoutOrigin.MCP
    )
    item, _ = workouts.schedule_workout(session, user(session, name), workout.id, day, today=TODAY)
    return item


def run(
    session: Session,
    settings: Settings,
    gateway: FakeGarminGateway,
    name: str = "tomek",
    now: datetime = NOW,
) -> Any:
    return process_user_outbox(
        session,
        user(session, name),
        gateway,
        key=settings.encryption_key,
        now=now,
        sleep=lambda _s: None,
        lock_dir=settings.data_dir,
    )


def raise_on(method: str, error: Exception) -> Callable[[str, tuple[Any, ...]], None]:
    def hook(name: str, _args: tuple[Any, ...]) -> None:
        if name == method:
            raise error

    return hook


# --- service -------------------------------------------------------------------------------


def test_create_workout_is_idempotent_by_client_id(session: Session) -> None:
    client_id = str(uuid.uuid4())
    first, created = workouts.create_workout(
        session, user(session), RUN, client_id=client_id, origin=WorkoutOrigin.APP
    )
    again, created_again = workouts.create_workout(
        session, user(session), RUN, client_id=client_id, origin=WorkoutOrigin.APP
    )
    assert created and not created_again
    assert again.id == first.id
    assert session.query(Workout).count() == 1
    assert first.name == "Easy" and first.sport == "running"
    assert first.definition_json["steps"][0]["target"] == {"type": "hr_zone", "zone": 2}


def test_same_client_id_for_another_user_is_a_new_workout(session: Session) -> None:
    client_id = str(uuid.uuid4())
    a, _ = workouts.create_workout(session, user(session), RUN, client_id=client_id, origin="app")
    b, _ = workouts.create_workout(
        session, user(session, "brat"), RUN, client_id=client_id, origin="app"
    )
    assert a.id != b.id


def test_create_rejects_bad_definition_and_client_id(session: Session) -> None:
    with pytest.raises(DefinitionError):
        workouts.create_workout(session, user(session), {"sport": "running"}, origin="mcp")
    with pytest.raises(workouts.WorkoutError, match="client_id"):
        workouts.create_workout(session, user(session), RUN, client_id="abc", origin="app")


def test_schedule_validates_date_and_ownership(session: Session) -> None:
    workout, _ = workouts.create_workout(session, user(session), RUN, origin="mcp")
    with pytest.raises(workouts.WorkoutError, match="past"):
        workouts.schedule_workout(
            session, user(session), workout.id, TODAY - timedelta(1), today=TODAY
        )
    with pytest.raises(workouts.WorkoutError, match="365"):
        workouts.schedule_workout(
            session, user(session), workout.id, TODAY + timedelta(400), today=TODAY
        )
    with pytest.raises(workouts.NotFound):
        workouts.schedule_workout(session, user(session, "brat"), workout.id, DAY, today=TODAY)


def test_scheduling_same_day_twice_returns_the_same_entry(session: Session) -> None:
    item = schedule(session)
    again, created = workouts.schedule_workout(
        session, user(session), item.workout_id, DAY, today=TODAY
    )
    assert again.id == item.id and not created
    assert item.status == ScheduleStatus.PENDING


def test_scheduling_a_failed_entry_again_retries_it(session: Session) -> None:
    item = schedule(session)
    item.status, item.attempts, item.last_error = ScheduleStatus.FAILED, MAX_ATTEMPTS, "x"
    session.commit()
    again, _ = workouts.schedule_workout(session, user(session), item.workout_id, DAY, today=TODAY)
    assert again.id == item.id
    assert (again.status, again.attempts, again.last_error) == (ScheduleStatus.PENDING, 0, None)


def test_list_scheduled_is_per_user_and_skips_deleted(session: Session) -> None:
    mine = schedule(session)
    schedule(session, "brat")
    gone = schedule(session, day=DAY + timedelta(1))
    workouts.request_delete(session, user(session), gone.id)
    listed = workouts.list_scheduled(session, user(session), TODAY, DAY + timedelta(5))
    assert [i.id for i in listed] == [mine.id]


def test_delete_only_own_entries(session: Session) -> None:
    item = schedule(session, "brat")
    with pytest.raises(workouts.NotFound):
        workouts.request_delete(session, user(session), item.id)
    assert session.get(ScheduledWorkout, item.id).status == ScheduleStatus.PENDING  # type: ignore[union-attr]


def test_delete_of_unsent_entry_needs_no_garmin(session: Session) -> None:
    item = schedule(session)
    assert workouts.request_delete(session, user(session), item.id).status == ScheduleStatus.DELETED


# --- worker --------------------------------------------------------------------------------


def test_pending_workout_is_uploaded_and_scheduled(session: Session, settings: Settings) -> None:
    item = schedule(session)
    fake = FakeGarminGateway(refreshed_tokens='{"t": 2}')
    result = run(session, settings, fake)
    session.refresh(item)
    assert result.sent == 1
    assert item.status == ScheduleStatus.SENT
    assert item.last_error is None and item.next_attempt_at is None
    assert item.workout.garmin_workout_id in fake.workouts
    assert fake.calendar[item.garmin_schedule_id]["date"] == "2026-11-20"  # type: ignore[index]
    assert fake.workouts[item.workout.garmin_workout_id]["workoutName"] == "CG Easy"  # type: ignore[index]
    # Tokens are saved again after the session.
    creds = user(session).garmin_credentials
    assert creds is not None and creds.encrypted_tokens is not None
    assert decrypt(creds.encrypted_tokens, settings.encryption_key) == '{"t": 2}'


def test_nothing_due_means_no_garmin_traffic(session: Session, settings: Settings) -> None:
    fake = FakeGarminGateway()
    run(session, settings, fake)
    assert fake.calls == []


def test_retry_after_failure_in_the_middle_does_not_duplicate(
    session: Session, settings: Settings
) -> None:
    item = schedule(session)

    def fail_schedule(name: str, _args: tuple[Any, ...]) -> None:
        if name == "schedule_workout":
            error = GarminConnectConnectionError("API Error 500")
            error.response = type("R", (), {"status_code": 500})()
            raise error

    fake = FakeGarminGateway(on_call=fail_schedule)
    run(session, settings, fake)
    session.refresh(item)
    assert item.status == ScheduleStatus.PENDING
    assert item.attempts == 1
    assert item.last_error == "send_failed: Garmin HTTP 500"
    assert item.workout.garmin_workout_id is not None  # saved before scheduling
    assert item.next_attempt_at is not None

    # Not due yet: no traffic.
    fake.on_call = None
    fake.calls.clear()
    run(session, settings, fake, now=NOW + timedelta(minutes=1))
    assert fake.calls == []

    run(session, settings, fake, now=NOW + retry_delay(1))
    session.refresh(item)
    assert item.status == ScheduleStatus.SENT
    assert len(fake.workouts) == 1  # uploaded once
    assert len(fake.calendar) == 1
    assert "upload_workout" not in fake.calls


def test_retry_after_crash_between_schedule_and_commit(
    session: Session, settings: Settings
) -> None:
    """The process died after Garmin scheduled it but before we saved that: no 2nd entry."""
    item = schedule(session)
    fake = FakeGarminGateway()
    fake.resume("{}")
    garmin_id = fake.upload_workout({"workoutName": "CG Easy"})
    fake.schedule_workout(garmin_id, "2026-11-20")
    item.workout.garmin_workout_id = garmin_id
    item.attempts = 1  # the attempt was counted before contacting Garmin
    session.commit()

    run(session, settings, fake)
    session.refresh(item)
    assert item.status == ScheduleStatus.SENT
    assert len(fake.calendar) == 1
    assert item.garmin_schedule_id in fake.calendar


def test_deleted_template_on_garmin_is_uploaded_again(session: Session, settings: Settings) -> None:
    item = schedule(session)
    item.workout.garmin_workout_id = 424242  # unknown to Garmin (deleted there)
    session.commit()
    fake = FakeGarminGateway()
    run(session, settings, fake)
    session.refresh(item)
    assert item.status == ScheduleStatus.PENDING
    assert item.workout.garmin_workout_id is None
    run(session, settings, fake, now=NOW + retry_delay(1))
    session.refresh(item)
    assert item.status == ScheduleStatus.SENT


def test_gives_up_after_max_attempts(session: Session, settings: Settings) -> None:
    item = schedule(session)
    fake = FakeGarminGateway(on_call=raise_on("upload_workout", ValueError("boom")))
    now = NOW
    for _ in range(MAX_ATTEMPTS):
        run(session, settings, fake, now=now)
        now += timedelta(days=1)
    session.refresh(item)
    assert item.status == ScheduleStatus.FAILED
    assert item.attempts == MAX_ATTEMPTS
    assert item.next_attempt_at is None
    fake.calls.clear()
    run(session, settings, fake, now=now)
    assert fake.calls == []


def test_429_stops_and_pauses_like_sync(session: Session, settings: Settings) -> None:
    first = schedule(session)
    second = schedule(session, day=DAY + timedelta(1))
    fake = FakeGarminGateway(on_call=raise_on("upload_workout", GarminRateLimited()))
    result = run(session, settings, fake)
    assert result.rate_limited
    assert fake.calls.count("upload_workout") == 1  # stopped at once
    for item in (first, second):
        session.refresh(item)
        assert item.status == ScheduleStatus.PENDING
        assert item.attempts == 0  # not the workout's fault
    creds = user(session).garmin_credentials
    assert creds is not None and creds.status == GarminStatus.RATE_LIMITED
    assert creds.rate_limited_at is not None
    # During the 24 h pause nothing goes to Garmin.
    fake.on_call = None
    fake.calls.clear()
    later = datetime.now(UTC) + timedelta(hours=1)
    assert run(session, settings, fake, now=later).skipped == "rate_limited"
    assert fake.calls == []


def test_without_garmin_login_nothing_is_sent(session: Session, settings: Settings) -> None:
    item = schedule(session)
    creds = user(session).garmin_credentials
    assert creds is not None
    creds.status = GarminStatus.NEEDS_LOGIN
    session.commit()
    fake = FakeGarminGateway()
    assert run(session, settings, fake).skipped == "needs_login"
    assert fake.calls == []
    session.refresh(item)
    assert item.status == ScheduleStatus.PENDING and item.attempts == 0
    assert item.last_error is not None and "garmin-login" in item.last_error


def test_delete_sent_entry_removes_it_from_garmin_calendar(
    session: Session, settings: Settings
) -> None:
    item = schedule(session)
    fake = FakeGarminGateway()
    run(session, settings, fake)
    assert (
        workouts.request_delete(session, user(session), item.id).status == ScheduleStatus.DELETING
    )
    run(session, settings, fake)
    session.refresh(item)
    assert item.status == ScheduleStatus.DELETED
    assert fake.calendar == {}
    assert item.workout.garmin_workout_id in fake.workouts  # template stays


def test_delete_already_gone_on_garmin(session: Session, settings: Settings) -> None:
    item = schedule(session)
    fake = FakeGarminGateway()
    run(session, settings, fake)
    fake.calendar.clear()  # user removed it in Garmin Connect
    workouts.request_delete(session, user(session), item.id)
    run(session, settings, fake)
    session.refresh(item)
    assert item.status == ScheduleStatus.DELETED


def test_delete_while_sending_still_ends_deleted(
    sessions: sessionmaker[Session], session: Session, settings: Settings
) -> None:
    item = schedule(session)

    def delete_meanwhile(name: str, _args: tuple[Any, ...]) -> None:
        if name == "schedule_workout":
            with sessions() as other:
                workouts.request_delete(other, user(other), item.id)

    fake = FakeGarminGateway(on_call=delete_meanwhile)
    run(session, settings, fake)
    session.refresh(item)
    assert item.status == ScheduleStatus.DELETED
    assert fake.calendar == {}


def test_only_own_entries_are_processed(session: Session, settings: Settings) -> None:
    mine = schedule(session)
    theirs = schedule(session, "brat")
    run(session, settings, FakeGarminGateway())
    session.refresh(mine)
    session.refresh(theirs)
    assert mine.status == ScheduleStatus.SENT
    assert theirs.status == ScheduleStatus.PENDING


def test_shares_the_sync_lock(session: Session, settings: Settings) -> None:
    schedule(session)
    with sync_lock(settings.data_dir), pytest.raises(SyncBusy):
        run(session, settings, FakeGarminGateway())


def test_runner_sends_for_all_users_and_survives_errors(
    sessions: sessionmaker[Session], settings: Settings, tmp_path: Path
) -> None:
    with sessions() as session:
        mine, theirs = schedule(session), schedule(session, "brat")
    fakes: list[FakeGarminGateway] = []

    def factory() -> FakeGarminGateway:
        fakes.append(FakeGarminGateway())
        return fakes[-1]

    runner = OutboxRunner(sessions, settings, factory, sleep=lambda _s: None)
    runner.run_all()
    with sessions() as session:
        assert session.get(ScheduledWorkout, mine.id).status == ScheduleStatus.SENT  # type: ignore[union-attr]
        assert session.get(ScheduledWorkout, theirs.id).status == ScheduleStatus.SENT  # type: ignore[union-attr]
    assert len(fakes) == 2

    def broken() -> FakeGarminGateway:
        raise RuntimeError("boom")

    with sessions() as session:
        schedule(session, day=DAY + timedelta(2))
    OutboxRunner(sessions, settings, broken).run_all()  # logged, never raised


@pytest.mark.parametrize(("attempts", "minutes"), [(1, 5), (2, 10), (3, 20), (6, 160), (20, 360)])
def test_retry_delay(attempts: int, minutes: int) -> None:
    assert retry_delay(attempts) == timedelta(minutes=minutes)


def http_error(status: int) -> GarminConnectConnectionError:
    error = GarminConnectConnectionError(f"API Error {status}")
    error.response = type("R", (), {"status_code": status})()
    return error


def test_errors_are_english_codes_send_vs_delete(session: Session, settings: Settings) -> None:
    item = schedule(session)
    fake = FakeGarminGateway(on_call=raise_on("upload_workout", http_error(503)))
    run(session, settings, fake)
    session.refresh(item)
    assert item.last_error == "send_failed: Garmin HTTP 503"

    fake.on_call = None
    run(session, settings, fake, now=NOW + retry_delay(1))
    workouts.request_delete(session, user(session), item.id)
    fake.on_call = raise_on("unschedule_workout", http_error(500))
    run(session, settings, fake, now=NOW + retry_delay(1))
    session.refresh(item)
    assert item.status == ScheduleStatus.DELETING
    assert item.last_error == "delete_failed: Garmin HTTP 500"


# --- unknown Garmin schedule id (field names not verified live) -----------------------------


def test_schedule_answer_without_id_is_looked_up(session: Session, settings: Settings) -> None:
    item = schedule(session)
    fake = FakeGarminGateway()
    fake.schedule_answer_has_id = False
    run(session, settings, fake)
    session.refresh(item)
    assert item.status == ScheduleStatus.SENT
    assert item.garmin_schedule_id in fake.calendar


def test_calendar_match_without_id_counts_as_scheduled(
    session: Session, settings: Settings
) -> None:
    item = schedule(session)
    fake = FakeGarminGateway()
    fake.resume("{}")
    garmin_id = fake.upload_workout({"workoutName": "CG Easy"})
    fake.schedule_workout(garmin_id, "2026-11-20")
    fake.calendar_entries_have_id = False
    item.workout.garmin_workout_id = garmin_id
    session.commit()
    fake.calls.clear()
    run(session, settings, fake)
    session.refresh(item)
    assert "schedule_workout" not in fake.calls  # never twice
    assert len(fake.calendar) == 1
    assert item.status == ScheduleStatus.SENT
    assert item.garmin_schedule_id is None


def sent_without_id(
    session: Session, settings: Settings, fake: FakeGarminGateway
) -> ScheduledWorkout:
    item = schedule(session)
    fake.schedule_answer_has_id = False
    fake.calendar_entries_have_id = False
    run(session, settings, fake)
    session.refresh(item)
    assert (item.status, item.garmin_schedule_id) == (ScheduleStatus.SENT, None)
    return item


def test_delete_without_known_id_looks_it_up(session: Session, settings: Settings) -> None:
    fake = FakeGarminGateway()
    item = sent_without_id(session, settings, fake)
    assert (
        workouts.request_delete(session, user(session), item.id).status == ScheduleStatus.DELETING
    )
    fake.calendar_entries_have_id = True
    run(session, settings, fake)
    session.refresh(item)
    assert item.status == ScheduleStatus.DELETED
    assert fake.calendar == {}


def test_delete_never_claims_deleted_when_id_cannot_be_found(
    session: Session, settings: Settings
) -> None:
    fake = FakeGarminGateway()
    item = sent_without_id(session, settings, fake)
    workouts.request_delete(session, user(session), item.id)
    now = NOW
    for _ in range(MAX_ATTEMPTS):
        run(session, settings, fake, now=now)
        now += timedelta(days=1)
    session.refresh(item)
    assert item.status == ScheduleStatus.FAILED
    assert item.last_error is not None
    assert item.last_error.startswith("delete_failed: calendar entry has no known id")
    assert len(fake.calendar) == 1
    # Asking again retries the removal, it does not just mark it deleted.
    assert (
        workouts.request_delete(session, user(session), item.id).status == ScheduleStatus.DELETING
    )
