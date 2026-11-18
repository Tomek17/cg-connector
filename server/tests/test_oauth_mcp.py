"""OAuth 2.1 (DCR + PKCE) with the personal CG code, and the MCP endpoint behind it."""

import time
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx2
import pytest
from fastapi import FastAPI
from mcp.server.auth.provider import TokenError
from sqlalchemy import select

from cg_server.app import create_app
from cg_server.config import Settings
from cg_server.db import db_path, open_db
from cg_server.models import (
    Activity,
    ActivityKind,
    OAuthClient,
    OAuthToken,
    SyncRun,
    SyncStatus,
    User,
    utcnow,
)
from cg_server.oauth import MAX_CLIENTS, CgOAuthProvider
from cg_server.user_secrets import SecretKind, hash_secret, issue_secret
from cg_server.users import add_user
from tests.helpers import (
    BASE,
    CALLBACK,
    PUBLIC_HOST,
    Pkce,
    authorize,
    call_tool,
    exchange_code,
    login_page,
    login_tokens,
    refresh,
    register,
    running,
    start_authorize,
    submit_code,
)

pytestmark = pytest.mark.anyio


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@dataclass
class Account:
    name: str
    cg_code: str
    app_token: str


@pytest.fixture
def app_settings(settings: Settings) -> Settings:
    return settings.model_copy(update={"env": "test", "public_host": PUBLIC_HOST})


@pytest.fixture
def accounts(app_settings: Settings) -> dict[str, Account]:
    result = {}
    with open_db(app_settings)() as session:
        for name in ("tomek", "brat"):
            user = add_user(session, name)
            result[name] = Account(
                name,
                issue_secret(session, user, SecretKind.CG_CODE),
                issue_secret(session, user, SecretKind.APP_TOKEN),
            )
    return result


@pytest.fixture
def app(app_settings: Settings, accounts: dict[str, Account]) -> Iterator[FastAPI]:
    yield create_app(app_settings)


async def test_metadata_announces_dcr_and_pkce(app: FastAPI) -> None:
    async with running(app) as http:
        meta = (await http.get("/.well-known/oauth-authorization-server")).json()
        assert meta["issuer"] == BASE
        assert meta["registration_endpoint"] == f"{BASE}/register"
        assert meta["code_challenge_methods_supported"] == ["S256"]
        prm = (await http.get("/.well-known/oauth-protected-resource/mcp")).json()
        assert prm["resource"] == f"{BASE}/mcp"
        assert prm["authorization_servers"] == [BASE]


async def test_mcp_without_token_returns_401_with_resource_metadata(app: FastAPI) -> None:
    async with running(app) as http:
        response = await http.post("/mcp", json={})
        assert response.status_code == 401
        challenge = response.headers["www-authenticate"]
        assert f'resource_metadata="{BASE}/.well-known/oauth-protected-resource/mcp"' in challenge


async def test_full_flow_tool_call_and_refresh_rotation(
    app: FastAPI, accounts: dict[str, Account]
) -> None:
    async with running(app) as http:
        client = await register(http)
        pkce = Pkce.new()
        code = await authorize(http, client["client_id"], pkce, accounts["tomek"].cg_code)

        response = await exchange_code(http, client, code, pkce)
        assert response.status_code == 200, response.text
        tokens = response.json()
        assert tokens["token_type"].lower() == "bearer"
        assert tokens["expires_in"] > 0

        profile = await call_tool(http, tokens["access_token"], "get_profile")
        assert profile["user"] == "tomek"
        assert profile["last_sync_at"] is None

        # The code is single-use.
        again = await exchange_code(http, client, code, pkce)
        assert again.status_code == 400
        assert again.json()["error"] == "invalid_grant"

        # Refresh rotates both tokens; the old refresh token stops working.
        rotated = await refresh(http, client, tokens["refresh_token"])
        assert rotated.status_code == 200, rotated.text
        new = rotated.json()
        assert new["refresh_token"] != tokens["refresh_token"]
        assert new["access_token"] != tokens["access_token"]
        old_access = await http.post(
            "/mcp", json={}, headers={"Authorization": f"Bearer {tokens['access_token']}"}
        )
        assert old_access.status_code == 401
        profile = await call_tool(http, new["access_token"], "get_profile")
        assert profile["user"] == "tomek"

        # The next rotation works too (same grant).
        newer = await refresh(http, client, new["refresh_token"])
        assert newer.status_code == 200, newer.text
        assert (await call_tool(http, newer.json()["access_token"], "get_profile"))[
            "user"
        ] == "tomek"
        reuse = await refresh(http, client, tokens["refresh_token"])
        assert reuse.status_code == 400
        assert reuse.json()["error"] == "invalid_grant"


