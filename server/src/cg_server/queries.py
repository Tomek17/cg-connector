"""Read queries for the REST API and MCP tools.

Every public function takes a keyword-only, required `user_id` and filters by it, so data
of one user can never be read through another user's token. Dates are local calendar
days in `tz` (Europe/Warsaw by default); times are compared in UTC.
"""

from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, time, timedelta
from enum import StrEnum
from typing import Any
from zoneinfo import ZoneInfo

from sqlalchemy import Select, func, select
from sqlalchemy.orm import Session

from cg_server.models import (
    Activity,
    ActivityKind,
    GarminCredentials,
    GarminStatus,
    SyncRun,
    SyncStatus,
    Vo2Max,
)

WARSAW = ZoneInfo("Europe/Warsaw")
STALE_AFTER = timedelta(hours=24)
# Accepted dates: no Garmin data before 2000, nothing further than a year ahead. Checked
# before any date arithmetic, so extreme dates never overflow.
MIN_DATE = date(2000, 1, 1)
MAX_AHEAD = timedelta(days=366)
MAX_RANGE_DAYS = 2 * 366


class RangeProblem(StrEnum):
    OUT_OF_BOUNDS = "out_of_bounds"
    REVERSED = "reversed"
    TOO_LONG = "too_long"


class InvalidRange(ValueError):
    def __init__(self, problem: RangeProblem) -> None:
        super().__init__(problem.value)
        self.problem = problem


def resolve_range(
    start: date | None,
    end: date | None,
    today: date,
    default_start: Callable[[date], date],
    max_days: int = MAX_RANGE_DAYS,
) -> tuple[date, date]:
    """[start, end] with defaults (`end` = today, `start` = default_start(end)), checked:
    both within [MIN_DATE, today + 1 year], start <= end, at most `max_days` long."""
    latest = today + MAX_AHEAD

    def check(day: date) -> date:
        if not MIN_DATE <= day <= latest:
            raise InvalidRange(RangeProblem.OUT_OF_BOUNDS)
        return day

    end = check(end or today)
    start = check(start or max(default_start(end), MIN_DATE))
    if start > end:
        raise InvalidRange(RangeProblem.REVERSED)
    if (end - start).days > max_days:
        raise InvalidRange(RangeProblem.TOO_LONG)
    return start, end


class Period(StrEnum):
    WEEK = "week"
    MONTH = "month"


def _check_user_id(user_id: int) -> int:
    if type(user_id) is not int:
        raise TypeError("user_id must be an int")
    return user_id


def check_user_id(user_id: int) -> int:
    """Public form of the user id check, for other query modules (progress)."""
    return _check_user_id(user_id)


def as_utc(value: datetime) -> datetime:
    """SQLite returns naive datetimes; every stored time is UTC."""
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


def local_date(value: datetime, tz: ZoneInfo = WARSAW) -> date:
    return as_utc(value).astimezone(tz).date()


def _day_start_utc(day: date, tz: ZoneInfo) -> datetime:
    return datetime.combine(day, time.min, tz).astimezone(UTC)


def _in_local_days(
    stmt: Select[Activity], start: date | None, end: date | None, tz: ZoneInfo
) -> Select[Activity]:
    if start is not None:
        stmt = stmt.where(Activity.start_time_utc >= _day_start_utc(start, tz))
    if end is not None:
        stmt = stmt.where(Activity.start_time_utc < _day_start_utc(end + timedelta(days=1), tz))
    return stmt


# --- activities ---------------------------------------------------------------------------


def list_activities(
    session: Session,
    *,
    user_id: int,
    start: date | None = None,
    end: date | None = None,
    kind: ActivityKind | None = None,
    limit: int | None = None,
    tz: ZoneInfo = WARSAW,
) -> list[Activity]:
    """The user's activities starting within local days [start, end], newest first."""
    stmt = select(Activity).where(Activity.user_id == _check_user_id(user_id))
    stmt = _in_local_days(stmt, start, end, tz)
    if kind is not None:
        stmt = stmt.where(Activity.kind == kind)
    stmt = stmt.order_by(Activity.start_time_utc.desc(), Activity.id.desc())
    if limit is not None:
        stmt = stmt.limit(limit)
    return list(session.scalars(stmt))


def get_activity(session: Session, *, user_id: int, activity_id: int) -> Activity | None:
    """One activity by its Garmin id, only if it belongs to the user."""
    return session.scalars(
        select(Activity).where(
            Activity.user_id == _check_user_id(user_id),
            Activity.garmin_activity_id == activity_id,
        )
    ).one_or_none()


