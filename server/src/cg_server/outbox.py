"""Outbox worker: sends scheduled workouts to Garmin Connect (and removes deleted ones).

For each due entry of a user (`pending` or `deleting`, `next_attempt_at` passed):
- `pending`: upload the workout if it has no `garmin_workout_id` yet, save that id at once
  (own transaction), then put it on the date in the Garmin calendar -> `sent`;
- `deleting`: remove the calendar entry -> `deleted` (the workout template stays).

Idempotency: the attempt is counted (and committed) before Garmin is contacted, so an
entry whose process died becomes due again after the backoff. The workout id is saved
before scheduling, so a retry never uploads it again; a workout that already has a
Garmin id is first looked up in the Garmin calendar for that day, so a retry after a
crash right after scheduling does not put it there twice. [Not covered: a crash between Garmin
creating the workout and us saving its id leaves one extra template in the library.]

Garmin rules as in the sync (docs/research/garmin.md section 5): a pause before every
request, HTTP 429 stops at once and starts the 24 h pause for the user
(`accounts.mark_rate_limited`), rejected tokens -> `needs_login`; nothing is sent during
the pause. The inter-process sync lock (`synclock`) is shared with the sync, so only one
Garmin session per server runs at a time. Errors stored in `last_error` are fixed texts.
"""

import contextlib
import logging
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, TypeVar

from garminconnect import GarminConnectConnectionError
from sqlalchemy import case, or_, select, update
from sqlalchemy.orm import Session, sessionmaker

from cg_server.config import Settings
from cg_server.crypto import decrypt
from cg_server.garmin import accounts
from cg_server.garmin.gateway import (
    GarminAuthError,
    GarminGateway,
    GarminNotFound,
    GarminRateLimited,
    RealGarminGateway,
    schedule_id_of,
)
from cg_server.models import GarminStatus, ScheduledWorkout, ScheduleStatus, User, utcnow
from cg_server.synclock import SyncBusy, sync_lock
from cg_server.workout_definition import DefinitionError, parse_definition
from cg_server.workout_garmin import garmin_json

log = logging.getLogger(__name__)

T = TypeVar("T")
GatewayFactory = Callable[[], GarminGateway]

MAX_ATTEMPTS = 6
FIRST_RETRY = timedelta(minutes=5)
MAX_RETRY = timedelta(hours=6)
DUE = (ScheduleStatus.PENDING, ScheduleStatus.DELETING)

# `last_error`: "<code>: <short English detail>" (read by Claude and the app). Codes:
# send_failed, delete_failed, rate_limited, garmin_login_needed, date_passed.
ERR_RATE_LIMITED = "rate_limited: Garmin HTTP 429, paused for 24 h"
ERR_NEEDS_LOGIN = "garmin_login_needed: the server owner must run cg-server garmin-login"


def retry_delay(attempts: int) -> timedelta:
    """Wait after the n-th failed attempt: 5, 10, 20, 40 ... minutes, at most 6 h."""
    delay: timedelta = FIRST_RETRY * 2 ** min(max(attempts - 1, 0), 10)
    return min(delay, MAX_RETRY)


class OutboxError(Exception):
    """Expected failure of one entry; the (English, fixed) message goes to `last_error`."""


@dataclass
class OutboxResult:
    sent: int = 0
    deleted: int = 0
    failed: int = 0
    rate_limited: bool = False
    # Why nothing was tried: "rate_limited" (24 h pause) or "needs_login".
    skipped: str | None = None


class _Paced:
    """Pause before every Garmin request except the first one."""

    def __init__(self, delay_s: float, sleep: Callable[[float], None]) -> None:
        self.delay_s, self.sleep, self.requests = delay_s, sleep, 0

    def __call__(self, fn: Callable[..., T], *args: Any) -> T:
        if self.requests and self.delay_s > 0:
            self.sleep(self.delay_s)
        self.requests += 1
        return fn(*args)


