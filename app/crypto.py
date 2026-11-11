import base64
import hashlib
import logging
from functools import lru_cache

from cryptography.fernet import Fernet, InvalidToken

from app.config import settings

log = logging.getLogger(__name__)


@lru_cache(maxsize=4)
def _fernet(secret_key: str) -> Fernet:
    return Fernet(base64.urlsafe_b64encode(hashlib.sha256(f"site-logins:{secret_key}".encode()).digest()))


def encrypt(value: str) -> str:
    return _fernet(settings.secret_key).encrypt(value.encode()).decode()


def decrypt(token: str) -> str | None:
    """None if the token can't be read (e.g. SECRET_KEY changed since it was saved)."""
    try:
        return _fernet(settings.secret_key).decrypt(token.encode()).decode()
    except (InvalidToken, ValueError):
        log.warning("Could not decrypt a stored secret; was SECRET_KEY changed?")
        return None
