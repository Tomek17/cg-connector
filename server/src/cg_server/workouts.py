"""Workouts of one user: create (idempotent by `client_id`), schedule, list, delete.

Shared by MCP and REST. Every query filters by the user from the token; an object of
another user is reported as not found. Sending to Garmin happens in `cg_server.outbox`.
Messages of WorkoutError are in English (shown to Claude and in the app).
"""

import uuid
from datetime import date, timedelta
from typing import Any

from sqlalchemy import case, or_, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from cg_server.models import (
    ScheduledWorkout,
    ScheduleStatus,
    User,
    Workout,
    WorkoutOrigin,
    WorkoutSport,
)
from cg_server.workout_definition import (
    RunningDefinition,
    StrengthDefinition,
    parse_definition,
    to_json,
)

MAX_DAYS_AHEAD = 365
ACTIVE = (ScheduleStatus.PENDING, ScheduleStatus.SENT, ScheduleStatus.FAILED)


class WorkoutError(ValueError):
    """Invalid request (e.g. date in the past). The message is safe to show."""


class NotFound(LookupError):
    """No such workout / scheduled workout for this user."""


def _client_id(value: str | None) -> str:
    if value is None:
        return str(uuid.uuid4())
    try:
        return str(uuid.UUID(str(value)))
    except ValueError:
        raise WorkoutError("client_id must be a UUID") from None


def _find_by_client_id(session: Session, user: User, client_id: str) -> Workout | None:
    return session.scalars(
        select(Workout).where(Workout.user_id == user.id, Workout.client_id == client_id)
    ).one_or_none()


def create_workout(
    session: Session,
    user: User,
    definition: Any,
    *,
    origin: WorkoutOrigin | str,
    client_id: str | None = None,
) -> tuple[Workout, bool]:
    """Validate and save a workout draft. Returns (workout, created); with a `client_id`
    seen before for this user the existing workout is returned unchanged."""
    client_id = _client_id(client_id)
    existing = _find_by_client_id(session, user, client_id)
    if existing is not None:
        return existing, False
    parsed = parse_definition(definition)
    sport = (
        WorkoutSport.STRENGTH if isinstance(parsed, StrengthDefinition) else WorkoutSport.RUNNING
    )
    workout = Workout(
        user_id=user.id,
        client_id=client_id,
        name=parsed.name,
        sport=sport,
        definition_json=to_json(parsed),
        created_by=WorkoutOrigin(origin),
    )
    session.add(workout)
    try:
        session.commit()
    except IntegrityError:  # the same client_id arrived twice at the same moment
        session.rollback()
        existing = _find_by_client_id(session, user, client_id)
        if existing is None:
            raise
        return existing, False
    return workout, True


def definition_of(workout: Workout) -> RunningDefinition | StrengthDefinition:
    return parse_definition(workout.definition_json)


def get_workout(session: Session, user: User, workout_id: int) -> Workout:
    workout = session.get(Workout, workout_id)
    if workout is None or workout.user_id != user.id:
        raise NotFound(f"Workout {workout_id} not found")
    return workout


def get_scheduled(session: Session, user: User, scheduled_id: int) -> ScheduledWorkout:
    item = session.get(ScheduledWorkout, scheduled_id)
    if item is None or item.user_id != user.id:
        raise NotFound(f"Scheduled workout {scheduled_id} not found")
    return item


def schedule_workout(
    session: Session, user: User, workout_id: int, day: date, *, today: date
) -> tuple[ScheduledWorkout, bool]:
    """Queue the workout for `day` in the outbox. Returns (entry, created). The same
    workout on the same day is one entry; a failed entry is queued again."""
    workout = get_workout(session, user, workout_id)
    if day < today:
        raise WorkoutError("date is in the past")
    if day > today + timedelta(days=MAX_DAYS_AHEAD):
        raise WorkoutError(f"date must be within {MAX_DAYS_AHEAD} days")
    existing = session.scalars(
        select(ScheduledWorkout)
        .where(
            ScheduledWorkout.user_id == user.id,
            ScheduledWorkout.workout_id == workout.id,
            ScheduledWorkout.date == day,
            ScheduledWorkout.status.in_(ACTIVE),
        )
        .order_by(ScheduledWorkout.id)
        .limit(1)
    ).one_or_none()
    if existing is not None:
        if existing.status == ScheduleStatus.FAILED:
            existing.status = ScheduleStatus.PENDING
            existing.attempts = 0
            existing.last_error = None
            existing.next_attempt_at = None
            session.commit()
        return existing, False
    item = ScheduledWorkout(
        user_id=user.id, workout_id=workout.id, date=day, status=ScheduleStatus.PENDING
    )
    session.add(item)
    session.commit()
    return item, True


def list_scheduled(session: Session, user: User, start: date, end: date) -> list[ScheduledWorkout]:
    """Entries of this user between two days (inclusive), deleted ones left out."""
    return list(
        session.scalars(
            select(ScheduledWorkout)
            .where(
                ScheduledWorkout.user_id == user.id,
                ScheduledWorkout.date >= start,
                ScheduledWorkout.date <= end,
                ScheduledWorkout.status != ScheduleStatus.DELETED,
            )
            .order_by(ScheduledWorkout.date, ScheduledWorkout.id)
        )
    )


def request_delete(session: Session, user: User, scheduled_id: int) -> ScheduledWorkout:
    """Delete one of this user's scheduled workouts (only CG-connector's own, MP 7.11).

    In the Garmin calendar already: status `deleting`, the outbox removes it there. Not
    sent yet: `deleted` at once. The workout template stays in the Garmin library.
    """
    item = get_scheduled(session, user, scheduled_id)
    # Sent (even if Garmin's entry id is unknown) or an earlier delete failed: the outbox
    # must remove it from the Garmin calendar.
    in_garmin_calendar = or_(
        ScheduledWorkout.garmin_schedule_id.is_not(None),
        ScheduledWorkout.status.in_([ScheduleStatus.SENT.value, ScheduleStatus.DELETING.value]),
        ScheduledWorkout.last_error.startswith("delete_failed"),
    )
    # One statement, decided on the current row: the outbox may be saving the Garmin
    # schedule id at this very moment (it then turns `deleted` into `deleting`).
    session.execute(
        update(ScheduledWorkout)
        .where(
            ScheduledWorkout.id == item.id,
            ScheduledWorkout.status != ScheduleStatus.DELETED,
        )
        .values(
            status=case(
                (in_garmin_calendar, ScheduleStatus.DELETING.value),
                else_=ScheduleStatus.DELETED.value,
            ),
            attempts=0,
            last_error=None,
            next_attempt_at=None,
        )
    )
    session.commit()
    session.refresh(item)
    return item