def _aware(value: datetime | None) -> datetime | None:
    if value is None or value.tzinfo is not None:
        return value
    return value.replace(tzinfo=UTC)  # SQLite returns naive datetimes; we store UTC


def due_items(session: Session, user: User, now: datetime) -> list[ScheduledWorkout]:
    return list(
        session.scalars(
            select(ScheduledWorkout)
            .where(
                ScheduledWorkout.user_id == user.id,
                ScheduledWorkout.status.in_(DUE),
                or_(
                    ScheduledWorkout.next_attempt_at.is_(None),
                    ScheduledWorkout.next_attempt_at <= now,
                ),
            )
            .order_by(ScheduledWorkout.id)
        )
    )


def _error_text(error: Exception, deleting: bool) -> str:
    code = "delete_failed" if deleting else "send_failed"
    if isinstance(error, GarminNotFound):
        detail = "Garmin HTTP 404"
    elif isinstance(error, DefinitionError):
        detail = "invalid workout definition"
    elif isinstance(error, GarminConnectConnectionError):
        status = getattr(getattr(error, "response", None), "status_code", None)
        detail = f"Garmin HTTP {status}" if isinstance(status, int) else "connection error"
    else:
        detail = str(error) if isinstance(error, OutboxError) else type(error).__name__
    return f"{code}: {detail}"[:200]


def _entry_workout_id(entry: dict[str, Any]) -> Any:
    workout = entry.get("workout")
    nested = workout.get("workoutId") if isinstance(workout, dict) else None
    return entry.get("workoutId", nested)


