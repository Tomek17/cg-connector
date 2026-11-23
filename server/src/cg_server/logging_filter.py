"""Log redaction: no tokens, codes or Authorization headers in logs (master prompt 7.8)."""

import logging
import re
from typing import Any

from uvicorn.config import LOGGING_CONFIG

REDACTED = "[REDACTED]"
PATTERNS = [
    (re.compile(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]+"), rf"\1 {REDACTED}"),
    (re.compile(r"\bcg[aci]_[A-Za-z0-9_-]+"), REDACTED),
    (
        re.compile(
            r"(?i)\b(code|code_verifier|state|request|token|access_token|refresh_token"
            r"|client_secret)=[^&\s\"']*"
        ),
        rf"\1={REDACTED}",
    ),
]


def redact(text: str) -> str:
    for pattern, replacement in PATTERNS:
        text = pattern.sub(replacement, text)
    return text


class RedactSecretsFilter(logging.Filter):
    """Redacts the message template and its arguments (uvicorn's access log needs args kept)."""

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str):
            record.msg = redact(record.msg)
        if isinstance(record.args, tuple):
            record.args = tuple(_redact_value(a) for a in record.args)
        elif isinstance(record.args, dict):
            record.args = {k: _redact_value(v) for k, v in record.args.items()}
        return True


def _redact_value(value: object) -> object:
    return redact(value) if isinstance(value, str) else value


def uvicorn_log_config() -> dict[str, Any]:
    """Uvicorn's default logging plus the redaction filter on every handler (incl. root)."""
    config: dict[str, Any] = {
        **LOGGING_CONFIG,
        "filters": {"redact": {"()": RedactSecretsFilter}},
        "handlers": {
            name: {**handler, "filters": ["redact"]}
            for name, handler in LOGGING_CONFIG["handlers"].items()
        },
    }
    config["root"] = {"handlers": ["default"], "level": "INFO"}
    return config
