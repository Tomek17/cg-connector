"""In-memory `GarminGateway` with synthetic data, for tests and local development.

All data here is made up (no real people, places or recordings). The shapes follow
garminconnect 0.3.20 responses, including a few fields we must drop (GPS, owner), so tests
can check that they never reach the database.
"""

from collections.abc import Callable
from datetime import date, datetime, timedelta
from typing import Any

from cg_server.garmin.gateway import (
    GarminAuthError,
    GarminNotFound,
    HrZones,
    MaxMetrics,
    MfaPrompt,
)

CallHook = Callable[[str, tuple[Any, ...]], None]


def make_activity(
    activity_id: int,
    start_local: datetime,
    type_key: str = "running",
    *,
    utc_offset_h: int = 1,
    **overrides: Any,
) -> dict[str, Any]:
    """One synthetic entry of `get_activities_by_date` (Garmin's activity list item)."""
    running = "run" in type_key
    activity: dict[str, Any] = {
        "activityId": activity_id,
        "activityName": f"Aktywność testowa {activity_id}",
        "startTimeLocal": start_local.strftime("%Y-%m-%d %H:%M:%S"),
        "startTimeGMT": (start_local - timedelta(hours=utc_offset_h)).strftime("%Y-%m-%d %H:%M:%S"),
        "activityType": {"typeId": 1, "typeKey": type_key, "parentTypeId": 17},
        "eventType": {"typeKey": "uncategorized"},
        "distance": 10000.0 if running else 0.0,
        "duration": 3000.0,
        "movingDuration": 2950.0,
        "elevationGain": 85.0 if running else None,
        "averageSpeed": 3.3333 if running else 0.0,
        "averageHR": 150.0,
        "maxHR": 176.0,
        "avgPower": 290.0 if running else None,
        "averageRunningCadenceInStepsPerMinute": 172.0 if running else None,
        "aerobicTrainingEffect": 3.2,
        "anaerobicTrainingEffect": 1.1,
        "activityTrainingLoad": 120.5,
        "vO2MaxValue": 52.0 if running else None,
        # Must never be stored:
        "startLatitude": 50.0001,
        "startLongitude": 19.0001,
        "endLatitude": 50.0002,
        "endLongitude": 19.0002,
        "locationName": "Miasto Testowe",
        "ownerId": 99999,
        "ownerFullName": "Test Biegacz",
        "ownerDisplayName": "test-biegacz",
        "deviceId": 123456,
    }
    activity.update(overrides)
    return activity


def default_activities(today: date) -> list[dict[str, Any]]:
    base = datetime(today.year, today.month, today.day)
    return [
        make_activity(1001, base - timedelta(days=6, hours=-7), "running"),
        make_activity(1002, base - timedelta(days=5, hours=-18), "strength_training"),
        make_activity(1003, base - timedelta(days=3, hours=-19), "indoor_climbing"),
        make_activity(1004, base - timedelta(days=1, hours=-8), "trail_running"),
    ]


