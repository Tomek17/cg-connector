"""The "sync now" button in the app and the sync_now tool for Claude: one user's sync
outside the daily schedule.

Garmin limits logins and requests per account, so a manual sync is refused while one is
running, within `MIN_INTERVAL` of the previous attempt, during the 24 h pause after a 429,
and when Garmin needs a new login. The sync itself is the same incremental `sync_user` as
the scheduler's (only days since the last sync, plus a small overlap).
"""

from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import StrEnum

from sqlalchemy import select
from sqlalchemy.orm import Session, sessionmaker

from cg_server.config import Settings
from cg_server.garmin import accounts
from cg_server.models import GarminStatus, SyncRun, SyncStatus, User, utcnow
from cg_server.queries import as_utc
from cg_server.scheduler import GatewayFactory
from cg_server.sync import SyncOptions, SyncResult, sync_user
from cg_server.synclock import SyncBusy

MIN_INTERVAL = timedelta(hours=1)
# A `running` run older than this was interrupted (laptop asleep), not still running.
RUNNING_FOR_AT_MOST = timedelta(minutes=30)


class Refusal(StrEnum):
    NO_GARMIN = "no_garmin"
    NEEDS_LOGIN = "needs_login"
    RATE_LIMITED = "rate_limited"
    RUNNING = "running"
    TOO_SOON = "too_soon"


@dataclass(frozen=True)
class Check:
    refusal: Refusal | None
    retry_at: datetime | None = None


def check(session: Session, user: User, now: datetime | None = None) -> Check:
    """May this user start a manual sync now?"""
    now = now or utcnow()
    creds = user.garmin_credentials
    if creds is None or creds.encrypted_tokens is None:
        return Check(Refusal.NO_GARMIN)
    if creds.status == GarminStatus.NEEDS_LOGIN:
        return Check(Refusal.NEEDS_LOGIN)
    until = accounts.rate_limit_cooldown_until(user, now)
    if until is not None:
        return Check(Refusal.RATE_LIMITED, until)
    last = session.scalars(
        select(SyncRun)
        .where(SyncRun.user_id == user.id)
        .order_by(SyncRun.started_at.desc(), SyncRun.id.desc())
        .limit(1)
    ).one_or_none()
    if last is not None:
        started = as_utc(last.started_at)
        if last.status == SyncStatus.RUNNING and now - started < RUNNING_FOR_AT_MOST:
            return Check(Refusal.RUNNING)
        if now - started < MIN_INTERVAL:
            return Check(Refusal.TOO_SOON, started + MIN_INTERVAL)
    return Check(None)


class ManualSync:
    """Runs one user's sync (blocking; call it in a worker thread)."""

    def __init__(
        self, sessions: sessionmaker[Session], settings: Settings, gateway_factory: GatewayFactory
    ) -> None:
        self.sessions = sessions
        self.settings = settings
        self.gateway_factory = gateway_factory
        self.options = SyncOptions.from_settings(settings)

    def run(self, user_id: int) -> SyncResult | None:
        """The sync result, or None when another sync holds the lock or the user is gone."""
        if not self.settings.encryption_key:
            return None
        with self.sessions() as session:
            user = session.get(User, user_id)
            if user is None:
                return None
            try:
                return sync_user(
                    session,
                    user,
                    self.gateway_factory(),
                    key=self.settings.encryption_key,
                    options=self.options,
                    lock_dir=self.settings.data_dir,
                )
            except SyncBusy:
                return None
