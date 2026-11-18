"""RealGarminGateway is tested against a stub of `garminconnect.Garmin` (no network)."""

import os
from collections.abc import Callable
from types import SimpleNamespace
from typing import Any

import pytest
from garminconnect import (
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectTooManyRequestsError,
)

from cg_server.garmin import gateway as gateway_module
from cg_server.garmin.fake import FakeGarminGateway
from cg_server.garmin.gateway import (
    GarminAuthError,
    GarminGateway,
    GarminRateLimited,
    RealGarminGateway,
)


class StubClient:
    def __init__(self) -> None:
        self.tokens = ""

    def dumps(self) -> str:
        return self.tokens


class StubGarmin:
    """Mimics the parts of garminconnect.Garmin 0.3.20 that the gateway uses."""

    error: Exception | None = None
    instances: list["StubGarmin"] = []
    login_calls = 0
    env_tokens_seen: list[str | None] = []

    def __init__(
        self,
        email: str | None = None,
        password: str | None = None,
        prompt_mfa: Callable[[], str] | None = None,
    ) -> None:
        self.email, self.password, self.prompt_mfa = email, password, prompt_mfa
        self.client = StubClient()
        self.full_name: str | None = None
        self.tokenstore: str | None = None
        StubGarmin.instances.append(self)

    def login(self, tokenstore: str | None = None) -> tuple[None, None]:
        StubGarmin.login_calls += 1
        StubGarmin.env_tokens_seen.append(os.environ.get("GARMINTOKENS"))
        if StubGarmin.error is not None:
            raise StubGarmin.error
        self.tokenstore = tokenstore
        if self.prompt_mfa is not None:
            self.client.tokens = f'{{"mfa": "{self.prompt_mfa()}"}}'
        else:
            self.client.tokens = tokenstore or ""
        self.full_name = "Jan Testowy"
        return None, None

    def get_full_name(self) -> str | None:
        return self.full_name

    def get_user_profile(self) -> dict[str, Any]:
        if StubGarmin.error is not None:
            raise StubGarmin.error
        self.client.tokens = '{"refreshed": true}'
        return {"userData": {"measurementSystem": "metric"}}

    def get_activities_by_date(self, *args: Any) -> list[dict[str, Any]]:
        return [{"args": list(args)}]

    def get_max_metrics_range(self, *args: Any) -> dict[str, Any]:
        return {"args": list(args)}


@pytest.fixture(autouse=True)
def stub_garmin(monkeypatch: pytest.MonkeyPatch) -> None:
    StubGarmin.error = None
    StubGarmin.instances = []
    StubGarmin.login_calls = 0
    StubGarmin.env_tokens_seen = []
    monkeypatch.setattr(gateway_module, "Garmin", StubGarmin)


def test_implementations_satisfy_protocol() -> None:
    assert isinstance(RealGarminGateway(), GarminGateway)
    assert isinstance(FakeGarminGateway(), GarminGateway)


def test_login_returns_dumped_tokens_and_uses_mfa_prompt() -> None:
    gw = RealGarminGateway()
    tokens = gw.login("a@example.com", "haslo", lambda: "123456")
    assert tokens == '{"mfa": "123456"}'
    assert StubGarmin.instances[0].email == "a@example.com"
    assert gw.get_full_name() == "Jan Testowy"


def test_resume_logs_in_with_inline_tokens_and_dumps_refreshed_tokens() -> None:
    gw = RealGarminGateway()
    gw.resume('{"di_token": "x"}')
    garmin = StubGarmin.instances[0]
    assert garmin.tokenstore == '{"di_token": "x"}'
    assert garmin.password is None
    assert gw.get_profile()["userData"]["measurementSystem"] == "metric"
    assert gw.dumps() == '{"refreshed": true}'


def test_data_calls_pass_library_arguments() -> None:
    gw = RealGarminGateway()
    gw.resume("{}")
    acts = gw.get_activities_by_date("2026-01-01", "2026-01-31", "running", "asc")
    assert acts == [{"args": ["2026-01-01", "2026-01-31", "running", "asc"]}]
    vo2 = gw.get_max_metrics_range("2026-01-01", "2026-01-31", "weekly", "running")
    assert vo2 == {"args": ["2026-01-01", "2026-01-31", "weekly", "running"]}


def test_calls_before_login_are_rejected() -> None:
    with pytest.raises(GarminAuthError):
        RealGarminGateway().get_profile()


@pytest.mark.parametrize(
    ("library_error", "expected"),
    [
        (GarminConnectTooManyRequestsError("429"), GarminRateLimited),
        (GarminConnectAuthenticationError("401"), GarminAuthError),
    ],
)
def test_library_errors_are_translated(library_error: Exception, expected: type[Exception]) -> None:
    StubGarmin.error = library_error
    with pytest.raises(expected):
        RealGarminGateway().login("a@example.com", "haslo", lambda: "")
    with pytest.raises(expected):
        RealGarminGateway().resume("{}")
    # The gateway itself never retries: exactly one library login() per gateway call.
    assert StubGarmin.login_calls == 2


def connection_error(status: int) -> GarminConnectConnectionError:
    error = GarminConnectConnectionError(f"HTTP {status}")
    error.response = SimpleNamespace(status_code=status)
    return error


def test_connection_error_with_429_is_rate_limited() -> None:
    StubGarmin.error = connection_error(429)
    with pytest.raises(GarminRateLimited):
        RealGarminGateway().resume("{}")


def test_other_connection_errors_pass_through() -> None:
    StubGarmin.error = connection_error(500)
    with pytest.raises(GarminConnectConnectionError):
        RealGarminGateway().resume("{}")


def test_garmintokens_env_is_ignored(monkeypatch: pytest.MonkeyPatch) -> None:
    """GARMINTOKENS could make the library read/write plaintext token files."""
    monkeypatch.setenv("GARMINTOKENS", "/tmp/garmin_tokens")  # noqa: S108 - never touched
    RealGarminGateway().login("a@example.com", "haslo", lambda: "")
    monkeypatch.setenv("GARMINTOKENS", "/tmp/garmin_tokens")  # noqa: S108
    RealGarminGateway().resume("{}")
    assert StubGarmin.env_tokens_seen == [None, None]