class _UserOutbox:
    def __init__(
        self,
        session: Session,
        user: User,
        gateway: GarminGateway,
        paced: _Paced,
        now: datetime,
    ) -> None:
        self.session, self.user, self.gateway, self.paced, self.now = (
            session,
            user,
            gateway,
            paced,
            now,
        )
        self.result = OutboxResult()

    def _start_attempt(self, item: ScheduledWorkout) -> None:
        """Count the attempt before contacting Garmin; if the process dies, the entry
        becomes due again after the backoff."""
        item.attempts += 1
        item.next_attempt_at = self.now + retry_delay(item.attempts)
        self.session.commit()

    def _undo_attempt(self, item: ScheduledWorkout, error: str) -> None:
        """429 / login problems are not the entry's fault: do not count them."""
        self.session.rollback()
        item.attempts = max(item.attempts - 1, 0)
        item.next_attempt_at = None
        item.last_error = error
        self.session.commit()

    def _failed_attempt(
        self, item: ScheduledWorkout, status: ScheduleStatus, error: Exception
    ) -> None:
        self.session.rollback()
        log.warning("outbox entry %s failed: %s", item.id, type(error).__name__)
        deleting = status == ScheduleStatus.DELETING
        values: dict[str, Any] = {"last_error": _error_text(error, deleting)}
        if item.attempts >= MAX_ATTEMPTS:
            values |= {"status": ScheduleStatus.FAILED.value, "next_attempt_at": None}
            self.result.failed += 1
        # Only if nobody changed the entry meanwhile (e.g. a delete request).
        self.session.execute(
            update(ScheduledWorkout)
            .where(ScheduledWorkout.id == item.id, ScheduledWorkout.status == status.value)
            .values(**values)
        )
        self.session.commit()

    def _find_in_calendar(self, garmin_workout_id: int, day: str) -> tuple[bool, int | None]:
        """(is the workout in the Garmin calendar on that day, its entry id if known)."""
        entries = self.paced(self.gateway.get_scheduled_workouts_by_period, day, day)
        for entry in entries:
            if str(_entry_workout_id(entry)) == str(garmin_workout_id):
                return True, schedule_id_of(entry)
        return False, None

    def send(self, item: ScheduledWorkout) -> None:
        workout = item.workout
        day = item.date.isoformat()
        # Uploaded before (by an earlier attempt, maybe one that died right after
        # scheduling, or for another day): look in the calendar before scheduling.
        maybe_scheduled = workout.garmin_workout_id is not None
        if workout.garmin_workout_id is None:
            body = garmin_json(parse_definition(workout.definition_json))
            workout.garmin_workout_id = self.paced(self.gateway.upload_workout, body)
            self.session.commit()  # saved before scheduling: a retry never uploads again
        garmin_id = workout.garmin_workout_id
        found, schedule_id = (
            self._find_in_calendar(garmin_id, day) if maybe_scheduled else (False, None)
        )
        # Found without a recognised id: still already scheduled, never schedule it twice.
        if not found:
            try:
                schedule_id = self.paced(self.gateway.schedule_workout, garmin_id, day)
            except GarminNotFound:
                # The template was deleted in Garmin Connect: upload it again next time.
                workout.garmin_workout_id = None
                self.session.commit()
                raise
            if schedule_id is None:  # answer without an id [not verified live]
                _, schedule_id = self._find_in_calendar(garmin_id, day)
        # One statement on the current row: a delete request that came in meanwhile
        # turns the entry into `deleting`, so it is removed from the calendar again.
        self.session.execute(
            update(ScheduledWorkout)
            .where(ScheduledWorkout.id == item.id)
            .values(
                garmin_schedule_id=schedule_id,
                last_error=None,
                next_attempt_at=None,
                status=case(
                    (
                        ScheduledWorkout.status == ScheduleStatus.PENDING.value,
                        ScheduleStatus.SENT.value,
                    ),
                    else_=ScheduleStatus.DELETING.value,
                ),
            )
        )
        self.session.commit()
        self.session.refresh(item)
        if item.status == ScheduleStatus.SENT:
            self.result.sent += 1
        else:
            self.unschedule(item)

    def unschedule(self, item: ScheduledWorkout) -> None:
        """Remove the entry from the Garmin calendar. Without a known entry id it is looked
        up there (workout id + day); if that fails it is an error, never a false 'deleted'."""
        schedule_id = item.garmin_schedule_id
        garmin_workout_id = item.workout.garmin_workout_id
        if schedule_id is None and garmin_workout_id is not None:
            found, schedule_id = self._find_in_calendar(garmin_workout_id, item.date.isoformat())
            if schedule_id is None:
                raise OutboxError(
                    "calendar entry has no known id; remove it in Garmin Connect"
                    if found
                    else "calendar entry not found; check Garmin Connect"
                )
        if schedule_id is not None:
            with contextlib.suppress(GarminNotFound):  # already removed in Garmin Connect
                self.paced(self.gateway.unschedule_workout, schedule_id)
        item.status = ScheduleStatus.DELETED
        item.last_error = None
        item.next_attempt_at = None
        self.session.commit()
        self.result.deleted += 1

    def process(self, items: list[ScheduledWorkout]) -> None:
        for item in items:
            self.session.refresh(item)
            if item.status not in DUE:
                continue
            status = item.status
            self._start_attempt(item)
            try:
                if status == ScheduleStatus.PENDING:
                    self.send(item)
                else:
                    self.unschedule(item)
            except GarminRateLimited:
                self._undo_attempt(item, ERR_RATE_LIMITED)
                accounts.mark_rate_limited(self.session, self.user)
                self.result.rate_limited = True
                return
            except GarminAuthError:
                self._undo_attempt(item, ERR_NEEDS_LOGIN)
                accounts.mark_needs_login(self.session, self.user)
                return
            except Exception as e:
                self._failed_attempt(item, status, e)


def _note_on_items(session: Session, items: list[ScheduledWorkout], error: str) -> None:
    for item in items:
        item.last_error = error
    session.commit()


def process_user_outbox(
    session: Session,
    user: User,
    gateway: GarminGateway,
    *,
    key: str,
    now: datetime | None = None,
    delay_s: float = 2.0,
    sleep: Callable[[float], None] = time.sleep,
    lock_dir: Path | None = None,
) -> OutboxResult:
    """Process the user's due outbox entries under the sync lock (SyncBusy at once if
    taken). Garmin is contacted only if something is due."""
    if lock_dir is None:
        lock_dir = Path(str(session.get_bind().engine.url.database)).parent
    now = (now or utcnow()).astimezone(UTC)
    if not due_items(session, user, now):
        return OutboxResult()
    with sync_lock(lock_dir):
        return _process_locked(session, user, gateway, key, now, delay_s, sleep)


