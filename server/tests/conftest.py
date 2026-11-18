from pathlib import Path

import pytest

from cg_server.config import Settings
from cg_server.crypto import generate_key


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(data_dir=tmp_path / "data", encryption_key=generate_key())
