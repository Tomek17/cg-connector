"""CLI: sync, sync-status. Always FakeGarminGateway, never real Garmin."""

from datetime import date, datetime, timedelta

import pytest

from cg_server.cli import main
from cg_server.config import Settings
from cg_server.garmin.fake import FakeGarminGateway, make_activity
from cg_server.garmin.gateway import GarminRateLimited

TOKENS = '{"di_token": "fake-access-1", "di_refresh_token": "fake-refresh-1"}'


@pytest.fixture(autouse=True)
def env(settings: Settings, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CG_DATA_DIR", str(settings.data_dir))
    monkeypatch.setenv("CG_ENCRYPTION_KEY", settings.encryption_key)
    monkeypatch.setenv("CG_SYNC_REQUEST_DELAY_S", "0")
    monkeypatch.setattr("builtins.input", lambda _prompt="": "jan@example.com")
    monkeypatch.setattr("getpass.getpass", lambda _prompt="": "haslo")


def logged_in_user(name: str = "tomek") -> None:
    assert main(["user-add", "--name", name]) == 0
    fake = FakeGarminGateway(tokens=TOKENS)
    assert main(["garmin-login", "--user", name], gateway_factory=lambda: fake) == 0


def recent_activities() -> list[dict[str, object]]:
    yesterday = date.today() - timedelta(days=1)
    start = datetime(yesterday.year, yesterday.month, yesterday.day, 7)
    return [make_activity(7, start, "running"), make_activity(8, start, "strength_training")]


def test_sync_command(capsys: pytest.CaptureFixture[str]) -> None:
    logged_in_user()
    capsys.readouterr()
    fake = FakeGarminGateway(activities=recent_activities())

    assert main(["sync", "--user", "tomek"], gateway_factory=lambda: fake) == 0

    out = capsys.readouterr().out
    assert "ok" in out
    assert "nowe aktywności: 2" in out
    assert "fake-access" not in out


def test_sync_unknown_user(capsys: pytest.CaptureFixture[str]) -> None:
    fake = FakeGarminGateway()
    assert main(["sync", "--user", "nikt"], gateway_factory=lambda: fake) == 1
    assert fake.calls == []
    assert "user-add" in capsys.readouterr().err


def test_sync_rate_limited_reports_and_fails(capsys: pytest.CaptureFixture[str]) -> None:
    logged_in_user()
    capsys.readouterr()
    fake = FakeGarminGateway(error=GarminRateLimited())

    assert main(["sync", "--user", "tomek"], gateway_factory=lambda: fake) == 1
    assert "429" in capsys.readouterr().err
    assert fake.calls == ["resume"]

    again = FakeGarminGateway()
    assert main(["sync", "--user", "tomek"], gateway_factory=lambda: again) == 1
    assert again.calls == []
    assert "skipped_rate_limited" in capsys.readouterr().err


def test_sync_status(capsys: pytest.CaptureFixture[str]) -> None:
    logged_in_user("tomek")
    assert main(["user-add", "--name", "brat"]) == 0
    fake = FakeGarminGateway(activities=recent_activities())
    main(["sync", "--user", "tomek"], gateway_factory=lambda: fake)
    capsys.readouterr()

    assert main(["sync-status"]) == 0

    lines = capsys.readouterr().out.splitlines()
    tomek = next(line for line in lines if line.startswith("tomek"))
    brat = next(line for line in lines if line.startswith("brat"))
    assert "ok" in tomek and "aktywności: 2" in tomek
    assert "nigdy" in brat


def test_sync_while_another_sync_runs(
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    from cg_server.synclock import sync_lock

    logged_in_user()
    capsys.readouterr()
    fake = FakeGarminGateway(activities=recent_activities())

    with sync_lock(settings.data_dir):
        assert main(["sync", "--user", "tomek"], gateway_factory=lambda: fake) == 1

    assert "Synchronizacja już trwa" in capsys.readouterr().err
    assert fake.calls == []
