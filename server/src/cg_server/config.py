"""Application settings, read from environment / .env (never committed)."""

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

LOCAL_HOSTS = ("", "localhost", "127.0.0.1")


class ConfigError(Exception):
    """Invalid configuration; the message (Polish) is shown to the operator."""


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="CG_", env_file=".env", extra="ignore", env_ignore_empty=True
    )

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
    # "production" hides OpenAPI; any other value (e.g. "test", "dev") exposes /openapi.json.
    env: str = "production"
    # Public host name of the tunnel, e.g. "cg-server.tail1234.ts.net" (no scheme, no path).
    public_host: str = "localhost:8000"
    # Allow OAuth redirect URIs on http://localhost / 127.0.0.1 (MCP Inspector, Claude Code).
    oauth_allow_localhost: bool = False
    max_request_bytes: int = 1024 * 1024
    # Requests per minute per client IP on /authorize, /token, /register, /revoke.
    rate_limit_per_minute: int = 30
    # Who may invite new people from the app; empty = the first account created (Tomek).
    admin_user: str = ""

    @property
    def is_production(self) -> bool:
        return self.env == "production"

    def check_public_host(self) -> None:
        """In production the OAuth issuer must be the public tunnel address."""
        if self.is_production and self.public_host.split(":")[0] in LOCAL_HOSTS:
            raise ConfigError(
                "Brak publicznego adresu serwera. Ustaw CG_PUBLIC_HOST w pliku .env na nazwę "
                "hosta z tunelu Tailscale, np. cg-server.tail1234.ts.net (bez https:// i bez /mcp)."
            )

    @property
    def public_url(self) -> str:
        host = self.public_host.split(":")[0]
        scheme = "http" if host in LOCAL_HOSTS else "https"
        return f"{scheme}://{self.public_host}"


def get_settings() -> Settings:
    return Settings()
