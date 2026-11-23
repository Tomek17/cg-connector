"""Personal user secrets: CG code (OAuth login for claude.ai), app token (Android REST) and
invite code (one-time, exchanged in the app for an app token).

Secrets have 32 bytes of randomness, are shown once and only their SHA-256 hash is stored.
Issuing a new secret of a kind replaces the old one (rotation).
"""

import hashlib
import hmac
import secrets
from datetime import UTC, datetime, timedelta
from enum import StrEnum

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from cg_server.models import OAuthCode, OAuthToken, User, UserSecret, utcnow

SECRET_BYTES = 32


class SecretKind(StrEnum):
    CG_CODE = "cg_code"
    APP_TOKEN = "app_token"  # noqa: S105 - name of a kind, not a secret
    INVITE = "invite"


PREFIX = {SecretKind.CG_CODE: "cgc_", SecretKind.APP_TOKEN: "cga_", SecretKind.INVITE: "cgi_"}
INVITE_VALID_FOR = timedelta(days=7)


def new_secret(prefix: str = "") -> str:
    return prefix + secrets.token_urlsafe(SECRET_BYTES)


def hash_secret(secret: str) -> str:
    """SHA-256 is enough here: secrets are high-entropy random values, not passwords."""
    return hashlib.sha256(secret.encode()).hexdigest()


def issue_secret(session: Session, user: User, kind: SecretKind) -> str:
    """Create (or rotate) the user's secret of `kind`; return the plaintext (show it once).

    Rotating the CG code also revokes the user's OAuth codes and tokens (claude.ai must
    reconnect), so a leaked code cannot keep working through old tokens.
    """
    secret = new_secret(PREFIX[kind])
    session.execute(
        delete(UserSecret).where(UserSecret.user_id == user.id, UserSecret.kind == kind.value)
    )
    if kind is SecretKind.CG_CODE:
        session.execute(delete(OAuthToken).where(OAuthToken.user_id == user.id))
        session.execute(delete(OAuthCode).where(OAuthCode.user_id == user.id))
    session.add(UserSecret(user_id=user.id, kind=kind.value, secret_hash=hash_secret(secret)))
    session.commit()
    return secret


def user_for_secret(session: Session, kind: SecretKind, secret: str) -> User | None:
    if not secret:
        return None
    wanted = hash_secret(secret)
    row = session.scalars(
        select(UserSecret).where(UserSecret.kind == kind.value, UserSecret.secret_hash == wanted)
    ).one_or_none()
    if row is None or not hmac.compare_digest(row.secret_hash, wanted):
        return None
    return session.get(User, row.user_id)


def redeem_invite(
    session: Session, code: str, now: datetime | None = None
) -> tuple[User, str] | None:
    """Exchange a valid invite code (one-time, max `INVITE_VALID_FOR` old) for a new app token.

    Returns the user and the app token, or None for an unknown, used or expired code.
    """
    if not code:
        return None
    wanted = hash_secret(code)
    row = session.scalars(
        select(UserSecret).where(
            UserSecret.kind == SecretKind.INVITE.value, UserSecret.secret_hash == wanted
        )
    ).one_or_none()
    if row is None or not hmac.compare_digest(row.secret_hash, wanted):
        return None
    created = row.created_at if row.created_at.tzinfo else row.created_at.replace(tzinfo=UTC)
    user = session.get(User, row.user_id)
    session.delete(row)  # one-time, also when expired
    session.commit()
    if user is None or (now or utcnow()) - created > INVITE_VALID_FOR:
        return None
    return user, issue_secret(session, user, SecretKind.APP_TOKEN)
