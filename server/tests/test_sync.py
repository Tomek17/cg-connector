"""Sync of activities, details and VO2max. Always FakeGarminGateway, never real Garmin."""

from collections.abc import Iterator
from datetime import UTC, date, datetime, timedelta
from typing import Any

import pytest
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from cg_server.config import Settings
from cg_server.crypto import decrypt, encrypt
from cg_server.db import open_db
from cg_server.garmin.fake import FakeGarminGateway, make_activity
from cg_server.garmin.gateway import GarminAuthError, GarminRateLimited
from cg_server.models import (
    Activity,
    ActivityDetails,
    ActivityKind,
    GarminCredentials,
    GarminStatus,
    SyncRun,
    SyncStatus,
    User,
    Vo2Max,
)
from cg_server.sync import SyncOptions, normalize_kind, sync_user

TOKENS = '{"di_token": "fake-access-1", "di_refresh_token": "fake-refresh-1"}'
# 2026-11-18 14:00 in Warsaw (UTC+1 in November).
NOW = datetime(2026, 11, 18, 13, 0, tzinfo=UTC)
TODAY = date(2026, 11, 18)
OPTIONS = SyncOptions(request_delay_s=0)
DETAIL_CALLS = {
    "get_activity_splits",
    "get_activity_hr_in_timezones",
    "get_activity_exercise_sets",
}


class Interrupted(BaseException):
    """Simulates the process stopping (laptop sleep/power off): not an ordinary error."""


@pytest.fixture
def session(settings: Settings) -> Iterator[Session]:
    with open_db(settings)() as s:
        yield s


def make_user(session: Session, key: str, name: str = "tomek") -> User:
    user = User(name=name)
    user.garmin_credentials = GarminCredentials(
        status=GarminStatus.OK, encrypted_tokens=encrypt(TOKENS, key)
    )
    session.add(user)
    session.commit()
    return user


def activities_fixture() -> list[dict[str, Any]]:
    return [
        make_activity(1, datetime(2026, 1, 10, 7), "running"),
        make_activity(2, datetime(2026, 3, 2, 18), "strength_training"),
        make_activity(3, datetime(2026, 6, 15, 19), "indoor_climbing"),
        make_activity(4, datetime(2026, 11, 10, 8), "trail_running"),
        make_activity(5, datetime(2026, 11, 17, 8), "cycling"),
    ]


def run(
    session: Session,
    user: User,
    fake: FakeGarminGateway,
    key: str,
    now: datetime = NOW,
    options: SyncOptions = OPTIONS,
) -> Any:
    return sync_user(session, user, fake, key=key, options=options, now=now)


def count(session: Session, model: Any, user: User | None = None) -> int:
    q = select(func.count()).select_from(model)
    if user is not None:
        q = q.where(model.user_id == user.id)
    return int(session.scalar(q) or 0)


def detail_calls(fake: FakeGarminGateway) -> list[tuple[str, Any]]:
    return [(name, args[0]) for name, args in fake.call_args if name in DETAIL_CALLS]


def list_windows(fake: FakeGarminGateway) -> list[tuple[str, str]]:
    return [args for name, args in fake.call_args if name == "get_activities_by_date"]  # type: ignore[misc]


@pytest.mark.parametrize(
    ("type_key", "kind"),
    [
        ("running", ActivityKind.RUNNING),
        ("trail_running", ActivityKind.RUNNING),
        ("treadmill_running", ActivityKind.RUNNING),
        ("ultra_run", ActivityKind.RUNNING),
        ("strength_training", ActivityKind.STRENGTH),
        ("indoor_climbing", ActivityKind.CLIMBING),
        ("bouldering", ActivityKind.CLIMBING),
        ("rock_climbing", ActivityKind.CLIMBING),
        ("cycling", ActivityKind.OTHER),
        ("", ActivityKind.OTHER),
    ],
)
def test_normalize_kind(type_key: str, kind: ActivityKind) -> None:
    assert normalize_kind(type_key) == kind


