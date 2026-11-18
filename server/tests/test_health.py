from fastapi.testclient import TestClient

from cg_server.app import create_app
from cg_server.config import Settings
from tests.helpers import BASE, PUBLIC_HOST


def test_health_returns_ok(settings: Settings) -> None:
    client = TestClient(
        create_app(settings.model_copy(update={"public_host": PUBLIC_HOST})), base_url=BASE
    )
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_docs_ui_not_exposed(settings: Settings) -> None:
    client = TestClient(
        create_app(settings.model_copy(update={"public_host": PUBLIC_HOST})), base_url=BASE
    )
    assert client.get("/docs").status_code == 404
