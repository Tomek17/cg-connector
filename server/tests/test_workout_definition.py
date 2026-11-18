"""Our own workout definition (v1): validation, readable errors, text preview."""

from typing import Any

import pytest

from cg_server.workout_definition import (
    MAX_STEPS,
    DefinitionError,
    RunningDefinition,
    StrengthDefinition,
    parse_definition,
    preview,
)


def run(steps: list[dict[str, Any]], **extra: Any) -> dict[str, Any]:
    return {"sport": "running", "name": "5x1km", "steps": steps, **extra}


INTERVALS = run(
    [
        {"type": "warmup", "seconds": 600},
        {
            "type": "repeat",
            "times": 5,
            "steps": [
                {
                    "type": "interval",
                    "distance_km": 1,
                    "target": {"type": "pace", "from": "4:30", "to": "4:15"},
                },
                {"type": "recovery", "seconds": 90},
            ],
        },
        {"type": "cooldown", "lap_button": True},
    ]
)

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
        },
        {"exercise": "plank", "sets": 2, "seconds": 45},
    ],
}


def error_of(data: Any) -> str:
    with pytest.raises(DefinitionError) as info:
        parse_definition(data)
    return str(info.value)


def test_running_definition_is_parsed() -> None:
    definition = parse_definition(INTERVALS)
    assert isinstance(definition, RunningDefinition)
    assert definition.version == 1
    repeat = definition.steps[1]
    assert repeat.type == "repeat"


def test_strength_exercise_name_is_resolved_case_insensitively() -> None:
    definition = parse_definition(STRENGTH)
    assert isinstance(definition, StrengthDefinition)
    assert definition.exercises[1].exercise == "Plank"
    assert definition.exercises[0].rest_seconds == 120
    assert definition.exercises[1].rest_seconds == 60  # default


def test_unknown_exercise_lists_suggestions() -> None:
    data = {**STRENGTH, "exercises": [{"exercise": "Back Squat", "sets": 3, "reps": 5}]}
    message = error_of(data)
    assert "Unknown exercise 'Back Squat'" in message
    assert "Barbell Back Squat" in message
    assert "exercises.0.exercise" in message


def test_unknown_exercise_without_any_match() -> None:
    data = {**STRENGTH, "exercises": [{"exercise": "Xyzzy", "sets": 3, "reps": 5}]}
    assert "Unknown exercise 'Xyzzy'" in error_of(data)


@pytest.mark.parametrize(
    ("step", "expected"),
    [
        ({"type": "interval"}, "exactly one of seconds, distance_km, lap_button"),
        ({"type": "interval", "seconds": 60, "distance_km": 1}, "exactly one of"),
        ({"type": "interval", "seconds": 0}, "greater than 0"),
        ({"type": "jog", "seconds": 60}, "type"),
        (
            {
                "type": "interval",
                "seconds": 60,
                "target": {"type": "pace", "from": "4:75", "to": "4"},
            },
            "pace must look like '4:30'",
        ),
        ({"type": "interval", "seconds": 60, "target": {"type": "hr_zone", "zone": 6}}, "zone"),
        (
            {
                "type": "interval",
                "seconds": 60,
                "target": {"type": "hr_range", "from": 170, "to": 150},
            },
            "'from' must be lower",
        ),
        (
            {
                "type": "repeat",
                "times": 3,
                "steps": [
                    {"type": "repeat", "times": 2, "steps": [{"type": "rest", "seconds": 9}]}
                ],
            },
            "Nested repeats",
        ),
    ],
)
def test_invalid_running_steps(step: dict[str, Any], expected: str) -> None:
    assert expected in error_of(run([step]))


def test_too_many_steps() -> None:
    steps = [{"type": "interval", "seconds": 60} for _ in range(MAX_STEPS + 1)]
    assert f"at most {MAX_STEPS} steps" in error_of(run(steps))


def test_steps_inside_repeats_count_towards_limit() -> None:
    inner = [{"type": "interval", "seconds": 60} for _ in range(MAX_STEPS)]  # + the repeat
    assert "at most" in error_of(run([{"type": "repeat", "times": 2, "steps": inner}]))


def test_strength_needs_reps_or_seconds() -> None:
    data = {**STRENGTH, "exercises": [{"exercise": "Plank", "sets": 3}]}
    assert "exactly one of reps, seconds" in error_of(data)


def test_unknown_fields_are_rejected() -> None:
    assert "Extra inputs" in error_of(run([{"type": "warmup", "seconds": 60, "pace": "5:00"}]))


def test_unknown_sport_and_version() -> None:
    assert "sport" in error_of({"sport": "swimming", "name": "x"})
    assert "version" in error_of({**INTERVALS, "version": 2})


def test_empty_name_is_rejected() -> None:
    assert "name" in error_of({**INTERVALS, "name": "  "})


def test_json_round_trip_keeps_aliases() -> None:
    definition = parse_definition(INTERVALS)
    dumped = definition.model_dump(mode="json", by_alias=True, exclude_none=True)
    assert dumped["steps"][1]["steps"][0]["target"] == {
        "type": "pace",
        "from": "4:30",
        "to": "4:15",
    }
    assert parse_definition(dumped) == definition


def test_running_preview() -> None:
    text = preview(parse_definition(INTERVALS))
    assert text == (
        "Running: 5x1km\n"
        "1. Warmup 10:00\n"
        "2. Repeat 5x:\n"
        "   a. Interval 1 km @ 4:15-4:30 /km\n"
        "   b. Recovery 1:30\n"
        "3. Cooldown until lap button"
    )


def test_strength_preview() -> None:
    text = preview(parse_definition(STRENGTH))
    assert text == (
        "Strength: Siła A\n"
        "1. Barbell Back Squat: 3 x 5 reps @ 80 kg, rest 2:00\n"
        "2. Plank: 2 x 0:45, rest 1:00"
    )


def test_hr_targets_in_preview() -> None:
    steps = [
        {"type": "interval", "seconds": 1200, "target": {"type": "hr_zone", "zone": 2}},
        {
            "type": "interval",
            "distance_km": 0.4,
            "target": {"type": "hr_range", "from": 150, "to": 165},
        },
    ]
    assert preview(parse_definition(run(steps))).splitlines()[1:] == [
        "1. Interval 20:00 @ HR zone 2",
        "2. Interval 0.4 km @ 150-165 bpm",
    ]
