# /// script
# requires-python = ">=3.12"
# dependencies = ["garminconnect[workout]==0.3.20"]
# ///
"""Test przed przeprowadzką serwera: czy Garmin wpuszcza z IP serwerowni (VPS)?

Uruchom NA VPS (instrukcja: docs/test-vps.md):

    uv run test_garmin_vps.py

Kroki (dokładnie te, których serwer CG-connector używa na co dzień):
  1. logowanie hasłem + kod MFA (tak loguje się nowa osoba z aplikacji),
  2. wznowienie sesji z samych tokenów (tak działa codzienna synchronizacja),
  3. pobranie aktywności z 14 dni i VO2max z 30 dni,
  4. wysłanie testowego treningu do kalendarza Garmina, a potem jego usunięcie.

Bezpieczeństwo:
  - hasło wpisujesz ręcznie (niewidoczne), skrypt NIC nie zapisuje na dysku:
    ani hasła, ani tokenów (zmienna GARMINTOKENS jest czyszczona),
  - jedno logowanie na uruchomienie, bez ponawiania. Po HTTP 429 (zbyt wiele
    zapytań) skrypt od razu kończy pracę: odczekaj 24 h, inaczej Garmin może
    zablokować konto,
  - testowy trening ma nazwę „CG TEST VPS (usuń)” i jest usuwany na końcu.
"""

import getpass
import os
import sys
from datetime import date, timedelta
from typing import Any

from garminconnect import (
    Garmin,
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectTooManyRequestsError,
)
from garminconnect.workout import (
    RunningWorkout,
    SportType,
    WorkoutSegment,
    create_cooldown_step,
    create_warmup_step,
)

TEST_WORKOUT_NAME = "CG TEST VPS (usuń)"
SCHEDULE_ID_FIELDS = ("workoutScheduleId", "scheduleId", "id")

results: list[tuple[str, bool, str]] = []


class RateLimited(Exception):
    """HTTP 429: przerywamy wszystko."""


def status_of(e: BaseException) -> int | None:
    return getattr(getattr(e, "response", None), "status_code", None)


def step(name: str, fn: Any) -> Any:
    """Wykonaj krok, zapisz wynik. 429 przerywa cały test."""
    print(f"\n▶ {name}…", flush=True)
    try:
        value, detail = fn()
    except GarminConnectTooManyRequestsError as e:
        results.append((name, False, "HTTP 429 – zbyt wiele zapytań"))
        raise RateLimited from e
    except GarminConnectConnectionError as e:
        if status_of(e) == 429:
            results.append((name, False, "HTTP 429 – zbyt wiele zapytań"))
            raise RateLimited from e
        detail = f"błąd połączenia (HTTP {status_of(e) or '?'}): {e}"
        results.append((name, False, detail))
        print(f"  ✗ {detail}")
        return None
    except GarminConnectAuthenticationError as e:
        detail = f"odmowa logowania: {e}"
        results.append((name, False, detail))
        print(f"  ✗ {detail}")
        return None
    except Exception as e:  # noqa: BLE001 - test ma raportować wszystko
        detail = f"{type(e).__name__}: {e}"
        results.append((name, False, detail))
        print(f"  ✗ {detail}")
        return None
    results.append((name, True, detail))
    print(f"  ✓ {detail}")
    return value


def test_workout() -> RunningWorkout:
    steps = [
        create_warmup_step(300.0, step_order=1),
        create_cooldown_step(300.0, step_order=2),
    ]
    return RunningWorkout(
        workoutName=TEST_WORKOUT_NAME,
        estimatedDurationInSecs=600,
        description="Test serwera CG-connector na VPS. Można usunąć.",
        workoutSegments=[
            WorkoutSegment(
                segmentOrder=1,
                sportType={"sportTypeId": SportType.RUNNING, "sportTypeKey": "running"},
                workoutSteps=steps,
            )
        ],
    )


def schedule_id_of(response: Any) -> int | None:
    if isinstance(response, dict):
        for field in SCHEDULE_ID_FIELDS:
            try:
                value = int(response.get(field))
            except (TypeError, ValueError):
                continue
            if value > 0:
                return value
    return None


