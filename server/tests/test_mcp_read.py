"""MCP read tools: compact answers in km, min/km, bpm and Europe/Warsaw time."""

import json
from collections.abc import AsyncIterator
from datetime import date, datetime, timedelta
from typing import Any

import httpx2
import pytest
from fastapi import FastAPI
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

from cg_server.app import create_app
from cg_server.config import Settings
from cg_server.db import open_db
from cg_server.models import ActivityKind, GarminStatus, User, utcnow
from cg_server.queries import WARSAW
from cg_server.user_secrets import SecretKind, issue_secret
from cg_server.users import add_user
from tests.helpers import BASE, PUBLIC_HOST, login_tokens, running
from tests.seed import add_activity, add_sync, add_vo2max, set_garmin_status

pytestmark = pytest.mark.anyio

TOOLS = ["list_activities", "get_activity", "get_vo2max_trend", "get_training_summary"]


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture
def app_settings(settings: Settings) -> Settings:
    return settings.model_copy(update={"env": "test", "public_host": PUBLIC_HOST})


def today() -> date:
    return utcnow().astimezone(WARSAW).date()


def at(day: date, hour: int = 7) -> datetime:
    return datetime.combine(day, datetime.min.time()).replace(hour=hour)


@pytest.fixture
def codes(app_settings: Settings) -> dict[str, str]:
    """CG codes of A (fresh sync, data) and B (stale sync, one activity)."""
    t = today()
    with open_db(app_settings)() as session:
        a, b = add_user(session, "anna"), add_user(session, "bartek")
        add_activity(session, a, 101, at(t - timedelta(days=2)), details=True)
        add_activity(
            session, a, 102, at(t - timedelta(days=3), 18), ActivityKind.STRENGTH, details=True
        )
        add_activity(session, a, 103, at(t - timedelta(days=40)), ActivityKind.CLIMBING)
        add_activity(session, b, 201, at(t - timedelta(days=1)))
        for i, value in enumerate([50.0, 50.0, 50.5, 51.0]):
            add_vo2max(session, a, t - timedelta(days=30 - i * 7), value)
        add_vo2max(session, b, t - timedelta(days=5), 40.0)
        add_sync(session, a, utcnow() - timedelta(hours=2))
        add_sync(session, b, utcnow() - timedelta(hours=30))
        set_garmin_status(session, a, GarminStatus.OK)
        session.commit()
        result = {
            "a": issue_secret(session, a, SecretKind.CG_CODE),
            "b": issue_secret(session, b, SecretKind.CG_CODE),
        }
    return result


@pytest.fixture
def app(app_settings: Settings, codes: dict[str, str]) -> FastAPI:
    return create_app(app_settings)


@pytest.fixture
async def http(app: FastAPI) -> AsyncIterator[httpx2.AsyncClient]:
    async with running(app) as client:
        yield client


async def call(
    http: httpx2.AsyncClient, code: str, name: str, args: dict[str, Any] | None = None
) -> tuple[bool, str, Any]:
    """(is_error, text content, structured content) of one tool call."""
    tokens = await login_tokens(http, code)
    http.headers["Authorization"] = f"Bearer {tokens['access_token']}"
    try:
        async with Client(streamable_http_client(f"{BASE}/mcp", http_client=http)) as client:
            result = await client.call_tool(name, args or {})
    finally:
        del http.headers["Authorization"]
    text = "".join(getattr(c, "text", "") for c in result.content)
    if result.is_error:
        return True, text, None
    # One copy of the data only: compact text, no duplicated structured content.
    assert result.structured_content is None
    return False, text, json.loads(text)


async def ok(
    http: httpx2.AsyncClient, code: str, name: str, args: dict[str, Any] | None = None
) -> Any:
    is_error, text, data = await call(http, code, name, args)
    assert not is_error, text
    assert '": ' not in text  # compact separators
    return data


async def test_tools_are_listed_with_english_descriptions(
    http: httpx2.AsyncClient, codes: dict[str, str]
) -> None:
    tokens = await login_tokens(http, codes["a"])
    http.headers["Authorization"] = f"Bearer {tokens['access_token']}"
    async with Client(streamable_http_client(f"{BASE}/mcp", http_client=http)) as client:
        tools = {t.name: t for t in (await client.list_tools()).tools}
    for name in TOOLS:
        assert name in tools
        description = tools[name].description or ""
        assert description.isascii()
        assert len(description) < 600
        assert "user" not in tools[name].input_schema.get("properties", {})


async def test_list_activities(http: httpx2.AsyncClient, codes: dict[str, str]) -> None:
    data = await ok(http, codes["a"], "list_activities")
    assert [x["id"] for x in data["activities"]] == [101, 102]  # default: last 30 days
    run = data["activities"][0]
    assert run == {
        "id": 101,
        "start": f"{today() - timedelta(days=2)} 07:00",
        "type": "running",
        "name": "Aktywność 101",
        "km": 10.0,
        "time": "50:00",
        "pace": "5:00",
        "hr": 150,
        "elev_m": 85,
    }
    strength = data["activities"][1]
    assert "km" not in strength
    assert "pace" not in strength
    assert "note" not in data


