"""Admin-editable settings persisted in the database, plus first-launch bootstrapping."""

import json
import logging
import re
import secrets
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import FIELD_MAP, FIELDS, Field, settings
from app.crypto import decrypt, encrypt
from app.models import AppSetting, User

log = logging.getLogger(__name__)

# internal (non-field) keys
SECRET_KEY = "_secret_key"
SETUP_COMPLETE = "_setup_complete"
SETUP_CODE = "_setup_code"
WORKER_HEARTBEAT = "_worker_heartbeat"

_UNSET_SECRETS = {"", "change-me"}


class SettingsError(ValueError):
    pass


# --------------------------------------------------------------- raw state

def get_state(db: Session, key: str, default=None):
    row = db.get(AppSetting, key)
    return json.loads(row.value) if row else default


def set_state(db: Session, key: str, value, user: User | None = None) -> None:
    row = db.get(AppSetting, key)
    if row is None:
        row = AppSetting(key=key)
        db.add(row)
    row.value = json.dumps(value)
    row.updated_by_id = user.id if user else None


def delete_state(db: Session, key: str) -> None:
    row = db.get(AppSetting, key)
    if row:
        db.delete(row)


# --------------------------------------------------------------- settings

def load(db: Session) -> None:
    """Apply database overrides to the shared settings object (call in every process)."""
    overrides = {}
    for row in db.scalars(select(AppSetting).where(AppSetting.key.in_(list(FIELD_MAP)))):
        field = FIELD_MAP[row.key]
        value = json.loads(row.value)
        if field.kind == "secret":
            value = decrypt(value) if value else ""
            if value is None:
                continue
        overrides[row.key] = value
    settings.apply(overrides)


def overridden_keys(db: Session) -> set[str]:
    return set(db.scalars(select(AppSetting.key).where(AppSetting.key.in_(list(FIELD_MAP)))))


def save(db: Session, values: dict, user: User | None) -> None:
    for key, value in values.items():
        field = FIELD_MAP[key]
        set_state(db, key, encrypt(value) if field.kind == "secret" and value else value, user)
    db.flush()
    load(db)


def reset(db: Session, keys: list[str]) -> None:
    for key in keys:
        delete_state(db, key)
    db.flush()
    load(db)


def parse_form(section: str, form) -> dict:
    """Validate a section's submitted form; secrets left blank keep their current value."""
    values: dict = {}
    errors: list[str] = []
    for field in (f for f in FIELDS if f.section == section):
        raw = form.get(field.key)
        try:
            if field.kind == "bool":
                values[field.key] = raw is not None
            elif field.kind == "secret":
                if form.get(f"{field.key}__clear"):
                    values[field.key] = ""
                elif raw and raw.strip():
                    values[field.key] = raw.strip()
            elif raw is None:
                continue
            else:
                values[field.key] = _parse(field, raw)
        except SettingsError as e:
            errors.append(f"{field.label}: {e}")
    if errors:
        raise SettingsError(" ".join(errors))
    return values


def _parse(field: Field, raw: str):
    raw = raw.strip()
    if field.kind == "int":
        if not raw:
            raise SettingsError("enter a number.")
        if not raw.isdigit():
            raise SettingsError("must be a whole number of 0 or more.")
        return int(raw)
    if field.kind == "list":
        items = [i.strip() for i in re.split(r"[\n,]+", raw) if i.strip()]
        return [i.lower() for i in items] if field.key == "admin_emails" else items
    if field.kind == "url":
        if raw and not re.match(r"^https?://[^\s/]+", raw):
            raise SettingsError("must start with http:// or https://")
        return raw.rstrip("/") if field.key in ("base_url", "oidc_issuer") else raw
    return raw if field.kind == "text" else raw.strip()


# --------------------------------------------------------------- bootstrap

def ensure_secret_key(db: Session) -> None:
    """Use SECRET_KEY from the environment, or generate one on first launch and keep it."""
    if settings.secret_key not in _UNSET_SECRETS:
        return
    stored = get_state(db, SECRET_KEY)
    if not stored:
        stored = secrets.token_urlsafe(48)
        set_state(db, SECRET_KEY, stored)
        db.commit()
        log.info("Generated a SECRET_KEY and stored it in the database")
    settings.secret_key = stored


def admin_exists(db: Session) -> bool:
    return db.scalar(select(User.id).where(User.is_admin.is_(True), User.disabled.is_(False)).limit(1)) is not None


def setup_needed(db: Session) -> bool:
    return not get_state(db, SETUP_COMPLETE, False) and not admin_exists(db)


def setup_code(db: Session) -> str:
    code = get_state(db, SETUP_CODE)
    if not code:
        code = "-".join(secrets.token_hex(2).upper() for _ in range(3))
        set_state(db, SETUP_CODE, code)
        db.commit()
    return code


def complete_setup(db: Session, user: User | None) -> None:
    set_state(db, SETUP_COMPLETE, True, user)
    delete_state(db, SETUP_CODE)


def heartbeat(db: Session) -> None:
    set_state(db, WORKER_HEARTBEAT, datetime.now(timezone.utc).isoformat())


def last_heartbeat(db: Session) -> datetime | None:
    value = get_state(db, WORKER_HEARTBEAT)
    return datetime.fromisoformat(value) if value else None
