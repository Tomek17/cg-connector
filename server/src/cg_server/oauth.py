"""Minimal OAuth 2.1 authorization server (DCR + PKCE) for the claude.ai connector (ADR-0003).

Built on the `mcp` SDK: the SDK serves the metadata, /authorize, /token, /register and /revoke
endpoints and verifies PKCE and redirect URIs; this provider stores clients, codes and tokens
in SQLite (hashes only) and lets the user log in with the personal CG code.
"""

import secrets
import threading
import time
from collections import deque
from dataclasses import dataclass
from datetime import timedelta
from typing import Any
from urllib.parse import urlsplit

from mcp.server.auth.provider import (
    AccessToken,
    AuthorizationCode,
    AuthorizationParams,
    AuthorizeError,
    OAuthAuthorizationServerProvider,
    RefreshToken,
    RegistrationError,
    TokenError,
    construct_redirect_uri,
)
from mcp.shared.auth import OAuthClientInformationFull
from mcp.shared.auth import OAuthToken as TokenResponse
from pydantic import AnyUrl
from sqlalchemy import delete, exists, func, select, update
from sqlalchemy.orm import Session, sessionmaker

from cg_server.config import Settings
from cg_server.crypto import decrypt, encrypt
from cg_server.models import OAuthClient, OAuthCode, OAuthToken, utcnow
from cg_server.user_secrets import SecretKind, hash_secret, new_secret, user_for_secret

SCOPE = "cg"
CLAUDE_CALLBACKS = (
    "https://claude.ai/api/mcp/auth_callback",
    "https://claude.com/api/mcp/auth_callback",
)
LOGIN_PATH = "/authorize/login"
# Kind of a rotated refresh token, kept until expiry to detect replay.
USED_REFRESH = "refresh_used"
# Kind of an exchanged authorization code, kept until the code's expiry: if the code comes
# back, it was copied, and the tokens issued for it are revoked (OAuth 2.1, 4.1.3).
USED_CODE = "code_used"
ACCESS_TTL = 60 * 60
REFRESH_TTL = 60 * 24 * 60 * 60
CODE_TTL = 5 * 60
LOGIN_TTL = 10 * 60
MAX_LOGIN_ATTEMPTS = 5
MAX_PENDING_LOGINS = 1000
# Wrong CG codes per client IP: after this many in the window the IP is locked out until
# the window passes (on top of the per-request limit above and the HTTP rate limit).
MAX_FAILED_CODES_PER_IP = 10
FAILED_CODES_WINDOW = 15 * 60
# Dynamic client registration is open to anyone: bound what it can store.
MAX_CLIENTS = 100
MAX_CLIENT_METADATA_BYTES = 4096
# A client that never got a code or token is removed after this time; when the table is
# full, the oldest such client older than LOGIN_TTL (not in the middle of a login) gives way.
STALE_CLIENT_AGE = timedelta(hours=1)


class LoginRequestInvalid(Exception):
    """Unknown, expired or used-up login request."""


class WrongCode(Exception):
    """The CG code does not match any user."""


class TooManyFailures(Exception):
    """Too many wrong CG codes from this client IP; try again later."""


class _Rejected(Exception):
    """Rolls back the transaction. The SDK's errors are frozen dataclasses, which cannot pass
    through a `with` block (contextlib sets `__traceback__`), so they are raised outside it."""


def redirect_uri_allowed(uri: str, *, allow_localhost: bool) -> bool:
    if uri in CLAUDE_CALLBACKS:
        return True
    if not allow_localhost:
        return False
    parts = urlsplit(uri)
    return (
        parts.scheme == "http"
        and parts.hostname in ("localhost", "127.0.0.1")
        and parts.username is None
        and parts.password is None
    )


@dataclass
class PendingLogin:
    client_id: str
    client_name: str
    params: AuthorizationParams
    expires_at: float
    attempts: int = 0