async def test_wrong_pkce_verifier_is_rejected(app: FastAPI, accounts: dict[str, Account]) -> None:
    async with running(app) as http:
        client = await register(http)
        pkce = Pkce.new()
        code = await authorize(http, client["client_id"], pkce, accounts["tomek"].cg_code)
        response = await exchange_code(http, client, code, Pkce.new())
        assert response.status_code == 400
        assert response.json()["error"] == "invalid_grant"


async def test_wrong_cg_code_is_rejected_and_retry_works(
    app: FastAPI, accounts: dict[str, Account]
) -> None:
    async with running(app) as http:
        client = await register(http)
        request_id = await start_authorize(http, client["client_id"], Pkce.new())
        for wrong in ("zly-kod", accounts["tomek"].app_token, ""):
            response = await submit_code(http, request_id, wrong)
            assert response.status_code == 401
            assert "location" not in response.headers
            assert "Nieprawidłowy kod" in response.text
        ok = await submit_code(http, request_id, accounts["tomek"].cg_code)
        assert ok.status_code == 302
        assert ok.headers["location"].startswith(CALLBACK)
        # The login request is single-use.
        reuse = await submit_code(http, request_id, accounts["tomek"].cg_code)
        assert reuse.status_code == 400


async def test_too_many_wrong_codes_invalidate_login_request(
    app: FastAPI, accounts: dict[str, Account]
) -> None:
    async with running(app) as http:
        client = await register(http)
        request_id = await start_authorize(http, client["client_id"], Pkce.new())
        for _ in range(5):
            assert (await submit_code(http, request_id, "zly")).status_code == 401
        response = await submit_code(http, request_id, accounts["tomek"].cg_code)
        assert response.status_code == 400


async def test_unknown_login_request_is_rejected(app: FastAPI) -> None:
    async with running(app) as http:
        page = await http.get("/authorize/login", params={"request": "nie-ma"})
        assert page.status_code == 400
        response = await submit_code(http, "nie-ma", "x")
        assert response.status_code == 400


async def test_login_page_escapes_request_id(app: FastAPI) -> None:
    async with running(app) as http:
        page = await http.get("/authorize/login", params={"request": '"><script>x</script>'})
        assert "<script>" not in page.text


@pytest.mark.parametrize(
    "redirect_uri",
    [
        "https://evil.example.com/callback",
        "https://claude.ai.evil.com/api/mcp/auth_callback",
        "http://claude.ai/api/mcp/auth_callback",
        "http://localhost:6274/callback",
    ],
)
async def test_register_rejects_redirect_uri_outside_allowlist(
    app: FastAPI, redirect_uri: str
) -> None:
    async with running(app) as http:
        response = await http.post(
            "/register",
            json={
                "redirect_uris": [redirect_uri],
                "token_endpoint_auth_method": "none",
                "grant_types": ["authorization_code", "refresh_token"],
                "response_types": ["code"],
            },
        )
        assert response.status_code == 400
        assert response.json()["error"] == "invalid_redirect_uri"


async def test_claude_com_callback_is_allowed(app: FastAPI) -> None:
    async with running(app) as http:
        await register(http, redirect_uri="https://claude.com/api/mcp/auth_callback")


