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
from cg_server.garmin.gateway import GarminAuthError, GarminNotFound, GarminRateLimited
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
from cg_server.synclock import SyncBusy, sync_lock

TOKENS = '{"di_token": "fake-access-1", "di_refresh_token": "fake-refresh-1"}'
# 2026-11-18 14:00 in Warsaw (UTC+1 in November).
NOW = datetime(2026, 11, 18, 13, 0, tzinfo=UTC)
TODAY = date(2026, 11, 18)
OPTIONS = SyncOptions(request_delay_s=0)
DETAIL_CALLS = {
    "get_activity_splits",
    "get_activity_hr_in_timezones",
    "get_activity_exercise_sets",
    "get_activity_typed_splits",
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


def test_first_sync_fetches_last_30_days_then_history_newest_first(
    session: Session, settings: Settings
) -> None:
    user = make_user(session, settings.encryption_key)
    fake = FakeGarminGateway(activities=activities_fixture())

    result = run(session, user, fake, settings.encryption_key)

    assert result.status == SyncStatus.OK
    windows = [(date.fromisoformat(a), date.fromisoformat(b)) for a, b in list_windows(fake)]
    assert windows[0] == (TODAY - timedelta(days=29), TODAY)
    # Then the history, chunk by chunk going back, without gaps, down to 12 months.
    for (start, _), (_, prev_end) in zip(windows, windows[1:], strict=False):
        assert prev_end == start - timedelta(days=1)
    for start, end in windows:
        assert end - start <= timedelta(days=29)
    assert windows[-1][0] == TODAY - timedelta(days=365)
    assert count(session, Activity, user) == 5
    assert result.activities_new == 5
    sync_run = session.scalars(select(SyncRun)).one()
    assert sync_run.status == SyncStatus.OK
    assert sync_run.cursor_date == TODAY
    assert sync_run.backfill_date == TODAY - timedelta(days=365)
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
    assert result.activities_updated == 0  # seen again, but nothing changed


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

    # Recent activities first, committed one by one; 3 (history) is not half-saved.
    assert set(session.scalars(select(Activity.garmin_activity_id))) == {4, 5}
    interrupted = session.scalars(select(SyncRun)).one()
    assert interrupted.status == SyncStatus.RUNNING
    assert interrupted.cursor_date == TODAY
    assert interrupted.backfill_date is not None
    assert interrupted.backfill_date > date(2026, 6, 15)
    backfill_at = interrupted.backfill_date

    second = FakeGarminGateway(activities=activities_fixture())
    result = run(session, user, second, key, now=NOW + timedelta(minutes=30))

    assert result.status == SyncStatus.OK
    windows = list_windows(second)
    assert windows[0] == ((TODAY - timedelta(days=3)).isoformat(), TODAY.isoformat())
    assert windows[1][1] == (backfill_at - timedelta(days=1)).isoformat()
    assert count(session, Activity, user) == 5
    assert count(session, ActivityDetails, user) == 5
    assert {aid for _, aid in detail_calls(second)} == {1, 2, 3}
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


@pytest.mark.parametrize("error", [GarminNotFound("404"), ConnectionError("HTTP 500")])
def test_details_error_of_one_activity_does_not_stop_sync(
    session: Session, settings: Settings, error: Exception
) -> None:
    user = make_user(session, settings.encryption_key)

    def fail_for_activity_2(name: str, args: tuple[Any, ...]) -> None:
        if name == "get_activity_exercise_sets" and args[0] == 2:
            raise error

    fake = FakeGarminGateway(activities=activities_fixture(), on_call=fail_for_activity_2)
    result = run(session, user, fake, settings.encryption_key)

    assert result.status == SyncStatus.OK
    assert count(session, Activity, user) == 5
    details = {d.activity.garmin_activity_id: d for d in session.scalars(select(ActivityDetails))}
    assert details[2].fetch_failed is True
    assert details[2].laps_json is None and details[2].exercise_sets_json is None
    assert details[1].fetch_failed is False and details[1].laps_json is not None

    # Not fetched again on the next sync (no hammering on a 404).
    again = FakeGarminGateway(activities=activities_fixture())
    run(session, user, again, settings.encryption_key, now=NOW + timedelta(hours=1))
    assert detail_calls(again) == []


def test_request_limit_first_run_gets_last_30_days_and_history_follows(
    session: Session, settings: Settings
) -> None:
    user = make_user(session, settings.encryption_key)
    key = settings.encryption_key
    options = SyncOptions(request_delay_s=0, max_requests=12)

    first = FakeGarminGateway(activities=activities_fixture())
    result = run(session, user, first, key, options=options)

    assert 0 < len(first.calls) <= 12
    assert result.status == SyncStatus.PARTIAL
    assert result.error is not None and "Limit" in result.error
    assert list_windows(first)[0] == ((TODAY - timedelta(days=29)).isoformat(), TODAY.isoformat())
    # The last 30 days are complete after the first run.
    assert set(session.scalars(select(Activity.garmin_activity_id))) == {4, 5}
    first_run = session.scalars(select(SyncRun)).one()
    assert first_run.request_limit_reached is True
    assert first_run.cursor_date == TODAY
    assert first_run.backfill_date is not None
    assert first_run.backfill_date > TODAY - timedelta(days=365)

    statuses = []
    all_details: list[tuple[str, Any]] = detail_calls(first)
    for day in range(1, 15):
        fake = FakeGarminGateway(activities=activities_fixture())
        res = run(session, user, fake, key, now=NOW + timedelta(days=day), options=options)
        assert len(fake.calls) <= 12
        all_details += detail_calls(fake)
        statuses.append(res.status)
        if res.status == SyncStatus.OK:
            break

    assert statuses[-1] == SyncStatus.OK
    assert count(session, Activity, user) == 5
    assert count(session, ActivityDetails, user) == 5
    # Details of each activity fetched once, even across runs.
    splits = [aid for name, aid in all_details if name == "get_activity_splits"]
    assert sorted(splits) == sorted(set(splits))


def test_current_window_always_goes_before_history(session: Session, settings: Settings) -> None:
    user = make_user(session, settings.encryption_key)
    key = settings.encryption_key
    options = SyncOptions(request_delay_s=0, max_requests=8)
    run(session, user, FakeGarminGateway(activities=activities_fixture()), key, options=options)

    newer = activities_fixture()
    newer.append(make_activity(6, datetime(2026, 11, 19, 7), "running"))
    fake = FakeGarminGateway(activities=newer)
    run(session, user, fake, key, now=NOW + timedelta(days=1), options=options)

    names = [name for name in fake.calls if name != "resume"]
    windows = list_windows(fake)
    assert windows[0] == ("2026-11-15", "2026-11-19")
    # The new activity and its details come before any history request.
    assert names[:3] == [
        "get_activities_by_date",
        "get_activity_splits",
        "get_activity_hr_in_timezones",
    ]
    assert detail_calls(fake)[0] == ("get_activity_splits", 6)
    assert len(windows) > 1  # the rest of the limit went to the history


def test_sync_refuses_to_run_while_another_process_holds_the_lock(
    session: Session, settings: Settings
) -> None:
    user = make_user(session, settings.encryption_key)
    # A run left `running` by another process that is still working.
    session.add(SyncRun(user_id=user.id, status=SyncStatus.RUNNING, started_at=NOW))
    session.commit()
    fake = FakeGarminGateway(activities=activities_fixture())

    with sync_lock(settings.data_dir), pytest.raises(SyncBusy):
        run(session, user, fake, settings.encryption_key)

    assert fake.calls == []
    statuses = list(session.scalars(select(SyncRun.status)))
    assert statuses == [SyncStatus.RUNNING]  # not marked as interrupted

    # Once the lock is free the sync works (and the stale run is marked interrupted).
    assert run(session, user, fake, settings.encryption_key).status == SyncStatus.OK
    assert (settings.data_dir / "sync.lock").exists()


def test_climbing_gets_typed_splits_and_old_sessions_are_backfilled(
    session: Session, settings: Settings
) -> None:
    user = make_user(session, settings.encryption_key)
    fake = FakeGarminGateway(activities=activities_fixture())
    run(session, user, fake, settings.encryption_key)

    climb = session.scalars(select(Activity).where(Activity.garmin_activity_id == 3)).one()
    assert climb.details is not None
    assert climb.details.typed_splits_json["splits"][0]["type"] == "CLIMB_ACTIVE"
    running = session.scalars(select(Activity).where(Activity.garmin_activity_id == 1)).one()
    assert running.details is not None and running.details.typed_splits_json is None

    # A session synced before typed splits existed is completed by the next run.
    climb.details.typed_splits_json = None
    session.commit()
    run(session, user, fake, settings.encryption_key, now=NOW + timedelta(days=1))
    session.refresh(climb)
    assert climb.details is not None
    assert climb.details.typed_splits_json["splits"][1]["type"] == "CLIMB_REST"
