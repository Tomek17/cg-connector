"""MCP server (Streamable HTTP, stateless) with the tools for Claude.

The user always comes from the OAuth access token (`subject` = user id), never from a tool
argument. Tool descriptions are in English and answers are short (free-plan token limits).
"""

from datetime import UTC, date, datetime

from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions, RevocationOptions
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from pydantic import BaseModel
from sqlalchemy import func, select
from sqlalchemy.orm import Session, sessionmaker

from cg_server.config import Settings
from cg_server.models import Activity, SyncRun, SyncStatus, User
from cg_server.oauth import SCOPE, CgOAuthProvider
from cg_server.outbox import OutboxRunner

INSTRUCTIONS = (
    "CG-connector: the user's Garmin Connect training data (activities, VO2max) "
    "synced once a day, and workouts sent to the user's Garmin calendar/watch. "
    "Units: km, min/km, bpm."
)


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


def last_sync_at(session: Session, user: User) -> datetime | None:
    """When the user's last successful Garmin sync finished (UTC), None if never."""
    value = session.scalar(
        select(func.max(SyncRun.finished_at)).where(
            SyncRun.user_id == user.id, SyncRun.status == SyncStatus.OK
        )
    )
    if value is None:
        return None
    return value if value.tzinfo is not None else value.replace(tzinfo=UTC)  # SQLite: naive


def latest_activity_date(session: Session, user: User) -> date | None:
    """Local date of the user's newest synced activity."""
    value = session.scalar(
        select(func.max(Activity.start_time_local)).where(Activity.user_id == user.id)
    )
    return value.date() if value is not None else None


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

    @mcp.tool()
    def get_profile() -> Profile:
        """Signed-in user, Garmin connection status, last successful sync (UTC) and the
        local date of the newest synced activity."""
        with sessions() as session:
            user = current_user(session)
            creds = user.garmin_credentials
            return Profile(
                user=user.name,
                garmin_status=creds.status.value if creds else None,
                last_sync_at=last_sync_at(session, user),
                latest_activity_date=latest_activity_date(session, user),
            )

    return mcp
