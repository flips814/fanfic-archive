from datetime import datetime, timedelta, timezone

from app import settings_store
from app.db import SessionLocal
from app.healthcheck import worker_healthy
from app.models import ImportJob


def _heartbeat(age: timedelta | None) -> None:
    with SessionLocal() as db:
        if age is None:
            settings_store.delete_state(db, settings_store.WORKER_HEARTBEAT)
        else:
            settings_store.set_state(db, settings_store.WORKER_HEARTBEAT,
                                     (datetime.now(timezone.utc) - age).isoformat())
        db.commit()


def test_worker_health_follows_heartbeat():
    _heartbeat(None)
    assert not worker_healthy(), "never reported in"
    _heartbeat(timedelta(seconds=30))
    assert worker_healthy()
    _heartbeat(timedelta(minutes=10))
    assert not worker_healthy(), "stale heartbeat"


def test_long_running_job_counts_as_alive():
    _heartbeat(timedelta(minutes=30))
    with SessionLocal() as db:
        db.add(ImportJob(kind="import", url="https://example.com", status="running"))
        db.commit()
    assert worker_healthy()
