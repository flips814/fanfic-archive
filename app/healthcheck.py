"""Container health checks, e.g. `python -m app.healthcheck worker` (exit 0 = healthy)."""

import sys
from datetime import datetime, timedelta, timezone

from sqlalchemy import func, select

from app import settings_store
from app.db import SessionLocal
from app.models import ImportJob

HEARTBEAT_MAX_AGE = timedelta(minutes=5)


def worker_healthy() -> bool:
    with SessionLocal() as db:
        # a long import holds the loop, so a running job also counts as alive
        if db.scalar(select(func.count(ImportJob.id)).where(ImportJob.status == "running")):
            return True
        heartbeat = settings_store.last_heartbeat(db)
        return heartbeat is not None and datetime.now(timezone.utc) - heartbeat < HEARTBEAT_MAX_AGE


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "worker"
    if target != "worker":
        sys.exit(f"unknown health check: {target}")
    try:
        sys.exit(0 if worker_healthy() else 1)
    except Exception as e:  # database unreachable etc.
        print(f"unhealthy: {e}", file=sys.stderr)
        sys.exit(1)
