"""MCP tools for workouts: create, schedule (send to the watch), list, delete.

The user comes from the OAuth token (`current_user`), never from arguments. Only workouts
created by CG-connector can be deleted (MASTER_PROMPT 7.11).

Token budget (free Claude plan): the four tools together must describe themselves in under
2 kB (test). So the definition argument has a minimal hand-written schema and the format is
given by one example in the description; validation happens in `workout_definition`, whose
errors are readable. Schemas carry no titles and no `anyOf null`, answers are compact JSON
text (no output schema).
"""

import json
from collections.abc import Callable
from datetime import date, datetime, timedelta
from typing import Annotated, Any
from zoneinfo import ZoneInfo

import anyio
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.types import CallToolResult, TextContent, ToolAnnotations
from pydantic import Field, WithJsonSchema
from sqlalchemy.orm import Session, sessionmaker

from cg_server import workouts
from cg_server.config import Settings
from cg_server.models import ScheduledWorkout, ScheduleStatus, User, WorkoutOrigin
from cg_server.outbox import OutboxRunner
from cg_server.workout_definition import DefinitionError, preview

TOOLS = (
    "create_workout",
    "schedule_workout",
    "list_scheduled_workouts",
    "delete_scheduled_workout",
)
MAX_LIST_DAYS = 366
LIST_DEFAULT_DAYS = 30

NOTES = {
    ScheduleStatus.PENDING: "Queued; retried automatically every 15 min.",
    ScheduleStatus.SENT: "In the Garmin calendar; the watch gets it on its next sync.",
    ScheduleStatus.FAILED: "Not sent, see error. Scheduling it again retries.",
    ScheduleStatus.DELETING: "Being removed from the Garmin calendar.",
    ScheduleStatus.DELETED: "Deleted.",
}

Definition = Annotated[
    dict[str, Any],
    WithJsonSchema({"type": "object", "description": "Workout JSON, see example."}),
]
FromDate = Annotated[date | None, Field(description="First day; default today.")]
ToDate = Annotated[date | None, Field(description="Last day; default +30 days.")]


# Hints for the client (claude.ai may ask the user before tools that are not read-only).
# Only hints that differ from the MCP defaults (not read-only, destructive, not idempotent,
# open world), to keep the tool list small. Only delete_scheduled_workout removes anything in
# Garmin Connect (MASTER_PROMPT 7.11); it is explicitly marked destructive.
READ_ONLY = ToolAnnotations(read_only_hint=True)
CREATE = ToolAnnotations(destructive_hint=False, open_world_hint=False)
SEND = ToolAnnotations(destructive_hint=False, idempotent_hint=True)
DELETE = ToolAnnotations(destructive_hint=True)


def compact_schema(value: Any) -> Any:
    """Drop titles and null defaults, turn `anyOf: [X, null]` into X (smaller tool list)."""
    if isinstance(value, list):
        return [compact_schema(v) for v in value]
    if not isinstance(value, dict):
        return value
    result = {
        k: compact_schema(v)
        for k, v in value.items()
        if k != "title" and not (k == "default" and v is None)
    }
    options = result.get("anyOf")
    if isinstance(options, list):
        non_null = [o for o in options if o != {"type": "null"}]
        if len(non_null) == 1 and len(non_null) < len(options):
            del result["anyOf"]
            result = {**non_null[0], **result}
    return result


def _result(data: dict[str, Any]) -> CallToolResult:
    """Compact JSON text only, like the read tools (no structured copy: half the tokens)."""
    text = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return CallToolResult(content=[TextContent(type="text", text=text)])


