import logging
from datetime import timedelta

from sqlalchemy import and_, not_, or_, select
from sqlalchemy.orm import Session

from app import notify
from app.config import settings
from app.db import session_scope
from app.importers import ImportError_, WorkMeta, open_work, site_label
from app.logins import logins_for_job
from app.models import Chapter, Follow, ImportJob, User, Work, utcnow
from app.sanitize import clean_html, count_words

log = logging.getLogger(__name__)

PENDING = ("queued", "running")


# ---------------------------------------------------------------- enqueueing

def enqueue_import(db: Session, url: str, user: User | None) -> ImportJob:
    existing = db.scalar(select(Work).where(Work.source_url == url.strip()))
    if existing:
        if user:
            follow(db, user, existing)
        job = enqueue_update(db, existing, user)
        if job:
            return job
    job = ImportJob(kind="import", url=url.strip(), requested_by_id=user.id if user else None)
    db.add(job)
    db.flush()
    return job


def enqueue_update(db: Session, work: Work, user: User | None = None) -> ImportJob | None:
    pending = db.scalar(
        select(ImportJob).where(ImportJob.work_id == work.id, ImportJob.status.in_(PENDING))
    )
    if pending:
        return pending if user else None
    job = ImportJob(
        kind="update",
        url=work.source_url,
        work_id=work.id,
        work_title=work.title,
        requested_by_id=user.id if user else None,
    )
    db.add(job)
    db.flush()
    return job


def follow(db: Session, user: User, work: Work) -> None:
    if not db.get(Follow, (user.id, work.id)):
        db.add(Follow(user_id=user.id, work_id=work.id))
        db.flush()


def schedule_due_updates(db: Session) -> int:
    now = utcnow()
    pending_ids = select(ImportJob.work_id).where(
        ImportJob.status.in_(PENDING), ImportJob.work_id.is_not(None)
    )
    is_complete = Work.status.ilike("complete%")
    due_ongoing = and_(
        or_(Work.status.is_(None), not_(is_complete)),
        Work.last_checked_at < now - timedelta(minutes=settings.update_interval_minutes),
    )
    conditions = [Work.last_checked_at.is_(None), due_ongoing]
    if settings.completed_update_interval_minutes > 0:
        conditions.append(
            and_(
                is_complete,
                Work.last_checked_at
                < now - timedelta(minutes=settings.completed_update_interval_minutes),
            )
        )
    works = db.scalars(
        select(Work)
        .where(Work.auto_update.is_(True), Work.id.not_in(pending_ids), or_(*conditions))
        .order_by(Work.last_checked_at.asc().nulls_first())
    ).all()
    for work in works:
        enqueue_update(db, work)
    return len(works)


def claim_next_job(db: Session) -> int | None:
    job = db.scalar(
        select(ImportJob)
        .where(ImportJob.status == "queued")
        # user-requested jobs before scheduled update checks
        .order_by(ImportJob.requested_by_id.is_(None), ImportJob.created_at)
        .limit(1)
        .with_for_update(skip_locked=True)
    )
    if not job:
        db.rollback()
        return None
    job.status = "running"
    job.started_at = utcnow()
    db.commit()
    return job.id


def requeue_stale_jobs(db: Session, older_than: timedelta = timedelta(hours=1)) -> None:
    for job in db.scalars(
        select(ImportJob).where(
            ImportJob.status == "running", ImportJob.started_at < utcnow() - older_than
        )
    ):
        job.status = "queued"
    db.commit()


# ------------------------------------------------------------------ running

def _apply_meta(work: Work, meta: WorkMeta) -> None:
    work.site = meta.site
    work.source_url = meta.url
    work.source_id = meta.source_id
    work.title = meta.title
    work.author = meta.author
    work.author_url = meta.author_url
    work.summary_html = clean_html(meta.summary_html)
    work.rating = meta.rating
    work.status = meta.status
    work.language = meta.language
    work.word_count = meta.word_count
    work.fandoms = meta.fandoms
    work.relationships = meta.relationships
    work.characters = meta.characters
    work.tags = meta.tags
    work.warnings = meta.warnings
    work.published_at = meta.published_at
    work.source_updated_at = meta.updated_at


