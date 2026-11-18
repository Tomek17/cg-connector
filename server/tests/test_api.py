"""REST API for the Android app: Bearer app token -> current user."""

import pytest
from fastapi.testclient import TestClient

from cg_server.app import create_app
from cg_server.config import Settings
from cg_server.db import open_db
from cg_server.user_secrets import SecretKind, issue_secret
from cg_server.users import add_user, get_user
from tests.helpers import BASE, PUBLIC_HOST


@pytest.fixture
def tokens(settings: Settings) -> dict[str, dict[SecretKind, str]]:
    result = {}
    with open_db(settings)() as session:
        for name in ("tomek", "brat"):
            user = add_user(session, name)
            result[name] = {kind: issue_secret(session, user, kind) for kind in SecretKind}
    return result


@pytest.fixture
def client(settings: Settings) -> TestClient:
    app = create_app(settings.model_copy(update={"public_host": PUBLIC_HOST}))
    return TestClient(app, base_url=BASE)


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def test_me_returns_user_of_app_token(
    client: TestClient, tokens: dict[str, dict[SecretKind, str]]
) -> None:
    for name in ("tomek", "brat"):
        response = client.get("/api/v1/me", headers=bearer(tokens[name][SecretKind.APP_TOKEN]))
        assert response.status_code == 200
        assert response.json()["name"] == name


@pytest.mark.parametrize("header", [None, "Bearer ", "Bearer zly-token", "Basic abc"])
def test_me_requires_valid_app_token(
    client: TestClient, tokens: dict[str, dict[SecretKind, str]], header: str | None
) -> None:
    headers = {"Authorization": header} if header else {}
    response = client.get("/api/v1/me", headers=headers)
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"


def test_cg_code_is_not_an_app_token(
    client: TestClient, tokens: dict[str, dict[SecretKind, str]]
) -> None:
    response = client.get("/api/v1/me", headers=bearer(tokens["tomek"][SecretKind.CG_CODE]))
    assert response.status_code == 401


def test_rotated_app_token_stops_working(
    settings: Settings, client: TestClient, tokens: dict[str, dict[SecretKind, str]]
) -> None:
    old = tokens["tomek"][SecretKind.APP_TOKEN]
    with open_db(settings)() as session:
        user = get_user(session, "tomek")
        assert user is not None
        new = issue_secret(session, user, SecretKind.APP_TOKEN)
    assert client.get("/api/v1/me", headers=bearer(old)).status_code == 401
    assert client.get("/api/v1/me", headers=bearer(new)).status_code == 200
