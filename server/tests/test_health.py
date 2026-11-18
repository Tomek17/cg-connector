from fastapi.testclient import TestClient

from cg_server.app import create_app


def test_health_returns_ok() -> None:
    client = TestClient(create_app())
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_docs_ui_not_exposed() -> None:
    client = TestClient(create_app())
    assert client.get("/docs").status_code == 404
