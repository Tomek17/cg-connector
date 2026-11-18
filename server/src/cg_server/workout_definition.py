"""Our own workout definition (version 1), independent of Garmin's format.

Used by MCP (`create_workout`) and REST (`POST /api/v1/workouts`); stored as JSON in
`workouts.definition_json` and mapped to Garmin models only when sending
(`cg_server.workout_garmin`). Validation messages are in English, for Claude.

Running:
    {"sport": "running", "name": "5x1km", "steps": [
        {"type": "warmup", "seconds": 600},
        {"type": "repeat", "times": 5, "steps": [
            {"type": "interval", "distance_km": 1,
             "target": {"type": "pace", "from": "4:30", "to": "4:15"}},
            {"type": "recovery", "seconds": 90}]},
        {"type": "cooldown", "lap_button": true}]}
    Step end: exactly one of `seconds`, `distance_km`, `lap_button`. Target (optional):
    pace in min/km, `hr_zone` (1-5) or `hr_range` in bpm.

Strength (exercise names from Garmin's catalog, `garminconnect.exercises`):
    {"sport": "strength", "name": "Strength A", "exercises": [
        {"exercise": "Barbell Back Squat", "sets": 3, "reps": 5, "weight_kg": 80,
         "rest_seconds": 120},
        {"exercise": "Plank", "sets": 3, "seconds": 45}]}
"""

import re
from typing import Annotated, Any, Literal, Self

from garminconnect import exercises as catalog
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    TypeAdapter,
    ValidationError,
    field_validator,
    model_validator,
)

VERSION: Literal[1] = 1
MAX_STEPS = 50  # running: executable steps + repeat groups, nested ones included
MAX_EXERCISES = 30
MAX_SUGGESTIONS = 5
PACE_PATTERN = re.compile(r"(\d{1,2}):([0-5]\d)")
MIN_PACE_S, MAX_PACE_S = 2 * 60, 20 * 60

Name = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=60)]
Description = Annotated[str, StringConstraints(strip_whitespace=True, max_length=200)]


class DefinitionError(ValueError):
    """Invalid workout definition; the message lists every problem, one per line."""


class _Model(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True, frozen=True)


def pace_seconds(value: str) -> int:
    """'4:30' -> 270 (seconds per km)."""
    match = PACE_PATTERN.fullmatch(value.strip())
    if match is None:
        raise ValueError("pace must look like '4:30' (minutes:seconds per km)")
    seconds = int(match.group(1)) * 60 + int(match.group(2))
    if not MIN_PACE_S <= seconds <= MAX_PACE_S:
        raise ValueError("pace must be between 2:00 and 20:00 per km")
    return seconds


class PaceTarget(_Model):
    """Pace range in min/km, e.g. from '4:30' to '4:15' (order does not matter)."""

    type: Literal["pace"]
    from_: str = Field(alias="from")
    to: str

    @field_validator("from_", "to")
    @classmethod
    def _valid_pace(cls, value: str) -> str:
        pace_seconds(value)
        return value.strip()

    @property
    def fastest_s(self) -> int:
        return min(pace_seconds(self.from_), pace_seconds(self.to))

    @property
    def slowest_s(self) -> int:
        return max(pace_seconds(self.from_), pace_seconds(self.to))


class HrZoneTarget(_Model):
    """One of the heart rate zones configured on the watch."""

    type: Literal["hr_zone"]
    zone: int = Field(ge=1, le=5)


class HrRangeTarget(_Model):
    """Heart rate range in bpm."""

    type: Literal["hr_range"]
    from_: int = Field(alias="from", ge=30, le=250)
    to: int = Field(ge=30, le=250)

    @model_validator(mode="after")
    def _ordered(self) -> Self:
        if self.from_ >= self.to:
            raise ValueError("'from' must be lower than 'to' (bpm)")
        return self


Target = Annotated[PaceTarget | HrZoneTarget | HrRangeTarget, Field(discriminator="type")]


