"""CLI: user-add, garmin-login, garmin-check. Always with FakeGarminGateway, never real Garmin."""

import builtins
import getpass
from collections.abc import Iterator

import pytest

from cg_server.cli import main
from cg_server.config import Settings
from cg_server.crypto import decrypt
from cg_server.db import db_path, open_db
from cg_server.garmin.fake import FakeGarminGateway
from cg_server.garmin.gateway import GarminAuthError, GarminRateLimited
from cg_server.models import GarminCredentials, GarminStatus, User

PASSWORD = "bardzo-tajne-haslo-123"  # noqa: S105 - synthetic test value
TOKENS = '{"di_token": "fake-access-1", "di_refresh_token": "fake-refresh-1"}'


@pytest.fixture(autouse=True)
def env(settings: Settings, monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    monkeypatch.setenv("CG_DATA_DIR", str(settings.data_dir))
    monkeypatch.setenv("CG_ENCRYPTION_KEY", settings.encryption_key)
    answers = iter(["jan@example.com", "123456"])
    monkeypatch.setattr(builtins, "input", lambda _prompt="": next(answers))
    monkeypatch.setattr(getpass, "getpass", lambda _prompt="": PASSWORD)
    yield


def credentials(settings: Settings, name: str = "tomek") -> GarminCredentials | None:
    with open_db(settings)() as session:
        user = session.query(User).filter_by(name=name).one()
        return user.garmin_credentials


def login(fake: FakeGarminGateway, name: str = "tomek") -> int:
    return main(["garmin-login", "--user", name], gateway_factory=lambda: fake)


def check(fake: FakeGarminGateway, name: str = "tomek") -> int:
    return main(["garmin-check", "--user", name], gateway_factory=lambda: fake)


def test_user_add(settings: Settings, capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["user-add", "--name", "tomek"]) == 0
    assert "tomek" in capsys.readouterr().out
    with open_db(settings)() as session:
        assert session.query(User).filter_by(name="tomek").count() == 1


def test_user_add_rejects_duplicate(capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["user-add", "--name", "tomek"]) == 0
    assert main(["user-add", "--name", "tomek"]) == 1
    assert "już istnieje" in capsys.readouterr().err


@pytest.mark.parametrize("name", ["", "Tomek Kowalski", "a" * 33, "../x"])
def test_user_add_rejects_invalid_name(name: str) -> None:
    assert main(["user-add", "--name", name]) == 1


def test_login_unknown_user_fails(capsys: pytest.CaptureFixture[str]) -> None:
    fake = FakeGarminGateway()
    assert login(fake, "nikt") == 1
    assert "user-add" in capsys.readouterr().err
    assert fake.calls == []


def test_login_stores_encrypted_tokens_not_plaintext(
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    main(["user-add", "--name", "tomek"])
    fake = FakeGarminGateway(tokens=TOKENS)

    assert login(fake) == 0

    assert fake.mfa_code == "123456"
    creds = credentials(settings)
    assert creds is not None and creds.encrypted_tokens is not None
    assert creds.status == GarminStatus.OK
    assert decrypt(creds.encrypted_tokens, settings.encryption_key) == TOKENS
    raw_db = db_path(settings).read_bytes()
    for secret in ("fake-access-1", "fake-refresh-1", PASSWORD, "jan@example.com"):
        assert secret.encode() not in raw_db
    out = capsys.readouterr()
    assert PASSWORD not in out.out + out.err
    assert "fake-access-1" not in out.out + out.err


def test_login_without_encryption_key_fails_before_contacting_garmin(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    main(["user-add", "--name", "tomek"])
    monkeypatch.setenv("CG_ENCRYPTION_KEY", "")
    fake = FakeGarminGateway()
    assert login(fake) == 1
    assert fake.calls == []


def test_login_rate_limited_sets_status_and_does_not_retry(
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    main(["user-add", "--name", "tomek"])
    fake = FakeGarminGateway(error=GarminRateLimited())

    assert login(fake) == 1

    assert fake.calls == ["login"]
    creds = credentials(settings)
    assert creds is not None and creds.status == GarminStatus.RATE_LIMITED
    assert "429" in capsys.readouterr().err


def test_login_auth_error_sets_needs_login(settings: Settings) -> None:
    main(["user-add", "--name", "tomek"])
    assert login(FakeGarminGateway(error=GarminAuthError())) == 1
    creds = credentials(settings)
    assert creds is not None and creds.status == GarminStatus.NEEDS_LOGIN


def test_check_saves_refreshed_tokens(
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    main(["user-add", "--name", "tomek"])
    login(FakeGarminGateway(tokens=TOKENS))
    fake = FakeGarminGateway(refreshed_tokens='{"di_token": "fake-access-2"}')

    assert check(fake) == 0

    assert fake.calls == ["resume", "get_profile"]
    creds = credentials(settings)
    assert creds is not None and creds.encrypted_tokens is not None
    assert decrypt(creds.encrypted_tokens, settings.encryption_key) == (
        '{"di_token": "fake-access-2"}'
    )
    out = capsys.readouterr().out
    assert "OK, zalogowano jako Test Biegacz" in out
    assert "fake-access" not in out


def test_check_without_tokens_asks_for_login(capsys: pytest.CaptureFixture[str]) -> None:
    main(["user-add", "--name", "tomek"])
    fake = FakeGarminGateway()
    assert check(fake) == 1
    assert "garmin-login --user tomek" in capsys.readouterr().err
    assert fake.calls == []


def test_check_rate_limited_sets_status_and_keeps_tokens(settings: Settings) -> None:
    main(["user-add", "--name", "tomek"])
    login(FakeGarminGateway(tokens=TOKENS))
    fake = FakeGarminGateway(error=GarminRateLimited())

    assert check(fake) == 1

    assert fake.calls == ["resume"]
    creds = credentials(settings)
    assert creds is not None and creds.status == GarminStatus.RATE_LIMITED
    assert creds.encrypted_tokens is not None
    assert decrypt(creds.encrypted_tokens, settings.encryption_key) == TOKENS


def test_check_rejected_tokens_set_needs_login(settings: Settings) -> None:
    main(["user-add", "--name", "tomek"])
    login(FakeGarminGateway(tokens=TOKENS))
    assert check(FakeGarminGateway(error=GarminAuthError())) == 1
    creds = credentials(settings)
    assert creds is not None and creds.status == GarminStatus.NEEDS_LOGIN
