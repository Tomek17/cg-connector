"""MCP server (Streamable HTTP, stateless) with the tools for Claude.

The user always comes from the OAuth access token (`subject` = user id), never from a tool
argument. Tool descriptions are in English and answers are short (free-plan token limits).
"""

from datetime import datetime

from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions, RevocationOptions
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from pydantic import BaseModel
from sqlalchemy.orm import Session, sessionmaker

from cg_server.config import Settings
from cg_server.models import User
from cg_server.oauth import SCOPE, CgOAuthProvider

INSTRUCTIONS = (
    "CG-connector: the user's Garmin Connect training data (activities, VO2max) "
    "synced once a day. Units: km, min/km, bpm."
)


class Profile(BaseModel):
    user: str
    garmin_status: str | None
    last_sync_at: datetime | None


def current_user(session: Session) -> User:
    token = get_access_token()
    if token is None or token.subject is None:
        raise ToolError("Not authenticated")
    user = session.get(User, int(token.subject))
    if user is None:
        raise ToolError("Unknown user")
    return user


def last_sync_at(session: Session, user: User) -> datetime | None:
    """Time of the last successful Garmin sync. None until the sync tables exist."""
    return None


def build_mcp(
    settings: Settings, sessions: sessionmaker[Session], provider: CgOAuthProvider
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
        """Who the signed-in user is, Garmin connection status and last data sync time."""
        with sessions() as session:
            user = current_user(session)
            creds = user.garmin_credentials
            return Profile(
                user=user.name,
                garmin_status=creds.status.value if creds else None,
                last_sync_at=last_sync_at(session, user),
            )

    return mcp