def test_first_sync_fetches_12_months_in_30_day_chunks(
    session: Session, settings: Settings
) -> None:
    user = make_user(session, settings.encryption_key)
    fake = FakeGarminGateway(activities=activities_fixture())

    result = run(session, user, fake, settings.encryption_key)

    assert result.status == SyncStatus.OK
    windows = list_windows(fake)
    assert windows[0][0] == (TODAY - timedelta(days=365)).isoformat()
    assert windows[-1][1] == TODAY.isoformat()
    for (start, end), (next_start, _) in zip(windows, windows[1:], strict=False):
        assert date.fromisoformat(end) - date.fromisoformat(start) <= timedelta(days=29)
        assert date.fromisoformat(next_start) == date.fromisoformat(end) + timedelta(days=1)
    assert count(session, Activity, user) == 5
    assert result.activities_new == 5
    sync_run = session.scalars(select(SyncRun)).one()
    assert sync_run.status == SyncStatus.OK
    assert sync_run.cursor_date == TODAY
    assert sync_run.finished_at is not None


def test_activity_fields_are_normalized_and_trimmed(session: Session, settings: Settings) -> None:
    user = make_user(session, settings.encryption_key)
    run(session, user, FakeGarminGateway(activities=activities_fixture()), settings.encryption_key)

    run_act = session.scalars(select(Activity).where(Activity.garmin_activity_id == 1)).one()
    assert run_act.kind == ActivityKind.RUNNING
    assert run_act.type_key == "running"
    assert run_act.start_time_local == datetime(2026, 1, 10, 7)
    assert run_act.start_time_utc.replace(tzinfo=UTC) == datetime(2026, 1, 10, 6, tzinfo=UTC)
    assert run_act.distance_m == 10000.0
    assert run_act.duration_s == 3000.0
    assert run_act.avg_hr == 150.0 and run_act.max_hr == 176.0
    assert run_act.avg_pace_s_per_km == pytest.approx(300.0, abs=0.1)
    assert run_act.elevation_gain_m == 85.0
    assert run_act.avg_power_w == 290.0
    assert run_act.avg_cadence_spm == 172.0
    assert run_act.aerobic_training_effect == 3.2
    summary = run_act.summary_json
    assert summary["activityId"] == 1
    for banned in ("startLatitude", "endLongitude", "locationName", "ownerFullName", "deviceId"):
        assert banned not in summary
    assert run_act.details is not None
    assert run_act.details.laps_json["lapDTOs"][0]["distance"] == 1000.0
    assert "startLatitude" not in run_act.details.laps_json["lapDTOs"][0]
    assert len(run_act.details.hr_zones_json) == 5
    assert run_act.details.exercise_sets_json is None

    strength = session.scalars(select(Activity).where(Activity.garmin_activity_id == 2)).one()
    assert strength.kind == ActivityKind.STRENGTH
    assert strength.avg_pace_s_per_km is None
    assert strength.details is not None
    sets = strength.details.exercise_sets_json["exerciseSets"]
    assert sets[0]["exercises"][0]["name"] == "BARBELL_BACK_SQUAT"


def test_sync_is_idempotent_and_fetches_details_only_once(
    session: Session, settings: Settings
) -> None:
    user = make_user(session, settings.encryption_key)
    key = settings.encryption_key
    run(session, user, FakeGarminGateway(activities=activities_fixture()), key)

    again = FakeGarminGateway(activities=activities_fixture())
    result = run(session, user, again, key, now=NOW + timedelta(hours=1))

    assert result.status == SyncStatus.OK
    assert count(session, Activity, user) == 5
    assert count(session, ActivityDetails, user) == 5
    assert detail_calls(again) == []
    assert result.activities_new == 0


def test_next_sync_starts_3_days_before_last_successful_one(
    session: Session, settings: Settings
) -> None:
    user = make_user(session, settings.encryption_key)
    key = settings.encryption_key
    run(session, user, FakeGarminGateway(activities=activities_fixture()), key)

    later = activities_fixture()
    later[4]["activityName"] = "Poprawiona nazwa"  # edited in Garmin within the overlap
    later.append(make_activity(6, datetime(2026, 11, 19, 7), "running"))
    fake = FakeGarminGateway(activities=later)
    result = run(session, user, fake, key, now=NOW + timedelta(days=1))

    assert list_windows(fake) == [("2026-11-15", "2026-11-19")]
    assert result.activities_new == 1
    assert result.activities_updated == 1
    edited = session.scalars(select(Activity).where(Activity.garmin_activity_id == 5)).one()
    assert edited.name == "Poprawiona nazwa"
    assert {aid for _, aid in detail_calls(fake)} == {6}