@dataclass(frozen=True)
class Lap:
    index: int
    distance_m: float | None
    duration_s: float | None
    avg_hr: float | None
    max_hr: float | None
    avg_speed_mps: float | None
    elevation_gain_m: float | None
    avg_power_w: float | None
    avg_cadence_spm: float | None
    # Garmin lap intensity (WARMUP, ACTIVE, INTERVAL, RECOVERY, REST, COOLDOWN), if any.
    intensity: str | None = None

    @property
    def pace_s_per_km(self) -> float | None:
        return 1000.0 / self.avg_speed_mps if self.avg_speed_mps else None


@dataclass(frozen=True)
class HrZone:
    zone: int
    seconds: float
    low_bpm: int | None


@dataclass(frozen=True)
class ExerciseSet:
    set_type: str | None
    category: str | None
    exercise: str | None
    reps: int | None
    weight_kg: float | None
    duration_s: float | None


@dataclass(frozen=True)
class Details:
    available: bool
    laps: list[Lap] = field(default_factory=list)
    hr_zones: list[HrZone] = field(default_factory=list)
    exercise_sets: list[ExerciseSet] = field(default_factory=list)


def _num(raw: dict[str, Any], *keys: str) -> float | None:
    for key in keys:
        value = raw.get(key)
        if isinstance(value, int | float) and not isinstance(value, bool):
            return float(value)
    return None


def _str(raw: dict[str, Any], key: str) -> str | None:
    value = raw.get(key)
    return str(value) if isinstance(value, str | int) and value != "" else None


def _dicts(value: Any, key: str) -> list[dict[str, Any]]:
    """Items of a Garmin list that may be wrapped in an object under `key`."""
    if isinstance(value, dict):
        value = value.get(key)
    return [x for x in value if isinstance(x, dict)] if isinstance(value, list) else []


def _laps(raw: Any) -> list[Lap]:
    laps = []
    for i, lap in enumerate(_dicts(raw, "lapDTOs"), start=1):
        index = _num(lap, "lapIndex")
        laps.append(
            Lap(
                index=int(index) if index is not None else i,
                distance_m=_num(lap, "distance"),
                duration_s=_num(lap, "duration"),
                avg_hr=_num(lap, "averageHR"),
                max_hr=_num(lap, "maxHR"),
                avg_speed_mps=_num(lap, "averageSpeed"),
                elevation_gain_m=_num(lap, "elevationGain"),
                avg_power_w=_num(lap, "averagePower", "avgPower"),
                avg_cadence_spm=_num(
                    lap, "averageRunCadence", "averageRunningCadenceInStepsPerMinute"
                ),
                intensity=_str(lap, "intensityType"),
            )
        )
    return laps


def _hr_zones(raw: Any) -> list[HrZone]:
    zones = []
    for item in _dicts(raw, "hrTimeInZones"):
        number, seconds = _num(item, "zoneNumber"), _num(item, "secsInZone")
        if number is None or seconds is None:
            continue
        low = _num(item, "zoneLowBoundary")
        zones.append(HrZone(int(number), seconds, int(low) if low is not None else None))
    return sorted(zones, key=lambda z: z.zone)


def _exercise_sets(raw: Any) -> list[ExerciseSet]:
    sets = []
    for item in _dicts(raw, "exerciseSets"):
        exercises = _dicts(item.get("exercises"), "")
        first = exercises[0] if exercises else {}
        reps = _num(item, "repetitionCount")
        grams = _num(item, "weight")  # Garmin sends grams
        sets.append(
            ExerciseSet(
                set_type=_str(item, "setType"),
                category=_str(first, "category"),
                exercise=_str(first, "name") or _str(first, "category"),
                reps=int(reps) if reps is not None else None,
                weight_kg=round(grams / 1000.0, 2) if grams else None,
                duration_s=_num(item, "duration"),
            )
        )
    return sets


def parse_details(activity: Activity) -> Details:
    """Laps, HR time in zones and strength sets from the stored Garmin JSON."""
    raw = activity.details
    if raw is None or raw.fetch_failed:
        return Details(available=False)
    return Details(
        available=True,
        laps=_laps(raw.laps_json),
        hr_zones=_hr_zones(raw.hr_zones_json),
        exercise_sets=_exercise_sets(raw.exercise_sets_json),
    )


# --- VO2max -------------------------------------------------------------------------------


