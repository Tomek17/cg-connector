"""REST read API for the Android app: activities, details, VO2max, summaries, /me."""

from collections.abc import Iterator
from datetime import UTC, date, datetime, timedelta
from typing import Any

import pytest
from fastapi.testclient import TestClient

from cg_server.app import create_app
from cg_server.config import Settings
from cg_server.db import open_db
from cg_server.models import ActivityKind, GarminStatus, SyncStatus, utcnow
from cg_server.user_secrets import SecretKind, issue_secret
from cg_server.users import add_user, get_user
from tests.helpers import BASE, PUBLIC_HOST
from tests.seed import add_activity, add_sync, add_vo2max, set_garmin_status


@pytest.fixture
def tokens(settings: Settings) -> dict[str, str]:
    """App tokens of two users with synthetic data. A: ids 101-104, B: id 201."""
    result = {}
    with open_db(settings)() as session:
        a, b = add_user(session, "anna"), add_user(session, "bartek")
        add_activity(session, a, 101, datetime(2026, 11, 2, 7, 30), details=True)
        add_activity(
            session, a, 102, datetime(2026, 11, 4, 18), ActivityKind.STRENGTH, details=True
        )
        add_activity(session, a, 103, datetime(2026, 11, 9, 7), ActivityKind.CLIMBING)
        add_activity(
            session, a, 104, datetime(2026, 11, 16, 7), distance_m=5000.0, duration_s=1500.0
        )
        add_activity(session, b, 201, datetime(2026, 11, 3, 7))
        add_vo2max(session, a, date(2026, 11, 1), 51.5)
        add_vo2max(session, a, date(2026, 11, 10), 52.0)
        add_vo2max(session, b, date(2026, 11, 5), 40.0)
        add_sync(session, a, datetime(2026, 11, 17, 13, 5, tzinfo=UTC))
        add_sync(session, a, datetime(2026, 11, 18, 13, 5, tzinfo=UTC), SyncStatus.FAILED)
        set_garmin_status(session, a, GarminStatus.OK)
        session.commit()
        result["a"] = issue_secret(session, a, SecretKind.APP_TOKEN)
        result["b"] = issue_secret(session, b, SecretKind.APP_TOKEN)
    return result


@pytest.fixture
def client(settings: Settings) -> Iterator[TestClient]:
    app = create_app(settings.model_copy(update={"public_host": PUBLIC_HOST}))
    yield TestClient(app, base_url=BASE)


