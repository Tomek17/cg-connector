"""Background sync schedule (APScheduler, Europe/Warsaw), started in the FastAPI lifespan.

- `daily_sync` at each of `CG_SYNC_HOURS` (8:00, 17:00, 21:00) and
- `catch_up` every 15 min and right after start: both sync the users whose sync is due
  according to `needs_catch_up` (missed the last sync hour because the server was off, or
  >24 h old), with backoff after failures.
- `outbox` every 15 min and right after start: sends scheduled workouts to Garmin.
- Jobs: coalesce, max_instances=1, misfire_grace_time; a lock keeps the two jobs from
  syncing at the same time. Users are synced one after another, never in parallel.
"""

import logging
import threading
from collections.abc import AsyncIterator, Callable, Sequence
from contextlib import asynccontextmanager
from datetime import UTC, datetime, time, timedelta
from zoneinfo import ZoneInfo

from apscheduler.schedulers.background import (  # type: ignore[import-untyped]
    BackgroundScheduler,
)
from apscheduler.triggers.cron import CronTrigger  # type: ignore[import-untyped]
from apscheduler.triggers.interval import IntervalTrigger  # type: ignore[import-untyped]
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session, sessionmaker

from cg_server.config import Settings
from cg_server.db import open_db
from cg_server.garmin import accounts
from cg_server.garmin.gateway import GarminGateway, RealGarminGateway
from cg_server.models import GarminCredentials, GarminStatus, SyncRun, SyncStatus, User, utcnow
from cg_server.outbox import OutboxRunner
from cg_server.sync import SyncOptions, sync_user
from cg_server.synclock import SyncBusy

log = logging.getLogger(__name__)

GatewayFactory = Callable[[], GarminGateway]
CATCH_UP_EVERY = timedelta(minutes=15)
RETRY_AFTER_FAILURE = timedelta(hours=1)
MAX_RETRY_DELAY = timedelta(hours=24)
MISFIRE_GRACE_S = 6 * 3600


def _aware(value: datetime) -> datetime:
    return value if value.tzinfo is not None else value.replace(tzinfo=UTC)


def retry_delay(failures: int) -> timedelta:
    """Wait after consecutive failed attempts: 1 h, 2 h, 4 h, ... at most 24 h."""
    if failures <= 0:
        return timedelta(0)
    delay: timedelta = RETRY_AFTER_FAILURE * 2 ** min(failures - 1, 10)
    return min(delay, MAX_RETRY_DELAY)


def needs_catch_up(
    last_ok: datetime | None,
    last_attempt: datetime | None,
    now: datetime,
    *,
    tz: ZoneInfo,
    hours: Sequence[int] = (8, 17, 21),
    failures: int = 0,
) -> bool:
    """Is a sync due now? (MASTER_PROMPT 3.1)

    `last_ok`: when the last successful sync finished; `last_attempt`: when the last run
    started; `failures`: failed runs since `last_ok`.
    Due when there was never a successful sync, when the last one finished before the latest
    sync hour (of `hours`, today or yesterday) that has already passed, or when it is older
    than 24 h. After failures
    wait `retry_delay(failures)` from the last attempt (no hammering Garmin every 15 min).
    Naive datetimes (SQLite) are UTC.
    """
    now = _aware(now)
    if last_attempt is not None and failures > 0:
        attempt = _aware(last_attempt)
        failed = last_ok is None or attempt > _aware(last_ok)
        if failed and now - attempt < retry_delay(failures):
            return False
    if last_ok is None:
        return True
    last_ok = _aware(last_ok)
    if last_ok < last_sync_slot(now, tz=tz, hours=hours):
        return True
    return now - last_ok > timedelta(hours=24)


def last_sync_slot(now: datetime, *, tz: ZoneInfo, hours: Sequence[int]) -> datetime:
    """The latest planned sync time (one of `hours`, local) not after `now`."""
    local = _aware(now).astimezone(tz)
    slots = [
        datetime.combine(local.date() - timedelta(days=back), time(hour), tzinfo=tz)
        for back in (0, 1)
        for hour in hours
    ]
    return max(slot for slot in slots if slot <= local)


