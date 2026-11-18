"""Read queries: always scoped to one user, local dates in Europe/Warsaw."""

import inspect
from collections.abc import Iterator
from datetime import UTC, date, datetime, timedelta
from typing import Any

import pytest
from sqlalchemy.orm import Session

from cg_server import queries
from cg_server.config import Settings
from cg_server.db import open_db
from cg_server.models import ActivityKind, GarminStatus, SyncStatus, User
from cg_server.queries import Period
from cg_server.users import add_user
from tests.seed import add_activity, add_sync, add_vo2max, set_garmin_status


@pytest.fixture
def session(settings: Settings) -> Iterator[Session]:
    with open_db(settings)() as session:
        yield session


@pytest.fixture
def users(session: Session) -> tuple[User, User]:
    return add_user(session, "anna"), add_user(session, "bartek")


PUBLIC_QUERIES = [
    queries.list_activities,
    queries.get_activity,
    queries.vo2max_trend,
    queries.training_summary,
    queries.sync_status,
]


@pytest.mark.parametrize("fn", PUBLIC_QUERIES)
def test_every_query_requires_keyword_user_id(fn: Any) -> None:
    param = inspect.signature(fn).parameters["user_id"]
    assert param.kind is inspect.Parameter.KEYWORD_ONLY
    assert param.default is inspect.Parameter.empty


@pytest.mark.parametrize("bad", [None, "1", True, 1.0])
def test_queries_reject_non_int_user_id(session: Session, bad: Any) -> None:
    with pytest.raises(TypeError):
        queries.list_activities(session, user_id=bad)
    with pytest.raises(TypeError):
        queries.sync_status(session, user_id=bad)


def test_list_activities_filters_by_user_dates_and_type(
    session: Session, users: tuple[User, User]
) -> None:
    a, b = users
    add_activity(session, a, 1, datetime(2026, 11, 1, 23, 30))  # 22:30 UTC, still Nov 1 local
    add_activity(session, a, 2, datetime(2026, 11, 2, 0, 30))  # 23:30 UTC on Nov 1
    add_activity(session, a, 3, datetime(2026, 11, 3, 8), ActivityKind.STRENGTH)
    add_activity(session, a, 4, datetime(2026, 11, 4, 8))
    add_activity(session, b, 5, datetime(2026, 11, 2, 8))

    ids = [x.garmin_activity_id for x in queries.list_activities(session, user_id=a.id)]
    assert ids == [4, 3, 2, 1]  # newest first, user A only

    in_range = queries.list_activities(
        session, user_id=a.id, start=date(2026, 11, 2), end=date(2026, 11, 3)
    )
    assert [x.garmin_activity_id for x in in_range] == [3, 2]

    runs = queries.list_activities(session, user_id=a.id, kind=ActivityKind.RUNNING, limit=2)
    assert [x.garmin_activity_id for x in runs] == [4, 2]


def test_get_activity_by_garmin_id_only_for_owner(
    session: Session, users: tuple[User, User]
) -> None:
    a, b = users
    add_activity(session, a, 11, datetime(2026, 11, 1, 8), details=True)
    assert queries.get_activity(session, user_id=a.id, activity_id=11) is not None
    assert queries.get_activity(session, user_id=b.id, activity_id=11) is None
    assert queries.get_activity(session, user_id=a.id, activity_id=999) is None


def test_activity_details_are_parsed(session: Session, users: tuple[User, User]) -> None:
    a, _ = users
    add_activity(session, a, 21, datetime(2026, 11, 1, 8), ActivityKind.STRENGTH, details=True)
    activity = queries.get_activity(session, user_id=a.id, activity_id=21)
    assert activity is not None
    details = queries.parse_details(activity)
    assert details.available
    assert [lap.index for lap in details.laps] == [1, 2]
    assert details.laps[0].distance_m == 1000.0
    assert details.laps[0].avg_hr == 149.0
    assert details.laps[0].pace_s_per_km == pytest.approx(299.0)
    assert [z.zone for z in details.hr_zones] == [1, 2, 3, 4, 5]
    assert details.hr_zones[0].seconds == 600.0
    assert details.hr_zones[0].low_bpm == 115
    assert len(details.exercise_sets) == 3
    first = details.exercise_sets[0]
    assert (first.exercise, first.reps, first.weight_kg) == ("BARBELL_BACK_SQUAT", 5, 80.0)
    assert details.exercise_sets[1].set_type == "REST"


