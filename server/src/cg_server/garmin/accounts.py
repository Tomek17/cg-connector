"""Garmin login state per user: encrypted tokens + status.

Tokens are re-encrypted and saved after every successful session, because Garmin may
issue a new refresh token. On HTTP 429 the status becomes `rate_limited` and nothing is
retried. The Garmin password only passes through `login()` and is never stored.
"""

from sqlalchemy.orm import Session

from cg_server.crypto import decrypt, encrypt
from cg_server.garmin.gateway import (
    GarminAuthError,
    GarminGateway,
    GarminRateLimited,
    MfaPrompt,
)
from cg_server.models import GarminCredentials, GarminStatus, User


class NotLoggedIn(GarminAuthError):
    """No stored Garmin tokens for the user."""


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


def _save_tokens(session: Session, user: User, tokens: str, key: str) -> None:
    creds = _credentials(user)
    creds.encrypted_tokens = encrypt(tokens, key)
    creds.status = GarminStatus.OK
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
) -> None:
    try:
        tokens = gateway.login(email, password, prompt_mfa)
    except GarminRateLimited:
        _set_status(session, user, GarminStatus.RATE_LIMITED)
        raise
    except GarminAuthError:
        # A failed re-login (e.g. mistyped password) must not discard working tokens.
        if not has_tokens(user):
            _set_status(session, user, GarminStatus.NEEDS_LOGIN)
        raise
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
        _set_status(session, user, GarminStatus.RATE_LIMITED)
        raise
    except GarminAuthError:
        _set_status(session, user, GarminStatus.NEEDS_LOGIN)
        raise
    _save_tokens(session, user, gateway.dumps(), key)
    return gateway.get_full_name()
