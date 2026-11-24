"""Work visibility: public (shared library) vs private (importer only)."""

from __future__ import annotations

from sqlalchemy import and_, cast, exists, func, or_, select
from sqlalchemy.orm import Session
from sqlalchemy.sql import ColumnElement

from app.models import ImportJob, User, Work

VISIBILITY_PUBLIC = "public"
VISIBILITY_PRIVATE = "private"
VISIBILITIES = frozenset({VISIBILITY_PUBLIC, VISIBILITY_PRIVATE})


def normalize_visibility(value: str | None) -> str:
    raw = (value or VISIBILITY_PUBLIC).strip().lower()
    return raw if raw in VISIBILITIES else VISIBILITY_PUBLIC


def can_view(user: User | None, work: Work | None) -> bool:
    if work is None or user is None:
        return False
    if (work.visibility or VISIBILITY_PUBLIC) != VISIBILITY_PRIVATE:
        return True
    return work.added_by_id == user.id


def visible_works_clause(user: User) -> ColumnElement[bool]:
    """SQL filter: public works, or private works owned by ``user``."""
    return or_(
        Work.visibility != VISIBILITY_PRIVATE,
        Work.visibility.is_(None),
        Work.added_by_id == user.id,
    )


def visibility_filter_clause(visibility: str | None) -> ColumnElement[bool] | None:
    """Optional library filter: ``public``, ``private``, or None/empty for all visible works."""
    value = (visibility or "").strip().lower()
    if value == VISIBILITY_PRIVATE:
        return Work.visibility == VISIBILITY_PRIVATE
    if value == VISIBILITY_PUBLIC:
        return or_(Work.visibility != VISIBILITY_PRIVATE, Work.visibility.is_(None))
    return None


def visible_jobs_clause(user: User) -> ColumnElement[bool]:
    """SQL filter for activity rows. Requires ImportJob outerjoined to Work.

    Private imports/updates are visible only to the importer (work owner, or
    the requester while the job has no work yet).
    """
    return or_(
        and_(ImportJob.work_id.is_not(None), visible_works_clause(user)),
        and_(
            ImportJob.work_id.is_(None),
            or_(
                ImportJob.visibility != VISIBILITY_PRIVATE,
                ImportJob.visibility.is_(None),
                ImportJob.requested_by_id == user.id,
            ),
        ),
    )


def can_view_job(user: User, job) -> bool:
    """Whether an activity/job row should be shown to ``user``."""
    work = getattr(job, "work", None)
    if work is not None:
        return can_view(user, work)
    if normalize_visibility(getattr(job, "visibility", None)) == VISIBILITY_PRIVATE:
        return job.requested_by_id == user.id
    return True


def prune_followers_for_privacy(work: Work) -> None:
    """When a work is private, only the importer may remain a follower."""
    if not work.is_private:
        return
    for follow in list(work.followers):
        if follow.user_id != work.added_by_id:
            work.followers.remove(follow)


def _json_list_contains(column, value: str, *, alias: str) -> ColumnElement[bool]:
    """True when a JSON string-array column includes ``value`` exactly."""
    from app.db import engine

    if engine.dialect.name == "postgresql":
        from sqlalchemy.dialects.postgresql import JSONB

        return cast(column, JSONB).contains([value])
    elements = func.json_each(column).table_valued("value").alias(alias)
    return exists(select(1).select_from(elements).where(elements.c.value == value))


def fandoms_contain(category: str) -> ColumnElement[bool]:
    """True when ``Work.fandoms`` (site category / fandom list) includes ``category`` exactly."""
    return _json_list_contains(Work.fandoms, category, alias="fandom_el")


def tags_contain(tag: str) -> ColumnElement[bool]:
    """True when ``Work.tags`` includes ``tag`` exactly."""
    return _json_list_contains(Work.tags, tag, alias="tag_el")


def relationships_contain(relationship: str) -> ColumnElement[bool]:
    """True when ``Work.relationships`` includes ``relationship`` exactly."""
    return _json_list_contains(Work.relationships, relationship, alias="relationship_el")


def _distinct_json_labels(db: Session, user: User, column) -> list[str]:
    rows = db.scalars(select(column).where(visible_works_clause(user))).all()
    names = {
        name.strip()
        for row in rows
        for name in (row or [])
        if isinstance(name, str) and name.strip()
    }
    return sorted(names, key=str.casefold)


def distinct_categories(db: Session, user: User) -> list[str]:
    """Sorted unique fandom/category labels from works ``user`` can see."""
    return _distinct_json_labels(db, user, Work.fandoms)


def distinct_tags(db: Session, user: User) -> list[str]:
    """Sorted unique tags from works ``user`` can see."""
    return _distinct_json_labels(db, user, Work.tags)


def distinct_relationships(db: Session, user: User) -> list[str]:
    """Sorted unique relationship labels from works ``user`` can see."""
    return _distinct_json_labels(db, user, Work.relationships)
