"""Compact answers of the MCP read tools (free Claude plan: every token counts).

Plain dicts with short keys, None values left out. Units: km, min/km ("m:ss"), bpm, m of
elevation; times are local (Europe/Warsaw) "YYYY-MM-DD HH:MM". All data comes from
`cg_server.queries`, always for the user of the access token.
"""

from collections.abc import Callable
from datetime import date, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from sqlalchemy.orm import Session

from cg_server import queries
from cg_server.models import Activity, ActivityKind, GarminStatus
from cg_server.queries import Period, SyncState

LIST_DEFAULT_DAYS = 30
NAME_CHARS = 40
MAX_LAPS = 30
VO2MAX_DEFAULT_DAYS = 180
VO2MAX_MAX_POINTS = 60
SUMMARY_DEFAULT_PERIODS = {Period.WEEK: 8, Period.MONTH: 6}
SUMMARY_MAX_PERIODS = {Period.WEEK: 26, Period.MONTH: 24}


class InvalidArgument(ValueError):
    """Bad tool argument; the message (English) goes back to the model."""


def compact(data: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in data.items() if v is not None}


def fmt_duration(seconds: float | None) -> str | None:
    if seconds is None:
        return None
    hours, rest = divmod(round(seconds), 3600)
    minutes, secs = divmod(rest, 60)
    return f"{hours}:{minutes:02d}:{secs:02d}" if hours else f"{minutes}:{secs:02d}"


def fmt_pace(seconds_per_km: float | None) -> str | None:
    if not seconds_per_km:
        return None
    minutes, secs = divmod(round(seconds_per_km), 60)
    return f"{minutes}:{secs:02d}"


def km(meters: float | None) -> float | None:
    return round(meters / 1000.0, 2) if meters else None


def whole(value: float | None) -> int | None:
    return round(value) if value is not None else None


def _short(text: str | None, limit: int) -> str | None:
    if not text:
        return None
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _local(value: datetime, tz: ZoneInfo) -> str:
    return queries.as_utc(value).astimezone(tz).strftime("%Y-%m-%d %H:%M")


RANGE_ERRORS = {
    queries.RangeProblem.OUT_OF_BOUNDS: "Dates must be between 2000-01-01 and one year from today.",
    queries.RangeProblem.REVERSED: "from_date must not be after to_date.",
    queries.RangeProblem.TOO_LONG: "Range too long: at most 2 years.",
}


def _range(
    start: date | None, end: date | None, today: date, default_start: Callable[[date], date]
) -> tuple[date, date]:
    try:
        return queries.resolve_range(start, end, today, default_start)
    except queries.InvalidRange as e:
        raise InvalidArgument(RANGE_ERRORS[e.problem]) from e


def _days_back(days: int) -> Callable[[date], date]:
    return lambda end: end - timedelta(days=days - 1)


def freshness_note(state: SyncState, now: datetime, tz: ZoneInfo) -> str | None:
    """Short warning when the data may be incomplete; None when all is well."""
    parts = []
    if state.garmin_status == GarminStatus.NEEDS_LOGIN:
        parts.append("Garmin login expired: the server owner must run cg-server garmin-login.")
    elif state.garmin_status == GarminStatus.RATE_LIMITED:
        parts.append("Garmin paused syncing (HTTP 429); it resumes automatically later.")
    if state.last_success_at is None:
        parts.append("Never synced with Garmin yet.")
    elif queries.is_stale(state.last_success_at, now):
        parts.append(
            f"Last successful sync {_local(state.last_success_at, tz)} (over 24 h ago); "
            "newer activities may be missing."
        )
    return " ".join(parts) or None


def _with_note(data: dict[str, Any], *notes: str | None) -> dict[str, Any]:
    note = " ".join(n for n in notes if n)
    if note:
        data["note"] = note
    return data


