"""Work visibility: public (shared library) vs private (importer only)."""

from __future__ import annotations

import re
from collections import Counter

from sqlalchemy import and_, cast, exists, false, func, or_, select
from sqlalchemy.orm import Session
from sqlalchemy.sql import ColumnElement

from app.models import ImportJob, User, Work

# Collapse "A / B" and "A\B" from different sites into one relationship key.
_SLASH_RE = re.compile(r"\s*[\\/]\s*")
_AMP_RE = re.compile(r"\s*&\s*")
_SPACE_RE = re.compile(r"\s+")

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


def normalize_label_key(name: str) -> str:
    """Canonical key so site casing/spacing variants collapse together."""
    s = (name or "").strip().casefold()
    s = _SLASH_RE.sub("/", s)
    s = _AMP_RE.sub(" & ", s)
    return _SPACE_RE.sub(" ", s).strip()


def _json_list_contains_any(column, values: list[str], *, alias: str) -> ColumnElement[bool]:
    """True when a JSON string-array column includes any of ``values`` exactly."""
    cleaned = list(dict.fromkeys(v for v in values if v))
    if not cleaned:
        return false()
    from app.db import engine

    if engine.dialect.name == "postgresql":
        from sqlalchemy import String
        from sqlalchemy.dialects.postgresql import ARRAY, JSONB

        if len(cleaned) == 1:
            return cast(column, JSONB).contains(cleaned)
        return cast(column, JSONB).op("?|")(cast(cleaned, ARRAY(String)))
    elements = func.json_each(column).table_valued("value").alias(alias)
    return exists(select(1).select_from(elements).where(elements.c.value.in_(cleaned)))


def _label_counts(db: Session, user: User, column) -> Counter[str]:
    counts: Counter[str] = Counter()
    rows = db.scalars(select(column).where(visible_works_clause(user))).all()
    for row in rows:
        for name in row or []:
            if isinstance(name, str) and name.strip():
                counts[name.strip()] += 1
    return counts


def _canonical_labels(counts: Counter[str]) -> list[str]:
    """One display label per normalized key (most common spelling wins)."""
    groups: dict[str, list[str]] = {}
    for label in counts:
        groups.setdefault(normalize_label_key(label), []).append(label)
    chosen: list[str] = []
    for variants in groups.values():
        chosen.append(
            max(
                variants,
                key=lambda lab: (counts[lab], sum(ch.isupper() for ch in lab), -len(lab), lab),
            )
        )
    return sorted(chosen, key=str.casefold)


def _aliases_for(counts: Counter[str], selected: str) -> list[str]:
    """All stored spellings that match ``selected`` after normalization."""
    key = normalize_label_key(selected)
    if not key:
        return []
    aliases = [lab for lab in counts if normalize_label_key(lab) == key]
    if selected.strip() and selected.strip() not in aliases:
        aliases.append(selected.strip())
    return aliases


def library_label_index(db: Session, user: User) -> dict[str, Counter[str]]:
    """Count raw fandom/tag/relationship spellings once for filters + dropdowns."""
    return {
        "fandoms": _label_counts(db, user, Work.fandoms),
        "tags": _label_counts(db, user, Work.tags),
        "relationships": _label_counts(db, user, Work.relationships),
    }


def fandoms_contain(category: str, counts: Counter[str]) -> ColumnElement[bool]:
    """Match category including case/spacing variants from other sites."""
    return _json_list_contains_any(
        Work.fandoms,
        _aliases_for(counts, category),
        alias="fandom_el",
    )


def tags_contain(tag: str, counts: Counter[str]) -> ColumnElement[bool]:
    """Match tag including case/spacing variants from other sites."""
    return _json_list_contains_any(
        Work.tags,
        _aliases_for(counts, tag),
        alias="tag_el",
    )


def relationships_contain(relationship: str, counts: Counter[str]) -> ColumnElement[bool]:
    """Match relationship including case/spacing/slash variants from other sites."""
    return _json_list_contains_any(
        Work.relationships,
        _aliases_for(counts, relationship),
        alias="relationship_el",
    )


def distinct_categories(db: Session, user: User, counts: Counter[str] | None = None) -> list[str]:
    """Sorted fandom/category labels with cross-site duplicates collapsed."""
    return _canonical_labels(counts if counts is not None else _label_counts(db, user, Work.fandoms))


def distinct_tags(db: Session, user: User, counts: Counter[str] | None = None) -> list[str]:
    """Sorted tags with cross-site duplicates collapsed."""
    return _canonical_labels(counts if counts is not None else _label_counts(db, user, Work.tags))


def distinct_relationships(db: Session, user: User, counts: Counter[str] | None = None) -> list[str]:
    """Sorted relationships with cross-site duplicates collapsed."""
    return _canonical_labels(
        counts if counts is not None else _label_counts(db, user, Work.relationships)
    )
