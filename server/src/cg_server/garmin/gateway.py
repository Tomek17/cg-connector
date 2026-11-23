"""`GarminGateway`: the only place that talks to Garmin Connect.

Method signatures follow `garminconnect` 0.3.20. Tokens are the DI token JSON from
`client.dumps()`; the caller stores them encrypted and saves them again after every
session (the refresh token may change). Errors are never retried here: a 429 must stop
all traffic to Garmin (risk of account blocking, docs/research/garmin.md section 5).

Known limitation (garminconnect 0.3.20): when resuming from tokens, a failed token refresh
(including HTTP 429 on the refresh endpoint) is swallowed by the library, which then
reports "Username and password are required" as `GarminConnectAuthenticationError`.
So a 429 during refresh surfaces here as `GarminAuthError` (status `needs_login`), not
`GarminRateLimited`. The CLI therefore warns to wait 24 h after a recent 429 before
logging in with the password again.

The `GARMINTOKENS` environment variable is removed before every login, so the library
never reads or writes plaintext token files; tokens only live encrypted in our database.
"""

import logging
import os
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Any, Protocol, runtime_checkable

from garminconnect import (
    Garmin,
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectNotFoundError,
    GarminConnectTooManyRequestsError,
)

log = logging.getLogger(__name__)

MfaPrompt = Callable[[], str]
MaxMetrics = dict[str, Any] | list[dict[str, Any]]
# `hrTimeInZones`: garminconnect annotates dict, but Garmin returns a list of zones
# ({"zoneNumber", "secsInZone", "zoneLowBoundary"}); accept both.
HrZones = dict[str, Any] | list[dict[str, Any]]


@dataclass(frozen=True)
class LoginStep:
    """Result of the first step of a two-step login (from the app).

    Either `tokens` (logged in) or `mfa_state` (Garmin wants an MFA code; pass the state to
    `finish_login`). The state holds Garmin session cookies: keep it in memory only.
    """

    tokens: str | None = None
    mfa_state: dict[str, Any] | None = None


class GarminError(Exception):
    """Base class for gateway errors."""


class GarminRateLimited(GarminError):
    """Garmin answered HTTP 429 (or the SSO equivalent). Do not retry."""


class GarminNotFound(GarminError):
    """HTTP 404: the resource does not exist (e.g. no details for this activity)."""


class GarminAuthError(GarminError):
    """Wrong credentials or tokens rejected/expired: a new `garmin-login` is needed."""


@runtime_checkable
class GarminGateway(Protocol):
    def login(self, email: str, password: str, prompt_mfa: MfaPrompt) -> str:
        """Log in with password (+ MFA code from `prompt_mfa`), return tokens to store."""
        ...

    def start_login(self, email: str, password: str) -> LoginStep:
        """Password login without a blocking MFA prompt (see `LoginStep`)."""
        ...

    def finish_login(self, mfa_state: dict[str, Any], mfa_code: str) -> str:
        """Finish a login waiting for MFA; returns the tokens."""
        ...

    def resume(self, tokens: str) -> None:
        """Start a session from stored tokens (refreshing them if needed)."""
        ...

    def dumps(self) -> str:
        """Current tokens; save them after each session."""
        ...

    def get_full_name(self) -> str | None: ...

    def get_profile(self) -> dict[str, Any]: ...

    def get_activities_by_date(
        self,
        startdate: str,
        enddate: str | None = None,
        activitytype: str | None = None,
        sortorder: str | None = None,
    ) -> list[dict[str, Any]]: ...

    def get_max_metrics_range(
        self,
        start: str,
        end: str,
        aggregation: str = "daily",
        sport: str | None = None,
    ) -> MaxMetrics:
        """VO2max etc. per day; Garmin returns a list of daily entries."""
        ...

    def get_activity_splits(self, activity_id: int) -> dict[str, Any]:
        """Laps of an activity (`lapDTOs`)."""
        ...

    def get_activity_hr_in_timezones(self, activity_id: int) -> HrZones:
        """Time in heart rate zones (Garmin returns a list of zones)."""
        ...

    def get_activity_exercise_sets(self, activity_id: int) -> dict[str, Any]:
        """Strength sets (`exerciseSets`); empty for non-strength activities."""
        ...

    def get_activity_typed_splits(self, activity_id: int) -> dict[str, Any]:
        """Typed splits (`/typedsplits`): for climbing one split per climb and rest."""
        ...

    def upload_workout(self, workout: dict[str, Any]) -> int:
        """Create a workout (JSON from `cg_server.workout_garmin`); return its `workoutId`."""
        ...

    def schedule_workout(self, workout_id: int, date: str) -> int | None:
        """Put a workout on a day (YYYY-MM-DD) of the calendar; return the schedule id if
        Garmin's answer has one (field name not verified live)."""
        ...

    def unschedule_workout(self, schedule_id: int) -> None:
        """Remove a calendar entry; the workout itself stays in the library."""
        ...

    def delete_workout(self, workout_id: int) -> None:
        """Delete a workout from the library."""
        ...

    def get_scheduled_workouts_by_period(self, start: str, end: str) -> list[dict[str, Any]]:
        """Calendar entries between two days (YYYY-MM-DD)."""
        ...


SCHEDULE_ID_FIELDS = ("workoutScheduleId", "scheduleId", "id")


def _positive_int(value: Any) -> int | None:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return None
    return number if number > 0 else None


def schedule_id_of(response: Any) -> int | None:
    """Id of a calendar entry in Garmin's answer to `schedule_workout` [not verified live]."""
    if not isinstance(response, dict):
        return None
    for field in SCHEDULE_ID_FIELDS:
        if (value := _positive_int(response.get(field))) is not None:
            return value
    return None


