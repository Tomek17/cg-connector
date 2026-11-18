"""FastAPI application factory: REST API, OAuth login page and MCP (`/mcp`) in one app."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from mcp.server.transport_security import TransportSecuritySettings

from cg_server import __version__
from cg_server.api import router as api_router
from cg_server.config import Settings, get_settings
from cg_server.db import open_db
from cg_server.login_page import login_router
from cg_server.mcp_server import build_mcp
from cg_server.oauth import CLAUDE_CALLBACKS, CgOAuthProvider
from cg_server.security import (
    BodySizeLimitMiddleware,
    PathAllowlistMiddleware,
    RateLimitMiddleware,
    SecurityHeadersMiddleware,
    content_security_policy,
)

OPENAPI_PATH = "/openapi.json"


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    sessions = open_db(settings)
    provider = CgOAuthProvider(settings, sessions)
    mcp = build_mcp(settings, sessions, provider)
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
        async with mcp.session_manager.run():
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

    @app.exception_handler(Exception)
    async def internal_error(_request: Request, _exc: Exception) -> JSONResponse:
        # No details or stack traces in responses (the traceback goes to the server log).
        return JSONResponse({"detail": "Błąd serwera"}, status_code=500)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok", "version": __version__}

    app.include_router(api_router)
    app.include_router(login_router(provider))

    # Added innermost first: the allowlist runs before rate limiting and body reading.
    app.add_middleware(RateLimitMiddleware, per_minute=settings.rate_limit_per_minute)
    app.add_middleware(BodySizeLimitMiddleware, max_bytes=settings.max_request_bytes)
    app.add_middleware(
        PathAllowlistMiddleware, extra_paths=() if settings.is_production else [OPENAPI_PATH]
    )
    app.add_middleware(
        SecurityHeadersMiddleware,
        csp=content_security_policy(settings.oauth_allow_localhost),
    )

    # Last: SDK routes (/mcp, /authorize, /token, /register, /revoke, /.well-known/*).
    app.mount("/", mcp_app)
    return app