def get(client: TestClient, token: str, path: str, **params: str | int) -> Any:
    response = client.get(path, params=params, headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200, response.text
    return response.json()


PATHS = [
    "/api/v1/activities",
    "/api/v1/activities/101",
    "/api/v1/vo2max",
    "/api/v1/summary",
]


@pytest.mark.parametrize("path", PATHS)
def test_read_endpoints_require_app_token(
    client: TestClient, tokens: dict[str, str], path: str
) -> None:
    assert client.get(path).status_code == 401
    assert client.get(path, headers={"Authorization": "Bearer zly"}).status_code == 401


def test_me_has_sync_state(client: TestClient, tokens: dict[str, str]) -> None:
    me = get(client, tokens["a"], "/api/v1/me")
    assert me["name"] == "anna"
    assert me["display_name"] == "anna"
    assert me["garmin_status"] == "ok"
    assert me["last_sync_at"] == "2026-11-17T14:05:00+01:00"
    assert me["last_sync_attempt_at"] == "2026-11-18T14:03:00+01:00"
    assert me["last_sync_status"] == "failed"
    assert me["latest_activity_date"] == "2026-11-16"

    other = get(client, tokens["b"], "/api/v1/me")
    assert other["name"] == "bartek"
    assert other["last_sync_at"] is None
    assert other["garmin_status"] is None
    assert other["latest_activity_date"] == "2026-11-03"


def test_activities_list_matches_contract(client: TestClient, tokens: dict[str, str]) -> None:
    data = get(
        client, tokens["a"], "/api/v1/activities", **{"from": "2026-11-01", "to": "2026-11-30"}
    )
    items = data["items"]
    assert isinstance(items, list)
    assert [x["id"] for x in items] == [104, 103, 102, 101]
    assert data["has_more"] is False
    run = items[3]
    assert run == {
        "id": 101,
        "type": "running",
        "type_key": "running",
        "name": "Aktywność 101",
        "start_time": "2026-11-02T07:30:00+01:00",
        "duration_sec": 3000,
        "moving_duration_sec": 2950,
        "distance_m": 10000.0,
        "avg_hr": 150,
        "max_hr": 176,
        "elevation_gain_m": 85.0,
        "avg_power_w": 290,
        "avg_cadence_spm": 172,
        "avg_speed_mps": 3.3333,
        "pace_min_per_km": 5.0,
        "aerobic_training_effect": 3.2,
        "anaerobic_training_effect": 1.1,
        "training_load": 120.5,
    }
    strength = items[2]
    assert strength["type"] == "strength"
    assert strength["distance_m"] is None
    assert strength["pace_min_per_km"] is None


def test_activities_filters_type_range_and_limit(
    client: TestClient, tokens: dict[str, str]
) -> None:
    token = tokens["a"]
    climbing = get(client, token, "/api/v1/activities", type="climbing")["items"]
    assert [x["id"] for x in climbing] == [103]
    window = get(client, token, "/api/v1/activities", **{"from": "2026-11-03", "to": "2026-11-09"})
    assert [x["id"] for x in window["items"]] == [103, 102]
    limited = get(client, token, "/api/v1/activities", limit=2, **{"from": "2026-11-01"})
    assert [x["id"] for x in limited["items"]] == [104, 103]
    assert limited["has_more"] is True


def test_activities_default_range_is_last_365_days(
    client: TestClient, settings: Settings, tokens: dict[str, str]
) -> None:
    with open_db(settings)() as session:
        a = get_user(session, "anna")
        assert a is not None
        today = utcnow().date()
        add_activity(
            session, a, 301, datetime.combine(today - timedelta(days=400), datetime.min.time())
        )
        session.commit()
    ids = [x["id"] for x in get(client, tokens["a"], "/api/v1/activities")["items"]]
    assert 301 not in ids
    assert 101 in ids


@pytest.mark.parametrize(
    "params",
    [
        {"from": "2026-11-10", "to": "2026-11-01"},
        {"from": "nie-data"},
        {"type": "swimming"},
        {"limit": 0},
        {"limit": 5000},
    ],
)
def test_activities_rejects_bad_params(
    client: TestClient, tokens: dict[str, str], params: dict[str, str | int]
) -> None:
    response = client.get(
        "/api/v1/activities", params=params, headers={"Authorization": f"Bearer {tokens['a']}"}
    )
    assert response.status_code == 422


def test_users_do_not_see_each_others_activities(
    client: TestClient, tokens: dict[str, str]
) -> None:
    ids_a = {x["id"] for x in get(client, tokens["a"], "/api/v1/activities")["items"]}
    ids_b = {x["id"] for x in get(client, tokens["b"], "/api/v1/activities")["items"]}
    assert ids_a == {101, 102, 103, 104}
    assert ids_b == {201}
    headers = {"Authorization": f"Bearer {tokens['a']}"}
    response = client.get("/api/v1/activities/201", headers=headers)
    assert response.status_code == 404
    # Same answer as for an id that does not exist at all.
    assert response.json() == client.get("/api/v1/activities/999", headers=headers).json()


def test_activity_details_of_run(client: TestClient, tokens: dict[str, str]) -> None:
    run = get(client, tokens["a"], "/api/v1/activities/101")
    assert run["id"] == 101
    assert run["details_available"] is True
    laps = run["laps"]
    assert isinstance(laps, list)
    assert laps[0] == {
        "index": 1,
        "distance_m": 1000.0,
        "duration_sec": 299.0,
        "avg_hr": 149,
        "max_hr": 161,
        "avg_speed_mps": pytest.approx(1000.0 / 299.0),
        "pace_min_per_km": pytest.approx(299.0 / 60.0, abs=0.001),
        "elevation_gain_m": 5.0,
        "avg_power_w": None,
        "avg_cadence_spm": None,
    }
    assert run["hr_zones"][0] == {"zone": 1, "seconds": 600.0, "low_bpm": 115}
    assert run["exercise_sets"] == []


def test_activity_details_of_strength_and_missing_details(
    client: TestClient, tokens: dict[str, str]
) -> None:
    strength = get(client, tokens["a"], "/api/v1/activities/102")
    assert strength["exercise_sets"][0] == {
        "set_type": "ACTIVE",
        "category": "SQUAT",
        "exercise": "BARBELL_BACK_SQUAT",
        "reps": 5,
        "weight_kg": 80.0,
        "duration_sec": 30.0,
    }
    climbing = get(client, tokens["a"], "/api/v1/activities/103")
    assert climbing["details_available"] is False
    assert climbing["laps"] == []


def test_activity_id_must_be_integer(client: TestClient, tokens: dict[str, str]) -> None:
    response = client.get(
        "/api/v1/activities/abc", headers={"Authorization": f"Bearer {tokens['a']}"}
    )
    assert response.status_code == 422


def test_vo2max(client: TestClient, tokens: dict[str, str]) -> None:
    data = get(client, tokens["a"], "/api/v1/vo2max", **{"from": "2026-11-01", "to": "2026-11-30"})
    assert data["items"] == [
        {"date": "2026-11-01", "value": 51.5, "sport": "running"},
        {"date": "2026-11-10", "value": 52.0, "sport": "running"},
    ]
    later = get(client, tokens["a"], "/api/v1/vo2max", **{"from": "2026-11-05", "to": "2026-11-30"})
    assert len(later["items"]) == 1
    other = get(client, tokens["b"], "/api/v1/vo2max", **{"from": "2026-11-01", "to": "2026-11-30"})
    assert other["items"] == [{"date": "2026-11-05", "value": 40.0, "sport": "running"}]


def test_vo2max_rejects_reversed_range(client: TestClient, tokens: dict[str, str]) -> None:
    response = client.get(
        "/api/v1/vo2max",
        params={"from": "2026-11-10", "to": "2026-11-01"},
        headers={"Authorization": f"Bearer {tokens['a']}"},
    )
    assert response.status_code == 422


def test_weekly_summary(client: TestClient, tokens: dict[str, str]) -> None:
    data = get(
        client,
        tokens["a"],
        "/api/v1/summary",
        period="week",
        **{"from": "2026-11-01", "to": "2026-11-22"},
    )
    assert data["period"] == "week"
    assert data["from"] == "2026-11-01"
    assert data["to"] == "2026-11-22"
    items = data["items"]
    assert isinstance(items, list)
    assert [(x["period_start"], x["type"]) for x in items] == [
        ("2026-11-02", "running"),
        ("2026-11-02", "strength"),
        ("2026-11-09", "climbing"),
        ("2026-11-16", "running"),
    ]
    last = items[-1]
    assert last == {
        "period_start": "2026-11-16",
        "period_end": "2026-11-22",
        "type": "running",
        "count": 1,
        "distance_m": 5000.0,
        "duration_sec": 1500,
        "elevation_gain_m": 85.0,
        "avg_pace_min_per_km": 5.0,
    }


def test_monthly_summary_and_isolation(client: TestClient, tokens: dict[str, str]) -> None:
    data = get(
        client,
        tokens["b"],
        "/api/v1/summary",
        period="month",
        **{"from": "2026-11-01", "to": "2026-11-30"},
    )
    assert data["items"] == [
        {
            "period_start": "2026-11-01",
            "period_end": "2026-11-30",
            "type": "running",
            "count": 1,
            "distance_m": 10000.0,
            "duration_sec": 3000,
            "elevation_gain_m": 85.0,
            "avg_pace_min_per_km": 5.0,
        }
    ]


@pytest.mark.parametrize(
    "params",
    [
        {"period": "year"},
        {"period": "week", "from": "2026-11-10", "to": "2026-11-01"},
        {"period": "month", "from": "2020-01-01", "to": "2026-11-01"},
    ],
)
def test_summary_rejects_bad_params(
    client: TestClient, tokens: dict[str, str], params: dict[str, str]
) -> None:
    response = client.get(
        "/api/v1/summary", params=params, headers={"Authorization": f"Bearer {tokens['a']}"}
    )
    assert response.status_code == 422


@pytest.mark.parametrize("path", ["/api/v1/activities", "/api/v1/vo2max", "/api/v1/summary"])
@pytest.mark.parametrize(
    "params",
    [
        {"from": "0001-01-01"},
        {"to": "9999-12-31"},
        {"from": "9999-12-30", "to": "9999-12-31"},
        {"from": "1999-12-31", "to": "2000-01-05"},
        {"from": "2023-01-01", "to": "2026-01-01"},  # longer than 2 years
    ],
)
def test_dates_out_of_bounds_or_too_long_range_give_422(
    client: TestClient, tokens: dict[str, str], path: str, params: dict[str, str]
) -> None:
    response = client.get(path, params=params, headers={"Authorization": f"Bearer {tokens['a']}"})
    assert response.status_code == 422
    assert isinstance(response.json()["detail"], str)


def test_two_year_range_is_allowed(client: TestClient, tokens: dict[str, str]) -> None:
    params = {"from": "2024-11-18", "to": "2026-11-18"}
    for path in ["/api/v1/activities", "/api/v1/vo2max", "/api/v1/summary"]:
        get(client, tokens["a"], path, **params)


def test_summer_time_offset_and_new_year_weeks(
    client: TestClient, settings: Settings, tokens: dict[str, str]
) -> None:
    with open_db(settings)() as session:
        a = get_user(session, "anna")
        assert a is not None
        add_activity(session, a, 401, datetime(2026, 7, 2, 0, 30))  # 22:30 UTC on Jul 1
        add_activity(session, a, 402, datetime(2026, 12, 31, 8))  # Thu
        add_activity(session, a, 403, datetime(2027, 1, 3, 9))  # Sun, same week
        session.commit()
    token = tokens["a"]
    summer = get(client, token, "/api/v1/activities", **{"from": "2026-07-02", "to": "2026-07-02"})
    assert [(x["id"], x["start_time"]) for x in summer["items"]] == [
        (401, "2026-07-02T00:30:00+02:00")
    ]
    weeks = get(
        client,
        token,
        "/api/v1/summary",
        period="week",
        **{"from": "2026-12-28", "to": "2027-01-03"},
    )
    assert [(x["period_start"], x["period_end"], x["count"]) for x in weeks["items"]] == [
        ("2026-12-28", "2027-01-03", 2)
    ]