def test_interrupted_sync_resumes_from_last_saved_point(
    session: Session, settings: Settings
) -> None:
    user = make_user(session, settings.encryption_key)
    key = settings.encryption_key

    def stop_at_activity_3(name: str, args: tuple[Any, ...]) -> None:
        if name == "get_activity_hr_in_timezones" and args[0] == 3:
            raise Interrupted

    first = FakeGarminGateway(activities=activities_fixture(), on_call=stop_at_activity_3)
    with pytest.raises(Interrupted):
        run(session, user, first, key)
    session.rollback()

    # Activities 1 and 2 were committed one by one; 3 is not half-saved.
    assert set(session.scalars(select(Activity.garmin_activity_id))) == {1, 2}
    interrupted = session.scalars(select(SyncRun)).one()
    assert interrupted.status == SyncStatus.RUNNING
    assert interrupted.cursor_date is not None and interrupted.cursor_date < date(2026, 6, 15)

    second = FakeGarminGateway(activities=activities_fixture())
    result = run(session, user, second, key, now=NOW + timedelta(minutes=30))

    assert result.status == SyncStatus.OK
    windows = list_windows(second)
    assert windows[0][0] == (interrupted.cursor_date - timedelta(days=3)).isoformat()
    assert count(session, Activity, user) == 5
    assert count(session, ActivityDetails, user) == 5
    assert {aid for _, aid in detail_calls(second)} == {3, 4, 5}
    session.refresh(interrupted)
    assert interrupted.status == SyncStatus.FAILED
    assert interrupted.error is not None


def test_429_stops_sync_without_retries_and_pauses_user(
    session: Session, settings: Settings
) -> None:
    user = make_user(session, settings.encryption_key)
    key = settings.encryption_key
    windows_seen = 0

    def rate_limit_on_second_window(name: str, args: tuple[Any, ...]) -> None:
        nonlocal windows_seen
        if name == "get_activities_by_date":
            windows_seen += 1
            if windows_seen == 2:
                raise GarminRateLimited("HTTP 429")

    fake = FakeGarminGateway(activities=activities_fixture(), on_call=rate_limit_on_second_window)
    result = run(session, user, fake, key)

    assert fake.calls[-1] == "get_activities_by_date"  # nothing after the 429
    assert fake.calls.count("get_activities_by_date") == 2  # no retry
    assert result.status in (SyncStatus.PARTIAL, SyncStatus.FAILED)
    assert result.error is not None and "429" in result.error
    creds = user.garmin_credentials
    assert creds is not None
    assert creds.status == GarminStatus.RATE_LIMITED
    assert creds.rate_limited_at is not None

    blocked = FakeGarminGateway(activities=activities_fixture())
    skipped = run(session, user, blocked, key, now=datetime.now(UTC))
    assert skipped.status == SyncStatus.SKIPPED_RATE_LIMITED
    assert blocked.calls == []


def test_refreshed_tokens_are_saved_after_session(session: Session, settings: Settings) -> None:
    user = make_user(session, settings.encryption_key)
    fake = FakeGarminGateway(activities=[], refreshed_tokens='{"di_token": "fake-access-2"}')

    run(session, user, fake, settings.encryption_key)

    creds = user.garmin_credentials
    assert creds is not None and creds.encrypted_tokens is not None
    assert (
        decrypt(creds.encrypted_tokens, settings.encryption_key) == '{"di_token": "fake-access-2"}'
    )


def test_tokens_saved_even_when_sync_fails_after_resume(
    session: Session, settings: Settings
) -> None:
    user = make_user(session, settings.encryption_key)

    def fail(name: str, _args: tuple[Any, ...]) -> None:
        if name == "get_activities_by_date":
            raise GarminRateLimited("HTTP 429")

    fake = FakeGarminGateway(refreshed_tokens='{"di_token": "fake-access-3"}', on_call=fail)
    run(session, user, fake, settings.encryption_key)

    creds = user.garmin_credentials
    assert creds is not None and creds.encrypted_tokens is not None
    assert (
        decrypt(creds.encrypted_tokens, settings.encryption_key) == '{"di_token": "fake-access-3"}'
    )


