"""Minimal OAuth 2.1 authorization server (DCR + PKCE) for the claude.ai connector (ADR-0003).

Built on the `mcp` SDK: the SDK serves the metadata, /authorize, /token, /register and /revoke
endpoints and verifies PKCE and redirect URIs; this provider stores clients, codes and tokens
in SQLite (hashes only) and lets the user log in with the personal CG code.
"""

import secrets
import time
from dataclasses import dataclass
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
from sqlalchemy import delete
from sqlalchemy.orm import Session, sessionmaker

from cg_server.config import Settings
from cg_server.crypto import decrypt, encrypt
from cg_server.models import OAuthClient, OAuthCode, OAuthToken
from cg_server.user_secrets import SecretKind, hash_secret, new_secret, user_for_secret

SCOPE = "cg"
CLAUDE_CALLBACKS = (
    "https://claude.ai/api/mcp/auth_callback",
    "https://claude.com/api/mcp/auth_callback",
)
LOGIN_PATH = "/authorize/login"
ACCESS_TTL = 60 * 60
REFRESH_TTL = 60 * 24 * 60 * 60
CODE_TTL = 5 * 60
LOGIN_TTL = 10 * 60
MAX_LOGIN_ATTEMPTS = 5
MAX_PENDING_LOGINS = 1000


class LoginRequestInvalid(Exception):
    """Unknown, expired or used-up login request."""


class WrongCode(Exception):
    """The CG code does not match any user."""


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
        with self._sessions.begin() as session:
            session.add(
                OAuthClient(
                    client_id=client_info.client_id,
                    metadata_json=public_info.model_dump_json(),
                    client_secret_encrypted=(
                        encrypt(secret, self._settings.encryption_key) if secret else None
                    ),
                )
            )

    # --- browser login with the CG code --------------------------------------------------

    async def authorize(
        self, client: OAuthClientInformationFull, params: AuthorizationParams
    ) -> str:
        allow_localhost = self._settings.oauth_allow_localhost
        if not redirect_uri_allowed(str(params.redirect_uri), allow_localhost=allow_localhost):
            raise AuthorizeError("invalid_request", "redirect_uri not allowed")
        now = time.time()
        self._pending = {k: v for k, v in self._pending.items() if v.expires_at > now}
        if len(self._pending) >= MAX_PENDING_LOGINS:
            raise AuthorizeError("temporarily_unavailable", "Too many pending logins")
        assert client.client_id is not None  # noqa: S101 - always set for registered clients
        request_id = secrets.token_urlsafe(32)
        self._pending[request_id] = PendingLogin(client.client_id, params, now + LOGIN_TTL)
        return construct_redirect_uri(
            f"{self._settings.public_url}{LOGIN_PATH}", request=request_id
        )

    def login_request_valid(self, request_id: str) -> bool:
        pending = self._pending.get(request_id)
        return pending is not None and pending.expires_at > time.time()

    def complete_login(self, request_id: str, cg_code: str) -> str:
        """Check the CG code; return the redirect URL (to Claude) with a new authorization code."""
        if not self.login_request_valid(request_id):
            self._pending.pop(request_id, None)
            raise LoginRequestInvalid
        pending = self._pending[request_id]
        with self._sessions.begin() as session:
            user = user_for_secret(session, SecretKind.CG_CODE, cg_code.strip())
            if user is None:
                pending.attempts += 1
                if pending.attempts >= MAX_LOGIN_ATTEMPTS:
                    del self._pending[request_id]
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
        with self._sessions() as session:
            row = session.get(OAuthCode, hash_secret(authorization_code))
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
        with self._sessions.begin() as session:
            row = session.get(OAuthCode, hash_secret(authorization_code.code))
            if row is None:  # used concurrently
                raise TokenError("invalid_grant", "authorization code already used")
            session.delete(row)
            return self._issue(
                session, row.client_id, row.user_id, row.scopes.split(), row.resource
            )

    async def load_refresh_token(
        self, client: OAuthClientInformationFull, refresh_token: str
    ) -> RefreshToken | None:
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
        # Rotation: the old refresh token and its access token stop working.
        with self._sessions.begin() as session:
            row = session.get(OAuthToken, hash_secret(refresh_token.token))
            if row is None or row.kind != "refresh":
                raise TokenError("invalid_grant", "refresh token is invalid")
            session.execute(delete(OAuthToken).where(OAuthToken.grant_id == row.grant_id))
            return self._issue(
                session, row.client_id, row.user_id, scopes or row.scopes.split(), row.resource
            )

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
    ) -> TokenResponse:
        now = int(time.time())
        session.execute(delete(OAuthToken).where(OAuthToken.expires_at <= now))
        session.execute(delete(OAuthCode).where(OAuthCode.expires_at <= now))
        grant_id = secrets.token_hex(16)
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
