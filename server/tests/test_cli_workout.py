"""CLI: workout-test (small test workout for the Enduro 3 spike). Always the fake Garmin."""

from datetime import date, timedelta

import pytest

from cg_server.cli import main
from cg_server.config import Settings
from cg_server.garmin.fake import FakeGarminGateway
from cg_server.garmin.gateway import GarminRateLimited

TOKENS = '{"di_token": "fake-access-1", "di_refresh_token": "fake-refresh-1"}'
DAY = (date.today() + timedelta(days=1)).isoformat()


@pytest.fixture(autouse=True)
def env(settings: Settings, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CG_DATA_DIR", str(settings.data_dir))
    monkeypatch.setenv("CG_ENCRYPTION_KEY", settings.encryption_key)
    monkeypatch.setenv("CG_SYNC_REQUEST_DELAY_S", "0")
    monkeypatch.setattr("builtins.input", lambda _prompt="": "jan@example.com")
    monkeypatch.setattr("getpass.getpass", lambda _prompt="": "haslo")
    assert main(["user-add", "--name", "tomek"]) == 0
    fake = FakeGarminGateway(tokens=TOKENS)
    assert main(["garmin-login", "--user", "tomek"], gateway_factory=lambda: fake) == 0


@pytest.mark.parametrize(("kind", "sport"), [("run", "running"), ("strength", "strength_training")])
def test_workout_test_creates_and_schedules(
    kind: str, sport: str, capsys: pytest.CaptureFixture[str]
) -> None:
    capsys.readouterr()
    fake = FakeGarminGateway()
    args = ["workout-test", "--user", "tomek", "--date", DAY, "--kind", kind]
    assert main(args, gateway_factory=lambda: fake) == 0
    out = capsys.readouterr().out
    assert "sent" in out
    (workout,) = fake.workouts.values()
    assert workout["sportType"]["sportTypeKey"] == sport
    assert workout["workoutName"].startswith("CG Test")
    (entry,) = fake.calendar.values()
    assert entry["date"] == DAY


def test_workout_test_reports_rate_limit(capsys: pytest.CaptureFixture[str]) -> None:
    capsys.readouterr()
    fake = FakeGarminGateway(error=GarminRateLimited())
    args = ["workout-test", "--user", "tomek", "--date", DAY, "--kind", "run"]
    assert main(args, gateway_factory=lambda: fake) == 1
    assert "429" in capsys.readouterr().err


def test_workout_test_bad_date(capsys: pytest.CaptureFixture[str]) -> None:
    fake = FakeGarminGateway()
    args = ["workout-test", "--user", "tomek", "--date", "2020-01-01", "--kind", "run"]
    assert main(args, gateway_factory=lambda: fake) == 1
    assert fake.calls == []
    assert "past" in capsys.readouterr().err