def test_rejected_tokens_mark_needs_login(session: Session, settings: Settings) -> None:
    user = make_user(session, settings.encryption_key)
    fake = FakeGarminGateway(error=GarminAuthError("odrzucone"))

    result = run(session, user, fake, settings.encryption_key)

    assert result.status == SyncStatus.FAILED
    assert fake.calls == ["resume"]
    creds = user.garmin_credentials
    assert creds is not None and creds.status == GarminStatus.NEEDS_LOGIN

    # Not retried against Garmin until the user logs in again.
    again = FakeGarminGateway()
    assert run(session, user, again, settings.encryption_key).status == SyncStatus.FAILED
    assert again.calls == []


def test_user_without_tokens_fails_without_contacting_garmin(
    session: Session, settings: Settings
) -> None:
    user = User(name="nowy")
    session.add(user)
    session.commit()
    fake = FakeGarminGateway()

    result = run(session, user, fake, settings.encryption_key)

    assert result.status == SyncStatus.FAILED
    assert fake.calls == []


def test_other_errors_are_recorded_without_details(session: Session, settings: Settings) -> None:
    user = make_user(session, settings.encryption_key)

    def boom(name: str, _args: tuple[Any, ...]) -> None:
        if name == "get_max_metrics_range":
            raise ConnectionError("HTTP 500 <html>tajna-tresc-odpowiedzi</html>")

    result = run(
        session,
        user,
        FakeGarminGateway(activities=activities_fixture(), on_call=boom),
        settings.encryption_key,
    )

    assert result.status in (SyncStatus.PARTIAL, SyncStatus.FAILED)
    sync_run = session.scalars(select(SyncRun)).one()
    assert sync_run.error is not None
    assert "tajna" not in sync_run.error


def test_vo2max_is_stored_and_upserted(session: Session, settings: Settings) -> None:
    user = make_user(session, settings.encryption_key)
    key = settings.encryption_key
    vo2 = [("2026-01-10", 51.5), ("2026-11-17", 52.4)]
    run(session, user, FakeGarminGateway(activities=[], vo2max=vo2), key)
    run(
        session,
        user,
        FakeGarminGateway(activities=[], vo2max=[("2026-11-17", 52.9)]),
        key,
        now=NOW + timedelta(hours=1),
    )

    rows = {(v.date, v.sport): v.value for v in session.scalars(select(Vo2Max))}
    assert rows == {
        (date(2026, 1, 10), "running"): 51.5,
        (date(2026, 11, 17), "running"): 52.9,
    }


def test_pauses_between_requests(session: Session, settings: Settings) -> None:
    user = make_user(session, settings.encryption_key)
    fake = FakeGarminGateway(activities=activities_fixture())
    sleeps: list[float] = []

    sync_user(
        session,
        user,
        fake,
        key=settings.encryption_key,
        options=SyncOptions(request_delay_s=1.5),
        now=NOW,
        sleep=sleeps.append,
    )

    assert sleeps and set(sleeps) == {1.5}
    assert len(sleeps) == len(fake.calls) - 1


def test_users_are_isolated(session: Session, settings: Settings) -> None:
    key = settings.encryption_key
    tomek = make_user(session, key, "tomek")
    brat = make_user(session, key, "brat")
    # Same Garmin activity ids on both accounts must not collide.
    brat_acts = [make_activity(1, datetime(2026, 11, 1, 9), "strength_training")]

    run(session, tomek, FakeGarminGateway(activities=activities_fixture()), key)
    run(session, brat, FakeGarminGateway(activities=brat_acts, vo2max=[("2026-11-01", 48.0)]), key)

    assert count(session, Activity, tomek) == 5
    assert count(session, Activity, brat) == 1
    brat_act = session.scalars(select(Activity).where(Activity.user_id == brat.id)).one()
    assert brat_act.kind == ActivityKind.STRENGTH
    assert count(session, Vo2Max, tomek) == 0
    assert count(session, SyncRun, tomek) == 1
    assert count(session, SyncRun, brat) == 1

    # Brat's 429 does not pause Tomek.
    run(
        session,
        brat,
        FakeGarminGateway(error=GarminRateLimited()),
        key,
        now=NOW + timedelta(days=1),
    )
    result = run(session, tomek, FakeGarminGateway(activities=[]), key, now=NOW + timedelta(days=1))
    assert result.status == SyncStatus.OK
