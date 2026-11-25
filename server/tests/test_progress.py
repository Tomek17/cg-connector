"""Activity tags (REST + MCP-side logic) and the Progress data per tag."""

from collections.abc import Iterator
from datetime import datetime
from typing import Any

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from cg_server.app import create_app
from cg_server.config import Settings
from cg_server.db import open_db
from cg_server.models import ActivityDetails, ActivityKind, User
from cg_server.progress import run_point
from cg_server.tags import TagError, set_tag
from cg_server.user_secrets import SecretKind, issue_secret
from cg_server.users import add_user
from tests.helpers import BASE, PUBLIC_HOST
from tests.seed import add_activity, climb_split


def interval_laps() -> dict[str, Any]:
    """Warm-up 2 km slow, 2 x 1 km fast with a slow recovery, cool-down."""

    def lap(kind: str, dist: float, secs: float, hr: float, cad: float) -> dict[str, Any]:
        return {
            "intensityType": kind,
            "distance": dist,
            "duration": secs,
            "averageHR": hr,
            "averageRunCadence": cad,
        }

    return {
        "lapDTOs": [
            lap("WARMUP", 2000, 720, 130, 160),
            lap("INTERVAL", 1000, 240, 170, 184),
            lap("RECOVERY", 200, 120, 150, 150),
            lap("INTERVAL", 1000, 250, 174, 182),
            lap("COOLDOWN", 1000, 360, 140, 162),
        ]
    }


@pytest.fixture
def token(settings: Settings) -> str:
    with open_db(settings)() as session:
        user = add_user(session, "anna")
        other = add_user(session, "bartek")
        run = add_activity(session, user, 1, datetime(2026, 11, 3, 18))
        run.details = ActivityDetails(
            user_id=user.id, laps_json=interval_laps(), fetch_failed=False
        )
        add_activity(session, user, 2, datetime(2026, 11, 10, 18), details=True)
        add_activity(
            session, user, 3, datetime(2026, 11, 4, 18), ActivityKind.STRENGTH, details=True
        )
        add_activity(
            session, user, 4, datetime(2026, 11, 11, 18), ActivityKind.STRENGTH, details=True
        )
        add_activity(session, user, 5, datetime(2026, 11, 12, 18), ActivityKind.CLIMBING)
        add_activity(session, other, 9, datetime(2026, 11, 3, 18))
        session.commit()
        return issue_secret(session, user, SecretKind.APP_TOKEN)


@pytest.fixture
def client(settings: Settings) -> Iterator[TestClient]:
    yield TestClient(
        create_app(settings.model_copy(update={"public_host": PUBLIC_HOST})), base_url=BASE
    )


def put_tag(client: TestClient, token: str, activity_id: int, tag: str | None) -> Any:
    return client.put(
        f"/api/v1/activities/{activity_id}/tag",
        json={"tag": tag},
        headers={"Authorization": f"Bearer {token}"},
    )


