"""MCP server (Streamable HTTP, stateless) with the tools for Claude.

The user always comes from the OAuth access token (`subject` = user id), never from a tool
argument. Tool descriptions are in English and answers are short (free-plan token limits).
"""

import json
from collections.abc import Callable
from datetime import date, datetime
from typing import Annotated, Any
from zoneinfo import ZoneInfo

import anyio
from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions, RevocationOptions
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.types import CallToolResult, TextContent, ToolAnnotations
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session, sessionmaker

from cg_server import goals, manual_sync, mcp_read, queries
from cg_server.config import Settings
from cg_server.manual_sync import ManualSync
from cg_server.mcp_workouts import READ_ONLY, register_workout_tools
from cg_server.models import ActivityKind, Goal, User, utcnow
from cg_server.oauth import SCOPE, CgOAuthProvider
from cg_server.outbox import OutboxRunner
from cg_server.queries import Period
from cg_server.tags import TAGS, TagError, set_tag

# Only changes our own database (no Garmin call), same result when repeated.
TAG = ToolAnnotations(destructive_hint=False, idempotent_hint=True, open_world_hint=False)
TAG_LIST = "; ".join(
    f"{kind.value}: {', '.join(t for t, k in TAGS.items() if k == kind)}"
    for kind in dict.fromkeys(TAGS.values())
)

# Talks to Garmin (open world) but only reads from it; repeating it is harmless.
SYNC = ToolAnnotations(
    read_only_hint=False, destructive_hint=False, idempotent_hint=True, open_world_hint=True
)

INSTRUCTIONS = (
    "CG-connector: the user's Garmin Connect training data (activities, VO2max) "
    "synced once a day, and workouts sent to the user's Garmin calendar/watch. "
    "Units: km, min/km, bpm; times local (Europe/Warsaw). Dates are YYYY-MM-DD."
)

FromDate = Annotated[date | None, Field(description="First local day, YYYY-MM-DD.")]
ToDate = Annotated[date | None, Field(description="Last local day, YYYY-MM-DD; default today.")]


class Profile(BaseModel):
    user: str
    garmin_status: str | None
    last_sync_at: datetime | None
    latest_activity_date: date | None
    goal: dict[str, Any] | None = None


def current_user(session: Session) -> User:
    token = get_access_token()
    if token is None or token.subject is None:
        raise ToolError("Not authenticated")
    user = session.get(User, int(token.subject))
    if user is None:
        raise ToolError("Unknown user")
    return user


def goal_item(goal: Goal) -> dict[str, Any]:
    pace = goals.pace_s_per_km(goal)
    return mcp_read.compact(
        {
            "name": goal.name,
            "date": str(goal.event_date),
            "km": goal.distance_km,
            "target": goals.format_time(goal.target_seconds) if goal.target_seconds else None,
            "pace": mcp_read.fmt_pace(pace),
        }
    )


def _result(data: dict[str, Any]) -> CallToolResult:
    """Compact JSON text only (no spaces, no nulls). No structured content and no output
    schema: the SDK does not need them, and a second copy would double the tokens."""
    text = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return CallToolResult(content=[TextContent(type="text", text=text)])