class SyncRunner:
    """Runs syncs for many users, one at a time; used by the scheduler jobs."""

    def __init__(
        self,
        sessions: sessionmaker[Session],
        settings: Settings,
        gateway_factory: GatewayFactory = RealGarminGateway,
    ) -> None:
        self.sessions = sessions
        self.settings = settings
        self.gateway_factory = gateway_factory
        self.options = SyncOptions.from_settings(settings)
        self.tz = ZoneInfo(settings.timezone)
        self._lock = threading.Lock()

    def _users_with_tokens(self, session: Session) -> list[User]:
        return list(
            session.scalars(
                select(User)
                .join(GarminCredentials)
                .where(GarminCredentials.encrypted_tokens.is_not(None))
                .order_by(User.id)
            )
        )

    def _is_due(self, session: Session, user: User, now: datetime) -> bool:
        creds = user.garmin_credentials
        if creds is None or creds.status == GarminStatus.NEEDS_LOGIN:
            return False
        if accounts.rate_limit_cooldown_until(user, now) is not None:
            return False
        # A run stopped by the request limit counts as this slot's sync; the rest comes next slot.
        done = or_(SyncRun.status == SyncStatus.OK, SyncRun.request_limit_reached.is_(True))
        last_ok = session.scalar(
            select(func.max(SyncRun.finished_at)).where(SyncRun.user_id == user.id, done)
        )
        since_ok = [SyncRun.user_id == user.id, ~done]
        if last_ok is not None:
            since_ok.append(SyncRun.started_at > last_ok)
        failures = session.scalar(select(func.count()).select_from(SyncRun).where(*since_ok))
        last_attempt = session.scalar(
            select(func.max(SyncRun.started_at)).where(SyncRun.user_id == user.id)
        )
        return needs_catch_up(
            last_ok,
            last_attempt,
            now,
            tz=self.tz,
            hours=self.settings.sync_hour_list,
            failures=failures or 0,
        )

    def daily(self, now: datetime | None = None) -> None:
        """Job at each sync hour. Same rule as catch-up, so a misfired daily job and the catch-up
        check firing together after wake-up never sync a user twice."""
        self.catch_up(now)

    def catch_up(self, now: datetime | None = None) -> None:
        self._run(lambda session, user: self._is_due(session, user, now or utcnow()), now)

    def _run(self, select_user: Callable[[Session, User], bool], now: datetime | None) -> None:
        if not self._lock.acquire(blocking=False):
            log.info("sync already running, skipping")
            return
        try:
            with self.sessions() as session:
                user_ids = [
                    u.id for u in self._users_with_tokens(session) if select_user(session, u)
                ]
            for user_id in user_ids:
                self._sync_one(user_id, now)
        finally:
            self._lock.release()

    def _sync_one(self, user_id: int, now: datetime | None) -> None:
        try:
            with self.sessions() as session:
                user = session.get(User, user_id)
                if user is None:
                    return
                sync_user(
                    session,
                    user,
                    self.gateway_factory(),
                    key=self.settings.encryption_key,
                    options=self.options,
                    now=now,
                    lock_dir=self.settings.data_dir,
                )
        except SyncBusy:
            log.info("sync user_id=%s skipped: another sync is running", user_id)
        except Exception as e:  # one user's problem never stops the others
            log.warning("sync user_id=%s crashed: %s", user_id, type(e).__name__)


def create_scheduler(
    runner: SyncRunner, settings: Settings, outbox: OutboxRunner | None = None
) -> BackgroundScheduler:
    tz = ZoneInfo(settings.timezone)
    scheduler = BackgroundScheduler(
        timezone=tz,
        job_defaults={
            "coalesce": True,
            "max_instances": 1,
            "misfire_grace_time": MISFIRE_GRACE_S,
        },
    )
    scheduler.add_job(
        runner.daily,
        CronTrigger(hour=settings.sync_hours, minute=0, timezone=tz),
        id="daily_sync",
    )
    scheduler.add_job(
        runner.catch_up,
        IntervalTrigger(seconds=int(CATCH_UP_EVERY.total_seconds()), timezone=tz),
        id="catch_up",
        next_run_time=datetime.now(tz),  # catch-up right after the app starts
    )
    if outbox is not None:
        # Workouts waiting for Garmin (e.g. laptop was asleep, or after a failure).
        scheduler.add_job(
            outbox.run_all,
            IntervalTrigger(seconds=int(CATCH_UP_EVERY.total_seconds()), timezone=tz),
            id="outbox",
            next_run_time=datetime.now(tz),
        )
    return scheduler


def start_scheduler(
    settings: Settings, gateway_factory: GatewayFactory | None = None
) -> BackgroundScheduler | None:
    if not settings.encryption_key:
        log.warning("CG_ENCRYPTION_KEY is not set: background sync disabled")
        return None
    sessions = open_db(settings)
    factory = gateway_factory or RealGarminGateway
    runner = SyncRunner(sessions, settings, factory)
    scheduler = create_scheduler(runner, settings, OutboxRunner(sessions, settings, factory))
    scheduler.start()
    return scheduler


@asynccontextmanager
async def scheduler_running(settings: Settings) -> AsyncIterator[None]:
    """Part of the app lifespan: run the sync scheduler unless CG_SCHEDULER_ENABLED=false."""
    scheduler = start_scheduler(settings) if settings.scheduler_enabled else None
    try:
        yield
    finally:
        if scheduler is not None:
            scheduler.shutdown(wait=False)