async def test_localhost_redirect_allowed_only_by_setting(
    app_settings: Settings, accounts: dict[str, Account]
) -> None:
    app = create_app(app_settings.model_copy(update={"oauth_allow_localhost": True}))
    async with running(app) as http:
        await register(http, redirect_uri="http://localhost:6274/oauth/callback")
        await register(http, redirect_uri="http://127.0.0.1:33418/callback")


async def test_authorize_rejects_unregistered_redirect_uri(
    app: FastAPI, accounts: dict[str, Account]
) -> None:
    async with running(app) as http:
        client = await register(http)
        response = await http.get(
            "/authorize",
            params={
                "response_type": "code",
                "client_id": client["client_id"],
                "redirect_uri": "https://evil.example.com/cb",
                "code_challenge": Pkce.new().challenge,
                "code_challenge_method": "S256",
                "state": "s",
            },
        )
        assert response.status_code == 400
        assert "location" not in response.headers


async def test_users_are_isolated(app: FastAPI, accounts: dict[str, Account]) -> None:
    async with running(app) as http:
        tomek = await login_tokens(http, accounts["tomek"].cg_code)
        brat = await login_tokens(http, accounts["brat"].cg_code)
        assert (await call_tool(http, tomek["access_token"], "get_profile"))["user"] == "tomek"
        assert (await call_tool(http, brat["access_token"], "get_profile"))["user"] == "brat"
        # A refresh token is bound to the client it was issued to.
        stolen = await refresh(http, tomek["client"], brat["refresh_token"])
        assert stolen.status_code == 400


async def test_confidential_client_needs_its_secret(
    app: FastAPI, accounts: dict[str, Account]
) -> None:
    async with running(app) as http:
        client = await register(http, auth_method="client_secret_post")
        assert client["client_secret"]
        pkce = Pkce.new()
        code = await authorize(http, client["client_id"], pkce, accounts["tomek"].cg_code)
        without = await exchange_code(http, {**client, "client_secret": None}, code, pkce)
        assert without.status_code == 401
        response = await exchange_code(http, client, code, pkce)
        assert response.status_code == 200, response.text


async def test_database_stores_only_hashes(
    app: FastAPI, app_settings: Settings, accounts: dict[str, Account]
) -> None:
    async with running(app) as http:
        client = await register(http, auth_method="client_secret_post")
        pkce = Pkce.new()
        code = await authorize(http, client["client_id"], pkce, accounts["tomek"].cg_code)
        tokens = (await exchange_code(http, client, code, pkce)).json()
        rotated = (await refresh_confidential(http, client, tokens["refresh_token"])).json()

    with open_db(app_settings)() as session:
        hashes = set(session.scalars(select(OAuthToken.token_hash)).all())
        assert hash_secret(rotated["access_token"]) in hashes
        assert hash_secret(rotated["refresh_token"]) in hashes
        stored_client = session.get(OAuthClient, client["client_id"])
        assert stored_client is not None
        assert client["client_secret"] not in stored_client.metadata_json
    raw = db_path(app_settings).read_bytes()
    secrets_ = [
        code,
        tokens["access_token"],
        tokens["refresh_token"],
        rotated["access_token"],
        rotated["refresh_token"],
        client["client_secret"],
        accounts["tomek"].cg_code,
        accounts["tomek"].app_token,
    ]
    for secret in secrets_:
        assert secret.encode() not in raw


async def refresh_confidential(
    http: httpx2.AsyncClient, client: dict[str, Any], refresh_token: str
) -> httpx2.Response:
    return await http.post(
        "/token",
        data={
            "grant_type": "refresh_token",
            "refresh_token": refresh_token,
            "client_id": client["client_id"],
            "client_secret": client["client_secret"],
        },
    )


async def test_expired_access_token_is_rejected(
    app: FastAPI, app_settings: Settings, accounts: dict[str, Account]
) -> None:
    async with running(app) as http:
        tokens = await login_tokens(http, accounts["tomek"].cg_code)
        with open_db(app_settings)() as session:
            row = session.get(OAuthToken, hash_secret(tokens["access_token"]))
            assert row is not None
            row.expires_at = int(time.time()) - 1
            session.commit()
        response = await http.post(
            "/mcp", json={}, headers={"Authorization": f"Bearer {tokens['access_token']}"}
        )
        assert response.status_code == 401