def activity_item(activity: Activity, tz: ZoneInfo, name_chars: int = NAME_CHARS) -> dict[str, Any]:
    return compact(
        {
            "id": activity.garmin_activity_id,
            "start": _local(activity.start_time_utc, tz),
            "type": activity.kind.value,
            "name": _short(activity.name, name_chars),
            "km": km(activity.distance_m),
            "time": fmt_duration(activity.duration_s),
            "pace": fmt_pace(activity.avg_pace_s_per_km),
            "hr": whole(activity.avg_hr),
            "elev_m": whole(activity.elevation_gain_m) or None,
            "tag": activity.tag,
        }
    )


def list_activities(
    session: Session,
    *,
    user_id: int,
    start: date | None,
    end: date | None,
    kind: ActivityKind | None,
    limit: int,
    now: datetime,
    tz: ZoneInfo,
) -> dict[str, Any]:
    start, end = _range(start, end, now.astimezone(tz).date(), _days_back(LIST_DEFAULT_DAYS))
    rows = queries.list_activities(
        session, user_id=user_id, start=start, end=end, kind=kind, limit=limit + 1, tz=tz
    )
    data: dict[str, Any] = {
        "range": f"{start}..{end}",
        "activities": [activity_item(a, tz) for a in rows[:limit]],
    }
    more = None
    if len(rows) > limit:
        data["more"] = True
        more = f"Only the newest {limit} shown; narrow the dates or filter by type."
    empty = None if rows else "No activities in this range."
    state = queries.sync_status(session, user_id=user_id, tz=tz)
    return _with_note(data, empty, more, freshness_note(state, now, tz))


def _sets(activity_sets: list[queries.ExerciseSet]) -> list[dict[str, Any]]:
    """Active sets grouped by consecutive exercise: [{"exercise", "sets": ["5x80kg", ...]}]."""
    groups: list[dict[str, Any]] = []
    for s in activity_sets:
        if s.set_type == "REST":
            continue
        if s.reps is not None and s.weight_kg:
            label = f"{s.reps}x{s.weight_kg:g}kg"
        elif s.reps is not None:
            label = str(s.reps)
        else:
            label = f"{whole(s.duration_s) or 0}s"
        name = s.exercise or "UNKNOWN"
        if not groups or groups[-1]["exercise"] != name:
            groups.append({"exercise": name, "sets": []})
        groups[-1]["sets"].append(label)
    return groups


def activity_detail(
    session: Session, *, user_id: int, activity_id: int, now: datetime, tz: ZoneInfo
) -> dict[str, Any] | None:
    activity = queries.get_activity(session, user_id=user_id, activity_id=activity_id)
    if activity is None:
        return None
    details = queries.parse_details(activity)
    running = activity.kind == ActivityKind.RUNNING
    data = activity_item(activity, tz, name_chars=100)
    data.update(
        compact(
            {
                "type_key": activity.type_key,
                "moving": fmt_duration(activity.moving_duration_s),
                "max_hr": whole(activity.max_hr),
                "power_w": whole(activity.avg_power_w),
                "cadence_spm": whole(activity.avg_cadence_spm),
                "te_aerobic": activity.aerobic_training_effect,
                "te_anaerobic": activity.anaerobic_training_effect,
                "load": whole(activity.training_load),
            }
        )
    )
    if not details.available:
        data["note"] = "Details (laps, zones, sets) not available for this activity."
        return data
    laps = [
        compact(
            {
                "n": lap.index,
                "km": km(lap.distance_m),
                "time": fmt_duration(lap.duration_s),
                "pace": fmt_pace(lap.pace_s_per_km) if running and lap.distance_m else None,
                "hr": whole(lap.avg_hr),
            }
        )
        for lap in details.laps
    ]
    if laps:
        data["laps"] = laps[:MAX_LAPS]
        if len(laps) > MAX_LAPS:
            data["laps_total"] = len(laps)
    if details.hr_zones:
        data["hr_zones_min"] = {f"z{z.zone}": round(z.seconds / 60) for z in details.hr_zones}
    if sets := _sets(details.exercise_sets):
        data["sets"] = sets
    return data


