"""In-memory `GarminGateway` with synthetic data, for tests and local development."""

from typing import Any

from cg_server.garmin.gateway import GarminAuthError, MfaPrompt


class FakeGarminGateway:
    """Never touches the network.

    `error`: raised by every call that would hit Garmin (e.g. `GarminRateLimited()`).
    `refreshed_tokens`: what `dumps()` returns after a session, simulating a token refresh.
    """

    def __init__(
        self,
        *,
        tokens: str = '{"di_token": "fake-access", "di_refresh_token": "fake-refresh"}',
        refreshed_tokens: str | None = None,
        full_name: str = "Test Biegacz",
        error: Exception | None = None,
    ) -> None:
        self.tokens = tokens
        self.refreshed_tokens = refreshed_tokens
        self.full_name = full_name
        self.error = error
        self.calls: list[str] = []
        self.mfa_code: str | None = None
        self._session_tokens: str | None = None

    def _call(self, name: str) -> None:
        self.calls.append(name)
        if self.error is not None:
            raise self.error

    def _require_session(self) -> None:
        if self._session_tokens is None:
            raise GarminAuthError("Brak sesji Garmin")

    def login(self, email: str, password: str, prompt_mfa: MfaPrompt) -> str:
        self._call("login")
        self.mfa_code = prompt_mfa()
        self._session_tokens = self.tokens
        return self.tokens

    def resume(self, tokens: str) -> None:
        self._call("resume")
        self._session_tokens = tokens

    def dumps(self) -> str:
        self._require_session()
        return self.refreshed_tokens or self._session_tokens or ""

    def get_full_name(self) -> str | None:
        self._require_session()
        return self.full_name

    def get_profile(self) -> dict[str, Any]:
        self._call("get_profile")
        self._require_session()
        return {"userData": {"measurementSystem": "metric", "weight": 70000.0}}

    def get_activities_by_date(
        self,
        startdate: str,
        enddate: str | None = None,
        activitytype: str | None = None,
        sortorder: str | None = None,
    ) -> list[dict[str, Any]]:
        self._call("get_activities_by_date")
        self._require_session()
        activities: list[dict[str, Any]] = [
            {
                "activityId": 1001,
                "activityName": "Bieg testowy",
                "startTimeLocal": f"{startdate} 07:00:00",
                "activityType": {"typeKey": "running"},
                "distance": 10000.0,
                "duration": 3000.0,
                "averageHR": 150.0,
            },
            {
                "activityId": 1002,
                "activityName": "Siłownia testowa",
                "startTimeLocal": f"{startdate} 18:00:00",
                "activityType": {"typeKey": "strength_training"},
                "distance": 0.0,
                "duration": 2700.0,
                "averageHR": 110.0,
            },
        ]
        if activitytype:
            activities = [a for a in activities if a["activityType"]["typeKey"] == activitytype]
        return activities

    def get_max_metrics_range(
        self,
        start: str,
        end: str,
        aggregation: str = "daily",
        sport: str | None = None,
    ) -> dict[str, Any]:
        self._call("get_max_metrics_range")
        self._require_session()
        return {
            "start": start,
            "end": end,
            "aggregation": aggregation,
            "metrics": [{"calendarDate": start, "generic": {"vo2MaxPreciseValue": 52.3}}],
        }
