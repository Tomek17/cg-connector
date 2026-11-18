"""Personal user secrets: CG code (OAuth login for claude.ai) and app token (Android REST).

Secrets have 32 bytes of randomness, are shown once and only their SHA-256 hash is stored.
Issuing a new secret of a kind replaces the old one (rotation).
"""

import hashlib
import hmac
import secrets
from enum import StrEnum

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from cg_server.models import OAuthCode, OAuthToken, User, UserSecret

SECRET_BYTES = 32


class SecretKind(StrEnum):
    CG_CODE = "cg_code"
    APP_TOKEN = "app_token"  # noqa: S105 - name of a kind, not a secret


PREFIX = {SecretKind.CG_CODE: "cgc_", SecretKind.APP_TOKEN: "cga_"}


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