class CgOAuthProvider(
    OAuthAuthorizationServerProvider[AuthorizationCode, RefreshToken, AccessToken]
):
    def __init__(self, settings: Settings, sessions: sessionmaker[Session]) -> None:
        self._settings = settings
        self._sessions = sessions
        # Short-lived browser login requests; losing them on restart only means "try again".
        self._pending: dict[str, PendingLogin] = {}
        self._failures: dict[str, deque[float]] = {}
        # complete_login runs in the threadpool, authorize on the event loop.
        self._lock = threading.Lock()

    # --- clients (DCR) -------------------------------------------------------------------

    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        with self._sessions() as session:
            row = session.get(OAuthClient, client_id)
            if row is None:
                return None
            info = OAuthClientInformationFull.model_validate_json(row.metadata_json)
            if row.client_secret_encrypted:
                info.client_secret = decrypt(
                    row.client_secret_encrypted, self._settings.encryption_key
                )
            return info

    async def register_client(self, client_info: OAuthClientInformationFull) -> None:
        uris = [str(u) for u in client_info.redirect_uris or []]
        allow_localhost = self._settings.oauth_allow_localhost
        if not uris or not all(
            redirect_uri_allowed(u, allow_localhost=allow_localhost) for u in uris
        ):
            raise RegistrationError("invalid_redirect_uri", "redirect_uri not allowed")
        secret = client_info.client_secret
        public_info = client_info.model_copy(update={"client_secret": None})
        metadata_json = public_info.model_dump_json()
        if len(metadata_json.encode()) > MAX_CLIENT_METADATA_BYTES:
            raise RegistrationError("invalid_client_metadata", "client metadata too large")
        try:
            with self._sessions.begin() as session:
                self._delete_stale_clients(session)
                count = session.scalar(select(func.count()).select_from(OAuthClient)) or 0
                if count >= MAX_CLIENTS and not self._evict_unused_client(session):
                    raise _Rejected
                session.add(
                    OAuthClient(
                        client_id=client_info.client_id,
                        metadata_json=metadata_json,
                        client_secret_encrypted=(
                            encrypt(secret, self._settings.encryption_key) if secret else None
                        ),
                    )
                )
            return
        except _Rejected:
            pass
        raise RegistrationError("invalid_client_metadata", "too many registered clients")

    @staticmethod
    def _unused(older_than: timedelta) -> list[Any]:
        return [
            OAuthClient.created_at < utcnow() - older_than,
            ~exists().where(OAuthToken.client_id == OAuthClient.client_id),
            ~exists().where(OAuthCode.client_id == OAuthClient.client_id),
        ]

    def _delete_stale_clients(self, session: Session) -> None:
        """Clients older than an hour that never got (or no longer have) tokens or codes."""
        session.execute(delete(OAuthClient).where(*self._unused(STALE_CLIENT_AGE)))

    def _evict_unused_client(self, session: Session) -> bool:
        """Table full: remove the oldest unused client that is past its login window, so a
        flood of registrations cannot lock out a new connector for long."""
        oldest = session.scalar(
            select(OAuthClient.client_id)
            .where(*self._unused(timedelta(seconds=LOGIN_TTL)))
            .order_by(OAuthClient.created_at)
            .limit(1)
        )
        if oldest is None:
            return False
        session.execute(delete(OAuthClient).where(OAuthClient.client_id == oldest))
        return True

    # --- browser login with the CG code --------------------------------------------------

    async def authorize(
        self, client: OAuthClientInformationFull, params: AuthorizationParams
    ) -> str:
        allow_localhost = self._settings.oauth_allow_localhost
        if not redirect_uri_allowed(str(params.redirect_uri), allow_localhost=allow_localhost):
            raise AuthorizeError("invalid_request", "redirect_uri not allowed")
        now = time.time()
        assert client.client_id is not None  # noqa: S101 - always set for registered clients
        request_id = secrets.token_urlsafe(32)
        with self._lock:
            self._pending = {k: v for k, v in self._pending.items() if v.expires_at > now}
            if len(self._pending) >= MAX_PENDING_LOGINS:
                raise AuthorizeError("temporarily_unavailable", "Too many pending logins")
            self._pending[request_id] = PendingLogin(
                client.client_id, client.client_name or "", params, now + LOGIN_TTL
            )
        return construct_redirect_uri(
            f"{self._settings.public_url}{LOGIN_PATH}", request=request_id
        )

    def pending_login(self, request_id: str) -> PendingLogin | None:
        pending = self._pending.get(request_id)
        return pending if pending is not None and pending.expires_at > time.time() else None

    def login_request_valid(self, request_id: str) -> bool:
        return self.pending_login(request_id) is not None

    def locked_out(self, client_ip: str) -> bool:
        with self._lock:
            return self._recent_failures(client_ip, time.time()) >= MAX_FAILED_CODES_PER_IP

    def _recent_failures(self, client_ip: str, now: float) -> int:
        """Caller holds the lock. Also drops old entries (bounded memory)."""
        if len(self._failures) > 10_000:
            self._failures = {
                k: v for k, v in self._failures.items() if v and v[-1] > now - FAILED_CODES_WINDOW
            }
        window = self._failures.get(client_ip)
        if window is None:
            return 0
        while window and window[0] <= now - FAILED_CODES_WINDOW:
            window.popleft()
        return len(window)

    def complete_login(self, request_id: str, cg_code: str, client_ip: str = "?") -> str:
        """Check the CG code; return the redirect URL (to Claude) with a new authorization code."""
        with self._lock:
            if self._recent_failures(client_ip, time.time()) >= MAX_FAILED_CODES_PER_IP:
                raise TooManyFailures
            pending = self.pending_login(request_id)
            if pending is None:
                self._pending.pop(request_id, None)
                raise LoginRequestInvalid
        with self._sessions.begin() as session:
            user = user_for_secret(session, SecretKind.CG_CODE, cg_code.strip())
            with self._lock:
                if self._pending.get(request_id) is not pending:  # used up meanwhile
                    raise LoginRequestInvalid
                if user is None:
                    pending.attempts += 1
                    if pending.attempts >= MAX_LOGIN_ATTEMPTS:
                        del self._pending[request_id]
                    self._failures.setdefault(client_ip, deque()).append(time.time())
                    raise WrongCode
                del self._pending[request_id]
            if session.get(OAuthClient, pending.client_id) is None:
                raise LoginRequestInvalid
            code = new_secret()
            params = pending.params
            session.add(
                OAuthCode(
                    code_hash=hash_secret(code),
                    client_id=pending.client_id,
                    user_id=user.id,
                    scopes=" ".join(params.scopes or [SCOPE]),
                    code_challenge=params.code_challenge,
                    redirect_uri=str(params.redirect_uri),
                    redirect_uri_provided_explicitly=params.redirect_uri_provided_explicitly,
                    resource=params.resource,
                    expires_at=int(time.time()) + CODE_TTL,
                )
            )
        return construct_redirect_uri(str(params.redirect_uri), code=code, state=params.state)

    # --- codes and tokens ----------------------------------------------------------------

    async def load_authorization_code(
        self, client: OAuthClientInformationFull, authorization_code: str
    ) -> AuthorizationCode | None:
        code_hash = hash_secret(authorization_code)
        with self._sessions.begin() as session:
            used = session.get(OAuthToken, code_hash)
            if used is not None and used.kind == USED_CODE:
                # The code came back after it was exchanged: revoke what it was exchanged for.
                session.execute(delete(OAuthToken).where(OAuthToken.grant_id == used.grant_id))
                return None
        with self._sessions() as session:
            row = session.get(OAuthCode, code_hash)
            if row is None or row.client_id != client.client_id:
                return None
            return AuthorizationCode(
                code=authorization_code,
                scopes=row.scopes.split(),
                expires_at=row.expires_at,
                client_id=row.client_id,
                code_challenge=row.code_challenge,
                redirect_uri=AnyUrl(row.redirect_uri),
                redirect_uri_provided_explicitly=row.redirect_uri_provided_explicitly,
                resource=row.resource,
                subject=str(row.user_id),
            )

    async def exchange_authorization_code(
        self, client: OAuthClientInformationFull, authorization_code: AuthorizationCode
    ) -> TokenResponse:
        subject = authorization_code.subject
        code_hash = hash_secret(authorization_code.code)
        try:
            with self._sessions.begin() as session:
                # Atomic single use: only the request that really deletes the row gets tokens.
                deleted = session.execute(
                    delete(OAuthCode).where(
                        OAuthCode.code_hash == code_hash,
                        OAuthCode.client_id == client.client_id,
                    )
                )
                if _rowcount(deleted) != 1 or subject is None:
                    raise _Rejected
                grant_id = secrets.token_hex(16)
                tokens = self._issue(
                    session,
                    authorization_code.client_id,
                    int(subject),
                    authorization_code.scopes,
                    authorization_code.resource,
                    grant_id=grant_id,
                )
                session.add(
                    OAuthToken(
                        token_hash=code_hash,
                        kind=USED_CODE,
                        grant_id=grant_id,
                        client_id=authorization_code.client_id,
                        user_id=int(subject),
                        scopes=" ".join(authorization_code.scopes),
                        resource=authorization_code.resource,
                        expires_at=int(authorization_code.expires_at),
                    )
                )
                return tokens
        except _Rejected:
            pass
        raise TokenError("invalid_grant", "authorization code already used")

    async def load_refresh_token(
        self, client: OAuthClientInformationFull, refresh_token: str
    ) -> RefreshToken | None:
        with self._sessions.begin() as session:
            used = session.get(OAuthToken, hash_secret(refresh_token))
            if used is not None and used.kind == USED_REFRESH:
                # A rotated refresh token came back: it was copied. Revoke the whole grant.
                session.execute(delete(OAuthToken).where(OAuthToken.grant_id == used.grant_id))
                return None
        row = self._load_token(refresh_token, "refresh")
        if row is None or row.client_id != client.client_id:
            return None
        return RefreshToken(
            token=refresh_token,
            client_id=row.client_id,
            scopes=row.scopes.split(),
            expires_at=row.expires_at,
            resource=row.resource,
            subject=str(row.user_id),
        )

    async def exchange_refresh_token(
        self,
        client: OAuthClientInformationFull,
        refresh_token: RefreshToken,
        scopes: list[str],
    ) -> TokenResponse:
        # Rotation: the old refresh token is marked used (to detect replay) and the grant's
        # access tokens stop working. Atomic: only one request can mark it used.
        token_hash = hash_secret(refresh_token.token)
        try:
            with self._sessions.begin() as session:
                marked = session.execute(
                    update(OAuthToken)
                    .where(
                        OAuthToken.token_hash == token_hash,
                        OAuthToken.kind == "refresh",
                        OAuthToken.client_id == client.client_id,
                    )
                    .values(kind=USED_REFRESH)
                )
                row = session.get(OAuthToken, token_hash)
                if _rowcount(marked) != 1 or row is None:
                    raise _Rejected
                session.execute(
                    delete(OAuthToken).where(
                        OAuthToken.grant_id == row.grant_id, OAuthToken.kind == "access"
                    )
                )
                return self._issue(
                    session,
                    row.client_id,
                    row.user_id,
                    scopes or row.scopes.split(),
                    row.resource,
                    grant_id=row.grant_id,
                )
        except _Rejected:
            pass
        raise TokenError("invalid_grant", "refresh token is invalid")

    async def load_access_token(self, token: str) -> AccessToken | None:
        row = self._load_token(token, "access")
        if row is None:
            return None
        return AccessToken(
            token=token,
            client_id=row.client_id,
            scopes=row.scopes.split(),
            expires_at=row.expires_at,
            resource=row.resource,
            subject=str(row.user_id),
        )

    async def revoke_token(self, token: AccessToken | RefreshToken) -> None:
        with self._sessions.begin() as session:
            row = session.get(OAuthToken, hash_secret(token.token))
            if row is not None:
                session.execute(delete(OAuthToken).where(OAuthToken.grant_id == row.grant_id))

    def _load_token(self, token: str, kind: str) -> OAuthToken | None:
        with self._sessions() as session:
            row = session.get(OAuthToken, hash_secret(token))
            if row is None or row.kind != kind or row.expires_at <= time.time():
                return None
            return row

    def _issue(
        self,
        session: Session,
        client_id: str,
        user_id: int,
        scopes: list[str],
        resource: str | None,
        *,
        grant_id: str,
    ) -> TokenResponse:
        """Issue an access + refresh pair. `grant_id` stays the same across refresh rotations."""
        now = int(time.time())
        session.execute(delete(OAuthToken).where(OAuthToken.expires_at <= now))
        session.execute(delete(OAuthCode).where(OAuthCode.expires_at <= now))
        access, refresh = new_secret(), new_secret()
        for token, kind, ttl in ((access, "access", ACCESS_TTL), (refresh, "refresh", REFRESH_TTL)):
            session.add(
                OAuthToken(
                    token_hash=hash_secret(token),
                    kind=kind,
                    grant_id=grant_id,
                    client_id=client_id,
                    user_id=user_id,
                    scopes=" ".join(scopes),
                    resource=resource,
                    expires_at=now + ttl,
                )
            )
        return TokenResponse(
            access_token=access,
            token_type="Bearer",  # noqa: S106 - token type, not a secret
            expires_in=ACCESS_TTL,
            scope=" ".join(scopes),
            refresh_token=refresh,
        )


def _rowcount(result: object) -> int:
    """Rows affected by an UPDATE/DELETE (SQLAlchemy's CursorResult.rowcount)."""
    count = getattr(result, "rowcount", -1)
    return count if isinstance(count, int) else -1
