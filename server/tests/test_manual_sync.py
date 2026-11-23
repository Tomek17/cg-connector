"""Sync now: refusal rules, REST POST /api/v1/sync and the blocking runner."""

from datetime import timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from cg_server import manual_sync
from cg_server.app import create_app
from cg_server.config import Settings
from cg_server.crypto import encrypt
from cg_server.db import open_db
from cg_server.garmin.fake import FakeGarminGateway
from cg_server.manual_sync import ManualSync, Refusal
from cg_server.models import (
    Activity,
    GarminCredentials,
    GarminStatus,
    SyncRun,
    SyncStatus,
    User,
    utcnow,
)
from cg_server.user_secrets import SecretKind, issue_secret
from cg_server.users import add_user
from tests.helpers import BASE, PUBLIC_HOST
from tests.seed import add_sync


@pytest.fixture
def app_settings(settings: Settings) -> Settings:
    return settings.model_copy(update={"public_host": PUBLIC_HOST, "sync_request_delay_s": 0})


def with_garmin(session: Session, settings: Settings, name: str = "tomek") -> User:
    user = add_user(session, name)
    user.garmin_credentials = GarminCredentials(
        status=GarminStatus.OK, encrypted_tokens=encrypt("{}", settings.encryption_key)
    )
    session.commit()
    return user


def test_check_rules(app_settings: Settings) -> None:
    now = utcnow()
    with open_db(app_settings)() as session:
        bare = add_user(session, "bez-garmina")
        assert manual_sync.check(session, bare, now).refusal == Refusal.NO_GARMIN

        user = with_garmin(session, app_settings)
        assert manual_sync.check(session, user, now).refusal is None

        add_sync(session, user, now - timedelta(minutes=1))
        session.commit()
        result = manual_sync.check(session, user, now)
        assert result.refusal == Refusal.TOO_SOON
        assert result.retry_at is not None and result.retry_at > now
        assert manual_sync.check(session, user, now + timedelta(minutes=10)).refusal is None

        session.add(SyncRun(user_id=user.id, started_at=now, status=SyncStatus.RUNNING))
        session.commit()
        assert manual_sync.check(session, user, now).refusal == Refusal.RUNNING
        # A run left `running` long ago was interrupted; it does not block for ever.
        assert manual_sync.check(session, user, now + timedelta(hours=1)).refusal is None

        assert user.garmin_credentials is not None
        user.garmin_credentials.rate_limited_at = now
        session.commit()
        assert manual_sync.check(session, user, now).refusal == Refusal.RATE_LIMITED

        user.garmin_credentials.status = GarminStatus.NEEDS_LOGIN
        session.commit()
        assert manual_sync.check(session, user, now).refusal == Refusal.NEEDS_LOGIN


def test_runner_syncs_and_reports_counts(app_settings: Settings) -> None:
    sessions = open_db(app_settings)
    with sessions() as session:
        user_id = with_garmin(session, app_settings).id
    fake = FakeGarminGateway()
    result = ManualSync(sessions, app_settings, lambda: fake).run(user_id)
    assert result is not None
    assert result.status == SyncStatus.OK
    assert result.activities_new == len(fake.activities)
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(Activity)) == len(fake.activities)


def test_rest_sync_now_starts_then_refuses_too_soon(app_settings: Settings) -> None:
    with open_db(app_settings)() as session:
        user = with_garmin(session, app_settings)
        token = issue_secret(session, user, SecretKind.APP_TOKEN)
    fake = FakeGarminGateway()
    client = TestClient(create_app(app_settings, gateway_factory=lambda: fake), base_url=BASE)
    headers = {"Authorization": f"Bearer {token}"}

    first = client.post("/api/v1/sync", headers=headers)
    assert first.status_code == 200
    assert first.json() == {"started": True, "reason": None, "retry_at": None}
    # The background task ran after the response (TestClient waits for it).
    assert client.get("/api/v1/me", headers=headers).json()["last_sync_status"] == "ok"

    second = client.post("/api/v1/sync", headers=headers).json()
    assert second["started"] is False
    assert second["reason"] == "too_soon"
    assert second["retry_at"] is not None


def test_rest_sync_now_needs_auth(app_settings: Settings) -> None:
    client = TestClient(create_app(app_settings), base_url=BASE)
    assert client.post("/api/v1/sync").status_code == 401