def test_details_tolerate_missing_and_odd_json(session: Session, users: tuple[User, User]) -> None:
    a, _ = users
    act = add_activity(session, a, 31, datetime(2026, 11, 1, 8))
    assert not queries.parse_details(act).available
    act2 = add_activity(session, a, 32, datetime(2026, 11, 2, 8), details=True)
    assert act2.details is not None
    act2.details.laps_json = [{"distance": "x"}, "junk"]
    act2.details.hr_zones_json = {"unexpected": True}
    details = queries.parse_details(act2)
    assert details.laps[0].distance_m is None
    assert details.hr_zones == []


def test_vo2max_trend_in_range_for_user(session: Session, users: tuple[User, User]) -> None:
    a, b = users
    add_vo2max(session, a, date(2026, 10, 1), 51.0)
    add_vo2max(session, a, date(2026, 10, 15), 51.5)
    add_vo2max(session, a, date(2026, 10, 15), 48.0, sport="cycling")
    add_vo2max(session, a, date(2026, 11, 1), 52.0)
    add_vo2max(session, b, date(2026, 10, 20), 40.0)
    rows = queries.vo2max_trend(
        session, user_id=a.id, start=date(2026, 10, 10), end=date(2026, 11, 1)
    )
    assert [(r.date, r.value, r.sport) for r in rows] == [
        (date(2026, 10, 15), 51.5, "running"),
        (date(2026, 11, 1), 52.0, "running"),
    ]
    every_sport = queries.vo2max_trend(
        session, user_id=a.id, start=date(2026, 10, 1), end=date(2026, 11, 1), sport=None
    )
    assert len(every_sport) == 4


def test_training_summary_per_week_and_type(session: Session, users: tuple[User, User]) -> None:
    a, b = users
    # Week of Mon 2026-11-02: two runs and one strength session.
    add_activity(session, a, 1, datetime(2026, 11, 2, 7), distance_m=10000.0, duration_s=3000.0)
    add_activity(session, a, 2, datetime(2026, 11, 8, 23, 30), distance_m=5000.0, duration_s=1200.0)
    add_activity(session, a, 3, datetime(2026, 11, 4, 18), ActivityKind.STRENGTH)
    # Next week.
    add_activity(session, a, 4, datetime(2026, 11, 9, 7), elevation_gain_m=None)
    add_activity(session, b, 5, datetime(2026, 11, 3, 7))

    rows = queries.training_summary(
        session,
        user_id=a.id,
        period=Period.WEEK,
        start=date(2026, 11, 1),
        end=date(2026, 11, 15),
    )
    summary = {(r.period_start, r.kind): r for r in rows}
    assert list(summary) == [
        (date(2026, 11, 2), ActivityKind.RUNNING),
        (date(2026, 11, 2), ActivityKind.STRENGTH),
        (date(2026, 11, 9), ActivityKind.RUNNING),
    ]
    week_runs = summary[(date(2026, 11, 2), ActivityKind.RUNNING)]
    assert week_runs.period_end == date(2026, 11, 8)
    assert week_runs.count == 2
    assert week_runs.distance_m == 15000.0
    assert week_runs.duration_s == 4200.0
    assert week_runs.elevation_gain_m == 170.0
    assert week_runs.pace_s_per_km == pytest.approx(280.0)
    strength = summary[(date(2026, 11, 2), ActivityKind.STRENGTH)]
    assert strength.distance_m is None
    assert strength.pace_s_per_km is None
    assert summary[(date(2026, 11, 9), ActivityKind.RUNNING)].elevation_gain_m is None


def test_training_summary_per_month(session: Session, users: tuple[User, User]) -> None:
    a, _ = users
    add_activity(session, a, 1, datetime(2026, 10, 31, 23, 30))  # Oct local, Oct 31 22:30 UTC
    add_activity(session, a, 2, datetime(2026, 11, 1, 0, 30))  # Nov local, Oct 31 UTC
    rows = queries.training_summary(
        session, user_id=a.id, period=Period.MONTH, start=date(2026, 10, 1), end=date(2026, 11, 30)
    )
    assert [(r.period_start, r.period_end, r.count) for r in rows] == [
        (date(2026, 10, 1), date(2026, 10, 31), 1),
        (date(2026, 11, 1), date(2026, 11, 30), 1),
    ]


