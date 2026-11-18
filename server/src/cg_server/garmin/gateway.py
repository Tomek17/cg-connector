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

import os
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from typing import Any, Protocol, runtime_checkable

from garminconnect import (
    Garmin,
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectTooManyRequestsError,
)

MfaPrompt = Callable[[], str]


class GarminError(Exception):
    """Base class for gateway errors."""


class GarminRateLimited(GarminError):
    """Garmin answered HTTP 429 (or the SSO equivalent). Do not retry."""


class GarminAuthError(GarminError):
    """Wrong credentials or tokens rejected/expired: a new `garmin-login` is needed."""


@runtime_checkable
class GarminGateway(Protocol):
    def login(self, email: str, password: str, prompt_mfa: MfaPrompt) -> str:
        """Log in with password (+ MFA code from `prompt_mfa`), return tokens to store."""
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
    ) -> dict[str, Any]: ...


@contextmanager
def _translate_errors() -> Iterator[None]:
    try:
        yield
    except GarminConnectTooManyRequestsError as e:
        raise GarminRateLimited("Garmin: zbyt wiele zapytań (HTTP 429)") from e
    except GarminConnectAuthenticationError as e:
        raise GarminAuthError("Garmin: błąd uwierzytelnienia") from e
    except GarminConnectConnectionError as e:
        if getattr(e.response, "status_code", None) == 429:
            raise GarminRateLimited("Garmin: zbyt wiele zapytań (HTTP 429)") from e
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
    ) -> dict[str, Any]:
        with _translate_errors():
            return self._session().get_max_metrics_range(start, end, aggregation, sport)
