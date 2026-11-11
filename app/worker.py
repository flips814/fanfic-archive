"""Background worker: runs queued imports and periodically checks works for new chapters."""

import logging
import signal
import time
from datetime import timedelta

from app import settings_store
from app.config import settings
from app.db import SessionLocal, init_db
from app.jobs import claim_next_job, requeue_stale_jobs, run_job, schedule_due_updates

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("worker")

SCHEDULE_EVERY_SECONDS = 60

_running = True


def _stop(*_):
    global _running
    log.info("Shutting down after current job…")
    _running = False


def main() -> None:
    signal.signal(signal.SIGTERM, _stop)
    signal.signal(signal.SIGINT, _stop)
    init_db()
    with SessionLocal() as db:
        settings_store.ensure_secret_key(db)
        settings_store.load(db)
        # single-worker deployment: anything still "running" was cut off by a restart
        requeue_stale_jobs(db, older_than=timedelta(0))
    log.info(
        "Worker started (update every %s min, completed works every %s min)",
        settings.update_interval_minutes,
        settings.completed_update_interval_minutes,
    )

    last_schedule = 0.0
    while _running:
        if time.monotonic() - last_schedule > SCHEDULE_EVERY_SECONDS:
            with SessionLocal() as db:
                settings_store.load(db)  # pick up changes admins made in the web UI
                settings_store.heartbeat(db)
                count = schedule_due_updates(db)
                db.commit()
            if count:
                log.info("Queued %s update check(s)", count)
            last_schedule = time.monotonic()

        with SessionLocal() as db:
            job_id = claim_next_job(db)
        if job_id is None:
            time.sleep(settings.worker_poll_seconds)
            continue

        log.info("Running job %s", job_id)
        run_job(job_id)
        time.sleep(settings.delay_between_works_seconds)


if __name__ == "__main__":
    main()
