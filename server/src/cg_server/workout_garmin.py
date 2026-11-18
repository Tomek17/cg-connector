"""Our workout definition -> garminconnect 0.3.20 typed models -> Garmin JSON.

The JSON is built only through the library's Pydantic models and helpers
(docs/research/garmin.md section 3), never by hand. Notes:
- pace targets are in m/s, `targetValueOne` = slower limit, `targetValueTwo` = faster;
- `stepOrder` is unique and increasing in the whole workout, repeat groups included;
- strength weight is stored by Garmin in grams (`create_strength_set(weight_kg=...)`).
"""

import math
from typing import Any

from garminconnect import exercises as catalog
from garminconnect.workout import (
    BaseWorkout,
    ConditionType,
    CustomHeartRateTarget,
    ExecutableStep,
    HeartRateZoneTarget,
    PaceTarget,
    RepeatGroup,
    RunningWorkout,
    SportType,
    StepType,
    StrengthWorkout,
    TargetType,
    WorkoutSegment,
    create_repeat_group,
    create_strength_exercise_step,
    create_strength_set,
    create_strength_timed_set,
    create_strength_timed_step,
)

from cg_server import workout_definition as wd

# Visible in Garmin Connect and on the watch: marks workouts created by CG-connector.
GARMIN_NAME_PREFIX = "CG "

STEP_TYPES = {
    "warmup": (StepType.WARMUP, 1),
    "cooldown": (StepType.COOLDOWN, 2),
    "interval": (StepType.INTERVAL, 3),
    "recovery": (StepType.RECOVERY, 4),
    "rest": (StepType.REST, 5),
}
END_TIME = {
    "conditionTypeId": ConditionType.TIME,
    "conditionTypeKey": "time",
    "displayOrder": 2,
    "displayable": True,
}
END_DISTANCE = {
    "conditionTypeId": ConditionType.DISTANCE,
    "conditionTypeKey": "distance",
    "displayOrder": 3,
    "displayable": True,
}
END_LAP_BUTTON = {
    "conditionTypeId": ConditionType.LAP_BUTTON,
    "conditionTypeKey": "lap.button",
    "displayOrder": 1,
    "displayable": True,
}
NO_TARGET = {
    "workoutTargetTypeId": TargetType.NO_TARGET,
    "workoutTargetTypeKey": "no.target",
    "displayOrder": 1,
}


def _target(target: wd.PaceTarget | wd.HrZoneTarget | wd.HrRangeTarget | None) -> dict[str, Any]:
    """ExecutableStep fields of a target, via the library's target models."""
    garmin: PaceTarget | HeartRateZoneTarget | CustomHeartRateTarget
    if target is None:
        return {"targetType": dict(NO_TARGET)}
    if isinstance(target, wd.PaceTarget):
        garmin = PaceTarget(
            lower_limit=1000 / target.slowest_s, upper_limit=1000 / target.fastest_s
        )
    elif isinstance(target, wd.HrZoneTarget):
        garmin = HeartRateZoneTarget(zone_number=target.zone)
    else:
        garmin = CustomHeartRateTarget(lower_limit=target.from_, upper_limit=target.to)
    return {
        "targetType": {
            "workoutTargetTypeId": garmin.target_type,
            "workoutTargetTypeKey": garmin.target_type_key,
            "displayOrder": 1,
        },
        "targetValueOne": garmin.lower_limit,
        "targetValueTwo": garmin.upper_limit,
        "zoneNumber": garmin.zone_number,
    }


def _run_step(step: wd.RunStep, order: int) -> ExecutableStep:
    type_id, display = STEP_TYPES[step.type]
    if step.seconds is not None:
        end, value = END_TIME, float(step.seconds)
    elif step.distance_km is not None:
        end, value = END_DISTANCE, round(step.distance_km * 1000, 1)
    else:
        end, value = END_LAP_BUTTON, None
    return ExecutableStep(
        stepOrder=order,
        stepType={"stepTypeId": type_id, "stepTypeKey": step.type, "displayOrder": display},
        endCondition=dict(end),
        endConditionValue=value,
        **_target(step.target),
    )


