"""Mapping our definition -> garminconnect 0.3.20 typed models -> Garmin JSON (snapshots).

The snapshots follow the JSON generated offline with the library in
docs/research/garmin.md (sections 3.3 and 3.4); all data is synthetic.
"""

import json
from pathlib import Path
from typing import Any

from garminconnect.workout import (
    RunningWorkout,
    StrengthWorkout,
    create_strength_set,
    create_warmup_step,
)

from cg_server.workout_definition import parse_definition
from cg_server.workout_garmin import GARMIN_NAME_PREFIX, garmin_json, to_garmin_workout

SNAPSHOTS = Path(__file__).parent / "snapshots"


def snapshot(name: str) -> dict[str, Any]:
    data: dict[str, Any] = json.loads((SNAPSHOTS / name).read_text())
    return data


RUN_5X1KM = {
    "sport": "running",
    "name": "5x1km",
    "steps": [
        {"type": "warmup", "seconds": 600},
        {
            "type": "repeat",
            "times": 5,
            "steps": [
                {
                    "type": "interval",
                    "distance_km": 1,
                    "target": {"type": "pace", "from": "4:15", "to": "4:30"},
                },
                {"type": "recovery", "seconds": 90},
            ],
        },
        {"type": "cooldown", "seconds": 600},
    ],
}


def test_running_snapshot() -> None:
    assert garmin_json(parse_definition(RUN_5X1KM)) == snapshot("running_5x1km.json")


def test_running_uses_typed_model() -> None:
    assert isinstance(to_garmin_workout(parse_definition(RUN_5X1KM)), RunningWorkout)


def test_strength_snapshot() -> None:
    definition = parse_definition(
        {
            "sport": "strength",
            "name": "Sila A",
            "exercises": [
                {
                    "exercise": "barbell back squat",
                    "sets": 3,
                    "reps": 5,
                    "weight_kg": 80,
                    "rest_seconds": 120,
                }
            ],
        }
    )
    workout = to_garmin_workout(definition)
    assert isinstance(workout, StrengthWorkout)
    assert garmin_json(definition) == snapshot("strength_squat.json")


def test_same_as_library_helpers() -> None:
    """Our generic step builder gives the same JSON as garminconnect's helpers."""
    data = garmin_json(
        parse_definition(
            {"sport": "running", "name": "x", "steps": [{"type": "warmup", "seconds": 300}]}
        )
    )
    expected_warmup = create_warmup_step(300).model_dump(exclude_none=True, mode="json")
    assert data["workoutSegments"][0]["workoutSteps"][0] == expected_warmup
    strength = garmin_json(
        parse_definition(
            {
                "sport": "strength",
                "name": "x",
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
        )
    )
    expected = create_strength_set(
        "SQUAT", 1, 3, 5, 120, exercise_name="BARBELL_BACK_SQUAT", weight_kg=80
    ).model_dump(exclude_none=True, mode="json")
    assert strength["workoutSegments"][0]["workoutSteps"][0] == expected


def steps_of(definition: dict[str, Any]) -> list[dict[str, Any]]:
    steps: list[dict[str, Any]] = garmin_json(parse_definition(definition))["workoutSegments"][0][
        "workoutSteps"
    ]
    return steps


def test_lap_button_hr_targets_and_rest() -> None:
    steps = steps_of(
        {
            "sport": "running",
            "name": "hr",
            "description": "Easy run",
            "steps": [
                {"type": "warmup", "lap_button": True},
                {"type": "interval", "seconds": 1200, "target": {"type": "hr_zone", "zone": 2}},
                {"type": "rest", "seconds": 60},
                {
                    "type": "interval",
                    "distance_km": 2.5,
                    "target": {"type": "hr_range", "from": 140, "to": 155},
                },
            ],
        }
    )
    assert steps[0]["endCondition"] == {
        "conditionTypeId": 1,
        "conditionTypeKey": "lap.button",
        "displayOrder": 1,
        "displayable": True,
    }
    assert "endConditionValue" not in steps[0]
    hr_zone = {
        "workoutTargetTypeId": 4,
        "workoutTargetTypeKey": "heart.rate.zone",
        "displayOrder": 1,
    }
    assert steps[1]["targetType"] == hr_zone
    assert steps[1]["zoneNumber"] == 2
    assert "targetValueOne" not in steps[1]
    assert steps[2]["stepType"] == {"stepTypeId": 5, "stepTypeKey": "rest", "displayOrder": 5}
    assert steps[3]["endConditionValue"] == 2500.0
    assert steps[3]["targetType"] == hr_zone
    assert (steps[3]["targetValueOne"], steps[3]["targetValueTwo"]) == (140.0, 155.0)
    assert [s["stepOrder"] for s in steps] == [1, 2, 3, 4]


def test_strength_timed_and_no_rest_keep_step_order_unique() -> None:
    steps = steps_of(
        {
            "sport": "strength",
            "name": "core",
            "exercises": [
                {"exercise": "Plank", "sets": 3, "seconds": 45, "rest_seconds": 30},
                {"exercise": "Push-up", "sets": 2, "reps": 15, "rest_seconds": 0},
                {"exercise": "Barbell Back Squat", "sets": 2, "reps": 5, "weight_kg": 62.5},
            ],
        }
    )
    plank, pushups, squat = steps
    assert plank["workoutSteps"][0]["endCondition"]["conditionTypeKey"] == "time"
    assert plank["workoutSteps"][0]["endConditionValue"] == 45.0
    assert "weightValue" not in plank["workoutSteps"][0]
    assert len(pushups["workoutSteps"]) == 1  # no rest step
    assert squat["workoutSteps"][0]["weightValue"] == 62500.0  # grams
    orders = [plank["stepOrder"], *(s["stepOrder"] for s in plank["workoutSteps"])]
    orders += [pushups["stepOrder"], *(s["stepOrder"] for s in pushups["workoutSteps"])]
    orders += [squat["stepOrder"], *(s["stepOrder"] for s in squat["workoutSteps"])]
    assert orders == list(range(1, 9))


def test_garmin_name_has_prefix_and_description() -> None:
    data = garmin_json(
        parse_definition(
            {**RUN_5X1KM, "description": "Tempo"}  # type: ignore[dict-item]
        )
    )
    assert data["workoutName"] == f"{GARMIN_NAME_PREFIX}5x1km"
    assert data["description"] == "Tempo"


def test_bouldering_goes_as_garmin_other_with_description_on_step() -> None:
    definition = parse_definition(
        {"sport": "bouldering", "name": "Ścianka", "description": "Rozgrzewka na łatwych"}
    )
    body = garmin_json(definition)
    assert body["workoutName"] == f"{GARMIN_NAME_PREFIX}Ścianka"
    assert body["sportType"]["sportTypeKey"] == "other"
    assert body["description"] == "Rozgrzewka na łatwych"
    (segment,) = body["workoutSegments"]
    assert segment["sportType"] == {"sportTypeId": 3, "sportTypeKey": "other"}
    (step,) = segment["workoutSteps"]
    assert step["endCondition"]["conditionTypeKey"] == "lap.button"
    assert step["description"] == "Rozgrzewka na łatwych"
    assert body["estimatedDurationInSecs"] == 0


def test_other_with_minutes_has_time_step() -> None:
    body = garmin_json(
        parse_definition({"sport": "other", "name": "Rower", "description": "Luz", "minutes": 60})
    )
    (step,) = body["workoutSegments"][0]["workoutSteps"]
    assert step["endCondition"]["conditionTypeKey"] == "time"
    assert step["endConditionValue"] == 3600.0
    assert body["estimatedDurationInSecs"] == 3600
