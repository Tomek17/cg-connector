"""Setting up a person from the app alone (no laptop visit): invite code -> app token,
Garmin login (with MFA) and the CG code for claude.ai. Contract: docs/api/README.md.

Garmin answers (wrong password, MFA, 429) come back as HTTP 200 with `status`, so the app
can show a message; 401 = bad app token, 422 = malformed request.
"""

import logging
import threading
from datetime import datetime
from enum import StrEnum
from typing import Annotated

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from cg_server import manual_sync
from cg_server.api import CurrentUser, DbSession, LocalTz
from cg_server.app_login import ALGORITHM, AppLogins, DecryptError, LoginKey
from cg_server.config import Settings
from cg_server.garmin import accounts
from cg_server.garmin.gateway import GarminAuthError, GarminRateLimited
from cg_server.join_page import invite_link
from cg_server.models import User, utcnow
from cg_server.outbox import GatewayFactory
from cg_server.user_secrets import INVITE_VALID_FOR, SecretKind, issue_secret, redeem_invite
from cg_server.users import UserError, add_user, get_user, is_admin

log = logging.getLogger(__name__)
_key_lock = threading.Lock()  # one key file even when the first requests come in together
router = APIRouter(prefix="/api/v1")


class LoginStatus(StrEnum):
    OK = "ok"
    NEEDS_MFA = "needs_mfa"
    BAD_CREDENTIALS = "bad_credentials"
    MFA_EXPIRED = "mfa_expired"
    RATE_LIMITED = "rate_limited"
    TOO_MANY_ATTEMPTS = "too_many_attempts"
    KEY_CHANGED = "key_changed"


class LoginKeyOut(BaseModel):
    """Public key for encrypting the Garmin password: RSA-OAEP, SHA-256 (also MGF1)."""

    algorithm: str = ALGORITHM
    public_key: str = Field(description="Base64 DER SubjectPublicKeyInfo")
    fingerprint: str = Field(description="SHA-256 klucza (hex), do przypięcia w aplikacji")


class InviteIn(BaseModel):
    code: str = Field(min_length=1, max_length=200)


class InviteOut(BaseModel):
    user: str
    app_token: str
    login_key: LoginKeyOut


class NewInviteIn(BaseModel):
    name: str = Field(min_length=1, max_length=32, description="Nazwa konta: małe litery, cyfry")


class NewInviteOut(BaseModel):
    name: str
    link: str = Field(description="Link zaproszenia do wysłania tej osobie (jednorazowy)")
    new_user: bool = Field(description="false = istniejące konto, poprzedni link przestał działać")
    expires_at: datetime


class GarminLoginIn(BaseModel):
    email: str = Field(min_length=3, max_length=200)
    password_encrypted: str = Field(min_length=1, max_length=2000, description="Base64")


class MfaIn(BaseModel):
    code: str = Field(min_length=1, max_length=20)


class GarminLoginOut(BaseModel):
    """`ok`: połączono, pierwsze pobranie danych ruszyło w tle. `needs_mfa`: wyślij kod
    z Garmina na /garmin/mfa (ważne 10 min). Pozostałe: komunikat dla użytkownika;
    `retry_at`, gdy wiadomo, od kiedy można spróbować ponownie."""

    status: LoginStatus
    retry_at: datetime | None = None
    sync_started: bool = False


class ClaudeCodeOut(BaseModel):
    code: str


def _settings(request: Request) -> Settings:
    settings: Settings = request.app.state.settings
    return settings


def _login_key(request: Request) -> LoginKey:
    key: LoginKey | None = getattr(request.app.state, "login_key", None)
    if key is not None:
        return key
    with _key_lock:
        existing: LoginKey | None = getattr(request.app.state, "login_key", None)
        if existing is not None:
            return existing
        settings = _settings(request)
        try:
            key = LoginKey.load_or_create(settings.data_dir, settings.encryption_key)
        except DecryptError as e:
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Serwer bez klucza") from e
        request.app.state.login_key = key
    return key


def _logins(request: Request) -> AppLogins:
    logins: AppLogins = request.app.state.app_logins
    return logins


Key = Annotated[LoginKey, Depends(_login_key)]
Logins = Annotated[AppLogins, Depends(_logins)]


def _key_out(key: LoginKey) -> LoginKeyOut:
    return LoginKeyOut(public_key=key.public_key_b64, fingerprint=key.fingerprint)


@router.post("/invite/redeem", responses={404: {"description": "Zły albo zużyty kod"}})
def redeem(body: InviteIn, session: DbSession, key: Key) -> InviteOut:
    """Wymienia jednorazowy kod zaproszenia (`cgi_…`, ważny 7 dni) na token aplikacji."""
    result = redeem_invite(session, body.code.strip())
    if result is None:
        raise HTTPException(
            status.HTTP_404_NOT_FOUND, "Kod zaproszenia jest nieprawidłowy, zużyty albo wygasł"
        )
    user, token = result
    return InviteOut(user=user.name, app_token=token, login_key=_key_out(key))


