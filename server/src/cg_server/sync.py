"""Daily sync of Garmin data for one user: activities, their details, VO2max.

Rules (MASTER_PROMPT 3.1, docs/research/garmin.md section 5):
- Window: from the saved cursor (last day fully synced) minus 3 days for late edits; the
  first sync goes back 12 months, in chunks of 30 days, oldest first.
- One transaction per activity (summary + details), and the cursor is saved after each
  chunk, so an interrupted sync (laptop sleep) resumes where it stopped.
- Details (laps, HR zones, strength sets) only for activities we do not have details for.
- A pause before every request. HTTP 429 stops the sync at once, with no retries, and
  starts the 24 h pause for this user. Tokens are saved again after every session.
- Error texts stored in `sync_runs.error` are fixed strings: no response bodies.
"""

import logging
import re
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any, TypeVar
from zoneinfo import ZoneInfo

from sqlalchemy import func, select, update
from sqlalchemy.orm import Session

from cg_server.config import Settings
from cg_server.crypto import decrypt
from cg_server.garmin import accounts
from cg_server.garmin.gateway import GarminAuthError, GarminGateway, GarminRateLimited
from cg_server.models import (
    Activity,
    ActivityDetails,
    ActivityKind,
    GarminStatus,
    SyncRun,
    SyncStatus,
    User,
    Vo2Max,
    utcnow,
)
from cg_server.synclock import sync_lock

log = logging.getLogger(__name__)

T = TypeVar("T")
GARMIN_DATETIME = "%Y-%m-%d %H:%M:%S"
# Keys never stored, at any depth: GPS positions, owner and device identifiers.
PRIVATE_KEY = re.compile(
    r"latitude|longitude|owner|location|device|userprofile|profileimage|polyline", re.I
)
VO2MAX_SPORTS = {"generic": "running", "cycling": "cycling"}


@dataclass(frozen=True)
class SyncOptions:
    timezone: str = "Europe/Warsaw"
    history_days: int = 365
    chunk_days: int = 30
    overlap_days: int = 3
    request_delay_s: float = 2.0
    # Requests per run; a longer history is finished by the following runs.
    max_requests: int = 300

    @classmethod
    def from_settings(cls, settings: Settings) -> "SyncOptions":
        return cls(
            timezone=settings.timezone,
            history_days=settings.sync_history_days,
            request_delay_s=settings.sync_request_delay_s,
            max_requests=settings.sync_max_requests_per_run,
        )


@dataclass
class SyncResult:
    status: SyncStatus
    activities_new: int = 0
    activities_updated: int = 0
    vo2max_points: int = 0
    error: str | None = None


class RequestLimitReached(Exception):
    """The per-run request limit is used up; the sync continues next time."""


class _Paced:
    """Calls the gateway with a pause before every request except the first, and stops
    (RequestLimitReached) once `max_requests` requests were made in this run."""

    def __init__(self, delay_s: float, sleep: Callable[[float], None], max_requests: int) -> None:
        self.delay_s, self.sleep, self.max_requests = delay_s, sleep, max_requests
        self.requests = 0

    def reserve(self, n: int) -> None:
        """Make sure `n` more requests fit in the limit, so a group is never cut in half."""
        if self.requests + n > self.max_requests:
            raise RequestLimitReached

    def __call__(self, fn: Callable[..., T], *args: Any) -> T:
        self.reserve(1)
        if self.requests and self.delay_s > 0:
            self.sleep(self.delay_s)
        self.requests += 1
        return fn(*args)


def normalize_kind(type_key: str) -> ActivityKind:
    key = type_key.lower()
    if "run" in key:
        return ActivityKind.RUNNING
    if "strength" in key:
        return ActivityKind.STRENGTH
    if "climb" in key or "boulder" in key:
        return ActivityKind.CLIMBING
    return ActivityKind.OTHER


def scrub(value: Any) -> Any:
    """Drop private keys (GPS, owner, device) recursively."""
    if isinstance(value, dict):
        return {k: scrub(v) for k, v in value.items() if not PRIVATE_KEY.search(str(k))}
    if isinstance(value, list):
        return [scrub(v) for v in value]
    return value