def _downsample(points: list[tuple[date, float]], period: Period) -> list[tuple[date, float]]:
    """Last value of each week/month, dated by its own day."""
    last: dict[date, tuple[date, float]] = {}
    for day, value in points:
        last[queries.period_start(day, period)] = (day, value)
    return list(last.values())


def _changes(points: list[tuple[date, float]]) -> list[tuple[date, float]]:
    kept: list[tuple[date, float]] = []
    for day, value in points:
        if not kept or kept[-1][1] != value:
            kept.append((day, value))
    return kept


def vo2max_trend(
    session: Session,
    *,
    user_id: int,
    start: date | None,
    end: date | None,
    now: datetime,
    tz: ZoneInfo,
) -> dict[str, Any]:
    start, end = _range(start, end, now.astimezone(tz).date(), _days_back(VO2MAX_DEFAULT_DAYS))
    rows = queries.vo2max_trend(session, user_id=user_id, start=start, end=end, sport="running")
    data: dict[str, Any] = {"range": f"{start}..{end}", "sport": "running"}
    state = queries.sync_status(session, user_id=user_id, tz=tz)
    stale = freshness_note(state, now, tz)
    if not rows:
        data["points"] = []
        return _with_note(data, "No VO2max data in this range.", stale)
    raw = [(r.date, round(r.value, 1)) for r in rows]
    values = [v for _, v in raw]
    points, resolution = _changes(raw), "daily"
    for period, name in ((Period.WEEK, "weekly"), (Period.MONTH, "monthly")):
        if len(points) <= VO2MAX_MAX_POINTS:
            break
        points, resolution = _changes(_downsample(raw, period)), name
    data.update(
        {
            "latest": {"date": str(raw[-1][0]), "value": raw[-1][1]},
            "change": round(raw[-1][1] - raw[0][1], 1),
            "min": min(values),
            "max": max(values),
            "resolution": resolution,
            "points": [[str(day), value] for day, value in points],
        }
    )
    return _with_note(data, stale)


def _periods_between(start: date, end: date, period: Period) -> int:
    if period is Period.WEEK:
        return (queries.period_start(end, period) - start).days // 7 + 1
    return (end.year - start.year) * 12 + end.month - start.month + 1


def training_summary(
    session: Session,
    *,
    user_id: int,
    period: Period,
    start: date | None,
    end: date | None,
    now: datetime,
    tz: ZoneInfo,
) -> dict[str, Any]:

    def default_start(end_day: date) -> date:
        first = queries.period_start(end_day, period)
        for _ in range(SUMMARY_DEFAULT_PERIODS[period] - 1):
            first = queries.period_start(first - timedelta(days=1), period)
        return first

    start, end = _range(start, end, now.astimezone(tz).date(), default_start)
    start = queries.period_start(start, period)  # whole periods only
    limit = SUMMARY_MAX_PERIODS[period]
    if _periods_between(start, end, period) > limit:
        raise InvalidArgument(f"Range too long: at most {limit} periods of type {period.value}.")

    rows = queries.training_summary(
        session, user_id=user_id, period=period, start=start, end=end, tz=tz
    )
    periods: dict[date, dict[str, Any]] = {}
    for r in rows:
        entry = periods.setdefault(r.period_start, {"start": str(r.period_start)})
        entry[r.kind.value] = compact(
            {
                "n": r.count,
                "km": km(r.distance_m),
                "time": fmt_duration(r.duration_s),
                "elev_m": whole(r.elevation_gain_m) or None,
                "pace": fmt_pace(r.pace_s_per_km),
            }
        )
    data: dict[str, Any] = {
        "period": period.value,
        "range": f"{start}..{end}",
        "periods": list(periods.values()),
    }
    state = queries.sync_status(session, user_id=user_id, tz=tz)
    empty = None if rows else "No activities in this range."
    return _with_note(data, empty, freshness_note(state, now, tz))