def build_mcp(
    settings: Settings,
    sessions: sessionmaker[Session],
    provider: CgOAuthProvider,
    outbox: OutboxRunner | None = None,
    manual: ManualSync | None = None,
) -> MCPServer:
    mcp = MCPServer(
        "CG-connector",
        instructions=INSTRUCTIONS,
        auth_server_provider=provider,
        auth=AuthSettings(
            issuer_url=settings.public_url,  # type: ignore[arg-type]
            resource_server_url=f"{settings.public_url}/mcp",  # type: ignore[arg-type]
            required_scopes=[SCOPE],
            # Single resource server; tokens are only ever issued for it.
            validate_token_resource=False,
            client_registration_options=ClientRegistrationOptions(
                enabled=True, valid_scopes=[SCOPE], default_scopes=[SCOPE]
            ),
            revocation_options=RevocationOptions(enabled=True),
        ),
    )

    tz = ZoneInfo(settings.timezone)

    @mcp.tool(annotations=READ_ONLY)
    def get_profile() -> Profile:
        """Signed-in user, Garmin connection status, last successful sync (local time), the
        local date of the newest synced activity and the user's goal (race, date, target
        time and pace) if set."""
        with sessions() as session:
            user = current_user(session)
            state = queries.sync_status(session, user_id=user.id, tz=tz)
            goal = goals.get_goal(session, user.id)
            return Profile(
                goal=goal_item(goal) if goal else None,
                user=user.name,
                garmin_status=state.garmin_status.value if state.garmin_status else None,
                last_sync_at=state.last_success_at.astimezone(tz)
                if state.last_success_at
                else None,
                latest_activity_date=state.latest_activity_date,
            )

    def run(build: Callable[[Session, int], dict[str, Any] | None]) -> CallToolResult:
        with sessions() as session:
            user = current_user(session)
            try:
                data = build(session, user.id)
            except mcp_read.InvalidArgument as e:
                raise ToolError(str(e)) from e
        if data is None:  # only get_activity: unknown id or another user's activity
            raise ToolError("Activity not found.")
        return _result(data)

    @mcp.tool(annotations=READ_ONLY)
    def list_activities(
        from_date: FromDate = None,
        to_date: ToDate = None,
        type: Annotated[  # name shown to the model
            ActivityKind | None, Field(description="Only this activity type.")
        ] = None,
        limit: Annotated[int, Field(ge=1, le=50, description="Max activities.")] = 20,
    ) -> CallToolResult:
        """List the user's activities, newest first: id, local start, type, km, duration,
        pace (min/km), avg HR. Default: last 30 days, 20 activities."""
        return run(
            lambda session, user_id: mcp_read.list_activities(
                session,
                user_id=user_id,
                start=from_date,
                end=to_date,
                kind=type,
                limit=limit,
                now=utcnow(),
                tz=tz,
            )
        )

    @mcp.tool(annotations=READ_ONLY)
    def get_activity(
        activity_id: Annotated[int, Field(description="Activity id from list_activities.")],
    ) -> CallToolResult:
        """One activity in detail: laps, minutes in HR zones, strength sets, training
        effect and load."""
        return run(
            lambda session, user_id: mcp_read.activity_detail(
                session, user_id=user_id, activity_id=activity_id, now=utcnow(), tz=tz
            )
        )

    @mcp.tool(
        annotations=TAG,
        description=f"Set one tag on an activity ({TAG_LIST}); "
        "null clears it. Tag what the session was; the app charts progress per tag.",
    )
    def set_activity_tag(
        activity_id: Annotated[int, Field(description="Activity id from list_activities.")],
        tag: Annotated[str | None, Field(description="Tag key or null.")],
    ) -> CallToolResult:
        with sessions() as session:
            user = current_user(session)
            try:
                activity = set_tag(session, user_id=user.id, activity_id=activity_id, tag=tag)
            except TagError as e:
                raise ToolError(str(e)) from None
            return _result({"id": activity.garmin_activity_id, "tag": activity.tag})

    @mcp.tool(annotations=TAG)
    def set_goal(
        name: Annotated[
            str | None, Field(description="Event, e.g. 'Warsaw Half Marathon'; null clears.")
        ],
        event_date: Annotated[date | None, Field(description="Race day, YYYY-MM-DD.")] = None,
        distance_km: Annotated[float | None, Field(description="Race distance in km.")] = None,
        target_time: Annotated[
            str | None, Field(description="Target finish time h:mm:ss, optional.")
        ] = None,
    ) -> CallToolResult:
        """Set the user's single goal (target race and finish time), shown on the app home
        screen. Replaces the previous goal; name null removes it."""
        with sessions() as session:
            user = current_user(session)
            if name is None:
                goals.clear_goal(session, user.id)
                return _result({"goal": None})
            if event_date is None or distance_km is None:
                raise ToolError("event_date and distance_km are required.")
            try:
                seconds = goals.parse_time(target_time) if target_time else None
                goal = goals.set_goal(
                    session, user.id, goals.GoalIn(name, event_date, distance_km, seconds)
                )
            except goals.GoalError as e:
                raise ToolError(str(e)) from None
            return _result({"goal": goal_item(goal)})

    @mcp.tool(annotations=SYNC)
    async def sync_now() -> CallToolResult:
        """Fetch new Garmin data now (normally once a day at 14:00), e.g. right after a
        workout. Waits for the sync; then read activities as usual. Refused if a sync
        ran in the last hour, is running, or Garmin needs login/is paused."""
        with sessions() as session:
            user = current_user(session)
            user_id = user.id
            result = manual_sync.check(session, user)
        if result.refusal is not None:
            data: dict[str, Any] = {"synced": False, "reason": result.refusal.value}
            if result.retry_at is not None:
                data["retry_at"] = result.retry_at.astimezone(tz).strftime("%H:%M")
            return _result(data)
        done = await anyio.to_thread.run_sync(manual.run, user_id) if manual else None
        if done is None:
            return _result({"synced": False, "reason": "running"})
        return _result(
            {
                "synced": True,
                "status": done.status.value,
                "new": done.activities_new,
                "updated": done.activities_updated,
            }
        )

    @mcp.tool(annotations=READ_ONLY)
    def get_vo2max_trend(from_date: FromDate = None, to_date: ToDate = None) -> CallToolResult:
        """Running VO2max trend: latest value, change, min/max and points [date, value]
        (only changes; weekly/monthly for long ranges). Default: last 180 days."""
        return run(
            lambda session, user_id: mcp_read.vo2max_trend(
                session, user_id=user_id, start=from_date, end=to_date, now=utcnow(), tz=tz
            )
        )

    @mcp.tool(annotations=READ_ONLY)
    def get_training_summary(
        period: Annotated[Period, Field(description="week (Mon-Sun) or month.")] = Period.WEEK,
        from_date: FromDate = None,
        to_date: ToDate = None,
    ) -> CallToolResult:
        """Totals per week or month and activity type: count, km, time, elevation, run
        pace. Default: last 8 weeks or 6 months."""
        return run(
            lambda session, user_id: mcp_read.training_summary(
                session,
                user_id=user_id,
                period=period,
                start=from_date,
                end=to_date,
                now=utcnow(),
                tz=tz,
            )
        )

    register_workout_tools(mcp, settings, sessions, current_user, outbox)
    return mcp