def trimmed_summary(raw: dict[str, Any]) -> dict[str, Any]:
    """Scalar summary fields only (no nested structures), without private keys."""
    summary = {
        k: v
        for k, v in raw.items()
        if isinstance(v, str | int | float | bool) and not PRIVATE_KEY.search(k)
    }
    type_key = (raw.get("activityType") or {}).get("typeKey")
    if type_key:
        summary["activityTypeKey"] = type_key
    return summary


def _float(raw: dict[str, Any], key: str) -> float | None:
    value = raw.get(key)
    return float(value) if isinstance(value, int | float) and not isinstance(value, bool) else None


def _parse_times(raw: dict[str, Any], tz: ZoneInfo) -> tuple[datetime, datetime]:
    local = datetime.strptime(raw["startTimeLocal"][:19], GARMIN_DATETIME)
    gmt = raw.get("startTimeGMT")
    if gmt:
        utc = datetime.strptime(gmt[:19], GARMIN_DATETIME).replace(tzinfo=UTC)
    else:
        utc = local.replace(tzinfo=tz).astimezone(UTC)
    return utc, local


def _apply_summary(activity: Activity, raw: dict[str, Any], tz: ZoneInfo) -> None:
    type_key = str((raw.get("activityType") or {}).get("typeKey") or "")
    activity.kind = normalize_kind(type_key)
    activity.type_key = type_key[:64]
    activity.name = (raw.get("activityName") or None) and str(raw["activityName"])[:200]
    activity.start_time_utc, activity.start_time_local = _parse_times(raw, tz)
    activity.duration_s = _float(raw, "duration")
    activity.moving_duration_s = _float(raw, "movingDuration")
    activity.distance_m = _float(raw, "distance")
    activity.avg_hr = _float(raw, "averageHR")
    activity.max_hr = _float(raw, "maxHR")
    speed = _float(raw, "averageSpeed")
    activity.avg_speed_mps = speed
    running = activity.kind == ActivityKind.RUNNING
    activity.avg_pace_s_per_km = 1000.0 / speed if running and speed else None
    activity.elevation_gain_m = _float(raw, "elevationGain")
    activity.avg_power_w = _float(raw, "avgPower")
    activity.avg_cadence_spm = _float(raw, "averageRunningCadenceInStepsPerMinute")
    activity.aerobic_training_effect = _float(raw, "aerobicTrainingEffect")
    activity.anaerobic_training_effect = _float(raw, "anaerobicTrainingEffect")
    activity.training_load = _float(raw, "activityTrainingLoad")
    activity.summary_json = trimmed_summary(raw)


def _date_range(start: date, end: date, days: int) -> list[tuple[date, date]]:
    chunks = []
    while start <= end:
        chunk_end = min(start + timedelta(days=days - 1), end)
        chunks.append((start, chunk_end))
        start = chunk_end + timedelta(days=1)
    return chunks


def last_cursor(session: Session, user: User) -> date | None:
    """Last local date up to which all data is saved (from any run, also interrupted)."""
    return session.scalar(select(func.max(SyncRun.cursor_date)).where(SyncRun.user_id == user.id))


