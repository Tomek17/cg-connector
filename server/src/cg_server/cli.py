"""Command line entry point (`cg-server ...`). Messages for the operator are in Polish."""

import argparse
import getpass
import sys
from collections.abc import Callable
from datetime import datetime
from typing import Any

import requests
import uvicorn
from curl_cffi.curl import CurlError
from garminconnect import GarminConnectConnectionError
from sqlalchemy.orm import Session

from cg_server.config import ConfigError, Settings, get_settings
from cg_server.crypto import CryptoError, generate_key, validate_key
from cg_server.db import open_db
from cg_server.garmin import accounts
from cg_server.garmin.gateway import (
    GarminAuthError,
    GarminError,
    GarminGateway,
    GarminRateLimited,
    RealGarminGateway,
)
from cg_server.logging_filter import uvicorn_log_config
from cg_server.models import User, utcnow
from cg_server.user_secrets import SecretKind, issue_secret
from cg_server.users import UserError, add_user, get_user

GatewayFactory = Callable[[], GarminGateway]

RATE_LIMITED_MSG = (
    "Garmin odrzucił zapytanie: zbyt wiele prób (HTTP 429). Nie ponawiaj teraz, "
    "spróbuj za kilka godzin (najlepiej jutro)."
)


WAIT_HINT = "Jeśli niedawno był błąd 429, odczekaj 24 h przed ponownym logowaniem hasłem."


# Network/server failures talking to Garmin. Their text may contain response bodies,
# so only a short generic message is shown.
GARMIN_CONNECTION_ERRORS = (
    GarminConnectConnectionError,
    GarminError,
    requests.RequestException,
    CurlError,
)


# Port 8000 is published only on 127.0.0.1 and reached from the internet only through the
# Tailscale container, which sets X-Forwarded-For to the real client IP. Trusting it makes the
# rate limit per real client instead of per proxy.
SERVE_OPTIONS: dict[str, Any] = {
    "factory": True,
    "proxy_headers": True,
    "forwarded_allow_ips": "*",
}


class CliError(Exception):
    """Expected failure; the message is printed to stderr and the exit code is 1."""


def _require_user(session: Session, name: str) -> User:
    user = get_user(session, name)
    if user is None:
        raise CliError(f"Nie ma użytkownika „{name}”. Dodaj go: cg-server user-add --name {name}")
    return user


def _require_key(settings: Settings) -> str:
    validate_key(settings.encryption_key)  # fail before contacting Garmin
    return settings.encryption_key


def cmd_user_add(settings: Settings, name: str) -> None:
    with open_db(settings)() as session:
        add_user(session, name)
    print(f"Dodano użytkownika „{name}”.")


SECRET_INFO = {
    SecretKind.CG_CODE: (
        "Osobisty kod CG-connector",
        "Wklej go na stronie logowania przy dodawaniu konektora w claude.ai.",
    ),
    SecretKind.APP_TOKEN: (
        "Token aplikacji Android",
        "Wpisz go w ustawieniach aplikacji CG-connector na telefonie.",
    ),
}


def cmd_issue_secret(settings: Settings, name: str, kind: SecretKind) -> None:
    with open_db(settings)() as session:
        user = _require_user(session, name)
        secret = issue_secret(session, user, kind)
    title, hint = SECRET_INFO[kind]
    print(f"{title} dla „{name}” (pokazywany tylko raz, zapisz go w bezpiecznym miejscu):")
    print()
    print(f"    {secret}")
    print()
    print(hint)
    print("Poprzedni kod/token (jeśli był) przestał działać.")
    if kind is SecretKind.CG_CODE:
        print("Wcześniejsze połączenia claude.ai zostały rozłączone; połącz konektor ponownie.")