async def test_list_activities_filters(http: httpx2.AsyncClient, codes: dict[str, str]) -> None:
    t = today()
    args = {"from_date": str(t - timedelta(days=60)), "to_date": str(t), "type": "climbing"}
    data = await ok(http, codes["a"], "list_activities", args)
    assert [x["id"] for x in data["activities"]] == [103]
    limited = await ok(
        http, codes["a"], "list_activities", {"from_date": str(t - timedelta(days=60)), "limit": 1}
    )
    assert [x["id"] for x in limited["activities"]] == [101]
    assert limited["more"] is True


async def test_list_activities_empty_and_stale_sync_notes(
    http: httpx2.AsyncClient, codes: dict[str, str]
) -> None:
    empty = await ok(
        http, codes["a"], "list_activities", {"from_date": "2020-01-01", "to_date": "2020-01-31"}
    )
    assert empty["activities"] == []
    assert "No activities" in empty["note"]

    stale = await ok(http, codes["b"], "list_activities")
    assert [x["id"] for x in stale["activities"]] == [201]
    assert "over 24 h ago" in stale["note"]


async def test_list_of_20_activities_is_small(
    app_settings: Settings, http: httpx2.AsyncClient
) -> None:
    t = today()
    with open_db(app_settings)() as session:
        user = add_user(session, "duzo")
        for i in range(40):
            add_activity(
                session,
                user,
                5000 + i,
                at(t - timedelta(days=i % 25), 6 + i % 12),
                name="Bieg z dość długą nazwą aktywności, która jest bardzo długa " * 3,
            )
        add_sync(session, user, utcnow())
        session.commit()
        code = issue_secret(session, user, SecretKind.CG_CODE)
    is_error, text, data = await call(http, code, "list_activities")
    assert not is_error
    assert len(data["activities"]) == 20
    assert data["more"] is True
    assert len(text.encode()) < 4096


async def test_get_activity_run_and_strength(
    http: httpx2.AsyncClient, codes: dict[str, str]
) -> None:
    run = await ok(http, codes["a"], "get_activity", {"activity_id": 101})
    assert run["id"] == 101
    assert run["max_hr"] == 176
    assert run["laps"][0] == {"n": 1, "km": 1.0, "time": "4:59", "pace": "4:59", "hr": 149}
    assert run["hr_zones_min"] == {"z1": 10, "z2": 10, "z3": 10, "z4": 10, "z5": 10}
    assert "sets" not in run

    strength = await ok(http, codes["a"], "get_activity", {"activity_id": 102})
    assert strength["sets"] == [{"exercise": "BARBELL_BACK_SQUAT", "sets": ["5x80kg", "5x82.5kg"]}]


async def test_get_activity_of_other_user_is_not_found(
    http: httpx2.AsyncClient, codes: dict[str, str]
) -> None:
    is_error, text, _ = await call(http, codes["a"], "get_activity", {"activity_id": 201})
    assert is_error
    assert "not found" in text.lower()
    assert "Aktywność 201" not in text
    # B sees it.
    data = await ok(http, codes["b"], "get_activity", {"activity_id": 201})
    assert data["id"] == 201


async def test_vo2max_trend(http: httpx2.AsyncClient, codes: dict[str, str]) -> None:
    data = await ok(http, codes["a"], "get_vo2max_trend")
    t = today()
    assert data["latest"] == {"date": str(t - timedelta(days=9)), "value": 51.0}
    assert data["change"] == 1.0
    assert data["min"] == 50.0
    assert data["max"] == 51.0
    # Repeated values are dropped.
    assert data["points"] == [
        [str(t - timedelta(days=30)), 50.0],
        [str(t - timedelta(days=16)), 50.5],
        [str(t - timedelta(days=9)), 51.0],
    ]
    other = await ok(http, codes["b"], "get_vo2max_trend")
    assert other["latest"]["value"] == 40.0


async def test_vo2max_trend_long_range_is_downsampled(
    app_settings: Settings, http: httpx2.AsyncClient
) -> None:
    t = today()
    with open_db(app_settings)() as session:
        user = add_user(session, "vo2")
        for i in range(365):
            add_vo2max(session, user, t - timedelta(days=i), 45.0 + (i % 50) / 10)
        add_sync(session, user, utcnow())
        session.commit()
        code = issue_secret(session, user, SecretKind.CG_CODE)
    _, text, data = await call(
        http, code, "get_vo2max_trend", {"from_date": str(t - timedelta(days=364))}
    )
    assert data["resolution"] in {"weekly", "monthly"}
    assert len(data["points"]) <= 60
    assert len(text.encode()) < 4096


async def test_vo2max_trend_without_data(http: httpx2.AsyncClient, codes: dict[str, str]) -> None:
    data = await ok(
        http, codes["a"], "get_vo2max_trend", {"from_date": "2020-01-01", "to_date": "2020-02-01"}
    )
    assert data["points"] == []
    assert "No VO2max" in data["note"]


