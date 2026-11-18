import pytest
from cryptography.fernet import Fernet

from cg_server.cli import main
from cg_server.crypto import CryptoError, decrypt, encrypt, generate_key


def test_round_trip() -> None:
    key = generate_key()
    plaintext = '{"di_token": "abc", "di_refresh_token": "def"}'
    ciphertext = encrypt(plaintext, key)
    assert ciphertext != plaintext
    assert "abc" not in ciphertext
    assert decrypt(ciphertext, key) == plaintext


def test_wrong_key_raises() -> None:
    ciphertext = encrypt("tajne", generate_key())
    with pytest.raises(CryptoError):
        decrypt(ciphertext, generate_key())


@pytest.mark.parametrize("key", ["", "not-a-fernet-key"])
def test_missing_or_invalid_key_raises(key: str) -> None:
    with pytest.raises(CryptoError):
        encrypt("tajne", key)


def test_gen_key_prints_valid_key(capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["gen-key"]) == 0
    key = capsys.readouterr().out.strip()
    Fernet(key.encode())  # raises if not a valid key