def get(client: TestClient, token: str, path: str) -> Any:
    response = client.get(path, headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200, response.text
    return response.json()


def test_tag_must_match_activity_type(client: TestClient, token: str) -> None:
    response = put_tag(client, token, 1, "Intervals")
    assert response.status_code == 200
    assert response.json()["tag"] == "intervals"
    wrong = put_tag(client, token, 3, "intervals")
    assert wrong.status_code == 422
    assert "push" in wrong.json()["detail"]
    assert put_tag(client, token, 1, "sprint").status_code == 422
    assert put_tag(client, token, 9, "base").status_code == 404  # another user's activity
    assert put_tag(client, token, 1, None).json()["tag"] is None
    put_tag(client, token, 1, "tempo")
    # The app's JSON leaves null fields out: an empty body clears the tag too.
    cleared = client.put(
        "/api/v1/activities/1/tag", json={}, headers={"Authorization": f"Bearer {token}"}
    )
    assert cleared.json()["tag"] is None


def test_running_progress_uses_work_laps_only(client: TestClient, token: str) -> None:
    for activity_id in (1, 2):
        assert put_tag(client, token, activity_id, "intervals").status_code == 200
    data = get(client, token, "/api/v1/progress/intervals")
    first, second = data["running"]
    assert data["type"] == "running" and data["strength"] == []
    # 2 km in 490 s -> 4:05 /km; HR and cadence weighted by time.
    assert first["pace_min_per_km"] == pytest.approx(490 / 2 / 60, abs=0.001)
    assert first["avg_hr"] == round((170 * 240 + 174 * 250) / 490)
    assert first["avg_cadence_spm"] == round((184 * 240 + 182 * 250) / 490)
    assert first["work_laps"] == 2
    assert first["distance_m"] == 10000.0
    # Seeded laps have no intensity: the whole activity counts.
    assert second["work_laps"] == 0
    assert second["pace_min_per_km"] == 5.0
    assert first["date"] < second["date"]


def test_strength_progress_has_every_set(client: TestClient, token: str) -> None:
    for activity_id in (3, 4):
        assert put_tag(client, token, activity_id, "legs").status_code == 200
    data = get(client, token, "/api/v1/progress/legs")
    (squat,) = data["strength"]
    assert squat["exercise"] == "BARBELL_BACK_SQUAT"
    assert squat["sessions"] == 2
    assert [(s["set_number"], s["weight_kg"], s["reps"]) for s in squat["sets"]] == [
        (1, 80.0, 5),
        (2, 82.5, 5),
        (1, 80.0, 5),
        (2, 82.5, 5),
    ]


def test_tag_list_counts(client: TestClient, token: str) -> None:
    put_tag(client, token, 1, "intervals")
    put_tag(client, token, 2, "intervals")
    put_tag(client, token, 5, "wall")
    data = get(client, token, "/api/v1/progress")
    assert [(t["tag"], t["type"], t["count"]) for t in data["tags"]] == [
        ("all_running", "running", 2),
        ("all_strength", "strength", 2),
        ("intervals", "running", 2),
        ("wall", "climbing", 1),
    ]
    assert data["tags"][2]["last_date"] == "2026-11-10"
    assert "all_running" not in data["available"]
    assert data["available"]["fbw"] == "strength"
    assert (
        client.get(
            "/api/v1/progress/nope", headers={"Authorization": f"Bearer {token}"}
        ).status_code
        == 404
    )


def test_set_tag_errors_for_claude(settings: Settings) -> None:
    with open_db(settings)() as session:
        user = add_user(session, "c")
        add_activity(session, user, 7, datetime(2026, 11, 3, 18), ActivityKind.OTHER)
        session.commit()
        with pytest.raises(TagError, match="allowed: none"):
            set_tag(session, user_id=user.id, activity_id=7, tag="base")
        with pytest.raises(TagError, match="No activity"):
            set_tag(session, user_id=user.id, activity_id=8, tag="base")


def test_run_point_without_details(settings: Settings) -> None:
    with open_db(settings)() as session:
        user = add_user(session, "d")
        activity = add_activity(session, user, 7, datetime(2026, 11, 3, 18))
        point = run_point(activity)
    assert point.work_laps == 0
    assert point.pace_s_per_km == 300.0


def test_climbing_progress_counts_boulders_and_covers_untagged_sessions(
    client: TestClient, token: str, settings: Settings
) -> None:
    with open_db(settings)() as session:
        user = session.scalars(select(User).where(User.name == "anna")).one()
        climb = add_activity(
            session, user, 6, datetime(2026, 11, 20, 18), ActivityKind.CLIMBING, details=True
        )
        assert climb.details is not None
        climb.details.typed_splits_json = {
            "splits": [
                climb_split("V2", 3, sent=True),
                {"type": "CLIMB_REST", "duration": 120.0},
                climb_split("V3", 4, sent=True),
                climb_split("V5", 6, sent=False),
                climb_split("V5", 6, sent=False),
            ]
        }
        session.commit()

    # The only climbing tag covers untagged sessions too (5 has no typed splits: no point).
    counts = {t["tag"]: t["count"] for t in get(client, token, "/api/v1/progress")["tags"]}
    assert counts["wall"] == 2
    data = get(client, token, "/api/v1/progress/wall")
    (only,) = data["climbing"]
    assert only["activity_id"] == 6
    assert (only["top_grade"], only["top_sort"], only["sent"], only["failed"]) == ("V3", 4, 2, 2)
    assert [(g["grade"], g["sent"], g["failed"]) for g in only["grades"]] == [
        ("V2", 1, 0),
        ("V3", 1, 0),
        ("V5", 0, 2),
    ]


def test_all_runs_without_tags(client: TestClient, token: str) -> None:
    # Nothing tagged: the all_* groups still show every run and strength session.
    counts = {t["tag"]: t["count"] for t in get(client, token, "/api/v1/progress")["tags"]}
    assert counts == {"all_running": 2, "all_strength": 2, "wall": 1}
    runs = get(client, token, "/api/v1/progress/all_running")
    assert runs["type"] == "running"
    assert [r["activity_id"] for r in runs["running"]] == [1, 2]
    (squat,) = get(client, token, "/api/v1/progress/all_strength")["strength"]
    assert squat["sessions"] == 2
    assert put_tag(client, token, 1, "all_running").status_code == 422