def test_period_start_helpers() -> None:
    assert queries.period_start(date(2026, 11, 18), Period.WEEK) == date(2026, 11, 16)
    assert queries.period_start(date(2026, 11, 18), Period.MONTH) == date(2026, 11, 1)
    assert queries.period_end(date(2026, 12, 7), Period.MONTH) == date(2026, 12, 31)
    assert queries.period_end(date(2026, 11, 16), Period.WEEK) == date(2026, 11, 22)


def test_sync_status(session: Session, users: tuple[User, User]) -> None:
    a, b = users
    empty = queries.sync_status(session, user_id=a.id)
    assert empty.last_success_at is None
    assert empty.last_attempt_at is None
    assert empty.garmin_status is None
    assert empty.latest_activity_date is None

    ok = datetime(2026, 11, 17, 13, 5, tzinfo=UTC)
    add_sync(session, a, ok)
    add_sync(session, a, ok + timedelta(days=1), SyncStatus.FAILED)
    add_sync(session, b, ok + timedelta(days=2))
    set_garmin_status(session, a, GarminStatus.NEEDS_LOGIN)
    add_activity(session, a, 1, datetime(2026, 11, 12, 0, 30))  # Nov 11 in UTC
    add_activity(session, b, 2, datetime(2026, 11, 15, 8))
    session.commit()

    state = queries.sync_status(session, user_id=a.id)
    assert state.last_success_at == ok
    assert state.last_success_at.tzinfo is not None
    assert state.last_attempt_at == ok + timedelta(days=1) - timedelta(minutes=2)
    assert state.last_status == SyncStatus.FAILED
    assert state.garmin_status == GarminStatus.NEEDS_LOGIN
    assert state.latest_activity_date == date(2026, 11, 12)


def test_sync_is_stale_after_24_hours() -> None:
    now = datetime(2026, 11, 18, 15, tzinfo=UTC)
    assert queries.is_stale(None, now)
    assert queries.is_stale(now - timedelta(hours=25), now)
    assert not queries.is_stale(now - timedelta(hours=23), now)


def test_summer_time_local_day_boundaries(session: Session, users: tuple[User, User]) -> None:
    a, _ = users
    # CEST (+02:00): 00:30 local on Jul 2 is 22:30 UTC on Jul 1.
    add_activity(session, a, 1, datetime(2026, 7, 2, 0, 30))
    add_activity(session, a, 2, datetime(2026, 7, 1, 23, 30))
    day = queries.list_activities(
        session, user_id=a.id, start=date(2026, 7, 2), end=date(2026, 7, 2)
    )
    assert [x.garmin_activity_id for x in day] == [1]
    assert queries.local_date(day[0].start_time_utc) == date(2026, 7, 2)


def test_weekly_summary_across_new_year(session: Session, users: tuple[User, User]) -> None:
    a, _ = users
    add_activity(session, a, 1, datetime(2026, 12, 28, 7))  # Mon
    add_activity(session, a, 2, datetime(2027, 1, 3, 23, 30))  # Sun, same week
    add_activity(session, a, 3, datetime(2027, 1, 4, 0, 30))  # Mon, next week
    rows = queries.training_summary(
        session, user_id=a.id, period=Period.WEEK, start=date(2026, 12, 20), end=date(2027, 1, 10)
    )
    assert [(r.period_start, r.period_end, r.count) for r in rows] == [
        (date(2026, 12, 28), date(2027, 1, 3), 2),
        (date(2027, 1, 4), date(2027, 1, 10), 1),
    ]
    months = queries.training_summary(
        session, user_id=a.id, period=Period.MONTH, start=date(2026, 12, 1), end=date(2027, 1, 31)
    )
    assert [(r.period_start, r.period_end, r.count) for r in months] == [
        (date(2026, 12, 1), date(2026, 12, 31), 1),
        (date(2027, 1, 1), date(2027, 1, 31), 2),
    ]
