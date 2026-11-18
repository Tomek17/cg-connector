from datetime import UTC, datetime, timedelta

from cg_server.garmin.accounts import RATE_LIMIT_COOLDOWN, rate_limit_cooldown_until
from cg_server.models import GarminCredentials, GarminStatus, User

NOW = datetime(2026, 11, 18, 14, 0, tzinfo=UTC)


def user_rate_limited_at(at: datetime | None) -> User:
    user = User(name="tomek")
    user.garmin_credentials = GarminCredentials(
        status=GarminStatus.RATE_LIMITED, rate_limited_at=at
    )
    return user


def test_no_cooldown_without_credentials_or_429() -> None:
    assert rate_limit_cooldown_until(User(name="tomek"), now=NOW) is None
    assert rate_limit_cooldown_until(user_rate_limited_at(None), now=NOW) is None


def test_cooldown_lasts_24_hours() -> None:
    at = NOW - timedelta(hours=1)
    assert rate_limit_cooldown_until(user_rate_limited_at(at), now=NOW) == at + RATE_LIMIT_COOLDOWN
    old = NOW - RATE_LIMIT_COOLDOWN - timedelta(seconds=1)
    assert rate_limit_cooldown_until(user_rate_limited_at(old), now=NOW) is None


def test_naive_datetime_from_sqlite_is_treated_as_utc() -> None:
    at = (NOW - timedelta(hours=1)).replace(tzinfo=None)
    until = rate_limit_cooldown_until(user_rate_limited_at(at), now=NOW)
    assert until == NOW - timedelta(hours=1) + RATE_LIMIT_COOLDOWN