@router.post(
    "/invites",
    status_code=status.HTTP_201_CREATED,
    responses={
        403: {"description": "Tylko administrator zaprasza"},
        409: {"description": "Własne konto"},
        422: {"description": "Zła nazwa"},
    },
)
def create_invite(
    body: NewInviteIn, request: Request, user: CurrentUser, session: DbSession, tz: LocalTz
) -> NewInviteOut:
    """Link zaproszenia dla nowej osoby (albo nowy dla istniejącej) prosto z aplikacji Tomka."""
    settings = _settings(request)
    if not is_admin(session, settings, user):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Zapraszać może tylko administrator")
    name = body.name.strip().lower()
    target = get_user(session, name)
    if target is not None and target.id == user.id:
        raise HTTPException(status.HTTP_409_CONFLICT, "To Twoje konto")
    new_user = target is None
    if target is None:
        try:
            target = add_user(session, name)
        except UserError as e:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, str(e)) from e
    code = issue_secret(session, target, SecretKind.INVITE)
    log.info("Zaproszenie z aplikacji dla użytkownika %s", target.id)
    return NewInviteOut(
        name=target.name,
        link=invite_link(settings, code),
        new_user=new_user,
        expires_at=(utcnow() + INVITE_VALID_FOR).astimezone(tz),
    )


@router.get("/garmin/login-key")
def login_key(_user: CurrentUser, key: Key) -> LoginKeyOut:
    """Klucz publiczny do zaszyfrowania hasła Garmina w aplikacji."""
    return _key_out(key)


def _start_sync(request: Request, tasks: BackgroundTasks, user: User, session: DbSession) -> bool:
    runner: manual_sync.ManualSync | None = getattr(request.app.state, "manual_sync", None)
    if runner is None or manual_sync.check(session, user).refusal is not None:
        return False
    tasks.add_task(runner.run, user.id)
    return True


def _garmin_step(
    request: Request,
    tasks: BackgroundTasks,
    user: User,
    session: DbSession,
    tz: LocalTz,
    logins: AppLogins,
    *,
    password: str | None = None,
    email: str = "",
    mfa_code: str | None = None,
) -> GarminLoginOut:
    """One password or MFA step against Garmin, with the shared limits and bookkeeping."""
    settings = _settings(request)
    until = accounts.rate_limit_cooldown_until(user)
    if until is not None:
        return GarminLoginOut(status=LoginStatus.RATE_LIMITED, retry_at=until.astimezone(tz))
    blocked = logins.blocked_until(user.id)
    if blocked is not None:
        return GarminLoginOut(status=LoginStatus.TOO_MANY_ATTEMPTS, retry_at=blocked.astimezone(tz))
    factory: GatewayFactory = request.app.state.gateway_factory
    gateway = factory()
    try:
        if mfa_code is None:
            assert password is not None  # noqa: S101 - one of the two is always given
            mfa = accounts.start_login(
                session, user, gateway, email=email, password=password, key=settings.encryption_key
            )
            if mfa is not None:
                logins.set_mfa(user.id, mfa)
                return GarminLoginOut(status=LoginStatus.NEEDS_MFA)
        else:
            state = logins.take_mfa(user.id)
            if state is None:
                return GarminLoginOut(status=LoginStatus.MFA_EXPIRED)
            accounts.finish_login(
                session,
                user,
                gateway,
                mfa_state=state,
                mfa_code=mfa_code,
                key=settings.encryption_key,
            )
    except GarminRateLimited:
        logins.clear(user.id)
        return GarminLoginOut(status=LoginStatus.RATE_LIMITED)
    except accounts.RateLimitCooldown as e:
        return GarminLoginOut(status=LoginStatus.RATE_LIMITED, retry_at=e.until.astimezone(tz))
    except GarminAuthError:
        logins.record_failure(user.id)
        log.info("Logowanie do Garmina z aplikacji nieudane (użytkownik %s)", user.id)
        return GarminLoginOut(status=LoginStatus.BAD_CREDENTIALS)
    logins.clear(user.id)
    return GarminLoginOut(
        status=LoginStatus.OK, sync_started=_start_sync(request, tasks, user, session)
    )


@router.post("/garmin/login")
def garmin_login(
    body: GarminLoginIn,
    request: Request,
    tasks: BackgroundTasks,
    user: CurrentUser,
    session: DbSession,
    tz: LocalTz,
    key: Key,
    logins: Logins,
) -> GarminLoginOut:
    """Loguje serwer do Garmin Connect hasłem zaszyfrowanym kluczem z /garmin/login-key.
    Hasło nie jest zapisywane; serwer trzyma tylko zaszyfrowane tokeny Garmina."""
    try:
        password = key.decrypt(body.password_encrypted)
    except DecryptError:
        return GarminLoginOut(status=LoginStatus.KEY_CHANGED)
    return _garmin_step(
        request, tasks, user, session, tz, logins, password=password, email=body.email.strip()
    )


@router.post("/garmin/mfa")
def garmin_mfa(
    body: MfaIn,
    request: Request,
    tasks: BackgroundTasks,
    user: CurrentUser,
    session: DbSession,
    tz: LocalTz,
    logins: Logins,
) -> GarminLoginOut:
    """Drugi krok logowania: kod MFA od Garmina (jedna próba na logowanie)."""
    return _garmin_step(request, tasks, user, session, tz, logins, mfa_code=body.code.strip())


@router.post("/claude-code")
def claude_code(user: CurrentUser, session: DbSession) -> ClaudeCodeOut:
    """Nowy kod CG (`cgc_…`) do podłączenia konektora w claude.ai. Unieważnia poprzedni kod
    i rozłącza dotychczasowe połączenia Claude tego użytkownika."""
    return ClaudeCodeOut(code=issue_secret(session, user, SecretKind.CG_CODE))
