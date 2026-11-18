from pathlib import Path

import pytest

from cg_server.config import Settings
from cg_server.crypto import generate_key


@pytest.fixture(autouse=True)
def no_background_scheduler(monkeypatch: pytest.MonkeyPatch) -> None:
    """Apps created in tests never start the sync scheduler (unless a test enables it)."""
    monkeypatch.setenv("CG_SCHEDULER_ENABLED", "false")


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(data_dir=tmp_path / "data", encryption_key=generate_key())
