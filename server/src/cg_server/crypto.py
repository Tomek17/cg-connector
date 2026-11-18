"""Symmetric encryption (Fernet) for secrets at rest, e.g. Garmin tokens."""

from cryptography.fernet import Fernet, InvalidToken


class CryptoError(Exception):
    """Missing/invalid key or data that cannot be decrypted with it."""


def generate_key() -> str:
    return Fernet.generate_key().decode()


def _fernet(key: str) -> Fernet:
    if not key:
        raise CryptoError(
            "Brak klucza szyfrowania (CG_ENCRYPTION_KEY). Wygeneruj: cg-server gen-key"
        )
    try:
        return Fernet(key.encode())
    except ValueError as e:
        raise CryptoError("Nieprawidłowy klucz szyfrowania (CG_ENCRYPTION_KEY)") from e


def encrypt(plaintext: str, key: str) -> str:
    return _fernet(key).encrypt(plaintext.encode()).decode()


def decrypt(ciphertext: str, key: str) -> str:
    try:
        return _fernet(key).decrypt(ciphertext.encode()).decode()
    except InvalidToken as e:
        raise CryptoError("Nie można odszyfrować danych tym kluczem") from e