def _run_steps(steps: list[wd.Step], order: int) -> tuple[list[ExecutableStep | RepeatGroup], int]:
    """Garmin steps for `steps`, numbered from `order`; returns them and the next order."""
    result: list[ExecutableStep | RepeatGroup] = []
    for step in steps:
        if isinstance(step, wd.RepeatStep):
            group_order = order
            inner, order = _run_steps(step.steps, order + 1)
            result.append(create_repeat_group(step.times, inner, group_order))
        else:
            result.append(_run_step(step, order))
            order += 1
    return result, order


def _estimated_seconds(steps: list[wd.Step]) -> float:
    """Time steps plus distance steps with a pace target (middle of the range)."""
    total = 0.0
    for step in steps:
        if isinstance(step, wd.RepeatStep):
            total += step.times * _estimated_seconds(step.steps)
        elif step.seconds is not None:
            total += step.seconds
        elif step.distance_km is not None and isinstance(step.target, wd.PaceTarget):
            total += step.distance_km * (step.target.fastest_s + step.target.slowest_s) / 2
    return total


def _exercise_block(exercise: wd.Exercise, order: int) -> tuple[RepeatGroup, int]:
    entry = catalog.resolve(exercise.exercise)
    if entry is None:  # the definition was validated against the same catalog
        raise ValueError(f"Unknown exercise: {exercise.exercise}")
    category, name = entry["category"], entry["exercise"]
    weight = exercise.weight_kg or None
    if exercise.rest_seconds:
        if exercise.reps is not None:
            block = create_strength_set(
                category, order, exercise.sets, exercise.reps, exercise.rest_seconds, name, weight
            )
        else:
            block = create_strength_timed_set(
                category,
                order,
                exercise.sets,
                exercise.seconds or 0,
                exercise.rest_seconds,
                name,
                weight,
            )
        return block, order + 3
    if exercise.reps is not None:
        step = create_strength_exercise_step(category, order + 1, exercise.reps, name, weight)
    else:
        step = create_strength_timed_step(category, order + 1, exercise.seconds or 0, name, weight)
    return create_repeat_group(exercise.sets, [step], order), order + 2


def to_garmin_workout(
    definition: wd.RunningDefinition | wd.StrengthDefinition,
) -> BaseWorkout:
    name = f"{GARMIN_NAME_PREFIX}{definition.name}"
    if isinstance(definition, wd.StrengthDefinition):
        blocks: list[ExecutableStep | RepeatGroup] = []
        order = 1
        for exercise in definition.exercises:
            block, order = _exercise_block(exercise, order)
            blocks.append(block)
        return StrengthWorkout(
            workoutName=name,
            estimatedDurationInSecs=0,
            description=definition.description,
            workoutSegments=[
                WorkoutSegment(
                    segmentOrder=1,
                    sportType={
                        "sportTypeId": SportType.STRENGTH_TRAINING,
                        "sportTypeKey": "strength_training",
                    },
                    workoutSteps=blocks,
                )
            ],
        )
    steps, _ = _run_steps(definition.steps, 1)
    return RunningWorkout(
        workoutName=name,
        estimatedDurationInSecs=math.ceil(_estimated_seconds(definition.steps)),
        description=definition.description,
        workoutSegments=[
            WorkoutSegment(
                segmentOrder=1,
                sportType={"sportTypeId": SportType.RUNNING, "sportTypeKey": "running"},
                workoutSteps=steps,
            )
        ],
    )


def garmin_json(definition: wd.RunningDefinition | wd.StrengthDefinition) -> dict[str, Any]:
    """The body of `POST /workout-service/workout`."""
    return to_garmin_workout(definition).to_dict()
