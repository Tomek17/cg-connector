"""Background sync schedule (APScheduler, Europe/Warsaw), started in the FastAPI lifespan.

- `daily_sync` at `CG_SYNC_HOUR` (14:00): every user with Garmin tokens.
- `catch_up` every 15 min and right after start: users whose sync is due according to
  `needs_catch_up` (missed 14:00 because the laptop was off/asleep, or >24 h old).
- Jobs: coalesce, max_instances=1, misfire_grace_time; a lock keeps the two jobs from
  syncing at the same time. Users are synced one after another, never in parallel.
"""

import logging
import threading
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo

from apscheduler.schedulers.background import (  # type: ignore[import-untyped]
    BackgroundScheduler,
)
from apscheduler.triggers.cron import CronTrigger  # type: ignore[import-untyped]
from apscheduler.triggers.interval import IntervalTrigger  # type: ignore[import-untyped]
from fastapi import FastAPI
from sqlalchemy import func, select
from sqlalchemy.orm import Session, sessionmaker

from cg_server.config import Settings, get_settings
from cg_server.db import open_db
from cg_server.garmin import accounts
from cg_server.garmin.gateway import GarminGateway, RealGarminGateway
from cg_server.models import GarminCredentials, GarminStatus, SyncRun, SyncStatus, User, utcnow
from cg_server.sync import SyncOptions, sync_user

log = logging.getLogger(__name__)

GatewayFactory = Callable[[], GarminGateway]
CATCH_UP_EVERY = timedelta(minutes=15)
RETRY_AFTER_FAILURE = timedelta(hours=1)
MISFIRE_GRACE_S = 6 * 3600


def _aware(value: datetime) -> datetime:
    return value if value.tzinfo is not None else value.replace(tzinfo=UTC)


def needs_catch_up(
    last_ok: datetime | None,
    last_attempt: datetime | None,
    now: datetime,
    *,
    tz: ZoneInfo,
    hour: int = 14,
    retry_after: timedelta = RETRY_AFTER_FAILURE,
) -> bool:
    """Is a sync due now? (MASTER_PROMPT 3.1)

    Due when there was never a successful sync, when the last one was before today's
    `hour` and it is already past `hour`, or when it is older than 24 h. After a failed
    attempt wait `retry_after` before trying again (no hammering Garmin every 15 min).
    Naive datetimes (SQLite) are UTC.
    """
    now = _aware(now)
    if last_attempt is not None:
        attempt = _aware(last_attempt)
        failed = last_ok is None or attempt > _aware(last_ok)
        if failed and now - attempt < retry_after:
            return False
    if last_ok is None:
        return True
    last_ok = _aware(last_ok)
    today_sync = now.astimezone(tz).replace(hour=hour, minute=0, second=0, microsecond=0)
    if now >= today_sync and last_ok < today_sync:
        return True
    return now - last_ok > timedelta(hours=24)


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
        last_ok = session.scalar(
            select(func.max(SyncRun.started_at)).where(
                SyncRun.user_id == user.id, SyncRun.status == SyncStatus.OK
            )
        )
        last_attempt = session.scalar(
            select(func.max(SyncRun.started_at)).where(SyncRun.user_id == user.id)
        )
        return needs_catch_up(last_ok, last_attempt, now, tz=self.tz, hour=self.settings.sync_hour)

    def sync_all(self, now: datetime | None = None) -> None:
        """Daily job: every user with tokens (skipped/failed runs are recorded too)."""
        self._run(lambda session, user: True, now)

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
                )
        except Exception as e:  # one user's problem never stops the others
            log.warning("sync user_id=%s crashed: %s", user_id, type(e).__name__)


def create_scheduler(runner: SyncRunner, settings: Settings) -> BackgroundScheduler:
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
        runner.sync_all,
        CronTrigger(hour=settings.sync_hour, minute=0, timezone=tz),
        id="daily_sync",
    )
    scheduler.add_job(
        runner.catch_up,
        IntervalTrigger(seconds=int(CATCH_UP_EVERY.total_seconds()), timezone=tz),
        id="catch_up",
        next_run_time=datetime.now(tz),  # catch-up right after the app starts
    )
    return scheduler


def start_scheduler(
    settings: Settings, gateway_factory: GatewayFactory | None = None
) -> BackgroundScheduler | None:
    if not settings.encryption_key:
        log.warning("CG_ENCRYPTION_KEY is not set: background sync disabled")
        return None
    runner = SyncRunner(open_db(settings), settings, gateway_factory or RealGarminGateway)
    scheduler = create_scheduler(runner, settings)
    scheduler.start()
    return scheduler


@asynccontextmanager
async def scheduler_lifespan(_app: FastAPI) -> AsyncIterator[None]:
    """FastAPI lifespan: start the sync scheduler unless CG_SCHEDULER_ENABLED=false."""
    settings = get_settings()
    scheduler = start_scheduler(settings) if settings.scheduler_enabled else None
    try:
        yield
    finally:
        if scheduler is not None:
            scheduler.shutdown(wait=False)
