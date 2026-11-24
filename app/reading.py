"""Helpers for continue-reading / caught-up state."""

from __future__ import annotations

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models import Chapter, ReadingProgress, Work


def is_work_complete(work: Work | None) -> bool:
    status = (work.status or "").strip().lower() if work else ""
    return status.startswith("complete")


def catch_up_label(work: Work | None) -> str:
    """User-facing label once the reader reaches the latest chapter."""
    return "Finished" if is_work_complete(work) else "Caught up"


def catch_up_message(work: Work | None) -> str:
    if is_work_complete(work):
        return "You've finished this work."
    return "You're all caught up — check back when a new chapter lands."


def last_chapter_number(db: Session, work_id: int) -> int | None:
    return db.scalar(select(func.max(Chapter.number)).where(Chapter.work_id == work_id))


def last_chapter_map(db: Session, work_ids: list[int]) -> dict[int, int]:
    if not work_ids:
        return {}
    rows = db.execute(
        select(Chapter.work_id, func.max(Chapter.number))
        .where(Chapter.work_id.in_(work_ids))
        .group_by(Chapter.work_id)
    ).all()
    return {int(work_id): int(number) for work_id, number in rows if number is not None}


def is_caught_up(chapter_number: int, last_chapter: int | None) -> bool:
    return last_chapter is not None and chapter_number >= last_chapter


def progress_is_caught_up(progress: ReadingProgress, last_chapter: int | None) -> bool:
    return is_caught_up(progress.chapter_number, last_chapter)
