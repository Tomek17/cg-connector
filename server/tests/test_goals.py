"""The user's goal (target race and time): parsing, REST round trip."""

from collections.abc import Iterator
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

from cg_server import goals
from cg_server.app import create_app
from cg_server.config import Settings
from cg_server.db import open_db
from cg_server.models import utcnow
from cg_server.user_secrets import SecretKind, issue_secret
from cg_server.users import add_user
from tests.helpers import BASE, PUBLIC_HOST


@pytest.fixture
def token(settings: Settings) -> str:
    with open_db(settings)() as session:
        user = add_user(session, "anna")
        session.commit()
        return issue_secret(session, user, SecretKind.APP_TOKEN)


@pytest.fixture
def client(settings: Settings) -> Iterator[TestClient]:
    yield TestClient(
        create_app(settings.model_copy(update={"public_host": PUBLIC_HOST})), base_url=BASE
    )


def test_parse_and_format_time() -> None:
    assert goals.parse_time("1:45:00") == 6300
    assert goals.parse_time("45:30") == 2730
    assert goals.format_time(6300) == "1:45:00"
    with pytest.raises(goals.GoalError):
        goals.parse_time("1h45")


def test_goal_api_round_trip(client: TestClient, token: str) -> None:
    headers = {"Authorization": f"Bearer {token}"}
    assert client.get("/api/v1/goal", headers=headers).json() == {"goal": None}
    day = (utcnow() + timedelta(days=30)).date()
    body = {
        "name": " Półmaraton Warszawski ",
        "date": day.isoformat(),
        "distance_km": 21.0975,
        "target_seconds": 6300,
    }
    goal = client.put("/api/v1/goal", json=body, headers=headers).json()["goal"]
    assert goal["name"] == "Półmaraton Warszawski"
    assert goal["target_pace_min_per_km"] == pytest.approx(6300 / 21.0975 / 60, abs=0.01)
    assert goal["days_left"] in (29, 30, 31)
    # Replacing keeps one goal.
    body["target_seconds"] = None
    again = client.put("/api/v1/goal", json=body, headers=headers).json()["goal"]
    assert again["target_seconds"] is None and again["target_pace_min_per_km"] is None
    bad = client.put("/api/v1/goal", json={**body, "distance_km": 0}, headers=headers)
    assert bad.status_code == 422
    assert client.delete("/api/v1/goal", headers=headers).json() == {"goal": None}
    assert client.get("/api/v1/goal", headers=headers).json() == {"goal": None}