def vo2max_trend(
    session: Session,
    *,
    user_id: int,
    start: date | None = None,
    end: date | None = None,
    sport: str | None = "running",
) -> list[Vo2Max]:
    """VO2max entries in [start, end], oldest first; `sport=None` returns every sport."""
    stmt = select(Vo2Max).where(Vo2Max.user_id == _check_user_id(user_id))
    if start is not None:
        stmt = stmt.where(Vo2Max.date >= start)
    if end is not None:
        stmt = stmt.where(Vo2Max.date <= end)
    if sport is not None:
        stmt = stmt.where(Vo2Max.sport == sport)
    return list(session.scalars(stmt.order_by(Vo2Max.date, Vo2Max.sport)))


# --- summaries ----------------------------------------------------------------------------


def period_start(day: date, period: Period) -> date:
    if period is Period.WEEK:
        return day - timedelta(days=day.weekday())
    return day.replace(day=1)


def period_end(day: date, period: Period) -> date:
    first = period_start(day, period)
    if period is Period.WEEK:
        return first + timedelta(days=6)
    next_month = (first + timedelta(days=32)).replace(day=1)
    return next_month - timedelta(days=1)


@dataclass(frozen=True)
class SummaryRow:
    period_start: date
    period_end: date
    kind: ActivityKind
    count: int
    duration_s: float
    distance_m: float | None
    elevation_gain_m: float | None
    # Running only: total time / total distance of runs with both values.
    pace_s_per_km: float | None


def _sum(values: list[float | None]) -> float | None:
    present = [v for v in values if v is not None]
    return sum(present) if present else None


def training_summary(
    session: Session,
    *,
    user_id: int,
    period: Period,
    start: date,
    end: date,
    tz: ZoneInfo = WARSAW,
) -> list[SummaryRow]:
    """Totals per period (week from Monday / calendar month) and activity type, for
    activities within local days [start, end]. Oldest period first."""
    groups: dict[tuple[date, ActivityKind], list[Activity]] = defaultdict(list)
    for activity in list_activities(session, user_id=user_id, start=start, end=end, tz=tz):
        first = period_start(local_date(activity.start_time_utc, tz), period)
        groups[(first, activity.kind)].append(activity)

    kinds = list(ActivityKind)
    rows = []
    for (first, kind), items in sorted(
        groups.items(), key=lambda g: (g[0][0], kinds.index(g[0][1]))
    ):
        pace = None
        if kind == ActivityKind.RUNNING:
            timed = [a for a in items if a.distance_m and a.duration_s]
            km = sum(a.distance_m or 0.0 for a in timed) / 1000.0
            pace = sum(a.duration_s or 0.0 for a in timed) / km if km else None
        rows.append(
            SummaryRow(
                period_start=first,
                period_end=period_end(first, period),
                kind=kind,
                count=len(items),
                duration_s=sum(a.duration_s or 0.0 for a in items),
                distance_m=_sum([a.distance_m for a in items]),
                elevation_gain_m=_sum([a.elevation_gain_m for a in items]),
                pace_s_per_km=pace,
            )
        )
    return rows


# --- sync status --------------------------------------------------------------------------


@dataclass(frozen=True)
class SyncState:
    last_success_at: datetime | None  # UTC
    last_attempt_at: datetime | None  # UTC, start of the newest run
    last_status: SyncStatus | None
    garmin_status: GarminStatus | None
    latest_activity_date: date | None  # local date of the newest activity


def sync_status(session: Session, *, user_id: int, tz: ZoneInfo = WARSAW) -> SyncState:
    _check_user_id(user_id)
    last_ok = session.scalar(
        select(func.max(SyncRun.finished_at)).where(
            SyncRun.user_id == user_id, SyncRun.status == SyncStatus.OK
        )
    )
    last_run = session.scalars(
        select(SyncRun)
        .where(SyncRun.user_id == user_id)
        .order_by(SyncRun.started_at.desc(), SyncRun.id.desc())
        .limit(1)
    ).one_or_none()
    garmin = session.scalar(
        select(GarminCredentials.status).where(GarminCredentials.user_id == user_id)
    )
    newest = session.scalar(
        select(func.max(Activity.start_time_utc)).where(Activity.user_id == user_id)
    )
    return SyncState(
        last_success_at=as_utc(last_ok) if last_ok is not None else None,
        last_attempt_at=as_utc(last_run.started_at) if last_run is not None else None,
        last_status=last_run.status if last_run is not None else None,
        garmin_status=garmin,
        latest_activity_date=local_date(newest, tz) if newest is not None else None,
    )


def is_stale(last_success_at: datetime | None, now: datetime) -> bool:
    """No successful sync, or the last one is older than 24 h."""
    return last_success_at is None or now - last_success_at > STALE_AFTER