class _UserSync:
    def __init__(
        self,
        session: Session,
        user: User,
        gateway: GarminGateway,
        run: SyncRun,
        options: SyncOptions,
        paced: _Paced,
    ) -> None:
        self.session, self.user, self.gateway, self.run = session, user, gateway, run
        self.tz = ZoneInfo(options.timezone)
        self.paced = paced
        self.vo2max_points = 0

    def chunk(self, start: date, end: date) -> None:
        raw_list = self.paced(
            self.gateway.get_activities_by_date, start.isoformat(), end.isoformat(), None, "asc"
        )
        for raw in sorted(raw_list, key=lambda a: str(a.get("startTimeLocal", ""))):
            if raw.get("activityId") is None or not raw.get("startTimeLocal"):
                continue
            self.activity(raw)
        self.vo2max(start, end)
        self.run.cursor_date = end
        self.session.commit()

    def activity(self, raw: dict[str, Any]) -> None:
        garmin_id = int(raw["activityId"])
        activity = self.session.scalars(
            select(Activity).where(
                Activity.user_id == self.user.id, Activity.garmin_activity_id == garmin_id
            )
        ).one_or_none()
        if activity is None:
            activity = Activity(user_id=self.user.id, garmin_activity_id=garmin_id)
            self.session.add(activity)
            self.run.activities_new += 1
        elif activity.summary_json != trimmed_summary(raw):
            # Every stored column is derived from the trimmed summary, so this is a real edit.
            self.run.activities_updated += 1
        _apply_summary(activity, raw, self.tz)
        if activity.details is None:
            activity.details = self.details(garmin_id, activity.kind)
        self.session.commit()  # one transaction per activity

    def details(self, garmin_id: int, kind: ActivityKind) -> ActivityDetails:
        """Details of one activity. An error here (404, 5xx, bad data) only marks these
        details as failed; 429, rejected tokens and the request limit stop the sync."""
        self.paced.reserve(3 if kind == ActivityKind.STRENGTH else 2)
        try:
            laps = self.paced(self.gateway.get_activity_splits, garmin_id)
            zones = self.paced(self.gateway.get_activity_hr_in_timezones, garmin_id)
            sets = None
            if kind == ActivityKind.STRENGTH:
                sets = self.paced(self.gateway.get_activity_exercise_sets, garmin_id)
        except (GarminRateLimited, GarminAuthError, RequestLimitReached):
            raise
        except Exception as e:
            log.warning("details of one activity failed: %s", type(e).__name__)
            return ActivityDetails(user_id=self.user.id, fetch_failed=True)
        return ActivityDetails(
            user_id=self.user.id,
            laps_json=scrub(laps),
            hr_zones_json=scrub(zones),
            exercise_sets_json=scrub(sets),
            fetch_failed=False,
        )

    def vo2max(self, start: date, end: date) -> None:
        data = self.paced(self.gateway.get_max_metrics_range, start.isoformat(), end.isoformat())
        entries = data if isinstance(data, list) else data.get("metrics", [])
        for entry in entries:
            for field, sport in VO2MAX_SPORTS.items():
                metric = entry.get(field) if isinstance(entry, dict) else None
                if not isinstance(metric, dict):
                    continue
                value = metric.get("vo2MaxPreciseValue") or metric.get("vo2MaxValue")
                day = metric.get("calendarDate")
                if not isinstance(value, int | float) or not day:
                    continue
                self._upsert_vo2max(date.fromisoformat(str(day)[:10]), sport, float(value))

    def _upsert_vo2max(self, day: date, sport: str, value: float) -> None:
        row = self.session.scalars(
            select(Vo2Max).where(
                Vo2Max.user_id == self.user.id, Vo2Max.date == day, Vo2Max.sport == sport
            )
        ).one_or_none()
        if row is None:
            self.session.add(Vo2Max(user_id=self.user.id, date=day, sport=sport, value=value))
        else:
            row.value = value
        self.vo2max_points += 1


def _finish(session: Session, run: SyncRun, status: SyncStatus, error: str | None) -> SyncResult:
    run.status = status
    run.error = error
    run.finished_at = utcnow()
    session.commit()
    log.info("sync user_id=%s status=%s new=%s", run.user_id, status, run.activities_new)
    return SyncResult(status, run.activities_new, run.activities_updated, error=error)


def sync_user(
    session: Session,
    user: User,
    gateway: GarminGateway,
    *,
    key: str,
    options: SyncOptions | None = None,
    now: datetime | None = None,
    sleep: Callable[[float], None] = time.sleep,
    lock_dir: Path | None = None,
) -> SyncResult:
    """Sync one user under the inter-process sync lock (`lock_dir`, by default the
    database's directory, i.e. the data dir). Raises SyncBusy at once if another sync
    holds it. Never raises for Garmin/network errors: the outcome is in the result and in
    `sync_runs`. Process interruption leaves the run as `running` with its cursor."""
    if lock_dir is None:
        lock_dir = Path(str(session.get_bind().engine.url.database)).parent
    with sync_lock(lock_dir):
        return _sync_user_locked(session, user, gateway, key, options, now, sleep)