_logged_shapes: set[str] = set()


def _log_keys_once(what: str, response: Any) -> None:
    """Log the field names (never values) of a Garmin answer once per process, so the
    watch spike can confirm them (docs/research/garmin.md, "Do potwierdzenia")."""
    if what in _logged_shapes:
        return
    _logged_shapes.add(what)
    keys = sorted(map(str, response)) if isinstance(response, dict) else type(response).__name__
    log.info("Garmin %s answer keys: %s", what, keys)


@contextmanager
def _translate_errors() -> Iterator[None]:
    try:
        yield
    except GarminConnectTooManyRequestsError as e:
        raise GarminRateLimited("Garmin: zbyt wiele zapytań (HTTP 429)") from e
    except GarminConnectAuthenticationError as e:
        raise GarminAuthError("Garmin: błąd uwierzytelnienia") from e
    except GarminConnectNotFoundError as e:
        raise GarminNotFound("Garmin: nie znaleziono (HTTP 404)") from e
    except GarminConnectConnectionError as e:
        status = getattr(e.response, "status_code", None)
        if status == 429:
            raise GarminRateLimited("Garmin: zbyt wiele zapytań (HTTP 429)") from e
        if status == 404:
            raise GarminNotFound("Garmin: nie znaleziono (HTTP 404)") from e
        raise


class RealGarminGateway:
    """Implementation on top of `garminconnect.Garmin`."""

    def __init__(self) -> None:
        self._garmin: Garmin | None = None

    def _session(self) -> Garmin:
        if self._garmin is None:
            raise GarminAuthError("Brak sesji Garmin: najpierw login() albo resume()")
        return self._garmin

    def login(self, email: str, password: str, prompt_mfa: MfaPrompt) -> str:
        os.environ.pop("GARMINTOKENS", None)
        garmin = Garmin(email=email, password=password, prompt_mfa=prompt_mfa)
        with _translate_errors():
            garmin.login()
        self._garmin = garmin
        return self.dumps()

    def start_login(self, email: str, password: str) -> LoginStep:
        os.environ.pop("GARMINTOKENS", None)
        garmin = Garmin(email=email, password=password, return_on_mfa=True)
        with _translate_errors():
            status, state = garmin.login()
        if status == "needs_mfa":
            return LoginStep(mfa_state=state)
        self._garmin = garmin
        return LoginStep(tokens=self.dumps())

    def finish_login(self, mfa_state: dict[str, Any], mfa_code: str) -> str:
        os.environ.pop("GARMINTOKENS", None)
        garmin = Garmin()
        with _translate_errors():
            garmin.resume_login(mfa_state, mfa_code)
        self._garmin = garmin
        return self.dumps()

    def resume(self, tokens: str) -> None:
        os.environ.pop("GARMINTOKENS", None)
        garmin = Garmin()
        with _translate_errors():
            garmin.login(tokens)
        self._garmin = garmin

    def dumps(self) -> str:
        tokens: str = self._session().client.dumps()
        return tokens

    def get_full_name(self) -> str | None:
        return self._session().get_full_name()

    def get_profile(self) -> dict[str, Any]:
        with _translate_errors():
            return self._session().get_user_profile()

    def get_activities_by_date(
        self,
        startdate: str,
        enddate: str | None = None,
        activitytype: str | None = None,
        sortorder: str | None = None,
    ) -> list[dict[str, Any]]:
        with _translate_errors():
            return self._session().get_activities_by_date(
                startdate, enddate, activitytype, sortorder
            )

    def get_max_metrics_range(
        self,
        start: str,
        end: str,
        aggregation: str = "daily",
        sport: str | None = None,
    ) -> MaxMetrics:
        with _translate_errors():
            return self._session().get_max_metrics_range(start, end, aggregation, sport)

    def get_activity_splits(self, activity_id: int) -> dict[str, Any]:
        with _translate_errors():
            return self._session().get_activity_splits(str(activity_id))

    def get_activity_hr_in_timezones(self, activity_id: int) -> HrZones:
        with _translate_errors():
            return self._session().get_activity_hr_in_timezones(str(activity_id))

    def get_activity_exercise_sets(self, activity_id: int) -> dict[str, Any]:
        with _translate_errors():
            return self._session().get_activity_exercise_sets(str(activity_id))

    def get_activity_typed_splits(self, activity_id: int) -> dict[str, Any]:
        with _translate_errors():
            result: dict[str, Any] = self._session().get_activity_typed_splits(str(activity_id))
            return result

    def upload_workout(self, workout: dict[str, Any]) -> int:
        with _translate_errors():
            response = self._session().upload_workout(workout)
        workout_id = (
            _positive_int(response.get("workoutId")) if isinstance(response, dict) else None
        )
        if workout_id is None:
            raise GarminError("Garmin: brak workoutId w odpowiedzi")
        return workout_id

    def schedule_workout(self, workout_id: int, date: str) -> int | None:
        with _translate_errors():
            response = self._session().schedule_workout(workout_id, date)
        _log_keys_once("schedule_workout", response)
        return schedule_id_of(response)

    def unschedule_workout(self, schedule_id: int) -> None:
        with _translate_errors():
            self._session().unschedule_workout(schedule_id)

    def delete_workout(self, workout_id: int) -> None:
        with _translate_errors():
            self._session().delete_workout(workout_id)

    def get_scheduled_workouts_by_period(self, start: str, end: str) -> list[dict[str, Any]]:
        with _translate_errors():
            result = self._session().get_scheduled_workouts_by_period(start, end)
        if isinstance(result, list) and result:
            _log_keys_once("workoutScheduleByPeriod entry", result[0])
        return [r for r in result if isinstance(r, dict)] if isinstance(result, list) else []
