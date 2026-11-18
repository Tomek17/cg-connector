"""Synthetic training data written straight to the database (no Garmin, no sync)."""

from datetime import UTC, date, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from sqlalchemy.orm import Session

from cg_server.models import (
    Activity,
    ActivityDetails,
    ActivityKind,
    GarminCredentials,
    GarminStatus,
    SyncRun,
    SyncStatus,
    User,
    Vo2Max,
)

WARSAW = ZoneInfo("Europe/Warsaw")


def add_activity(
    session: Session,
    user: User,
    garmin_id: int,
    start_local: datetime,
    kind: ActivityKind = ActivityKind.RUNNING,
    *,
    details: bool = False,
    **fields: Any,
) -> Activity:
    """A finished activity starting at `start_local` (Europe/Warsaw wall clock)."""
    running = kind == ActivityKind.RUNNING
    values: dict[str, Any] = {
        "name": f"Aktywność {garmin_id}",
        "type_key": {
            ActivityKind.RUNNING: "running",
            ActivityKind.STRENGTH: "strength_training",
            ActivityKind.CLIMBING: "indoor_climbing",
            ActivityKind.OTHER: "cycling",
        }[kind],
        "duration_s": 3000.0,
        "moving_duration_s": 2950.0,
        "distance_m": 10000.0 if running else None,
        "avg_hr": 150.0,
        "max_hr": 176.0,
        "avg_speed_mps": 3.3333 if running else None,
        "avg_pace_s_per_km": 300.0 if running else None,
        "elevation_gain_m": 85.0 if running else None,
        "avg_power_w": 290.0 if running else None,
        "avg_cadence_spm": 172.0 if running else None,
        "aerobic_training_effect": 3.2,
        "anaerobic_training_effect": 1.1,
        "training_load": 120.5,
    }
    values.update(fields)
    activity = Activity(
        user_id=user.id,
        garmin_activity_id=garmin_id,
        kind=kind,
        start_time_utc=start_local.replace(tzinfo=WARSAW).astimezone(UTC),
        start_time_local=start_local,
        summary_json={},
        **values,
    )
    if details:
        activity.details = ActivityDetails(
            user_id=user.id,
            laps_json={
                "lapDTOs": [
                    {
                        "lapIndex": i,
                        "distance": 1000.0,
                        "duration": 300.0 - i,
                        "averageHR": 148.0 + i,
                        "maxHR": 160.0 + i,
                        "averageSpeed": 1000.0 / (300.0 - i),
                        "elevationGain": 5.0,
                    }
                    for i in (1, 2)
                ]
            },
            hr_zones_json=[
                {"zoneNumber": z, "secsInZone": 600.0, "zoneLowBoundary": 100 + 15 * z}
                for z in range(1, 6)
            ],
            exercise_sets_json={
                "exerciseSets": [
                    {
                        "exercises": [{"category": "SQUAT", "name": "BARBELL_BACK_SQUAT"}],
                        "repetitionCount": 5,
                        "weight": 80000.0,
                        "duration": 30.0,
                        "setType": "ACTIVE",
                    },
                    {"exercises": [], "duration": 90.0, "setType": "REST"},
                    {
                        "exercises": [{"category": "SQUAT", "name": "BARBELL_BACK_SQUAT"}],
                        "repetitionCount": 5,
                        "weight": 82500.0,
                        "duration": 32.0,
                        "setType": "ACTIVE",
                    },
                ]
            }
            if kind == ActivityKind.STRENGTH
            else None,
            fetch_failed=False,
        )
    session.add(activity)
    session.flush()
    return activity


def add_vo2max(
    session: Session, user: User, day: date, value: float, sport: str = "running"
) -> None:
    session.add(Vo2Max(user_id=user.id, date=day, sport=sport, value=value))


def add_sync(
    session: Session,
    user: User,
    finished_at: datetime,
    status: SyncStatus = SyncStatus.OK,
) -> None:
    session.add(
        SyncRun(
            user_id=user.id,
            started_at=finished_at - timedelta(minutes=2),
            finished_at=finished_at,
            status=status,
        )
    )


def set_garmin_status(session: Session, user: User, status: GarminStatus) -> None:
    session.add(GarminCredentials(user_id=user.id, encrypted_tokens=None, status=status))
