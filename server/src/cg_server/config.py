"""Application settings, read from environment / .env (never committed)."""

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="CG_", env_file=".env", extra="ignore")

    data_dir: Path = Path("/data")
    timezone: str = "Europe/Warsaw"
    sync_hour: int = 14
    # Pause before each Garmin request during sync (seconds); be gentle with Garmin.
    sync_request_delay_s: float = 2.0
    # How far back the first sync goes.
    sync_history_days: int = 365
    # Max Garmin requests per sync run; the first 12-month sync finishes over several days.
    sync_max_requests_per_run: int = 300
    # Background scheduler (daily sync + catch-up). Off in tests.
    scheduler_enabled: bool = True
    # Fernet key for Garmin token encryption. Empty in .env.example, set on the server only.
    encryption_key: str = ""


def get_settings() -> Settings:
    return Settings()
