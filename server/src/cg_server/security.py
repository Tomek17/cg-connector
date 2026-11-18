"""Hardening of the public endpoint (master prompt 7.7), as plain ASGI middleware.

- path allowlist: anything else is 404 (no admin panel or docs on the internet),
- security headers on every response,
- request body size limit,
- in-memory rate limit on the OAuth endpoints.
"""

import json
import time
from collections import deque
from collections.abc import Iterable

from starlette.datastructures import Headers, MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from cg_server.oauth import CLAUDE_CALLBACKS, LOGIN_PATH

PUBLIC_PATHS = frozenset(
    {
        "/health",
        "/mcp",
        "/authorize",
        LOGIN_PATH,
        "/token",
        "/register",
        "/revoke",
        "/.well-known/oauth-authorization-server",
        "/.well-known/oauth-protected-resource",
        "/.well-known/oauth-protected-resource/mcp",
    }
)
PUBLIC_PREFIXES = ("/api/v1/",)
RATE_LIMITED_PATHS = frozenset({"/authorize", LOGIN_PATH, "/token", "/register", "/revoke"})


async def _send_json(send: Send, status: int, detail: str) -> None:
    body = json.dumps({"detail": detail}).encode()
    await send(
        {
            "type": "http.response.start",
            "status": status,
            "headers": [
                (b"content-type", b"application/json"),
                (b"content-length", str(len(body)).encode()),
            ],
        }
    )
    await send({"type": "http.response.body", "body": body})


class PathAllowlistMiddleware:
    def __init__(self, app: ASGIApp, extra_paths: Iterable[str] = ()) -> None:
        self.app = app
        self.paths = PUBLIC_PATHS | frozenset(extra_paths)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http":
            path: str = scope["path"]
            if path not in self.paths and not path.startswith(PUBLIC_PREFIXES):
                await _send_json(send, 404, "Not Found")
                return
        await self.app(scope, receive, send)


def _origin(url: str) -> str:
    scheme, rest = url.split("://", 1)
    return f"{scheme}://{rest.split('/', 1)[0]}"


def content_security_policy(allow_localhost: bool) -> str:
    # The login form redirects to Claude after POST; browsers apply form-action to that redirect.
    targets = ["'self'", *dict.fromkeys(_origin(u) for u in CLAUDE_CALLBACKS)]
    if allow_localhost:
        targets += ["http://localhost:*", "http://127.0.0.1:*"]
    return (
        "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; "
        f"frame-ancestors 'none'; form-action {' '.join(targets)}"
    )


class SecurityHeadersMiddleware:
    def __init__(self, app: ASGIApp, csp: str) -> None:
        self.app = app
        self.headers = {
            "X-Content-Type-Options": "nosniff",
            "X-Frame-Options": "DENY",
            "Referrer-Policy": "no-referrer",
            "Content-Security-Policy": csp,
            "Strict-Transport-Security": "max-age=31536000",
            "Cache-Control": "no-store",
        }

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        async def send_with_headers(message: Message) -> None:
            if message["type"] == "http.response.start":
                headers = MutableHeaders(scope=message)
                for name, value in self.headers.items():
                    headers[name] = value
            await send(message)

        await self.app(scope, receive, send_with_headers)


class BodySizeLimitMiddleware:
    def __init__(
        self, app: ASGIApp, max_bytes: int, per_path: dict[str, int] | None = None
    ) -> None:
        self.app = app
        self.default_max_bytes = max_bytes
        self.per_path = per_path or {}

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        max_bytes = self.per_path.get(scope["path"], self.default_max_bytes)
        length = Headers(scope=scope).get("content-length")
        if length is not None and (not length.isdigit() or int(length) > max_bytes):
            await _send_json(send, 413, "Request too large")
            return
        # Streamed body without Content-Length: count bytes; once over the limit answer 413,
        # tell the app the client is gone and drop whatever it tries to send afterwards.
        received = 0
        started = False
        rejected = False

        async def limited_receive() -> Message:
            nonlocal received, rejected
            if rejected:
                return {"type": "http.disconnect"}
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > max_bytes:
                    rejected = True
                    if not started:
                        await guarded_send(None)
                    return {"type": "http.disconnect"}
            return message

        async def guarded_send(message: Message | None) -> None:
            nonlocal started
            if message is None:  # our 413
                started = True
                await _send_json(send, 413, "Request too large")
                return
            if rejected:
                return
            if message["type"] == "http.response.start":
                started = True
            await send(message)

        try:
            await self.app(scope, limited_receive, guarded_send)
        except Exception:
            if not rejected:
                raise


class RateLimitMiddleware:
    """Sliding one-minute window per (client IP, path). In memory: fine for one process."""

    WINDOW = 60.0

    def __init__(self, app: ASGIApp, per_minute: int) -> None:
        self.app = app
        self.per_minute = per_minute
        self.hits: dict[tuple[str, str], deque[float]] = {}

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http" and scope["path"] in RATE_LIMITED_PATHS:
            client = scope.get("client")
            key = (client[0] if client else "?", scope["path"])
            if not self._allow(key, time.monotonic()):
                await _send_json(send, 429, "Too many requests")
                return
        await self.app(scope, receive, send)

    def _allow(self, key: tuple[str, str], now: float) -> bool:
        if len(self.hits) > 10_000:
            self.hits = {k: v for k, v in self.hits.items() if v and v[-1] > now - self.WINDOW}
        window = self.hits.setdefault(key, deque())
        while window and window[0] <= now - self.WINDOW:
            window.popleft()
        if len(window) >= self.per_minute:
            return False
        window.append(now)
        return True
