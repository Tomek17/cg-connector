"""MCP tools for workouts: create, schedule (send to the watch), list, delete.

The user comes from the OAuth token (`current_user`), never from arguments. Only workouts
created by CG-connector can be deleted (MASTER_PROMPT 7.11). Answers are short.
"""

from collections.abc import Callable
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

import anyio
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from pydantic import BaseModel
from sqlalchemy.orm import Session, sessionmaker

from cg_server import workouts
from cg_server.config import Settings
from cg_server.models import ScheduleStatus, User, WorkoutOrigin
from cg_server.outbox import OutboxRunner
from cg_server.workout_definition import DefinitionError, WorkoutDefinition, preview
from cg_server.workout_views import ScheduledOut, scheduled_out

MAX_LIST_DAYS = 366

STATUS_HINTS = {
    ScheduleStatus.PENDING: "Queued; sent to Garmin automatically (retried every 15 min).",
    ScheduleStatus.SENT: "In the Garmin Connect calendar; the watch gets it on its next sync.",
    ScheduleStatus.FAILED: "Sending failed; see last_error. Scheduling it again retries.",
    ScheduleStatus.DELETING: "Being removed from the Garmin calendar.",
    ScheduleStatus.DELETED: "Deleted.",
}


class CreatedWorkout(BaseModel):
    workout_id: int
    preview: str


class ScheduleResult(ScheduledOut):
    hint: str


class ScheduledList(BaseModel):
    items: list[ScheduledOut]


def register_workout_tools(
    mcp: MCPServer,
    settings: Settings,
    sessions: sessionmaker[Session],
    current_user: Callable[[Session], User],
    outbox: OutboxRunner | None,
) -> None:
    tz = ZoneInfo(settings.timezone)

    async def try_send(user_id: int) -> None:
        if outbox is not None:
            await anyio.to_thread.run_sync(outbox.run_user, user_id)

    def result_of(scheduled_id: int) -> ScheduleResult:
        with sessions() as session:
            item = workouts.get_scheduled(session, current_user(session), scheduled_id)
            out = scheduled_out(item)
        return ScheduleResult(**out.model_dump(), hint=STATUS_HINTS[item.status])

    @mcp.tool()
    def create_workout(definition: WorkoutDefinition) -> CreatedWorkout:
        """Create a workout draft (structured run or strength) and return a preview.
        Nothing is sent to Garmin until schedule_workout. Running steps end after exactly
        one of seconds / distance_km / lap_button; optional target: pace (min/km, e.g.
        from "5:00" to "4:40"), hr_zone (1-5) or hr_range (bpm). Strength exercises use
        exact names from Garmin's catalog (an unknown name returns suggestions)."""
        with sessions() as session:
            try:
                workout, _ = workouts.create_workout(
                    session, current_user(session), definition, origin=WorkoutOrigin.MCP
                )
            except (DefinitionError, workouts.WorkoutError) as e:
                raise ToolError(str(e)) from None
            return CreatedWorkout(
                workout_id=workout.id, preview=preview(workouts.definition_of(workout))
            )

    @mcp.tool()
    async def schedule_workout(workout_id: int, date: date) -> ScheduleResult:
        """Put a workout from create_workout on a day (YYYY-MM-DD, today or later) of the
        user's Garmin Connect calendar; the watch downloads it from there. Sends at once
        when possible, otherwise it stays queued. Returns the status."""
        today = datetime.now(tz).date()
        with sessions() as session:
            user = current_user(session)
            try:
                item, _ = workouts.schedule_workout(session, user, workout_id, date, today=today)
            except (workouts.NotFound, workouts.WorkoutError) as e:
                raise ToolError(str(e)) from None
            user_id, scheduled_id, pending = user.id, item.id, item.status == "pending"
        if pending:
            await try_send(user_id)
        return result_of(scheduled_id)

    @mcp.tool()
    def list_scheduled_workouts(date_from: date, date_to: date) -> ScheduledList:
        """Workouts scheduled by CG-connector between two days (inclusive), with status:
        pending, sent, failed or deleting."""
        if date_to < date_from or (date_to - date_from) > timedelta(days=MAX_LIST_DAYS):
            raise ToolError(f"date_to must be after date_from, at most {MAX_LIST_DAYS} days")
        with sessions() as session:
            items = workouts.list_scheduled(session, current_user(session), date_from, date_to)
            return ScheduledList(items=[scheduled_out(i) for i in items])

    @mcp.tool()
    async def delete_scheduled_workout(scheduled_id: int) -> ScheduleResult:
        """Remove a workout scheduled by CG-connector (id from list_scheduled_workouts)
        from the Garmin calendar. Only the user's explicit request justifies this."""
        with sessions() as session:
            user = current_user(session)
            try:
                item = workouts.request_delete(session, user, scheduled_id)
            except workouts.NotFound as e:
                raise ToolError(str(e)) from None
            user_id, deleting = user.id, item.status == ScheduleStatus.DELETING
        if deleting:
            await try_send(user_id)
        return result_of(scheduled_id)
