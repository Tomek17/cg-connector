"""Public endpoint hardening (master prompt 7.7): path allowlist, headers, limits, errors."""

import logging
from collections.abc import Iterator

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from uvicorn.middleware.proxy_headers import ProxyHeadersMiddleware

from cg_server.app import create_app
from cg_server.cli import SERVE_OPTIONS, main
from cg_server.config import ConfigError, Settings
from cg_server.logging_filter import RedactSecretsFilter
from tests.helpers import BASE, PUBLIC_HOST


def make_client(settings: Settings, **overrides: object) -> TestClient:
    app = create_app(settings.model_copy(update={"public_host": PUBLIC_HOST, **overrides}))
    return TestClient(app, base_url=BASE, raise_server_exceptions=False)


def test_default_env_is_production() -> None:
    assert Settings().env == "production"


@pytest.mark.parametrize(
    "path",
    ["/openapi.json", "/docs", "/redoc", "/admin", "/mcp/extra", "/.env", "/api/v2/me", "/"],
)
def test_paths_outside_allowlist_are_404(settings: Settings, path: str) -> None:
    response = make_client(settings).get(path)
    assert response.status_code == 404


def test_openapi_available_in_test_env(settings: Settings) -> None:
    response = make_client(settings, env="test").get("/openapi.json")
    assert response.status_code == 200
    assert "/api/v1/me" in response.json()["paths"]


@pytest.mark.parametrize(
    "path",
    [
        "/health",
        "/.well-known/oauth-authorization-server",
        "/.well-known/oauth-protected-resource/mcp",
    ],
)
def test_allowlisted_paths_work(settings: Settings, path: str) -> None:
    assert make_client(settings).get(path).status_code == 200


def test_security_headers(settings: Settings) -> None:
    response = make_client(settings).get("/health")
    headers = response.headers
    assert headers["x-content-type-options"] == "nosniff"
    assert headers["x-frame-options"] == "DENY"
    assert headers["referrer-policy"] == "no-referrer"
    assert "frame-ancestors 'none'" in headers["content-security-policy"]
    assert "max-age" in headers["strict-transport-security"]
    assert headers["cache-control"] == "no-store"


def test_login_page_csp_allows_only_self_and_callback_hosts(settings: Settings) -> None:
    csp = make_client(settings).get("/health").headers["content-security-policy"]
    assert "script-src" not in csp or "'none'" in csp
    assert "form-action 'self' https://claude.ai https://claude.com" in csp


def test_request_body_size_is_limited(settings: Settings) -> None:
    client = make_client(settings, max_request_bytes=1024)
    response = client.post("/token", content=b"x" * 2048)
    assert response.status_code == 413


def test_chunked_body_without_length_is_limited(settings: Settings) -> None:
    client = make_client(settings, max_request_bytes=1024)

    def chunks() -> Iterator[bytes]:
        for _ in range(4):
            yield b"x" * 512

    response = client.post("/token", content=chunks())
    assert response.status_code == 413


@pytest.mark.parametrize("path", ["/token", "/authorize", "/authorize/login", "/register"])
def test_rate_limit_on_oauth_endpoints(settings: Settings, path: str) -> None:
    client = make_client(settings, rate_limit_per_minute=3)
    statuses = [client.post(path, data={}).status_code for _ in range(4)]
    assert 429 not in statuses[:3]
    assert statuses[3] == 429


def test_rate_limit_does_not_apply_to_health(settings: Settings) -> None:
    client = make_client(settings, rate_limit_per_minute=1)
    assert all(client.get("/health").status_code == 200 for _ in range(5))


def test_errors_have_no_stack_trace(settings: Settings) -> None:
    app: FastAPI = create_app(settings.model_copy(update={"public_host": PUBLIC_HOST}))

    @app.get("/api/v1/boom")
    def boom() -> None:
        raise RuntimeError("sekretny-szczegol")

    app.router.routes.insert(0, app.router.routes.pop())  # before the catch-all MCP mount
    client = TestClient(app, base_url=BASE, raise_server_exceptions=False)
    response = client.get("/api/v1/boom")
    assert response.status_code == 500
    assert "sekretny-szczegol" not in response.text
    assert "Traceback" not in response.text


