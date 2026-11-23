"""Garmin login from the Android app: password encryption key, pending MFA, attempt limit.

The password travels through the tunnel (ngrok terminates TLS, so it could see plaintext).
The app therefore encrypts it with this server's RSA public key (RSA-OAEP, SHA-256); only
this server can decrypt it. The private key is stored in the data directory, encrypted
with `CG_ENCRYPTION_KEY`. The password is never stored or logged.

Garmin blocks an account for about a day after a few failed logins, so a user gets at
most `MAX_FAILED` failed password/MFA attempts per `FAILED_WINDOW`. Pending MFA states hold
Garmin session cookies and live only in memory for `MFA_TTL`.
"""

import base64
import hashlib
import threading
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from cg_server.models import utcnow

KEY_FILE = "app-login-key.pem"
MFA_TTL = timedelta(minutes=10)
MAX_FAILED = 2
FAILED_WINDOW = timedelta(hours=1)
ALGORITHM = "RSA-OAEP-256"


class DecryptError(Exception):
    """The encrypted password cannot be decrypted with this server's key."""


class LoginKey:
    """RSA key pair for encrypting the Garmin password in the app."""

    def __init__(self, private_key: rsa.RSAPrivateKey) -> None:
        self._key = private_key
        der = private_key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        self.public_key_b64 = base64.b64encode(der).decode()
        self.fingerprint = hashlib.sha256(der).hexdigest()

    @classmethod
    def load_or_create(cls, data_dir: Path, encryption_key: str) -> "LoginKey":
        if not encryption_key:
            raise DecryptError("Brak klucza szyfrowania (CG_ENCRYPTION_KEY)")
        password = encryption_key.encode()
        path = data_dir / KEY_FILE
        if path.exists():
            loaded = serialization.load_pem_private_key(path.read_bytes(), password=password)
            if not isinstance(loaded, rsa.RSAPrivateKey):
                raise DecryptError("Zły typ klucza logowania")
            return cls(loaded)
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        pem = key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.BestAvailableEncryption(password),
        )
        data_dir.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(pem)
        tmp.chmod(0o600)
        tmp.replace(path)
        return cls(key)

    def decrypt(self, ciphertext_b64: str) -> str:
        try:
            data = base64.b64decode(ciphertext_b64, validate=True)
            plain = self._key.decrypt(
                data,
                padding.OAEP(
                    mgf=padding.MGF1(algorithm=hashes.SHA256()),
                    algorithm=hashes.SHA256(),
                    label=None,
                ),
            )
            return plain.decode()
        except (ValueError, UnicodeDecodeError) as e:
            raise DecryptError("Nie da się odszyfrować hasła") from e


@dataclass
class _UserState:
    mfa_state: dict[str, Any] | None = None
    mfa_expires: datetime | None = None
    failures: list[datetime] = field(default_factory=list)


class AppLogins:
    """In-memory per-user state of logins from the app (single server process)."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._users: dict[int, _UserState] = {}

    def _state(self, user_id: int) -> _UserState:
        return self._users.setdefault(user_id, _UserState())

    def blocked_until(self, user_id: int, now: datetime | None = None) -> datetime | None:
        """End of the pause after `MAX_FAILED` failed attempts, or None."""
        now = now or utcnow()
        with self._lock:
            state = self._state(user_id)
            state.failures = [t for t in state.failures if now - t < FAILED_WINDOW]
            if len(state.failures) >= MAX_FAILED:
                return state.failures[0] + FAILED_WINDOW
            return None

    def record_failure(self, user_id: int, now: datetime | None = None) -> None:
        with self._lock:
            self._state(user_id).failures.append(now or utcnow())

    def set_mfa(self, user_id: int, mfa_state: dict[str, Any], now: datetime | None = None) -> None:
        with self._lock:
            state = self._state(user_id)
            state.mfa_state = mfa_state
            state.mfa_expires = (now or utcnow()) + MFA_TTL

    def take_mfa(self, user_id: int, now: datetime | None = None) -> dict[str, Any] | None:
        """The pending MFA state (removed: one code attempt per login), or None if expired."""
        with self._lock:
            state = self._state(user_id)
            mfa, expires = state.mfa_state, state.mfa_expires
            state.mfa_state = state.mfa_expires = None
        if mfa is None or expires is None or (now or utcnow()) > expires:
            return None
        return mfa

    def clear(self, user_id: int) -> None:
        with self._lock:
            self._users.pop(user_id, None)
