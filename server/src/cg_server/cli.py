"""Command line entry point (`cg-server ...`). Messages for the operator are in Polish."""

import argparse
import getpass
import json
import sys
import tempfile
from collections.abc import Callable
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import requests
import uvicorn
from curl_cffi.curl import CurlError
from garminconnect import GarminConnectConnectionError
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from cg_server import workouts
from cg_server.app import create_app
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
from cg_server.models import (
    Activity,
    ScheduleStatus,
    SyncRun,
    SyncStatus,
    User,
    WorkoutOrigin,
    utcnow,
)
from cg_server.outbox import process_user_outbox
from cg_server.sync import SyncOptions, sync_user
from cg_server.synclock import SyncBusy
from cg_server.user_secrets import SecretKind, issue_secret
from cg_server.users import UserError, add_user, get_user
from cg_server.workout_definition import preview

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


def _show_secret_once(secret: str) -> None:
    # Intentional: the freshly generated secret is shown to the operator exactly once, on the
    # terminal (stdout), so they can hand it to the user. It is not logged and not stored
    # anywhere in plaintext (the database keeps only its hash).
    sys.stdout.write(f"\n    {secret}\n\n")
    sys.stdout.flush()


def cmd_issue_secret(settings: Settings, name: str, kind: SecretKind) -> None:
    with open_db(settings)() as session:
        user = _require_user(session, name)
        secret = issue_secret(session, user, kind)
    title, hint = SECRET_INFO[kind]
    print(f"{title} dla „{name}” (pokazywany tylko raz, zapisz go w bezpiecznym miejscu):")
    _show_secret_once(secret)
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


def cmd_sync(settings: Settings, name: str, gateway: GarminGateway) -> None:
    key = _require_key(settings)
    with open_db(settings)() as session:
        user = _require_user(session, name)
        print(f"Synchronizacja danych Garmin dla „{name}”…")
        try:
            result = sync_user(
                session,
                user,
                gateway,
                key=key,
                options=SyncOptions.from_settings(settings),
                lock_dir=settings.data_dir,
            )
        except SyncBusy as e:
            raise CliError(
                "Synchronizacja już trwa (serwer albo inne polecenie). Spróbuj za chwilę."
            ) from e
    summary = (
        f"Status: {result.status.value}; nowe aktywności: {result.activities_new}, "
        f"zaktualizowane: {result.activities_updated}, wpisy VO2max: {result.vo2max_points}."
    )
    if result.status != SyncStatus.OK:
        raise CliError(f"{summary} {result.error or ''}".strip())
    print(summary)


TEST_WORKOUTS: dict[str, dict[str, Any]] = {
    "run": {
        "sport": "running",
        "name": "Test bieg",
        "description": "Trening testowy CG-connector",
        "steps": [
            {"type": "warmup", "seconds": 300},
            {
                "type": "repeat",
                "times": 3,
                "steps": [
                    {
                        "type": "interval",
                        "distance_km": 0.4,
                        "target": {"type": "pace", "from": "5:30", "to": "5:00"},
                    },
                    {"type": "recovery", "seconds": 90, "target": {"type": "hr_zone", "zone": 1}},
                ],
            },
            {"type": "cooldown", "lap_button": True},
        ],
    },
    "strength": {
        "sport": "strength",
        "name": "Test siła",
        "description": "Trening testowy CG-connector",
        "exercises": [
            {
                "exercise": "Barbell Back Squat",
                "sets": 3,
                "reps": 5,
                "weight_kg": 20,
                "rest_seconds": 90,
            },
            {"exercise": "Plank", "sets": 2, "seconds": 30, "rest_seconds": 60},
        ],
    },
}