async def test_rotating_cg_code_disconnects_claude(
    app: FastAPI, app_settings: Settings, accounts: dict[str, Account]
) -> None:
    async with running(app) as http:
        tokens = await login_tokens(http, accounts["tomek"].cg_code)
        with open_db(app_settings)() as session:
            user = session.scalars(select(User).where(User.name == "tomek")).one()
            issue_secret(session, user, SecretKind.CG_CODE)
        response = await refresh(http, tokens["client"], tokens["refresh_token"])
        assert response.status_code == 400


async def test_mcp_rejects_foreign_host(app: FastAPI, accounts: dict[str, Account]) -> None:
    async with running(app) as http:
        tokens = await login_tokens(http, accounts["tomek"].cg_code)
        response = await http.post(
            "/mcp",
            json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
            headers={
                "Authorization": f"Bearer {tokens['access_token']}",
                "Host": "evil.example.com",
                "Accept": "application/json, text/event-stream",
            },
        )
        assert response.status_code == 421


async def test_reused_refresh_token_revokes_whole_grant(
    app: FastAPI, accounts: dict[str, Account]
) -> None:
    async with running(app) as http:
        tokens = await login_tokens(http, accounts["tomek"].cg_code)
        client = tokens["client"]
        new = (await refresh(http, client, tokens["refresh_token"])).json()
        # Someone replays the old (already rotated) refresh token: treat as theft.
        replay = await refresh(http, client, tokens["refresh_token"])
        assert replay.status_code == 400
        assert replay.json()["error"] == "invalid_grant"
        # The whole grant is gone: the legitimate new tokens stop working too.
        assert (await refresh(http, client, new["refresh_token"])).status_code == 400
        response = await http.post(
            "/mcp", json={}, headers={"Authorization": f"Bearer {new['access_token']}"}
        )
        assert response.status_code == 401


async def test_code_and_refresh_are_single_use_at_db_level(
    app_settings: Settings, accounts: dict[str, Account]
) -> None:
    """The provider consumes codes/refresh tokens atomically (DELETE/UPDATE rowcount == 1)."""
    sessions = open_db(app_settings)
    provider = CgOAuthProvider(app_settings, sessions)
    app = create_app(app_settings)
    async with running(app) as http:
        client = await register(http)
        pkce = Pkce.new()
        code = await authorize(http, client["client_id"], pkce, accounts["tomek"].cg_code)
    info = await provider.get_client(client["client_id"])
    assert info is not None
    loaded = await provider.load_authorization_code(info, code)
    assert loaded is not None
    tokens = await provider.exchange_authorization_code(info, loaded)
    with pytest.raises(TokenError):
        await provider.exchange_authorization_code(info, loaded)
    assert tokens.refresh_token is not None
    rt = await provider.load_refresh_token(info, tokens.refresh_token)
    assert rt is not None
    await provider.exchange_refresh_token(info, rt, [])
    with pytest.raises(TokenError):
        await provider.exchange_refresh_token(info, rt, [])


async def test_metadata_allows_public_clients(app: FastAPI) -> None:
    async with running(app) as http:
        meta = (await http.get("/.well-known/oauth-authorization-server")).json()
        assert "none" in meta["token_endpoint_auth_methods_supported"]
        assert "client_secret_post" in meta["token_endpoint_auth_methods_supported"]


async def test_login_page_shows_client_name_and_redirect_host(
    app: FastAPI, accounts: dict[str, Account]
) -> None:
    async with running(app) as http:
        client = await register(http, client_name="<b>Claude</b>")
        html = await login_page(http, client["client_id"], Pkce.new())
        assert "&lt;b&gt;Claude&lt;/b&gt;" in html
        assert "<b>Claude</b>" not in html
        assert "claude.ai" in html


async def test_register_rejects_large_metadata(app: FastAPI) -> None:
    async with running(app) as http:
        response = await http.post(
            "/register",
            json={
                "redirect_uris": [CALLBACK],
                "token_endpoint_auth_method": "none",
                "client_name": "x" * 5000,
            },
        )
        assert response.status_code in (400, 413)


