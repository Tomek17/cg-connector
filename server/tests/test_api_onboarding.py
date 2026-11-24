"""Setting up a person from the app: invite -> app token, Garmin login with MFA, CG code."""

import base64
from datetime import timedelta

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from fastapi.testclient import TestClient
from sqlalchemy import select

from cg_server.app import create_app
from cg_server.cli import main
from cg_server.config import Settings
from cg_server.crypto import decrypt
from cg_server.db import open_db
from cg_server.garmin.fake import FakeGarminGateway
from cg_server.garmin.gateway import GarminRateLimited
from cg_server.models import GarminStatus, User, UserSecret, utcnow
from cg_server.user_secrets import SecretKind, issue_secret, redeem_invite, user_for_secret
from cg_server.users import add_user, get_user, is_admin
from tests.helpers import BASE, PUBLIC_HOST

PASSWORD = "-".join(["Tajne", "Haslo", "123"])  # synthetic test value


@pytest.fixture
def app_settings(settings: Settings) -> Settings:
    return settings.model_copy(update={"public_host": PUBLIC_HOST, "sync_request_delay_s": 0})


@pytest.fixture
def gateway() -> FakeGarminGateway:
    return FakeGarminGateway(password=PASSWORD, activities=[])


@pytest.fixture
def client(app_settings: Settings, gateway: FakeGarminGateway) -> TestClient:
    return TestClient(create_app(app_settings, gateway_factory=lambda: gateway), base_url=BASE)


@pytest.fixture
def invite(app_settings: Settings) -> str:
    with open_db(app_settings)() as session:
        return issue_secret(session, add_user(session, "ola"), SecretKind.INVITE)


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def encrypt_password(public_key_b64: str, password: str = PASSWORD) -> str:
    key = serialization.load_der_public_key(base64.b64decode(public_key_b64))
    assert isinstance(key, rsa.RSAPublicKey)
    data = key.encrypt(
        password.encode(),
        padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
    )
    return base64.b64encode(data).decode()


def onboard(client: TestClient, invite: str) -> tuple[str, str]:
    response = client.post("/api/v1/invite/redeem", json={"code": invite})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["user"] == "ola"
    assert body["app_token"].startswith("cga_")
    assert body["login_key"]["algorithm"] == "RSA-OAEP-256"
    return body["app_token"], body["login_key"]["public_key"]


def test_invite_is_one_time_and_gives_app_token(
    client: TestClient, invite: str, app_settings: Settings
) -> None:
    token, _ = onboard(client, invite)
    assert client.get("/api/v1/me", headers=bearer(token)).json()["name"] == "ola"
    again = client.post("/api/v1/invite/redeem", json={"code": invite})
    assert again.status_code == 404
    with open_db(app_settings)() as session:
        kinds = session.scalars(select(UserSecret.kind)).all()
        assert SecretKind.INVITE.value not in kinds


def test_expired_or_wrong_invite_is_refused(app_settings: Settings, invite: str) -> None:
    with open_db(app_settings)() as session:
        assert redeem_invite(session, "cgi_zly") is None
        assert redeem_invite(session, invite, now=utcnow() + timedelta(days=8)) is None
        # Expired codes are removed too.
        assert redeem_invite(session, invite) is None


def test_garmin_login_without_mfa_saves_tokens_and_starts_sync(
    client: TestClient, invite: str, gateway: FakeGarminGateway, app_settings: Settings
) -> None:
    token, key = onboard(client, invite)
    response = client.post(
        "/api/v1/garmin/login",
        headers=bearer(token),
        json={"email": " ola@example.com ", "password_encrypted": encrypt_password(key)},
    )
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "retry_at": None, "sync_started": True}
    assert gateway.logins[0] == ("ola@example.com", PASSWORD)
    assert "get_activities_by_date" in gateway.calls  # first sync ran (background task)
    with open_db(app_settings)() as session:
        user = get_user(session, "ola")
        assert user is not None and user.garmin_credentials is not None
        assert user.garmin_credentials.status == GarminStatus.OK
        assert user.garmin_credentials.encrypted_tokens is not None
        assert decrypt(user.garmin_credentials.encrypted_tokens, app_settings.encryption_key)
        assert PASSWORD not in user.garmin_credentials.encrypted_tokens


