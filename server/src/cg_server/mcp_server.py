"""MCP server (Streamable HTTP, stateless) with the tools for Claude.

The user always comes from the OAuth access token (`subject` = user id), never from a tool
argument. Tool descriptions are in English and answers are short (free-plan token limits).
"""

import json
from collections.abc import Callable
from datetime import date, datetime
from typing import Annotated, Any
from zoneinfo import ZoneInfo

from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions, RevocationOptions
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.types import CallToolResult, TextContent
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session, sessionmaker

from cg_server import mcp_read, queries
from cg_server.config import Settings
from cg_server.mcp_workouts import register_workout_tools
from cg_server.models import ActivityKind, User, utcnow
from cg_server.oauth import SCOPE, CgOAuthProvider
from cg_server.outbox import OutboxRunner
from cg_server.queries import Period

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


def current_user(session: Session) -> User:
    token = get_access_token()
    if token is None or token.subject is None:
        raise ToolError("Not authenticated")
    user = session.get(User, int(token.subject))
    if user is None:
        raise ToolError("Unknown user")
    return user


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

    @mcp.tool()
    def get_profile() -> Profile:
        """Signed-in user, Garmin connection status, last successful sync (local time) and the
        local date of the newest synced activity."""
        with sessions() as session:
            user = current_user(session)
            state = queries.sync_status(session, user_id=user.id, tz=tz)
            return Profile(
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

    @mcp.tool()
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

    @mcp.tool()
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

    @mcp.tool()
    def get_vo2max_trend(from_date: FromDate = None, to_date: ToDate = None) -> CallToolResult:
        """Running VO2max trend: latest value, change, min/max and points [date, value]
        (only changes; weekly/monthly for long ranges). Default: last 180 days."""
        return run(
            lambda session, user_id: mcp_read.vo2max_trend(
                session, user_id=user_id, start=from_date, end=to_date, now=utcnow(), tz=tz
            )
        )

    @mcp.tool()
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
