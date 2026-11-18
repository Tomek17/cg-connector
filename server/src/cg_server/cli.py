"""Command line entry point (`cg-server ...`). Messages for the operator are in Polish."""

import argparse
import getpass
import sys
from collections.abc import Callable

import uvicorn
from sqlalchemy.orm import Session

from cg_server.config import Settings, get_settings
from cg_server.crypto import CryptoError, generate_key, validate_key
from cg_server.db import open_db
from cg_server.garmin import accounts
from cg_server.garmin.gateway import (
    GarminAuthError,
    GarminGateway,
    GarminRateLimited,
    RealGarminGateway,
)
from cg_server.models import User
from cg_server.users import UserError, add_user, get_user

GatewayFactory = Callable[[], GarminGateway]

RATE_LIMITED_MSG = (
    "Garmin odrzucił zapytanie: zbyt wiele prób (HTTP 429). Nie ponawiaj teraz, "
    "spróbuj za kilka godzin (najlepiej jutro)."
)


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


def cmd_garmin_login(settings: Settings, name: str, gateway: GarminGateway) -> None:
    key = _require_key(settings)
    with open_db(settings)() as session:
        user = _require_user(session, name)
        _login(session, user, gateway, key)
    print("Zalogowano do Garmin Connect. Tokeny zapisane (zaszyfrowane).")


def _login(session: Session, user: User, gateway: GarminGateway, key: str) -> None:
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
        )
    except GarminRateLimited as e:
        raise CliError(RATE_LIMITED_MSG) from e
    except GarminAuthError as e:
        raise CliError("Logowanie nieudane: błędny e-mail, hasło albo kod MFA.") from e


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
            raise CliError(f"Tokeny Garmina wygasły lub zostały odrzucone. {relogin}") from e
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
    for command, help_text in [
        ("garmin-login", "zaloguj użytkownika do Garmin Connect (jednorazowo, hasło + MFA)"),
        ("garmin-check", "sprawdź zapisane tokeny Garmina i odśwież je"),
    ]:
        sub.add_parser(command, help=help_text).add_argument("--user", required=True)
    args = parser.parse_args(argv)

    try:
        if args.command == "serve":
            uvicorn.run("cg_server.app:app", host=args.host, port=args.port, proxy_headers=True)
        elif args.command == "gen-key":
            print(generate_key())
        elif args.command == "user-add":
            cmd_user_add(get_settings(), args.name)
        elif args.command == "garmin-login":
            cmd_garmin_login(get_settings(), args.user, gateway_factory())
        elif args.command == "garmin-check":
            cmd_garmin_check(get_settings(), args.user, gateway_factory())
    except (CliError, UserError, CryptoError) as e:
        print(f"Błąd: {e}", file=sys.stderr)
        return 1
    return 0