async def test_register_limits_number_of_clients(app_settings: Settings) -> None:
    app = create_app(app_settings.model_copy(update={"rate_limit_per_minute": 1000}))
    async with running(app) as http:
        for _ in range(MAX_CLIENTS):
            await register(http)
        response = await http.post(
            "/register",
            json={"redirect_uris": [CALLBACK], "token_endpoint_auth_method": "none"},
        )
        assert response.status_code == 400
        assert response.json()["error"] == "invalid_client_metadata"


async def test_register_cleans_up_stale_clients_without_tokens(
    app_settings: Settings, accounts: dict[str, Account]
) -> None:
    app = create_app(app_settings)
    async with running(app) as http:
        stale = await register(http)
        active = await login_tokens(http, accounts["tomek"].cg_code)
        fresh = await register(http)
        with open_db(app_settings)() as session:
            for client_id in (stale["client_id"], active["client"]["client_id"]):
                row = session.get(OAuthClient, client_id)
                assert row is not None
                row.created_at = utcnow() - timedelta(days=8)
            session.commit()
        await register(http)
    with open_db(app_settings)() as session:
        assert session.get(OAuthClient, stale["client_id"]) is None
        assert session.get(OAuthClient, active["client"]["client_id"]) is not None
        assert session.get(OAuthClient, fresh["client_id"]) is not None


async def test_get_profile_shows_last_sync_and_latest_activity_of_own_user(
    app: FastAPI, app_settings: Settings, accounts: dict[str, Account]
) -> None:
    def activity(user: User, garmin_id: int, day: int) -> Activity:
        return Activity(
            user_id=user.id,
            garmin_activity_id=garmin_id,
            kind=ActivityKind.RUNNING,
            type_key="running",
            start_time_utc=datetime(2026, 11, day, 6, tzinfo=UTC),
            start_time_local=datetime(2026, 11, day, 7),
        )

    with open_db(app_settings)() as session:
        tomek = session.scalars(select(User).where(User.name == "tomek")).one()
        brat = session.scalars(select(User).where(User.name == "brat")).one()
        ok_at = datetime(2026, 11, 17, 13, 5, tzinfo=UTC)
        session.add_all(
            [
                SyncRun(user_id=tomek.id, status=SyncStatus.OK, finished_at=ok_at),
                # A later failed run does not count as the last successful sync.
                SyncRun(
                    user_id=tomek.id,
                    status=SyncStatus.FAILED,
                    finished_at=ok_at + timedelta(days=1),
                ),
                SyncRun(
                    user_id=brat.id,
                    status=SyncStatus.OK,
                    finished_at=ok_at + timedelta(days=1),
                ),
                activity(tomek, 1, 10),
                activity(tomek, 2, 12),
                activity(brat, 3, 15),
            ]
        )
        session.commit()

    async with running(app) as http:
        tomek_tokens = await login_tokens(http, accounts["tomek"].cg_code)
        brat_tokens = await login_tokens(http, accounts["brat"].cg_code)
        profile = await call_tool(http, tomek_tokens["access_token"], "get_profile")
        other = await call_tool(http, brat_tokens["access_token"], "get_profile")

    assert profile["user"] == "tomek"
    assert profile["last_sync_at"] == "2026-11-17T14:05:00+01:00"  # Europe/Warsaw
    assert profile["latest_activity_date"] == "2026-11-12"
    assert other["latest_activity_date"] == "2026-11-15"
    assert other["last_sync_at"] == "2026-11-18T14:05:00+01:00"


async def test_get_profile_without_data(app: FastAPI, accounts: dict[str, Account]) -> None:
    async with running(app) as http:
        tokens = await login_tokens(http, accounts["tomek"].cg_code)
        profile = await call_tool(http, tokens["access_token"], "get_profile")
    assert profile == {
        "user": "tomek",
        "garmin_status": None,
        "last_sync_at": None,
        "latest_activity_date": None,
    }