class FakeGarminGateway:
    """Never touches the network.

    `error`: raised by every call that would hit Garmin (e.g. `GarminRateLimited()`).
    `refreshed_tokens`: what `dumps()` returns after a session, simulating a token refresh.
    `activities`, `vo2max` (list of (YYYY-MM-DD, value)): synthetic account data.
    `on_call`: called as `on_call(method, args)` before each call; may raise to simulate
    a failure in the middle of a sync.
    """

    def __init__(
        self,
        *,
        tokens: str = '{"di_token": "fake-access", "di_refresh_token": "fake-refresh"}',
        refreshed_tokens: str | None = None,
        full_name: str = "Test Biegacz",
        error: Exception | None = None,
        activities: list[dict[str, Any]] | None = None,
        vo2max: list[tuple[str, float]] | None = None,
        on_call: CallHook | None = None,
    ) -> None:
        self.tokens = tokens
        self.refreshed_tokens = refreshed_tokens
        self.full_name = full_name
        self.error = error
        self.activities = activities if activities is not None else default_activities(date.today())
        self.vo2max = vo2max if vo2max is not None else []
        self.on_call = on_call
        self.calls: list[str] = []
        self.call_args: list[tuple[str, tuple[Any, ...]]] = []
        self.mfa_code: str | None = None
        self._session_tokens: str | None = None
        # Garmin side of workouts: library {workoutId: json}, calendar {scheduleId: entry}.
        self.workouts: dict[int, dict[str, Any]] = {}
        self.calendar: dict[int, dict[str, Any]] = {}
        self._next_id = 9000
        # Simulate Garmin answers without the (unverified) schedule id fields.
        self.schedule_answer_has_id = True
        self.calendar_entries_have_id = True

    def _call(self, name: str, *args: Any) -> None:
        self.calls.append(name)
        self.call_args.append((name, args))
        if self.on_call is not None:
            self.on_call(name, args)
        if self.error is not None:
            raise self.error

    def _require_session(self) -> None:
        if self._session_tokens is None:
            raise GarminAuthError("Brak sesji Garmin")

    def login(self, email: str, password: str, prompt_mfa: MfaPrompt) -> str:
        self._call("login")
        self.mfa_code = prompt_mfa()
        self._session_tokens = self.tokens
        return self.tokens

    def resume(self, tokens: str) -> None:
        self._call("resume")
        self._session_tokens = tokens

    def dumps(self) -> str:
        self._require_session()
        return self.refreshed_tokens or self._session_tokens or ""

    def get_full_name(self) -> str | None:
        self._require_session()
        return self.full_name

    def get_profile(self) -> dict[str, Any]:
        self._call("get_profile")
        self._require_session()
        return {"userData": {"measurementSystem": "metric", "weight": 70000.0}}

    def get_activities_by_date(
        self,
        startdate: str,
        enddate: str | None = None,
        activitytype: str | None = None,
        sortorder: str | None = None,
    ) -> list[dict[str, Any]]:
        self._call("get_activities_by_date", startdate, enddate)
        self._require_session()
        end = enddate or "9999-12-31"
        found = [
            a
            for a in self.activities
            if startdate <= a["startTimeLocal"][:10] <= end
            and (activitytype is None or a["activityType"]["typeKey"] == activitytype)
        ]
        found.sort(key=lambda a: str(a["startTimeLocal"]), reverse=sortorder != "asc")
        return [dict(a) for a in found]

    def get_max_metrics_range(
        self,
        start: str,
        end: str,
        aggregation: str = "daily",
        sport: str | None = None,
    ) -> MaxMetrics:
        self._call("get_max_metrics_range", start, end)
        self._require_session()
        return [
            {
                "userId": 99999,
                "generic": {
                    "calendarDate": day,
                    "vo2MaxPreciseValue": value,
                    "vo2MaxValue": round(value),
                },
                "cycling": None,
            }
            for day, value in self.vo2max
            if start <= day <= end
        ]

    def get_activity_splits(self, activity_id: int) -> dict[str, Any]:
        self._call("get_activity_splits", activity_id)
        self._require_session()
        return {
            "activityId": activity_id,
            "lapDTOs": [
                {
                    "lapIndex": i,
                    "distance": 1000.0,
                    "duration": 300.0 - i,
                    "averageHR": 148.0 + i,
                    "averageSpeed": 3.33,
                    "startLatitude": 50.0001,
                    "startLongitude": 19.0001,
                }
                for i in (1, 2)
            ],
        }

    def get_activity_hr_in_timezones(self, activity_id: int) -> HrZones:
        self._call("get_activity_hr_in_timezones", activity_id)
        self._require_session()
        return [
            {"zoneNumber": z, "secsInZone": 600.0, "zoneLowBoundary": 100 + 15 * z}
            for z in range(1, 6)
        ]

    def get_activity_exercise_sets(self, activity_id: int) -> dict[str, Any]:
        self._call("get_activity_exercise_sets", activity_id)
        self._require_session()
        return {
            "activityId": activity_id,
            "exerciseSets": [
                {
                    "exercises": [{"category": "SQUAT", "name": "BARBELL_BACK_SQUAT"}],
                    "repetitionCount": 5,
                    "weight": 80000.0,
                    "duration": 30.0,
                    "setType": "ACTIVE",
                }
            ],
        }

    def _new_id(self) -> int:
        self._next_id += 1
        return self._next_id

    def upload_workout(self, workout: dict[str, Any]) -> int:
        self._call("upload_workout", workout.get("workoutName"))
        self._require_session()
        workout_id = self._new_id()
        self.workouts[workout_id] = workout
        return workout_id

    def schedule_workout(self, workout_id: int, date: str) -> int | None:
        self._call("schedule_workout", workout_id, date)
        self._require_session()
        if workout_id not in self.workouts:
            raise GarminNotFound("Garmin: nie znaleziono (HTTP 404)")
        schedule_id = self._new_id()
        self.calendar[schedule_id] = {
            "workoutScheduleId": schedule_id,
            "workoutId": workout_id,
            "date": date,
        }
        return schedule_id if self.schedule_answer_has_id else None

    def unschedule_workout(self, schedule_id: int) -> None:
        self._call("unschedule_workout", schedule_id)
        self._require_session()
        if self.calendar.pop(schedule_id, None) is None:
            raise GarminNotFound("Garmin: nie znaleziono (HTTP 404)")

    def delete_workout(self, workout_id: int) -> None:
        self._call("delete_workout", workout_id)
        self._require_session()
        if self.workouts.pop(workout_id, None) is None:
            raise GarminNotFound("Garmin: nie znaleziono (HTTP 404)")

    def get_scheduled_workouts_by_period(self, start: str, end: str) -> list[dict[str, Any]]:
        self._call("get_scheduled_workouts_by_period", start, end)
        self._require_session()
        entries = [dict(e) for e in self.calendar.values() if start <= e["date"] <= end]
        if not self.calendar_entries_have_id:
            for entry in entries:
                del entry["workoutScheduleId"]
        return entries