def test_garmin_login_with_mfa(client: TestClient, invite: str, gateway: FakeGarminGateway) -> None:
    gateway.expected_mfa = "123456"
    token, key = onboard(client, invite)
    first = client.post(
        "/api/v1/garmin/login",
        headers=bearer(token),
        json={"email": "ola@example.com", "password_encrypted": encrypt_password(key)},
    )
    assert first.json()["status"] == "needs_mfa"
    done = client.post("/api/v1/garmin/mfa", headers=bearer(token), json={"code": "123456"})
    assert done.json()["status"] == "ok"
    # The MFA state is single-use.
    again = client.post("/api/v1/garmin/mfa", headers=bearer(token), json={"code": "123456"})
    assert again.json()["status"] == "mfa_expired"


def test_wrong_password_is_limited_to_two_attempts(
    client: TestClient, invite: str, gateway: FakeGarminGateway, app_settings: Settings
) -> None:
    token, key = onboard(client, invite)
    body = {"email": "ola@example.com", "password_encrypted": encrypt_password(key, "zle")}
    for _ in range(2):
        response = client.post("/api/v1/garmin/login", headers=bearer(token), json=body)
        assert response.json()["status"] == "bad_credentials"
    third = client.post("/api/v1/garmin/login", headers=bearer(token), json=body).json()
    assert third["status"] == "too_many_attempts"
    assert third["retry_at"] is not None
    assert len(gateway.logins) == 2  # the third attempt never reached Garmin
    with open_db(app_settings)() as session:
        user = get_user(session, "ola")
        assert user is not None and user.garmin_credentials is not None
        assert user.garmin_credentials.status == GarminStatus.NEEDS_LOGIN


def test_rate_limit_starts_cooldown(
    client: TestClient, invite: str, gateway: FakeGarminGateway
) -> None:
    token, key = onboard(client, invite)
    gateway.error = GarminRateLimited()
    body = {"email": "ola@example.com", "password_encrypted": encrypt_password(key)}
    assert (
        client.post("/api/v1/garmin/login", headers=bearer(token), json=body).json()["status"]
        == "rate_limited"
    )
    gateway.error = None
    second = client.post("/api/v1/garmin/login", headers=bearer(token), json=body).json()
    assert second["status"] == "rate_limited"
    assert second["retry_at"] is not None
    assert len(gateway.logins) == 0  # error raised before the fake recorded anything


def test_unencrypted_password_is_refused(client: TestClient, invite: str) -> None:
    token, _ = onboard(client, invite)
    response = client.post(
        "/api/v1/garmin/login",
        headers=bearer(token),
        json={"email": "ola@example.com", "password_encrypted": base64.b64encode(b"x").decode()},
    )
    assert response.json()["status"] == "key_changed"


def test_login_key_is_stable_and_needs_token(
    client: TestClient, invite: str, app_settings: Settings
) -> None:
    token, key = onboard(client, invite)
    assert client.get("/api/v1/garmin/login-key").status_code == 401
    assert client.get("/api/v1/garmin/login-key", headers=bearer(token)).json()["public_key"] == key
    # A restarted server reads the same key from the data directory.
    other = TestClient(create_app(app_settings), base_url=BASE)
    assert other.get("/api/v1/garmin/login-key", headers=bearer(token)).json()["public_key"] == key
    assert (app_settings.data_dir / "app-login-key.pem").stat().st_mode & 0o077 == 0


def test_claude_code_is_issued_for_the_token_owner(
    client: TestClient, invite: str, app_settings: Settings
) -> None:
    token, _ = onboard(client, invite)
    code = client.post("/api/v1/claude-code", headers=bearer(token)).json()["code"]
    assert code.startswith("cgc_")
    with open_db(app_settings)() as session:
        user = user_for_secret(session, SecretKind.CG_CODE, code)
        assert isinstance(user, User) and user.name == "ola"
    assert client.post("/api/v1/claude-code").status_code == 401