async def test_training_summary_weekly(http: httpx2.AsyncClient, codes: dict[str, str]) -> None:
    data = await ok(http, codes["a"], "get_training_summary", {"period": "week"})
    assert data["period"] == "week"
    assert 1 <= len(data["periods"]) <= 8
    totals: dict[str, int] = {}
    for p in data["periods"]:
        date.fromisoformat(p["start"])
        for kind, values in p.items():
            if kind != "start":
                totals[kind] = totals.get(kind, 0) + values["n"]
    assert totals == {"running": 1, "strength": 1, "climbing": 1}
    run_week = next(p for p in data["periods"] if "running" in p)
    assert run_week["running"] == {
        "n": 1,
        "km": 10.0,
        "time": "50:00",
        "elev_m": 85,
        "pace": "5:00",
    }


async def test_training_summary_monthly_with_range(
    http: httpx2.AsyncClient, codes: dict[str, str]
) -> None:
    t = today()
    data = await ok(
        http,
        codes["a"],
        "get_training_summary",
        {"period": "month", "from_date": str(t - timedelta(days=60)), "to_date": str(t)},
    )
    assert sum(sum(v["n"] for k, v in p.items() if k != "start") for p in data["periods"]) == 3
    assert all(p["start"].endswith("-01") for p in data["periods"])


@pytest.mark.parametrize(
    ("tool", "args"),
    [
        ("list_activities", {"from_date": "2026-11-10", "to_date": "2026-11-01"}),
        ("list_activities", {"type": "swimming"}),
        ("list_activities", {"limit": 500}),
        ("get_vo2max_trend", {"from_date": "2026-11-10", "to_date": "2026-11-01"}),
        ("get_training_summary", {"period": "year"}),
        ("get_training_summary", {"period": "week", "from_date": "2020-01-01"}),
        ("get_activity", {"activity_id": "abc"}),
    ],
)
async def test_tools_reject_bad_arguments(
    http: httpx2.AsyncClient, codes: dict[str, str], tool: str, args: dict[str, Any]
) -> None:
    is_error, text, _ = await call(http, codes["a"], tool, args)
    assert is_error
    assert "Traceback" not in text


async def test_tools_ignore_user_argument(http: httpx2.AsyncClient, codes: dict[str, str]) -> None:
    # An extra "user" argument cannot switch to another user's data.
    for extra in ({"user": "bartek"}, {"user_id": 2}):
        data = await ok(http, codes["a"], "list_activities", extra)
        assert [x["id"] for x in data["activities"]] == [101, 102]


async def test_needs_login_note(app_settings: Settings, http: httpx2.AsyncClient) -> None:
    with open_db(app_settings)() as session:
        user: User = add_user(session, "relogin")
        set_garmin_status(session, user, GarminStatus.NEEDS_LOGIN)
        session.commit()
        code = issue_secret(session, user, SecretKind.CG_CODE)
    data = await ok(http, code, "list_activities")
    assert "garmin-login" in data["note"]
    assert "never" in data["note"].lower()


@pytest.mark.parametrize("tool", ["list_activities", "get_vo2max_trend", "get_training_summary"])
@pytest.mark.parametrize(
    "args",
    [
        {"from_date": "0001-01-01"},
        {"to_date": "9999-12-31"},
        {"from_date": "9999-12-30", "to_date": "9999-12-31"},
        {"from_date": "2020-01-01", "to_date": "2026-01-01"},
    ],
)
async def test_tools_reject_out_of_bounds_dates(
    http: httpx2.AsyncClient, codes: dict[str, str], tool: str, args: dict[str, Any]
) -> None:
    is_error, text, _ = await call(http, codes["a"], tool, args)
    assert is_error
    assert "Overflow" not in text
    assert "2000-01-01" in text or "2 years" in text or "at most" in text


async def test_summer_time_and_new_year_week_in_tools(
    app_settings: Settings, http: httpx2.AsyncClient
) -> None:
    with open_db(app_settings)() as session:
        user = add_user(session, "lato")
        add_activity(session, user, 701, datetime(2026, 7, 2, 0, 30))
        add_activity(session, user, 702, datetime(2026, 12, 31, 8))
        add_activity(session, user, 703, datetime(2027, 1, 3, 9))
        add_sync(session, user, utcnow())
        session.commit()
        code = issue_secret(session, user, SecretKind.CG_CODE)
    summer = await ok(
        http, code, "list_activities", {"from_date": "2026-07-02", "to_date": "2026-07-02"}
    )
    assert [(x["id"], x["start"]) for x in summer["activities"]] == [(701, "2026-07-02 00:30")]
    weeks = await ok(
        http,
        code,
        "get_training_summary",
        {"period": "week", "from_date": "2026-12-30", "to_date": "2027-01-03"},
    )
    assert weeks["range"] == "2026-12-28..2027-01-03"
    assert [(p["start"], p["running"]["n"]) for p in weeks["periods"]] == [("2026-12-28", 2)]