class RunStep(_Model):
    type: Literal["warmup", "interval", "recovery", "cooldown", "rest"]
    seconds: int | None = Field(default=None, gt=0, le=6 * 3600)
    distance_km: float | None = Field(default=None, gt=0, le=100)
    lap_button: bool | None = None
    target: Target | None = None

    @model_validator(mode="after")
    def _one_end_condition(self) -> Self:
        ends = [self.seconds is not None, self.distance_km is not None, bool(self.lap_button)]
        if sum(ends) != 1:
            raise ValueError("Step needs exactly one of seconds, distance_km, lap_button")
        return self


class RepeatStep(_Model):
    type: Literal["repeat"]
    times: int = Field(ge=1, le=50)
    steps: list[RunStep] = Field(min_length=1)

    @field_validator("steps", mode="before")
    @classmethod
    def _flat(cls, steps: Any) -> Any:
        if isinstance(steps, list) and any(
            isinstance(s, dict) and s.get("type") == "repeat" for s in steps
        ):
            raise ValueError("Nested repeats are not supported")
        return steps


Step = Annotated[RunStep | RepeatStep, Field(discriminator="type")]


def count_steps(steps: list[Step]) -> int:
    return sum(1 + (len(s.steps) if isinstance(s, RepeatStep) else 0) for s in steps)


class RunningDefinition(_Model):
    version: Literal[1] = VERSION
    sport: Literal["running"]
    name: Name
    description: Description | None = None
    steps: list[Step] = Field(min_length=1)

    @model_validator(mode="after")
    def _step_limit(self) -> Self:
        if count_steps(self.steps) > MAX_STEPS:
            raise ValueError(f"A workout can have at most {MAX_STEPS} steps (repeats included)")
        return self


_BY_LOWER_NAME = {" ".join(e["name"].lower().split()): e for e in catalog.EXERCISES}


def resolve_exercise(name: str) -> dict[str, str] | None:
    """Catalog entry ({name, category, exercise}) for a display name, any letter case."""
    return catalog.resolve(name) or _BY_LOWER_NAME.get(" ".join(name.lower().split()))


def suggest_exercises(name: str) -> list[str]:
    """Catalog names similar to `name`: containing it, else sharing the most words."""
    found = [e["name"] for e in catalog.find(name.strip())]
    if not found:
        words = [w for w in re.split(r"[\s\-_,]+", name.lower()) if len(w) >= 3]
        scores: dict[str, int] = {}
        for word in words:
            for entry in catalog.find(word):
                scores[entry["name"]] = scores.get(entry["name"], 0) + 1
        found = sorted(scores, key=lambda n: (-scores[n], len(n), n))
    else:
        found.sort(key=lambda n: (len(n), n))
    return found[:MAX_SUGGESTIONS]


class Exercise(_Model):
    exercise: str = Field(max_length=100)
    sets: int = Field(ge=1, le=20)
    reps: int | None = Field(default=None, ge=1, le=100)
    seconds: int | None = Field(default=None, ge=1, le=3600)
    weight_kg: float | None = Field(default=None, ge=0, le=500)
    rest_seconds: int = Field(default=60, ge=0, le=900)

    @field_validator("exercise")
    @classmethod
    def _in_catalog(cls, value: str) -> str:
        entry = resolve_exercise(value)
        if entry is not None:
            return entry["name"]
        hints = suggest_exercises(value)
        hint = f" Did you mean: {', '.join(hints)}?" if hints else ""
        raise ValueError(
            f"Unknown exercise '{value}'.{hint} Use an exact name from Garmin's exercise catalog."
        )

    @model_validator(mode="after")
    def _reps_or_time(self) -> Self:
        if (self.reps is None) == (self.seconds is None):
            raise ValueError("Exercise needs exactly one of reps, seconds")
        return self


class StrengthDefinition(_Model):
    version: Literal[1] = VERSION
    sport: Literal["strength"]
    name: Name
    description: Description | None = None
    exercises: list[Exercise] = Field(min_length=1, max_length=MAX_EXERCISES)


