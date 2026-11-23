"""Garmin login state per user: encrypted tokens + status.

Tokens are re-encrypted and saved after every successful session, because Garmin may
issue a new refresh token. On HTTP 429 the status becomes `rate_limited` and nothing is
retried. The Garmin password only passes through `login()` and is never stored.
"""

from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy.orm import Session

from cg_server.crypto import decrypt, encrypt
from cg_server.garmin.gateway import (
    GarminAuthError,
    GarminGateway,
    GarminRateLimited,
    LoginStep,
    MfaPrompt,
)
from cg_server.models import GarminCredentials, GarminStatus, User, utcnow

RATE_LIMIT_COOLDOWN = timedelta(hours=24)


class NotLoggedIn(GarminAuthError):
    """No stored Garmin tokens for the user."""


class RateLimitCooldown(Exception):
    """Password login refused: Garmin answered 429 less than 24 h ago."""

    def __init__(self, until: datetime) -> None:
        super().__init__(f"Logowanie wstrzymane do {until.isoformat()} po błędzie 429")
        self.until = until


def rate_limit_cooldown_until(user: User, now: datetime | None = None) -> datetime | None:
    """End of the 24 h pause after the last 429, or None if Garmin may be contacted.

    Use before any request that could hit Garmin's login (garmin-login, future sync).
    """
    creds = user.garmin_credentials
    if creds is None or creds.rate_limited_at is None:
        return None
    at = creds.rate_limited_at
    if at.tzinfo is None:  # SQLite returns naive datetimes; we always store UTC
        at = at.replace(tzinfo=UTC)
    until = at + RATE_LIMIT_COOLDOWN
    return until if until > (now or utcnow()) else None


def _credentials(user: User) -> GarminCredentials:
    if user.garmin_credentials is None:
        user.garmin_credentials = GarminCredentials(status=GarminStatus.NEEDS_LOGIN)
    return user.garmin_credentials


def has_tokens(user: User) -> bool:
    return (
        user.garmin_credentials is not None and user.garmin_credentials.encrypted_tokens is not None
    )


def _set_status(session: Session, user: User, status: GarminStatus) -> None:
    _credentials(user).status = status
    session.commit()


def mark_rate_limited(session: Session, user: User) -> None:
    """Record a 429: status `rate_limited` and the time, which starts the 24 h pause."""
    creds = _credentials(user)
    creds.status = GarminStatus.RATE_LIMITED
    creds.rate_limited_at = utcnow()
    session.commit()


def mark_needs_login(session: Session, user: User) -> None:
    _set_status(session, user, GarminStatus.NEEDS_LOGIN)


def store_session_tokens(session: Session, user: User, tokens: str, key: str) -> None:
    """Save tokens after a data session (sync) without touching status or the 429 time."""
    _credentials(user).encrypted_tokens = encrypt(tokens, key)
    session.commit()


def _save_tokens(session: Session, user: User, tokens: str, key: str) -> None:
    creds = _credentials(user)
    creds.encrypted_tokens = encrypt(tokens, key)
    creds.status = GarminStatus.OK
    creds.rate_limited_at = None
    session.commit()


def login(
    session: Session,
    user: User,
    gateway: GarminGateway,
    *,
    email: str,
    password: str,
    prompt_mfa: MfaPrompt,
    key: str,
    force: bool = False,
) -> None:
    """Password login. Refused (RateLimitCooldown) within 24 h of a 429 unless `force`."""
    with _password_attempt(session, user, force=force):
        tokens = gateway.login(email, password, prompt_mfa)
    _save_tokens(session, user, tokens, key)


@contextmanager
def _password_attempt(session: Session, user: User, *, force: bool = False) -> Iterator[None]:
    """Cooldown check before, and status bookkeeping after, any password/MFA step."""
    if not force and (until := rate_limit_cooldown_until(user)) is not None:
        raise RateLimitCooldown(until)
    try:
        yield
    except GarminRateLimited:
        mark_rate_limited(session, user)
        raise
    except GarminAuthError:
        # A failed re-login (e.g. mistyped password) must not discard working tokens.
        if not has_tokens(user):
            _set_status(session, user, GarminStatus.NEEDS_LOGIN)
        raise


def start_login(
    session: Session, user: User, gateway: GarminGateway, *, email: str, password: str, key: str
) -> dict[str, Any] | None:
    """First step of the login from the app: None = logged in (tokens saved), otherwise the
    MFA state to pass to `finish_login` together with the user's code."""
    with _password_attempt(session, user):
        step: LoginStep = gateway.start_login(email, password)
    if step.tokens is None:
        return step.mfa_state or {}
    _save_tokens(session, user, step.tokens, key)
    return None


def finish_login(
    session: Session,
    user: User,
    gateway: GarminGateway,
    *,
    mfa_state: dict[str, Any],
    mfa_code: str,
    key: str,
) -> None:
    with _password_attempt(session, user):
        tokens = gateway.finish_login(mfa_state, mfa_code)
    _save_tokens(session, user, tokens, key)


def check(session: Session, user: User, gateway: GarminGateway, *, key: str) -> str | None:
    """Resume the session from stored tokens, make one light call, save tokens again.

    Returns the Garmin account's full name (for display only).
    """
    creds = user.garmin_credentials
    if creds is None or creds.encrypted_tokens is None:
        raise NotLoggedIn("Brak zapisanych tokenów Garmina")
    tokens = decrypt(creds.encrypted_tokens, key)
    try:
        gateway.resume(tokens)
        gateway.get_profile()
    except GarminRateLimited:
        mark_rate_limited(session, user)
        raise
    except GarminAuthError:
        _set_status(session, user, GarminStatus.NEEDS_LOGIN)
        raise
    _save_tokens(session, user, gateway.dumps(), key)
    return gateway.get_full_name()