@pytest.mark.parametrize(
    "message",
    [
        "Authorization: Bearer abc.def-123",
        "GET /cb?code=abc123&state=x",
        "token cgc_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG leaked",
        "refresh_token=abcdef&x=1",
    ],
)
def test_log_filter_redacts_secrets(message: str) -> None:
    record = logging.LogRecord("x", logging.INFO, __file__, 1, message, None, None)
    assert RedactSecretsFilter().filter(record)
    text = record.getMessage()
    for secret in ("abc.def-123", "abc123", "cgc_abcdefghij", "abcdef&"):
        assert secret not in text


def test_log_filter_redacts_args() -> None:
    record = logging.LogRecord(
        "uvicorn.access",
        logging.INFO,
        __file__,
        1,
        '%s - "%s %s HTTP/%s" %d',
        ("1.2.3.4", "GET", "/authorize/login?request=tajne123", "1.1", 200),
        None,
    )
    RedactSecretsFilter().filter(record)
    assert "tajne123" not in record.getMessage()


def test_rate_limit_is_per_forwarded_client_ip(settings: Settings) -> None:
    """Behind Tailscale the real client IP is in X-Forwarded-For (uvicorn trusts it)."""
    app = create_app(
        settings.model_copy(update={"public_host": PUBLIC_HOST, "rate_limit_per_minute": 2})
    )
    proxied = ProxyHeadersMiddleware(app, trusted_hosts=SERVE_OPTIONS["forwarded_allow_ips"])
    client = TestClient(proxied, base_url=BASE)  # type: ignore[arg-type]

    def post(ip: str) -> int:
        return client.post("/token", data={}, headers={"X-Forwarded-For": ip}).status_code

    assert [post("203.0.113.1") for _ in range(3)][-1] == 429
    # Another client is not blocked by the first one's requests (e.g. Claude refreshing).
    assert post("198.51.100.7") != 429


def test_serve_trusts_forwarded_headers() -> None:
    assert SERVE_OPTIONS["proxy_headers"] is True
    assert SERVE_OPTIONS["forwarded_allow_ips"] == "*"


@pytest.mark.parametrize("host", ["", "localhost:8000", "127.0.0.1:8000", "localhost"])
def test_production_requires_public_host(settings: Settings, host: str) -> None:
    with pytest.raises(ConfigError, match="CG_PUBLIC_HOST"):
        create_app(settings.model_copy(update={"public_host": host}))


def test_test_env_allows_localhost(settings: Settings) -> None:
    create_app(settings.model_copy(update={"env": "test", "public_host": "localhost:8000"}))


def test_cli_serve_reports_missing_public_host(
    settings: Settings, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv("CG_DATA_DIR", str(settings.data_dir))
    monkeypatch.setenv("CG_PUBLIC_HOST", "")
    monkeypatch.setenv("CG_ENV", "production")
    assert main(["serve"]) == 1
    err = capsys.readouterr().err
    assert "CG_PUBLIC_HOST" in err
    assert "Traceback" not in err


@pytest.mark.parametrize("path", ["/health", "/api/v1/me", "/authorize/login", "/token"])
def test_foreign_host_is_rejected_everywhere(settings: Settings, path: str) -> None:
    """DNS rebinding: only the tunnel host and local addresses reach the app."""
    response = make_client(settings).get(path, headers={"Host": "evil.example.com"})
    assert response.status_code == 400


@pytest.mark.parametrize("host", [PUBLIC_HOST, "127.0.0.1:8000", "localhost:8000"])
def test_tunnel_and_local_hosts_are_allowed(settings: Settings, host: str) -> None:
    response = make_client(settings).get("/health", headers={"Host": host})
    assert response.status_code == 200
