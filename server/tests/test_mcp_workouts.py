"""MCP workout tools over the real MCP transport (OAuth token -> user), fake Garmin."""

from collections.abc import Iterator
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import httpx2
import pytest
from fastapi import FastAPI
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

from cg_server.app import create_app
from cg_server.config import Settings
from cg_server.crypto import encrypt
from cg_server.db import open_db
from cg_server.garmin.fake import FakeGarminGateway
from cg_server.models import GarminCredentials, GarminStatus
from cg_server.user_secrets import SecretKind, issue_secret
from cg_server.users import add_user
from tests.helpers import BASE, PUBLIC_HOST, call_tool, login_tokens, running

pytestmark = pytest.mark.anyio

DAY = (datetime.now(ZoneInfo("Europe/Warsaw")) + timedelta(days=3)).date()
STRENGTH = {
    "sport": "strength",
    "name": "Siła A",
    "exercises": [
        {
            "exercise": "Barbell Back Squat",
            "sets": 3,
            "reps": 5,
            "weight_kg": 80,
            "rest_seconds": 120,
        }
    ],
}


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture
def fake() -> FakeGarminGateway:
    return FakeGarminGateway()


@pytest.fixture
def codes(settings: Settings) -> dict[str, str]:
    result = {}
    with open_db(settings)() as session:
        for name in ("tomek", "brat"):
            user = add_user(session, name)
            user.garmin_credentials = GarminCredentials(
                status=GarminStatus.OK, encrypted_tokens=encrypt("{}", settings.encryption_key)
            )
            session.commit()
            result[name] = issue_secret(session, user, SecretKind.CG_CODE)
    return result


@pytest.fixture
def app(settings: Settings, fake: FakeGarminGateway, codes: dict[str, str]) -> Iterator[FastAPI]:
    app_settings = settings.model_copy(
        update={"env": "test", "public_host": PUBLIC_HOST, "sync_request_delay_s": 0}
    )
    yield create_app(app_settings, gateway_factory=lambda: fake)


async def call_error(http: httpx2.AsyncClient, token: str, name: str, args: dict[str, Any]) -> str:
    http.headers["Authorization"] = f"Bearer {token}"
    try:
        async with Client(streamable_http_client(f"{BASE}/mcp", http_client=http)) as client:
            result = await client.call_tool(name, args)
    finally:
        del http.headers["Authorization"]
    assert result.is_error
    return " ".join(getattr(c, "text", "") for c in result.content)


async def test_create_schedule_list_delete(
    app: FastAPI, codes: dict[str, str], fake: FakeGarminGateway
) -> None:
    async with running(app) as http:
        token = (await login_tokens(http, codes["tomek"]))["access_token"]
        created = await call_tool(http, token, "create_workout", {"definition": STRENGTH})
        assert created["preview"] == (
            "Strength: Siła A\n1. Barbell Back Squat: 3 x 5 reps @ 80 kg, rest 2:00"
        )

        scheduled = await call_tool(
            http,
            token,
            "schedule_workout",
            {"workout_id": created["workout_id"], "date": DAY.isoformat()},
        )
        assert scheduled["status"] == "sent"
        assert scheduled["date"] == DAY.isoformat()
        (garmin_workout,) = fake.workouts.values()
        assert garmin_workout["workoutName"] == "CG Siła A"
        assert garmin_workout["sportType"]["sportTypeKey"] == "strength_training"

        listed = await call_tool(
            http,
            token,
            "list_scheduled_workouts",
            {"date_from": DAY.isoformat(), "date_to": DAY.isoformat()},
        )
        assert [(i["id"], i["status"]) for i in listed["items"]] == [(scheduled["id"], "sent")]

        deleted = await call_tool(
            http, token, "delete_scheduled_workout", {"scheduled_id": scheduled["id"]}
        )
        assert deleted["status"] == "deleted"
        assert fake.calendar == {}


async def test_invalid_definition_gives_readable_error(app: FastAPI, codes: dict[str, str]) -> None:
    async with running(app) as http:
        token = (await login_tokens(http, codes["tomek"]))["access_token"]
        bad = {**STRENGTH, "exercises": [{"exercise": "Back Squat", "sets": 3, "reps": 5}]}
        message = await call_error(http, token, "create_workout", {"definition": bad})
        assert "Unknown exercise 'Back Squat'" in message
        assert "Barbell Back Squat" in message


async def test_schedule_stays_pending_during_429_pause(
    app: FastAPI, codes: dict[str, str], fake: FakeGarminGateway, settings: Settings
) -> None:
    from cg_server.models import User
    from cg_server.models import utcnow as now

    with open_db(settings)() as session:
        for user in session.query(User).all():
            assert user.garmin_credentials is not None
            user.garmin_credentials.rate_limited_at = now()
        session.commit()
    async with running(app) as http:
        token = (await login_tokens(http, codes["tomek"]))["access_token"]
        created = await call_tool(http, token, "create_workout", {"definition": STRENGTH})
        scheduled = await call_tool(
            http,
            token,
            "schedule_workout",
            {"workout_id": created["workout_id"], "date": DAY.isoformat()},
        )
        assert scheduled["status"] == "pending"
        assert "429" in scheduled["last_error"]
        assert fake.calls == []


async def test_other_users_workouts_are_invisible(app: FastAPI, codes: dict[str, str]) -> None:
    async with running(app) as http:
        tomek = (await login_tokens(http, codes["tomek"]))["access_token"]
        brat = (await login_tokens(http, codes["brat"]))["access_token"]
        created = await call_tool(http, tomek, "create_workout", {"definition": STRENGTH})
        scheduled = await call_tool(
            http,
            tomek,
            "schedule_workout",
            {"workout_id": created["workout_id"], "date": DAY.isoformat()},
        )
        args = {"workout_id": created["workout_id"], "date": DAY.isoformat()}
        assert "not found" in await call_error(http, brat, "schedule_workout", args)
        assert "not found" in await call_error(
            http, brat, "delete_scheduled_workout", {"scheduled_id": scheduled["id"]}
        )
        listed = await call_tool(
            http,
            brat,
            "list_scheduled_workouts",
            {"date_from": DAY.isoformat(), "date_to": DAY.isoformat()},
        )
        assert listed["items"] == []
