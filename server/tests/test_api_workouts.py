"""REST /api/v1 workouts: idempotent create, schedule (sent in the background), list, delete."""

import uuid
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from cg_server.app import create_app
from cg_server.config import Settings
from cg_server.crypto import encrypt
from cg_server.db import open_db
from cg_server.garmin.fake import FakeGarminGateway
from cg_server.models import GarminCredentials, GarminStatus, Workout
from cg_server.user_secrets import SecretKind, issue_secret
from cg_server.users import add_user
from tests.helpers import BASE, PUBLIC_HOST

TODAY = datetime.now(ZoneInfo("Europe/Warsaw")).date()
DAY = TODAY + timedelta(days=2)
RUN = {
    "sport": "running",
    "name": "Tempo",
    "steps": [
        {"type": "warmup", "seconds": 600},
        {
            "type": "interval",
            "distance_km": 5,
            "target": {"type": "pace", "from": "4:50", "to": "4:40"},
        },
        {"type": "cooldown", "lap_button": True},
    ],
}


@pytest.fixture
def fake() -> FakeGarminGateway:
    return FakeGarminGateway()


@pytest.fixture
def tokens(settings: Settings) -> dict[str, str]:
    result = {}
    with open_db(settings)() as session:
        for name in ("tomek", "brat"):
            user = add_user(session, name)
            user.garmin_credentials = GarminCredentials(
                status=GarminStatus.OK, encrypted_tokens=encrypt("{}", settings.encryption_key)
            )
            session.commit()
            result[name] = issue_secret(session, user, SecretKind.APP_TOKEN)
    return result


@pytest.fixture
def client(settings: Settings, fake: FakeGarminGateway, tokens: dict[str, str]) -> TestClient:
    app_settings = settings.model_copy(
        update={"public_host": PUBLIC_HOST, "sync_request_delay_s": 0}
    )
    return TestClient(create_app(app_settings, gateway_factory=lambda: fake), base_url=BASE)


def auth(tokens: dict[str, str], name: str = "tomek") -> dict[str, str]:
    return {"Authorization": f"Bearer {tokens[name]}"}


def new_workout(client: TestClient, tokens: dict[str, str], name: str = "tomek") -> dict[str, Any]:
    body = {"client_id": str(uuid.uuid4()), "definition": RUN}
    response = client.post("/api/v1/workouts", json=body, headers=auth(tokens, name))
    assert response.status_code == 201, response.text
    data: dict[str, Any] = response.json()
    return data


def test_double_post_with_same_client_id_creates_one_workout(
    client: TestClient, tokens: dict[str, str], settings: Settings
) -> None:
    body = {"client_id": str(uuid.uuid4()), "definition": RUN}
    first = client.post("/api/v1/workouts", json=body, headers=auth(tokens))
    second = client.post("/api/v1/workouts", json=body, headers=auth(tokens))
    assert (first.status_code, second.status_code) == (201, 200)
    assert first.json()["id"] == second.json()["id"]
    assert first.json()["preview"].startswith("Running: Tempo\n1. Warmup 10:00")
    assert first.json()["created_by"] == "app"
    with open_db(settings)() as session:
        assert len(session.scalars(select(Workout)).all()) == 1


def test_invalid_definition_is_422(client: TestClient, tokens: dict[str, str]) -> None:
    body = {
        "client_id": str(uuid.uuid4()),
        "definition": {
            "sport": "strength",
            "name": "x",
            "exercises": [{"exercise": "Back Squat", "sets": 3, "reps": 5}],
        },
    }
    response = client.post("/api/v1/workouts", json=body, headers=auth(tokens))
    assert response.status_code == 422
    assert "Barbell Back Squat" in response.text
    bad_id = {"client_id": "not-a-uuid", "definition": RUN}
    assert client.post("/api/v1/workouts", json=bad_id, headers=auth(tokens)).status_code == 422


def test_schedule_is_sent_in_background_and_listed(
    client: TestClient, tokens: dict[str, str], fake: FakeGarminGateway
) -> None:
    workout = new_workout(client, tokens)
    response = client.post(
        f"/api/v1/workouts/{workout['id']}/schedule",
        json={"date": DAY.isoformat()},
        headers=auth(tokens),
    )
    assert response.status_code == 201, response.text
    assert response.json()["status"] == "pending"
    # The background attempt ran after the response.
    assert len(fake.calendar) == 1
    listed = client.get(
        "/api/v1/scheduled",
        params={"from": TODAY.isoformat(), "to": (TODAY + timedelta(7)).isoformat()},
        headers=auth(tokens),
    ).json()["items"]
    assert [(i["id"], i["status"], i["name"]) for i in listed] == [
        (response.json()["id"], "sent", "Tempo")
    ]
    again = client.post(
        f"/api/v1/workouts/{workout['id']}/schedule",
        json={"date": DAY.isoformat()},
        headers=auth(tokens),
    )
    assert again.status_code == 200 and again.json()["id"] == response.json()["id"]
    assert len(fake.calendar) == 1


def test_delete_removes_from_garmin(
    client: TestClient, tokens: dict[str, str], fake: FakeGarminGateway
) -> None:
    workout = new_workout(client, tokens)
    item = client.post(
        f"/api/v1/workouts/{workout['id']}/schedule",
        json={"date": DAY.isoformat()},
        headers=auth(tokens),
    ).json()
    response = client.delete(f"/api/v1/scheduled/{item['id']}", headers=auth(tokens))
    assert response.status_code == 200
    assert response.json()["status"] == "deleting"
    assert fake.calendar == {}
    listed = client.get("/api/v1/scheduled", headers=auth(tokens)).json()
    assert listed == {"items": []}


def test_users_are_isolated(client: TestClient, tokens: dict[str, str]) -> None:
    workout = new_workout(client, tokens, "tomek")
    item = client.post(
        f"/api/v1/workouts/{workout['id']}/schedule",
        json={"date": DAY.isoformat()},
        headers=auth(tokens),
    ).json()
    brat = auth(tokens, "brat")
    response = client.post(
        f"/api/v1/workouts/{workout['id']}/schedule", json={"date": DAY.isoformat()}, headers=brat
    )
    assert response.status_code == 404
    assert client.get("/api/v1/scheduled", headers=brat).json() == {"items": []}
    assert client.delete(f"/api/v1/scheduled/{item['id']}", headers=brat).status_code == 404
    mine = client.get("/api/v1/scheduled", headers=auth(tokens)).json()["items"]
    assert [i["id"] for i in mine] == [item["id"]]


def test_schedule_in_past_and_bad_range(client: TestClient, tokens: dict[str, str]) -> None:
    workout = new_workout(client, tokens)
    past = (TODAY - timedelta(days=1)).isoformat()
    response = client.post(
        f"/api/v1/workouts/{workout['id']}/schedule", json={"date": past}, headers=auth(tokens)
    )
    assert response.status_code == 422
    assert "past" in response.json()["detail"]
    params = {"from": TODAY.isoformat(), "to": (TODAY - timedelta(1)).isoformat()}
    assert client.get("/api/v1/scheduled", params=params, headers=auth(tokens)).status_code == 422


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("post", "/api/v1/workouts"),
        ("post", "/api/v1/workouts/1/schedule"),
        ("get", "/api/v1/scheduled"),
        ("delete", "/api/v1/scheduled/1"),
    ],
)
def test_requires_app_token(client: TestClient, method: str, path: str) -> None:
    assert getattr(client, method)(path).status_code == 401
