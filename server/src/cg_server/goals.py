"""The user's goal: a target event with a date, distance and optional finish time."""

import re
from dataclasses import dataclass
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from cg_server.models import Goal

MAX_NAME = 80
MIN_KM, MAX_KM = 0.1, 500.0
MIN_SECONDS, MAX_SECONDS = 60, 7 * 24 * 3600
_TIME = re.compile(r"^(?:(\d{1,3}):)?([0-5]?\d):([0-5]\d)$")


class GoalError(ValueError):
    """Invalid goal; the message (English) goes back to the caller."""


@dataclass(frozen=True)
class GoalIn:
    name: str
    event_date: date
    distance_km: float
    target_seconds: int | None = None


def parse_time(text: str) -> int:
    """`1:45:00` or `45:00` (h:mm:ss / mm:ss) -> seconds."""
    match = _TIME.match(text.strip())
    if not match:
        raise GoalError("target_time must look like h:mm:ss or mm:ss, e.g. 1:45:00.")
    hours, minutes, seconds = (int(g) if g else 0 for g in match.groups())
    return hours * 3600 + minutes * 60 + seconds


def format_time(seconds: int) -> str:
    hours, rest = divmod(seconds, 3600)
    minutes, secs = divmod(rest, 60)
    return f"{hours}:{minutes:02d}:{secs:02d}"


def validate(goal: GoalIn) -> GoalIn:
    name = goal.name.strip()
    if not name or len(name) > MAX_NAME:
        raise GoalError(f"name must be 1-{MAX_NAME} characters.")
    if not MIN_KM <= goal.distance_km <= MAX_KM:
        raise GoalError(f"distance_km must be between {MIN_KM} and {MAX_KM:g}.")
    if goal.target_seconds is not None and not MIN_SECONDS <= goal.target_seconds <= MAX_SECONDS:
        raise GoalError("target time must be between 1 minute and 7 days.")
    return GoalIn(name, goal.event_date, round(goal.distance_km, 4), goal.target_seconds)


def get_goal(session: Session, user_id: int) -> Goal | None:
    return session.scalar(select(Goal).where(Goal.user_id == user_id))


def set_goal(session: Session, user_id: int, goal: GoalIn) -> Goal:
    goal = validate(goal)
    row = get_goal(session, user_id) or Goal(user_id=user_id)
    row.name = goal.name
    row.event_date = goal.event_date
    row.distance_km = goal.distance_km
    row.target_seconds = goal.target_seconds
    session.add(row)
    session.commit()
    return row


def clear_goal(session: Session, user_id: int) -> None:
    row = get_goal(session, user_id)
    if row is not None:
        session.delete(row)
        session.commit()


def pace_s_per_km(goal: Goal) -> float | None:
    if goal.target_seconds is None or goal.distance_km <= 0:
        return None
    return goal.target_seconds / goal.distance_km
