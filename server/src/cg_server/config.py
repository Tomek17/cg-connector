"""Application settings, read from environment / .env (never committed)."""

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="CG_", env_file=".env", extra="ignore")

    data_dir: Path = Path("/data")
    timezone: str = "Europe/Warsaw"
    sync_hour: int = 14
    # Fernet key for Garmin token encryption. Empty in .env.example, set on the server only.
    encryption_key: str = ""


def get_settings() -> Settings:
    return Settings()