def _cooldown_message(until: datetime) -> str:
    minutes = max(1, int((until - utcnow()).total_seconds() // 60))
    return (
        f"Garmin niedawno odrzucił zapytanie (HTTP 429). Logowanie hasłem wstrzymane "
        f"jeszcze przez ok. {minutes // 60} h {minutes % 60} min, żeby nie ryzykować "
        "blokady konta. Jeśli musisz, dodaj --force."
    )


def cmd_garmin_login(
    settings: Settings, name: str, gateway: GarminGateway, *, force: bool = False
) -> None:
    key = _require_key(settings)
    with open_db(settings)() as session:
        user = _require_user(session, name)
        until = accounts.rate_limit_cooldown_until(user)
        if until is not None and not force:
            raise CliError(_cooldown_message(until))
        _login(session, user, gateway, key, force=force)
    print("Zalogowano do Garmin Connect. Tokeny zapisane (zaszyfrowane).")


def _login(session: Session, user: User, gateway: GarminGateway, key: str, *, force: bool) -> None:
    name = user.name
    print(f"Logowanie do Garmin Connect dla użytkownika „{name}”.")
    print("Hasło nie jest nigdzie zapisywane; zapisujemy tylko zaszyfrowane tokeny.")
    email = input("E-mail konta Garmin: ").strip()
    password = getpass.getpass("Hasło Garmin (niewidoczne): ")
    try:
        accounts.login(
            session,
            user,
            gateway,
            email=email,
            password=password,
            prompt_mfa=lambda: input("Kod MFA z Garmina: ").strip(),
            key=key,
            force=force,
        )
    except accounts.RateLimitCooldown as e:
        raise CliError(_cooldown_message(e.until)) from e
    except GarminRateLimited as e:
        raise CliError(RATE_LIMITED_MSG) from e
    except GarminAuthError as e:
        msg = f"Logowanie nieudane: błędny e-mail, hasło albo kod MFA. {WAIT_HINT}"
        if accounts.has_tokens(user):
            msg += (
                " Wcześniej zapisane tokeny pozostały bez zmian; sprawdź je: "
                f"cg-server garmin-check --user {user.name}"
            )
        raise CliError(msg) from e


def cmd_garmin_check(settings: Settings, name: str, gateway: GarminGateway) -> None:
    key = _require_key(settings)
    relogin = f"Uruchom: cg-server garmin-login --user {name}"
    with open_db(settings)() as session:
        user = _require_user(session, name)
        try:
            full_name = accounts.check(session, user, gateway, key=key)
        except accounts.NotLoggedIn as e:
            raise CliError(f"Brak tokenów Garmina dla „{name}”. {relogin}") from e
        except GarminRateLimited as e:
            raise CliError(RATE_LIMITED_MSG) from e
        except GarminAuthError as e:
            raise CliError(
                f"Tokeny Garmina wygasły lub zostały odrzucone. {relogin}. {WAIT_HINT}"
            ) from e
    print(f"OK, zalogowano jako {full_name or '(brak nazwy w profilu Garmin)'}.")


def main(
    argv: list[str] | None = None, *, gateway_factory: GatewayFactory = RealGarminGateway
) -> int:
    parser = argparse.ArgumentParser(prog="cg-server")
    sub = parser.add_subparsers(dest="command", required=True)
    serve = sub.add_parser("serve", help="uruchom serwer HTTP")
    serve.add_argument("--host", default="0.0.0.0")  # noqa: S104 - inside container only
    serve.add_argument("--port", type=int, default=8000)
    sub.add_parser("gen-key", help="wypisz nowy klucz szyfrowania (CG_ENCRYPTION_KEY)")
    sub.add_parser("user-add", help="dodaj użytkownika").add_argument("--name", required=True)
    garmin_login = sub.add_parser(
        "garmin-login", help="zaloguj użytkownika do Garmin Connect (jednorazowo, hasło + MFA)"
    )
    garmin_login.add_argument("--user", required=True)
    garmin_login.add_argument(
        "--force", action="store_true", help="loguj mimo błędu 429 w ciągu ostatnich 24 h"
    )
    sub.add_parser(
        "garmin-check", help="sprawdź zapisane tokeny Garmina i odśwież je"
    ).add_argument("--user", required=True)
    sub.add_parser(
        "user-code", help="wygeneruj nowy osobisty kod CG (logowanie konektora w claude.ai)"
    ).add_argument("--user", required=True)
    sub.add_parser("app-token", help="wygeneruj nowy token aplikacji Android").add_argument(
        "--user", required=True
    )
    args = parser.parse_args(argv)

    try:
        if args.command == "serve":
            get_settings().check_public_host()
            uvicorn.run(
                "cg_server.app:create_app",
                host=args.host,
                port=args.port,
                log_config=uvicorn_log_config(),
                **SERVE_OPTIONS,
            )
        elif args.command == "gen-key":
            print(generate_key())
        elif args.command == "user-add":
            cmd_user_add(get_settings(), args.name)
        elif args.command == "user-code":
            cmd_issue_secret(get_settings(), args.user, SecretKind.CG_CODE)
        elif args.command == "app-token":
            cmd_issue_secret(get_settings(), args.user, SecretKind.APP_TOKEN)
        elif args.command == "garmin-login":
            cmd_garmin_login(get_settings(), args.user, gateway_factory(), force=args.force)
        elif args.command == "garmin-check":
            cmd_garmin_check(get_settings(), args.user, gateway_factory())
    except (CliError, UserError, CryptoError, ConfigError) as e:
        print(f"Błąd: {e}", file=sys.stderr)
        return 1
    except GARMIN_CONNECTION_ERRORS:
        print(
            "Błąd: Błąd połączenia z Garmin Connect (sieć albo serwer Garmina). Spróbuj później.",
            file=sys.stderr,
        )
        return 1
    return 0