def _item(item: ScheduledWorkout) -> dict[str, Any]:
    data: dict[str, Any] = {
        "id": item.id,
        "workout_id": item.workout_id,
        "date": item.date.isoformat(),
        "name": item.workout.name,
        "sport": item.workout.sport.value,
        "status": item.status.value,
    }
    if item.last_error:
        data["error"] = item.last_error
    return data


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

    def status_of(scheduled_id: int) -> CallToolResult:
        with sessions() as session:
            item = workouts.get_scheduled(session, current_user(session), scheduled_id)
            return _result({**_item(item), "note": NOTES[item.status]})

    @mcp.tool(annotations=CREATE)
    def create_workout(definition: Definition) -> CallToolResult:
        """Save a draft (not sent); returns id + preview. Run: {"sport":
        "running","name":"5x1km","steps":[{"type":"warmup","seconds":600},{"type":
        "repeat","times":5,"steps":[{"type":"interval","distance_km":1,"target":{"type":
        "pace","from":"4:30","to":"4:15"}},{"type":"recovery","seconds":90}]},{"type":
        "cooldown","lap_button":true}]}. Types warmup|interval|recovery|cooldown|rest;
        end: seconds|distance_km|lap_button; target: pace min/km, {"type":"hr_zone",
        "zone":2} or {"type":"hr_range","from":140,"to":155}. Strength: {"sport":"strength",
        "name":"A","exercises":[{"exercise":"Barbell Back Squat","sets":3,"reps":5,
        "weight_kg":80,"rest_seconds":120}]}, reps|seconds, Garmin catalog names.
        Text only: {"sport":"other"|"bouldering","name":"X","description":"..","minutes":60}."""
        with sessions() as session:
            try:
                workout, _ = workouts.create_workout(
                    session, current_user(session), definition, origin=WorkoutOrigin.MCP
                )
            except (DefinitionError, workouts.WorkoutError) as e:
                raise ToolError(str(e)) from None
            text = preview(workouts.definition_of(workout))
            return _result({"id": workout.id, "preview": text})

    @mcp.tool(annotations=SEND)
    async def schedule_workout(workout_id: int, date: date) -> CallToolResult:
        """Send a workout to the user's Garmin calendar on a day (today or later); the
        watch takes it from there. Returns the status."""
        today = datetime.now(tz).date()
        with sessions() as session:
            user = current_user(session)
            try:
                item, _ = workouts.schedule_workout(session, user, workout_id, date, today=today)
            except (workouts.NotFound, workouts.WorkoutError) as e:
                raise ToolError(str(e)) from None
            user_id, scheduled_id = user.id, item.id
            pending = item.status == ScheduleStatus.PENDING
        if pending:
            await try_send(user_id)
        return status_of(scheduled_id)

    @mcp.tool(annotations=READ_ONLY)
    def list_scheduled_workouts(
        from_date: FromDate = None, to_date: ToDate = None
    ) -> CallToolResult:
        """Workouts scheduled by CG-connector, with status."""
        start = from_date or datetime.now(tz).date()
        end = to_date or start + timedelta(days=LIST_DEFAULT_DAYS)
        if end < start or (end - start).days > MAX_LIST_DAYS:
            raise ToolError(f"to_date must be after from_date, at most {MAX_LIST_DAYS} days.")
        with sessions() as session:
            items = workouts.list_scheduled(session, current_user(session), start, end)
            return _result({"range": f"{start}..{end}", "items": [_item(i) for i in items]})

    @mcp.tool(annotations=DELETE)
    async def delete_scheduled_workout(scheduled_id: int) -> CallToolResult:
        """Remove a scheduled workout (id from list_scheduled_workouts) from the Garmin
        calendar. Only on the user's explicit request."""
        with sessions() as session:
            user = current_user(session)
            try:
                item = workouts.request_delete(session, user, scheduled_id)
            except workouts.NotFound as e:
                raise ToolError(str(e)) from None
            user_id, deleting = user.id, item.status == ScheduleStatus.DELETING
        if deleting:
            await try_send(user_id)
        return status_of(scheduled_id)

    for name in TOOLS:
        tool = mcp._tool_manager.get_tool(name)  # noqa: SLF001 - no public API for this
        if tool is not None:
            tool.parameters = compact_schema(tool.parameters)
            tool.description = " ".join((tool.description or "").split())