def _sync_user_locked(
    session: Session,
    user: User,
    gateway: GarminGateway,
    key: str,
    options: SyncOptions | None,
    now: datetime | None,
    sleep: Callable[[float], None],
) -> SyncResult:
    options = options or SyncOptions()
    now = (now or utcnow()).astimezone(UTC)  # SQLite keeps no offset: store UTC only
    today = now.astimezone(ZoneInfo(options.timezone)).date()

    # Under the lock, a run left `running` was interrupted (process stopped); its cursor
    # keeps the progress.
    session.execute(
        update(SyncRun)
        .where(SyncRun.user_id == user.id, SyncRun.status == SyncStatus.RUNNING)
        .values(status=SyncStatus.FAILED, error="Przerwany (np. uśpienie lub restart)")
    )
    cursor = last_cursor(session, user)
    run = SyncRun(user_id=user.id, started_at=now, status=SyncStatus.RUNNING)
    session.add(run)
    session.commit()

    creds = user.garmin_credentials
    if (until := accounts.rate_limit_cooldown_until(user, now)) is not None:
        return _finish(
            session,
            run,
            SyncStatus.SKIPPED_RATE_LIMITED,
            f"Pauza po HTTP 429 do {until:%Y-%m-%d %H:%M} UTC",
        )
    if creds is None or creds.encrypted_tokens is None:
        return _finish(session, run, SyncStatus.FAILED, "Brak tokenów Garmina: garmin-login")
    if creds.status == GarminStatus.NEEDS_LOGIN:
        return _finish(session, run, SyncStatus.FAILED, "Tokeny odrzucone: garmin-login")

    if cursor is None:
        start = today - timedelta(days=options.history_days)
    else:
        start = min(cursor, today) - timedelta(days=options.overlap_days)
    paced = _Paced(options.request_delay_s, sleep, options.max_requests)
    job = _UserSync(session, user, gateway, run, options, paced)
    resumed = limit_reached = False
    status, error = SyncStatus.OK, None
    try:
        paced(gateway.resume, decrypt(creds.encrypted_tokens, key))
        resumed = True
        for chunk_start, chunk_end in _date_range(start, today, options.chunk_days):
            job.chunk(chunk_start, chunk_end)
    except RequestLimitReached:
        session.rollback()
        limit_reached = True
        status = SyncStatus.PARTIAL
        error = f"Limit {options.max_requests} zapytań na przebieg: reszta przy następnym syncu"
    except GarminRateLimited:
        session.rollback()
        accounts.mark_rate_limited(session, user)
        status, error = SyncStatus.FAILED, "HTTP 429 od Garmina: sync przerwany, pauza 24 h"
    except GarminAuthError:
        session.rollback()
        accounts.mark_needs_login(session, user)
        status, error = SyncStatus.FAILED, "Tokeny odrzucone przez Garmina: garmin-login"
    except Exception as e:
        session.rollback()
        log.warning("sync user_id=%s failed: %s", user.id, type(e).__name__)
        status, error = SyncStatus.FAILED, f"Błąd: {type(e).__name__}"[:200]
    finally:
        session.rollback()  # drop a half-done activity if the process is being stopped
        if resumed:
            try:
                accounts.store_session_tokens(session, user, gateway.dumps(), key)
            except Exception as e:  # never lose the run status because of this
                session.rollback()
                log.warning("saving Garmin tokens failed: %s", type(e).__name__)

    run.request_limit_reached = limit_reached
    if status == SyncStatus.OK and creds.status != GarminStatus.OK:
        creds.status = GarminStatus.OK
    progressed = run.activities_new or run.activities_updated or run.cursor_date is not None
    if status == SyncStatus.FAILED and progressed:
        status = SyncStatus.PARTIAL
    result = _finish(session, run, status, error)
    result.vo2max_points = job.vo2max_points
    return result