def _process_locked(
    session: Session,
    user: User,
    gateway: GarminGateway,
    key: str,
    now: datetime,
    delay_s: float,
    sleep: Callable[[float], None],
) -> OutboxResult:
    items = due_items(session, user, now)
    if not items:
        return OutboxResult()
    if accounts.rate_limit_cooldown_until(user, now) is not None:
        _note_on_items(session, items, ERR_RATE_LIMITED)
        return OutboxResult(skipped="rate_limited")
    creds = user.garmin_credentials
    if creds is None or creds.encrypted_tokens is None or creds.status == GarminStatus.NEEDS_LOGIN:
        _note_on_items(session, items, ERR_NEEDS_LOGIN)
        return OutboxResult(skipped="needs_login")
    paced = _Paced(delay_s, sleep)
    job = _UserOutbox(session, user, gateway, paced, now)
    try:
        paced(gateway.resume, decrypt(creds.encrypted_tokens, key))
    except GarminRateLimited:
        accounts.mark_rate_limited(session, user)
        _note_on_items(session, items, ERR_RATE_LIMITED)
        return OutboxResult(rate_limited=True)
    except GarminAuthError:
        accounts.mark_needs_login(session, user)
        _note_on_items(session, items, ERR_NEEDS_LOGIN)
        return OutboxResult(skipped="needs_login")
    try:
        job.process(items)
    finally:
        session.rollback()
        try:
            accounts.store_session_tokens(session, user, gateway.dumps(), key)
        except Exception as e:  # never lose the outcome because of this
            session.rollback()
            log.warning("saving Garmin tokens failed: %s", type(e).__name__)
    if job.result.sent or job.result.deleted:
        log.info(
            "outbox user_id=%s sent=%s deleted=%s",
            user.id,
            job.result.sent,
            job.result.deleted,
        )
    return job.result


class OutboxRunner:
    """Runs the outbox in the background: after each new entry, every 15 min, at start."""

    def __init__(
        self,
        sessions: sessionmaker[Session],
        settings: Settings,
        gateway_factory: GatewayFactory = RealGarminGateway,
        *,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self.sessions = sessions
        self.settings = settings
        self.gateway_factory = gateway_factory
        self.sleep = sleep

    def run_user(self, user_id: int) -> OutboxResult | None:
        """One user's due entries now. Never raises; None if it could not run."""
        if not self.settings.encryption_key:
            return None
        try:
            with self.sessions() as session:
                user = session.get(User, user_id)
                if user is None:
                    return None
                return process_user_outbox(
                    session,
                    user,
                    self.gateway_factory(),
                    key=self.settings.encryption_key,
                    delay_s=self.settings.sync_request_delay_s,
                    sleep=self.sleep,
                    lock_dir=self.settings.data_dir,
                )
        except SyncBusy:
            log.info("outbox user_id=%s skipped: Garmin session busy", user_id)
        except Exception as e:  # one user's problem never stops the others
            log.warning("outbox user_id=%s crashed: %s", user_id, type(e).__name__)
        return None

    def run_all(self) -> None:
        """Scheduler job: every user with due entries, one after another."""
        with self.sessions() as session:
            user_ids = list(
                session.scalars(
                    select(ScheduledWorkout.user_id)
                    .where(ScheduledWorkout.status.in_(DUE))
                    .distinct()
                    .order_by(ScheduledWorkout.user_id)
                )
            )
        for user_id in user_ids:
            self.run_user(user_id)

    def kick(self, user_id: int) -> None:
        """Try at once, in a background thread (the caller does not wait)."""
        threading.Thread(target=self.run_user, args=(user_id,), daemon=True).start()