def test_cli_invite_adds_user_and_prints_code(
    app_settings: Settings, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv("CG_DATA_DIR", str(app_settings.data_dir))
    monkeypatch.setenv("CG_ENCRYPTION_KEY", app_settings.encryption_key)
    assert main(["invite", "--name", "brat"]) == 0
    out = capsys.readouterr().out
    assert "Dodano użytkownika „brat”" in out
    code = next(w for w in out.split() if w.startswith("cgi_"))
    with open_db(app_settings)() as session:
        result = redeem_invite(session, code)
        assert result is not None and result[0].name == "brat"


def test_join_page_links_app_and_prefills_code(client: TestClient, invite: str) -> None:
    response = client.get("/join", params={"code": invite})
    assert response.status_code == 200
    html = response.text
    assert "releases/latest/download/cg-connector.apk" in html
    assert "cgconnector://join?server=https%3A%2F%2Fcg.example.ts.net&amp;code=" + invite in html
    # The page never redeems the code.
    assert client.post("/api/v1/invite/redeem", json={"code": invite}).status_code == 200


@pytest.mark.parametrize(
    "code", ["", "cgi_", "cgi_<script>alert(1)</script>xxxxxxxxxxxx", "cga_" + "x" * 40]
)
def test_join_page_rejects_bad_codes(client: TestClient, code: str) -> None:
    response = client.get("/join", params={"code": code})
    assert response.status_code == 400
    assert "<script>" not in response.text


def test_cli_invite_prints_join_link(
    app_settings: Settings, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv("CG_DATA_DIR", str(app_settings.data_dir))
    monkeypatch.setenv("CG_ENCRYPTION_KEY", app_settings.encryption_key)
    monkeypatch.setenv("CG_PUBLIC_HOST", "cg.example.ngrok-free.dev")
    assert main(["invite", "--name", "ola"]) == 0
    out = capsys.readouterr().out
    assert "https://cg.example.ngrok-free.dev/join?code=cgi_" in out


def test_invite_codes_are_redacted_in_logs() -> None:
    from cg_server.logging_filter import redact

    assert "cgi_abc" not in redact("GET /join?code=cgi_abcdefghijklmnopqrstuvwxyz HTTP/1.1")
    assert "cgi_abc" not in redact("token cgi_abcdefghijklmnopqrstuvwxyz")


def test_admin_invites_from_the_app(client: TestClient, app_settings: Settings) -> None:
    with open_db(app_settings)() as session:
        admin = issue_secret(session, add_user(session, "tomek"), SecretKind.APP_TOKEN)
        other = issue_secret(session, add_user(session, "brat"), SecretKind.APP_TOKEN)
    me = client.get("/api/v1/me", headers=bearer(admin)).json()
    assert me["is_admin"] is True
    assert client.get("/api/v1/me", headers=bearer(other)).json()["is_admin"] is False

    created = client.post("/api/v1/invites", json={"name": " Wojtek "}, headers=bearer(admin))
    assert created.status_code == 201, created.text
    body = created.json()
    assert body["name"] == "wojtek" and body["new_user"] is True
    assert body["link"].startswith(f"https://{PUBLIC_HOST}/join?code=cgi_")
    code = body["link"].split("code=")[1]
    assert client.post("/api/v1/invite/redeem", json={"code": code}).json()["user"] == "wojtek"

    again = client.post("/api/v1/invites", json={"name": "brat"}, headers=bearer(admin)).json()
    assert again["new_user"] is False

    assert (
        client.post("/api/v1/invites", json={"name": "x"}, headers=bearer(other)).status_code == 403
    )
    assert (
        client.post("/api/v1/invites", json={"name": "tomek"}, headers=bearer(admin)).status_code
        == 409
    )
    bad = client.post("/api/v1/invites", json={"name": "Ża!"}, headers=bearer(admin))
    assert bad.status_code == 422


def test_admin_from_settings(app_settings: Settings) -> None:
    with open_db(app_settings)() as session:
        first = add_user(session, "tomek")
        second = add_user(session, "brat")
        assert is_admin(session, app_settings, first) and not is_admin(
            session, app_settings, second
        )
        chosen = app_settings.model_copy(update={"admin_user": "brat"})
        assert is_admin(session, chosen, second) and not is_admin(session, chosen, first)