def run_job(job_id: int) -> None:
    with session_scope() as db:
        job = db.get(ImportJob, job_id)
        url, kind, requester_id = job.url, job.kind, job.requested_by_id
        work_id = job.work_id

    try:
        with session_scope() as db:
            logins = logins_for_job(db, requester_id, work_id)
        source = open_work(url, logins)
        meta = source.meta
        created = False

        with session_scope() as db:
            work = db.get(Work, work_id) if work_id else None
            work = work or db.scalar(select(Work).where(Work.source_url == meta.url))
            if work is None:
                work = Work(added_by_id=requester_id, title=meta.title, site=meta.site, source_url=meta.url)
                db.add(work)
                created = True
            _apply_meta(work, meta)
            db.flush()
            work_id = work.id
            if requester_id:
                follow(db, db.get(User, requester_id), work)
            job = db.get(ImportJob, job_id)
            job.work_id, job.work_title = work.id, work.title
            existing = {c.source_key: c.id for c in work.chapters}

        new_chapters: list[tuple[int, str]] = []
        source_keys = {ref.key for ref in meta.chapters}
        for index, ref in enumerate(meta.chapters):
            with session_scope() as db:
                if ref.key in existing:
                    chapter = db.get(Chapter, existing[ref.key])
                    chapter.number, chapter.title, chapter.removed_from_source = index + 1, ref.title, False
                    continue
            html = clean_html(source.fetch_chapter(index, ref))
            with session_scope() as db:
                db.add(
                    Chapter(
                        work_id=work_id,
                        number=index + 1,
                        title=ref.title,
                        source_key=ref.key,
                        source_url=ref.url,
                        content_html=html,
                        word_count=count_words(html),
                        imported_by_id=requester_id,
                        job_id=job_id,
                    )
                )
                db.get(ImportJob, job_id).chapters_added = len(new_chapters) + 1
            new_chapters.append((index + 1, ref.title))

        with session_scope() as db:
            work = db.get(Work, work_id)
            for chapter in work.chapters:
                if chapter.source_key not in source_keys:
                    chapter.removed_from_source = True
            now = utcnow()
            work.last_checked_at = now
            work.last_error = None
            if new_chapters:
                work.last_new_chapter_at = now
            if not work.word_count:
                work.word_count = sum(c.word_count for c in work.chapters)
            job = db.get(ImportJob, job_id)
            job.status = "done"
            job.finished_at = now
            job.chapters_added = len(new_chapters)
            if created:
                job.message = f"Imported {len(new_chapters)} chapter(s)"
            elif kind == "import":
                job.message = f"Already in library; {len(new_chapters)} new chapter(s)"
            else:
                job.message = f"{len(new_chapters)} new chapter(s)" if new_chapters else "No new chapters"

        _send_notifications(job_id, created, new_chapters)

    except Exception as exc:
        friendly = str(exc) if isinstance(exc, ImportError_) else f"{type(exc).__name__}: {exc}"
        if not isinstance(exc, ImportError_):
            log.exception("Job %s failed", job_id)
        else:
            log.warning("Job %s failed: %s", job_id, friendly)
        with session_scope() as db:
            job = db.get(ImportJob, job_id)
            job.status = "failed"
            job.finished_at = utcnow()
            job.message = friendly[:2000]
            if job.work_id:
                work = db.get(Work, job.work_id)
                if work:
                    work.last_checked_at = utcnow()
                    work.last_error = friendly[:2000]


def _send_notifications(job_id: int, created: bool, new_chapters: list[tuple[int, str]]) -> None:
    with session_scope() as db:
        job = db.get(ImportJob, job_id)
        work = job.work
        who = job.requested_by.label if job.requested_by else "auto-updater"
        link = f"{settings.base_url}/works/{work.id}"
        byline = f"**{work.title}** by {work.author or 'unknown'} ({site_label(work.site)})"

        if created:
            notify.notify_global(
                f"New work imported: {work.title}",
                f"{who} imported {byline} — {len(new_chapters)} chapter(s).\n\n{link}",
            )
            return
        if not new_chapters:
            return

        listing = "\n".join(f"- Chapter {n}: {title}" for n, title in new_chapters[:20])
        if len(new_chapters) > 20:
            listing += f"\n- …and {len(new_chapters) - 20} more"
        title = f"{work.title}: {len(new_chapters)} new chapter(s)"
        body = f"{byline} was updated.\n\n{listing}\n\nRead: {link}\nSource: {work.source_url}"

        for f in work.followers:
            notify.notify_user(f.user, title, body)
        notify.notify_global(title, f"{body}\n\n(found by {who})")