def cmd_workout_test(
    settings: Settings, name: str, day: str, kind: str, gateway: GarminGateway
) -> None:
    """Create a small test workout and send it to the Garmin calendar now (watch spike)."""
    key = _require_key(settings)
    try:
        when = date.fromisoformat(day)
    except ValueError:
        raise CliError("Data w formacie RRRR-MM-DD, np. 2026-11-20.") from None
    today = datetime.now(ZoneInfo(settings.timezone)).date()
    with open_db(settings)() as session:
        user = _require_user(session, name)
        definition = TEST_WORKOUTS[kind]
        try:
            workout, _ = workouts.create_workout(
                session, user, definition, origin=WorkoutOrigin.APP
            )
            item, _ = workouts.schedule_workout(session, user, workout.id, when, today=today)
        except workouts.WorkoutError as e:
            raise CliError(f"Nie można zaplanować: {e}") from e
        print(preview(workouts.definition_of(workout)))
        try:
            process_user_outbox(
                session,
                user,
                gateway,
                key=key,
                delay_s=settings.sync_request_delay_s,
                lock_dir=settings.data_dir,
                timezone=settings.timezone,
            )
        except SyncBusy as e:
            raise CliError(
                "Trwa synchronizacja z Garminem; trening czeka w kolejce i zostanie wysłany "
                "automatycznie."
            ) from e
        session.refresh(item)
        print(f"Status: {item.status.value} (id wpisu: {item.id}, data: {item.date}).")
        if item.status != ScheduleStatus.SENT:
            raise CliError(item.last_error or "Trening nie został jeszcze wysłany.")
    print("Na zegarku: zsynchronizuj go z aplikacją Garmin Connect i sprawdź kalendarz.")


def _local(value: datetime | None, tz: ZoneInfo) -> str:
    if value is None:
        return "nigdy"
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.astimezone(tz).strftime("%Y-%m-%d %H:%M")


def cmd_sync_status(settings: Settings) -> None:
    tz = ZoneInfo(settings.timezone)
    with open_db(settings)() as session:
        users = session.scalars(select(User).order_by(User.name)).all()
        if not users:
            print("Brak użytkowników. Dodaj: cg-server user-add --name <nazwa>")
        for user in users:
            last = session.scalars(
                select(SyncRun)
                .where(SyncRun.user_id == user.id)
                .order_by(SyncRun.started_at.desc(), SyncRun.id.desc())
                .limit(1)
            ).one_or_none()
            last_ok = session.scalar(
                select(func.max(SyncRun.finished_at)).where(
                    SyncRun.user_id == user.id, SyncRun.status == SyncStatus.OK
                )
            )
            activities = session.scalar(
                select(func.count()).select_from(Activity).where(Activity.user_id == user.id)
            )
            garmin = user.garmin_credentials.status.value if user.garmin_credentials else "brak"
            line = f"{user.name}: Garmin: {garmin}; ostatni sync: "
            if last is None:
                line += "nigdy"
            else:
                line += f"{_local(last.started_at, tz)} ({last.status.value})"
                if last.error:
                    line += f" – {last.error}"
            line += f"; ostatni udany: {_local(last_ok, tz)}; aktywności: {activities}"
            print(line)


def cmd_export_openapi(settings: Settings, out: Path) -> None:
    """Write the REST contract (OpenAPI, as generated by FastAPI) to `out`.

    The app is built with a throw-away database in a temporary directory, so this works
    anywhere (CI, laptop) without touching real data; CG_ENV=test exposes the schema."""
    if settings.is_production:
        raise CliError("Eksport OpenAPI tylko poza produkcją: uruchom z CG_ENV=test.")
    with tempfile.TemporaryDirectory() as tmp:
        app = create_app(settings.model_copy(update={"data_dir": Path(tmp)}))
        spec = app.openapi()
    out.write_text(json.dumps(spec, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Zapisano kontrakt API: {out}")


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
    sub.add_parser("sync", help="pobierz teraz dane z Garmin Connect").add_argument(
        "--user", required=True
    )
    sub.add_parser("sync-status", help="pokaż stan synchronizacji wszystkich użytkowników")
    workout_test = sub.add_parser(
        "workout-test", help="utwórz i wyślij do kalendarza Garmin mały trening testowy"
    )
    workout_test.add_argument("--user", required=True)
    workout_test.add_argument("--date", required=True, help="RRRR-MM-DD")
    workout_test.add_argument("--kind", choices=sorted(TEST_WORKOUTS), default="run")
    sub.add_parser(
        "export-openapi", help="zapisz kontrakt REST API (OpenAPI JSON); wymaga CG_ENV=test"
    ).add_argument("path", type=Path)
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
        elif args.command == "sync":
            cmd_sync(get_settings(), args.user, gateway_factory())
        elif args.command == "sync-status":
            cmd_sync_status(get_settings())
        elif args.command == "workout-test":
            cmd_workout_test(get_settings(), args.user, args.date, args.kind, gateway_factory())
        elif args.command == "export-openapi":
            cmd_export_openapi(get_settings(), args.path)
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
