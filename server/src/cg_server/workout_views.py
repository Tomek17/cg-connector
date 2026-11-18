"""Response shapes of workouts and scheduled workouts, shared by REST and MCP."""

from datetime import date, datetime

from pydantic import BaseModel

from cg_server.models import ScheduledWorkout, Workout
from cg_server.workout_definition import preview
from cg_server.workouts import definition_of


class WorkoutOut(BaseModel):
    id: int
    client_id: str
    name: str
    sport: str
    created_by: str
    created_at: datetime
    preview: str
    definition: dict[str, object]


class ScheduledOut(BaseModel):
    id: int
    workout_id: int
    name: str
    sport: str
    date: date
    # pending | sent | failed | deleting | deleted
    status: str
    last_error: str | None = None


def workout_out(workout: Workout) -> WorkoutOut:
    return WorkoutOut(
        id=workout.id,
        client_id=workout.client_id,
        name=workout.name,
        sport=workout.sport.value,
        created_by=workout.created_by.value,
        created_at=workout.created_at,
        preview=preview(definition_of(workout)),
        definition=workout.definition_json,
    )


def scheduled_out(item: ScheduledWorkout) -> ScheduledOut:
    return ScheduledOut(
        id=item.id,
        workout_id=item.workout_id,
        name=item.workout.name,
        sport=item.workout.sport.value,
        date=item.date,
        status=item.status.value,
        last_error=item.last_error,
    )