def run() -> None:
    os.environ.pop("GARMINTOKENS", None)  # nigdy nie czytaj/zapisuj tokenów z plików
    print("=" * 60)
    print("Test Garmin Connect z tego serwera (CG-connector)")
    print("=" * 60)
    print("Hasło nie jest nigdzie zapisywane.")
    email = input("E-mail konta Garmin: ").strip()
    password = getpass.getpass("Hasło Garmin (niewidoczne): ")

    # 1. Logowanie hasłem + MFA (jedna próba, bez ponawiania)
    def login() -> tuple[Garmin, str]:
        g = Garmin(
            email=email,
            password=password,
            prompt_mfa=lambda: input(
                "  Kod MFA z Garmina (e-mail/SMS/aplikacja): "
            ).strip(),
        )
        g.login()
        return g, f"zalogowano jako {g.get_full_name() or '(brak nazwy)'}"

    garmin = step("1. Logowanie hasłem + MFA", login)
    if garmin is None:
        return

    # 2. Wznowienie z tokenów (codzienna synchronizacja serwera)
    tokens = garmin.client.dumps()

    def resume() -> tuple[Garmin, str]:
        g = Garmin()
        g.login(tokens)
        return g, f"sesja z tokenów działa ({g.get_full_name() or 'ok'})"

    resumed = step("2. Wznowienie sesji z tokenów", resume)
    api = resumed or garmin

    # 3. Odczyt danych
    today = date.today()

    def activities() -> tuple[None, str]:
        items = api.get_activities_by_date((today - timedelta(days=14)).isoformat())
        lines = [f"{len(items)} aktywności z 14 dni"]
        for a in items[:3]:
            kind = (a.get("activityType") or {}).get("typeKey", "?")
            km = (a.get("distance") or 0) / 1000
            lines.append(f"    {a.get('startTimeLocal', '?')}  {kind}  {km:.1f} km")
        return None, "\n".join(lines)

    step("3a. Pobranie aktywności", activities)

    def vo2max() -> tuple[None, str]:
        data = api.get_max_metrics_range(
            (today - timedelta(days=30)).isoformat(), today.isoformat()
        )
        entries = data if isinstance(data, list) else [data]
        return None, f"{len(entries)} wpisów VO2max/metryk z 30 dni"

    step("3b. Pobranie VO2max", vo2max)

    # 4. Trening: wysłanie, zaplanowanie (za 30 dni), usunięcie
    def upload() -> tuple[int, str]:
        response = api.upload_running_workout(test_workout())
        workout_id = int(response["workoutId"])
        return workout_id, f"utworzono trening id {workout_id}"

    workout_id = step("4a. Wysłanie treningu do Garmina", upload)
    if workout_id is None:
        return
    when = (today + timedelta(days=30)).isoformat()

    def schedule() -> tuple[int | None, str]:
        sid = schedule_id_of(api.schedule_workout(workout_id, when))
        return sid, f"zaplanowano na {when}" + (f" (wpis {sid})" if sid else "")

    schedule_id = step("4b. Zaplanowanie w kalendarzu", schedule)

    def cleanup() -> tuple[None, str]:
        if schedule_id:
            api.unschedule_workout(schedule_id)
        api.delete_workout(workout_id)
        return None, "testowy trening usunięty z kalendarza i biblioteki"

    step("4c. Sprzątanie (usunięcie treningu)", cleanup)
    if not results[-1][1]:
        print(f"  ! Usuń ręcznie w Garmin Connect trening „{TEST_WORKOUT_NAME}”.")


def summary() -> int:
    print("\n" + "=" * 60)
    print("PODSUMOWANIE")
    for name, ok, detail in results:
        print(f"  {'✓' if ok else '✗'} {name}: {detail.splitlines()[0]}")
    passed = bool(results) and all(ok for _, ok, _ in results) and len(results) >= 7
    print("=" * 60)
    if passed:
        print(
            "WYNIK: ZALICZONY – Garmin działa z tego serwera. Wklej to podsumowanie Claude."
        )
        return 0
    print("WYNIK: NIEZALICZONY – wklej całe wyjście Claude (bez hasła, go tu nie ma).")
    return 1


def main() -> int:
    try:
        run()
    except RateLimited:
        print("\n!!! Garmin odpowiedział HTTP 429 (zbyt wiele zapytań).")
        print(
            "!!! NIE uruchamiaj testu ponownie przez 24 godziny (ryzyko blokady konta)."
        )
    except KeyboardInterrupt:
        print("\nPrzerwano.")
    return summary()


if __name__ == "__main__":
    sys.exit(main())
