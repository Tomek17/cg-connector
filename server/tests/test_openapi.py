"""OpenAPI export (`cg-server export-openapi`) and the committed contract file."""

import json
from pathlib import Path

import pytest

from cg_server.cli import main

COMMITTED = Path(__file__).resolve().parents[2] / "docs" / "api" / "openapi.json"


@pytest.fixture
def exported(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    monkeypatch.setenv("CG_ENV", "test")
    monkeypatch.delenv("CG_ENCRYPTION_KEY", raising=False)
    monkeypatch.setenv("CG_DATA_DIR", str(tmp_path / "nie-istnieje" / "data"))
    out = tmp_path / "openapi.json"
    assert main(["export-openapi", str(out)]) == 0
    assert not (tmp_path / "nie-istnieje").exists()  # no database is created for the export
    return out


def test_export_contains_read_api(exported: Path) -> None:
    spec = json.loads(exported.read_text(encoding="utf-8"))
    for path in [
        "/api/v1/me",
        "/api/v1/activities",
        "/api/v1/activities/{activity_id}",
        "/api/v1/vo2max",
        "/api/v1/summary",
    ]:
        assert path in spec["paths"], path
    params = {p["name"] for p in spec["paths"]["/api/v1/activities"]["get"]["parameters"]}
    assert {"from", "to", "type", "limit"} <= params


def test_committed_openapi_is_up_to_date(exported: Path) -> None:
    assert COMMITTED.read_text(encoding="utf-8") == exported.read_text(encoding="utf-8"), (
        "docs/api/openapi.json jest nieaktualny: "
        "CG_ENV=test uv run cg-server export-openapi ../docs/api/openapi.json"
    )


def test_export_refuses_production_env(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv("CG_ENV", "production")
    out = tmp_path / "openapi.json"
    assert main(["export-openapi", str(out)]) == 1
    assert "CG_ENV=test" in capsys.readouterr().err
    assert not out.exists()