WorkoutDefinition = Annotated[RunningDefinition | StrengthDefinition, Field(discriminator="sport")]
_ADAPTER: TypeAdapter[RunningDefinition | StrengthDefinition] = TypeAdapter(WorkoutDefinition)
# Discriminator values that pydantic puts into error locations; not useful for the reader.
_TAGS = {"running", "strength", "pace", "hr_zone", "hr_range", "repeat"} | set(
    RunStep.model_fields["type"].annotation.__args__  # type: ignore[union-attr]
)


def _format_error(error: Any) -> str:
    loc = ".".join(
        str(p) for p in error["loc"] if p not in _TAGS and not str(p).startswith("function")
    )
    message = str(error["msg"]).removeprefix("Value error, ")
    return f"{loc}: {message}" if loc else message


def parse_definition(data: Any) -> RunningDefinition | StrengthDefinition:
    """Validate a definition (dict from JSON); raise DefinitionError with readable lines."""
    if isinstance(data, RunningDefinition | StrengthDefinition):
        return data
    try:
        return _ADAPTER.validate_python(data)
    except ValidationError as e:
        lines = list(dict.fromkeys(_format_error(err) for err in e.errors()))
        raise DefinitionError("Invalid workout definition:\n" + "\n".join(lines)) from None


def to_json(definition: RunningDefinition | StrengthDefinition) -> dict[str, Any]:
    """The form stored in the database and returned by the API."""
    return definition.model_dump(mode="json", by_alias=True, exclude_none=True)


def format_duration(seconds: float) -> str:
    total = int(round(seconds))
    hours, rest = divmod(total, 3600)
    minutes, secs = divmod(rest, 60)
    return f"{hours}:{minutes:02d}:{secs:02d}" if hours else f"{minutes}:{secs:02d}"


def _pace(seconds: int) -> str:
    return f"{seconds // 60}:{seconds % 60:02d}"


def _target_text(target: PaceTarget | HrZoneTarget | HrRangeTarget | None) -> str:
    if isinstance(target, PaceTarget):
        return f" @ {_pace(target.fastest_s)}-{_pace(target.slowest_s)} /km"
    if isinstance(target, HrZoneTarget):
        return f" @ HR zone {target.zone}"
    if isinstance(target, HrRangeTarget):
        return f" @ {target.from_}-{target.to} bpm"
    return ""


def _step_text(step: RunStep) -> str:
    if step.seconds is not None:
        end = format_duration(step.seconds)
    elif step.distance_km is not None:
        end = f"{step.distance_km:g} km"
    else:
        end = "until lap button"
    return f"{step.type.capitalize()} {end}{_target_text(step.target)}"


def _exercise_text(exercise: Exercise) -> str:
    amount = f"{exercise.reps} reps" if exercise.reps is not None else None
    if amount is None:
        amount = format_duration(exercise.seconds or 0)
    text = f"{exercise.exercise}: {exercise.sets} x {amount}"
    if exercise.weight_kg:
        text += f" @ {exercise.weight_kg:g} kg"
    if exercise.rest_seconds:
        text += f", rest {format_duration(exercise.rest_seconds)}"
    return text


def _letter(index: int) -> str:
    return chr(ord("a") + index) if index < 26 else str(index + 1)


def preview(definition: RunningDefinition | StrengthDefinition) -> str:
    """Short text form of a workout, for Claude and the app."""
    if isinstance(definition, StrengthDefinition):
        lines = [f"Strength: {definition.name}"]
        lines += [f"{i}. {_exercise_text(e)}" for i, e in enumerate(definition.exercises, 1)]
        return "\n".join(lines)
    lines = [f"Running: {definition.name}"]
    for i, step in enumerate(definition.steps, 1):
        if isinstance(step, RepeatStep):
            lines.append(f"{i}. Repeat {step.times}x:")
            lines += [f"   {_letter(j)}. {_step_text(inner)}" for j, inner in enumerate(step.steps)]
        else:
            lines.append(f"{i}. {_step_text(step)}")
    return "\n".join(lines)
