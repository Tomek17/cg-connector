"""Personal CG codes and app tokens: random, shown once, only hashes stored, rotation."""

import pytest
from sqlalchemy import select

from cg_server.cli import main
from cg_server.config import Settings
from cg_server.db import db_path, open_db
from cg_server.models import OAuthClient, OAuthToken, User, UserSecret
from cg_server.user_secrets import (
    SecretKind,
    hash_secret,
    issue_secret,
    user_for_secret,
)
from cg_server.users import add_user


@pytest.fixture(autouse=True)
def env(settings: Settings, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CG_DATA_DIR", str(settings.data_dir))
    monkeypatch.setenv("CG_ENCRYPTION_KEY", settings.encryption_key)


def test_secret_has_at_least_32_bytes_of_randomness(settings: Settings) -> None:
    with open_db(settings)() as session:
        user = add_user(session, "tomek")
        code = issue_secret(session, user, SecretKind.CG_CODE)
        token = issue_secret(session, user, SecretKind.APP_TOKEN)
    assert code.startswith("cgc_") and token.startswith("cga_")
    # token_urlsafe(32) -> 43 characters after the prefix
    assert len(code) >= 4 + 43 and len(token) >= 4 + 43
    assert code != token


def test_only_hash_is_stored(settings: Settings) -> None:
    sessions = open_db(settings)
    with sessions() as session:
        user = add_user(session, "tomek")
        code = issue_secret(session, user, SecretKind.CG_CODE)
        stored = session.scalars(select(UserSecret)).one()
        assert stored.secret_hash == hash_secret(code)
    assert code.encode() not in db_path(settings).read_bytes()


def test_secret_kinds_are_separate(settings: Settings) -> None:
    with open_db(settings)() as session:
        user = add_user(session, "tomek")
        code = issue_secret(session, user, SecretKind.CG_CODE)
        token = issue_secret(session, user, SecretKind.APP_TOKEN)
        assert user_for_secret(session, SecretKind.CG_CODE, code) == user
        assert user_for_secret(session, SecretKind.APP_TOKEN, token) == user
        assert user_for_secret(session, SecretKind.APP_TOKEN, code) is None
        assert user_for_secret(session, SecretKind.CG_CODE, token) is None
        assert user_for_secret(session, SecretKind.CG_CODE, "") is None
        assert user_for_secret(session, SecretKind.CG_CODE, "zły-kod") is None


def test_rotation_invalidates_old_secret(settings: Settings) -> None:
    with open_db(settings)() as session:
        user = add_user(session, "tomek")
        old = issue_secret(session, user, SecretKind.CG_CODE)
        new = issue_secret(session, user, SecretKind.CG_CODE)
        assert user_for_secret(session, SecretKind.CG_CODE, old) is None
        assert user_for_secret(session, SecretKind.CG_CODE, new) == user


def test_cg_code_rotation_revokes_oauth_tokens(settings: Settings) -> None:
    with open_db(settings)() as session:
        user = add_user(session, "tomek")
        issue_secret(session, user, SecretKind.CG_CODE)
        session.add(OAuthClient(client_id="c1", metadata_json="{}"))
        session.add(
            OAuthToken(
                token_hash=hash_secret("x"),
                kind="access",
                grant_id="g",
                client_id="c1",
                user_id=user.id,
                scopes="cg",
                expires_at=0,
            )
        )
        session.commit()
        issue_secret(session, user, SecretKind.CG_CODE)
        assert session.scalars(select(OAuthToken)).all() == []


def test_users_have_independent_secrets(settings: Settings) -> None:
    with open_db(settings)() as session:
        a = add_user(session, "tomek")
        b = add_user(session, "brat")
        code_a = issue_secret(session, a, SecretKind.CG_CODE)
        code_b = issue_secret(session, b, SecretKind.CG_CODE)
        assert user_for_secret(session, SecretKind.CG_CODE, code_a) == a
        assert user_for_secret(session, SecretKind.CG_CODE, code_b) == b


@pytest.mark.parametrize(
    ("command", "prefix", "kind"),
    [("user-code", "cgc_", SecretKind.CG_CODE), ("app-token", "cga_", SecretKind.APP_TOKEN)],
)
def test_cli_prints_secret_once(
    settings: Settings,
    capsys: pytest.CaptureFixture[str],
    command: str,
    prefix: str,
    kind: SecretKind,
) -> None:
    assert main(["user-add", "--name", "tomek"]) == 0
    capsys.readouterr()

    assert main([command, "--user", "tomek"]) == 0

    out = capsys.readouterr().out
    secret = next(w for w in out.split() if w.startswith(prefix))
    with open_db(settings)() as session:
        user = session.scalars(select(User)).one()
        assert user_for_secret(session, kind, secret) == user


def test_cli_unknown_user_fails(capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["user-code", "--user", "nikt"]) == 1
    assert "user-add" in capsys.readouterr().err
