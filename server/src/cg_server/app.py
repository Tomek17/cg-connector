"""FastAPI application factory: REST API, OAuth login page and MCP (`/mcp`) in one app."""

from collections.abc import AsyncIterator
from contextlib import AsyncExitStack, asynccontextmanager
from zoneinfo import ZoneInfo

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from mcp.server.auth.handlers.metadata import MetadataHandler
from mcp.server.auth.routes import build_metadata, cors_middleware
from mcp.server.auth.settings import ClientRegistrationOptions, RevocationOptions
from mcp.server.mcpserver import MCPServer
from mcp.server.transport_security import TransportSecuritySettings
from starlette.middleware.trustedhost import TrustedHostMiddleware
from starlette.routing import Route

from cg_server import __version__
from cg_server.api import router as api_router
from cg_server.api_workouts import router as workouts_router
from cg_server.config import Settings, get_settings
from cg_server.db import open_db
from cg_server.garmin.gateway import RealGarminGateway
from cg_server.login_page import login_router
from cg_server.mcp_server import build_mcp
from cg_server.oauth import CLAUDE_CALLBACKS, MAX_CLIENT_METADATA_BYTES, CgOAuthProvider
from cg_server.outbox import GatewayFactory, OutboxRunner
from cg_server.scheduler import scheduler_running
from cg_server.security import (
    BodySizeLimitMiddleware,
    PathAllowlistMiddleware,
    RateLimitMiddleware,
    SecurityHeadersMiddleware,
    content_security_policy,
)

OPENAPI_PATH = "/openapi.json"
METADATA_PATH = "/.well-known/oauth-authorization-server"


def authorization_server_metadata(mcp: MCPServer) -> Route:
    """RFC 8414 metadata as built by the SDK, plus `none` (public clients, e.g. claude.ai DCR).

    The SDK supports public clients but advertises only secret-based methods; this route is
    registered before the SDK mount, so it takes precedence.
    """
    auth = mcp.settings.auth
    assert auth is not None  # noqa: S101 - build_mcp always configures auth
    metadata = build_metadata(
        auth.issuer_url,
        auth.service_documentation_url,
        auth.client_registration_options or ClientRegistrationOptions(),
        auth.revocation_options or RevocationOptions(),
    )
    methods = ["none", "client_secret_post", "client_secret_basic"]
    metadata.token_endpoint_auth_methods_supported = methods
    metadata.revocation_endpoint_auth_methods_supported = methods
    return Route(
        METADATA_PATH,
        endpoint=cors_middleware(MetadataHandler(metadata).handle, ["GET", "OPTIONS"]),
        methods=["GET", "OPTIONS"],
    )


def create_app(
    settings: Settings | None = None, *, gateway_factory: GatewayFactory = RealGarminGateway
) -> FastAPI:
    settings = settings or get_settings()
    settings.check_public_host()
    sessions = open_db(settings)
    provider = CgOAuthProvider(settings, sessions)
    # Sends scheduled workouts right after they are added (scheduler: every 15 min).
    outbox = OutboxRunner(sessions, settings, gateway_factory)
    mcp = build_mcp(settings, sessions, provider, outbox)
    mcp_app = mcp.streamable_http_app(
        streamable_http_path="/mcp",
        stateless_http=True,
        json_response=True,
        max_request_body_size=settings.max_request_bytes,
        transport_security=TransportSecuritySettings(
            enable_dns_rebinding_protection=True,
            allowed_hosts=[settings.public_host],
            allowed_origins=sorted({u.split("/api/")[0] for u in CLAUDE_CALLBACKS}),
        ),
    )

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        # MCP session manager + daily Garmin sync scheduler (off with CG_SCHEDULER_ENABLED).
        async with AsyncExitStack() as stack:
            await stack.enter_async_context(mcp.session_manager.run())
            await stack.enter_async_context(scheduler_running(settings))
            yield

    app = FastAPI(
        title="CG-connector",
        version=__version__,
        docs_url=None,  # no public admin/docs UI (master prompt 3.3)
        redoc_url=None,
        openapi_url=None if settings.is_production else OPENAPI_PATH,
        lifespan=lifespan,
    )
    app.state.sessions = sessions
    app.state.outbox = outbox
    app.state.tz = ZoneInfo(settings.timezone)

    @app.exception_handler(Exception)
    async def internal_error(_request: Request, _exc: Exception) -> JSONResponse:
        # No details or stack traces in responses (the traceback goes to the server log).
        return JSONResponse({"detail": "Błąd serwera"}, status_code=500)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok", "version": __version__}

    app.include_router(api_router)
    app.include_router(workouts_router)
    app.include_router(login_router(provider))

    # Added innermost first: the allowlist runs before rate limiting and body reading.
    app.add_middleware(RateLimitMiddleware, per_minute=settings.rate_limit_per_minute)
    app.add_middleware(
        BodySizeLimitMiddleware,
        max_bytes=settings.max_request_bytes,
        per_path={"/register": MAX_CLIENT_METADATA_BYTES},
    )
    app.add_middleware(
        PathAllowlistMiddleware, extra_paths=() if settings.is_production else [OPENAPI_PATH]
    )
    # DNS rebinding: only the tunnel host (Tailscale keeps the original Host) and local checks
    # (healthcheck, status.ps1). /mcp checks the host again in the SDK.
    app.add_middleware(
        TrustedHostMiddleware,
        allowed_hosts=[settings.public_host.split(":")[0], "localhost", "127.0.0.1"],
    )
    app.add_middleware(
        SecurityHeadersMiddleware,
        csp=content_security_policy(settings.oauth_allow_localhost),
    )

    app.router.routes.append(authorization_server_metadata(mcp))
    # Last: SDK routes (/mcp, /authorize, /token, /register, /revoke, /.well-known/*).
    app.mount("/", mcp_app)
    return app
