"""Shared test helpers: OAuth client (DCR + PKCE) and MCP client over ASGI."""

import base64
import hashlib
import re
import secrets
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Any
from urllib.parse import parse_qs, urlparse

import httpx2
from fastapi import FastAPI
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

PUBLIC_HOST = "cg.example.ts.net"
BASE = f"https://{PUBLIC_HOST}"
CALLBACK = "https://claude.ai/api/mcp/auth_callback"


@dataclass
class Pkce:
    verifier: str
    challenge: str

    @classmethod
    def new(cls) -> "Pkce":
        verifier = secrets.token_urlsafe(48)
        digest = hashlib.sha256(verifier.encode()).digest()
        return cls(verifier, base64.urlsafe_b64encode(digest).decode().rstrip("="))


@asynccontextmanager
async def running(app: FastAPI) -> AsyncIterator[httpx2.AsyncClient]:
    """Run the app lifespan and yield an HTTP client talking to it in-process."""
    async with app.router.lifespan_context(app):
        transport = httpx2.ASGITransport(app=app)
        async with httpx2.AsyncClient(
            transport=transport, base_url=BASE, follow_redirects=False
        ) as http:
            yield http


async def register(
    http: httpx2.AsyncClient,
    redirect_uri: str = CALLBACK,
    auth_method: str = "none",
) -> dict[str, Any]:
    response = await http.post(
        "/register",
        json={
            "redirect_uris": [redirect_uri],
            "token_endpoint_auth_method": auth_method,
            "grant_types": ["authorization_code", "refresh_token"],
            "response_types": ["code"],
            "client_name": "Claude",
        },
    )
    assert response.status_code == 201, response.text
    data: dict[str, Any] = response.json()
    return data


async def start_authorize(
    http: httpx2.AsyncClient, client_id: str, pkce: Pkce, state: str = "st-1"
) -> str:
    """GET /authorize; return the login request id from our login page."""
    response = await http.get(
        "/authorize",
        params={
            "response_type": "code",
            "client_id": client_id,
            "redirect_uri": CALLBACK,
            "code_challenge": pkce.challenge,
            "code_challenge_method": "S256",
            "state": state,
            "scope": "cg",
            "resource": f"{BASE}/mcp",
        },
    )
    assert response.status_code == 302, response.text
    page = await http.get(response.headers["location"])
    assert page.status_code == 200
    assert "Wklej swój kod CG-connector" in page.text
    match = re.search(r'name="request" value="([^"]+)"', page.text)
    assert match is not None
    return match.group(1)


async def submit_code(http: httpx2.AsyncClient, request_id: str, code: str) -> httpx2.Response:
    return await http.post("/authorize/login", data={"request": request_id, "code": code})


async def authorize(
    http: httpx2.AsyncClient, client_id: str, pkce: Pkce, cg_code: str, state: str = "st-1"
) -> str:
    """Full browser part of the flow; return the authorization code from the callback."""
    request_id = await start_authorize(http, client_id, pkce, state)
    response = await submit_code(http, request_id, cg_code)
    assert response.status_code == 302, response.text
    location = urlparse(response.headers["location"])
    assert f"{location.scheme}://{location.netloc}{location.path}" == CALLBACK
    query = parse_qs(location.query)
    assert query["state"] == [state]
    return query["code"][0]


async def exchange_code(
    http: httpx2.AsyncClient, client: dict[str, Any], code: str, pkce: Pkce
) -> httpx2.Response:
    data = {
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": CALLBACK,
        "client_id": client["client_id"],
        "code_verifier": pkce.verifier,
    }
    if client.get("client_secret"):
        data["client_secret"] = client["client_secret"]
    return await http.post("/token", data=data)


async def refresh(
    http: httpx2.AsyncClient, client: dict[str, Any], refresh_token: str
) -> httpx2.Response:
    return await http.post(
        "/token",
        data={
            "grant_type": "refresh_token",
            "refresh_token": refresh_token,
            "client_id": client["client_id"],
        },
    )


async def login_tokens(http: httpx2.AsyncClient, cg_code: str) -> dict[str, Any]:
    """Register a client and go through the whole flow; return the token response."""
    client = await register(http)
    pkce = Pkce.new()
    code = await authorize(http, client["client_id"], pkce, cg_code)
    response = await exchange_code(http, client, code, pkce)
    assert response.status_code == 200, response.text
    tokens: dict[str, Any] = response.json()
    tokens["client"] = client
    return tokens


async def call_tool(
    http: httpx2.AsyncClient, access_token: str, name: str, args: dict[str, Any] | None = None
) -> Any:
    http.headers["Authorization"] = f"Bearer {access_token}"
    try:
        async with Client(streamable_http_client(f"{BASE}/mcp", http_client=http)) as client:
            result = await client.call_tool(name, args or {})
    finally:
        del http.headers["Authorization"]
    assert not result.is_error, result
    return result.structured_content
