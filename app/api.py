from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app import comments, translate
from app.auth import (
    authenticate_local,
    current_user,
    establish_session,
    local_login_available,
)
from app.config import settings
from app.db import get_db
from app.importers import is_supported
from app.jobs import enqueue_import, enqueue_update, follow
from app.models import Chapter, Comment, Follow, ImportJob, User, Work, utcnow

router = APIRouter(prefix="/api")

API_VERSION = 1


class ImportRequest(BaseModel):
    urls: list[str]


class LoginRequest(BaseModel):
    username: str = ""
    password: str = ""


class CommentCreate(BaseModel):
    body: str = ""
    chapter_id: int | None = None
    parent_id: int | None = None
    spoiler: bool = False


class CommentEdit(BaseModel):
    body: str = ""
    spoiler: bool | None = None


class SettingsUpdate(BaseModel):
    notify_email: bool | None = None
    notify_email_address: str | None = None
    discord_webhook_url: str | None = None
    apprise_urls: str | None = None


def _user(u: User | None) -> dict | None:
    return {"id": u.id, "name": u.label} if u else None


def _me(u: User) -> dict:
    return {**_user(u), "email": u.email, "is_admin": u.is_admin}


def _chapter_summary(c: Chapter, comment_count: int | None = None) -> dict:
    data = {
        "id": c.id,
        "number": c.number,
        "title": c.title,
        "word_count": c.word_count,
        "imported_at": c.imported_at,
        "imported_by": _user(c.imported_by),
        "removed_from_source": c.removed_from_source,
    }
    if comment_count is not None:
        data["comment_count"] = comment_count
    return data


def _work(
    w: Work,
    full: bool = False,
    is_following: bool | None = None,
    can_manage: bool | None = None,
    chapter_comment_counts: dict[int, int] | None = None,
    comment_count: int | None = None,
) -> dict:
    data = {
        "id": w.id,
        "title": w.title,
        "author": w.author,
        "author_url": w.author_url if full else None,
        "site": w.site,
        "source_url": w.source_url,
        "status": w.status,
        "rating": w.rating,
        "language": w.language if full else None,
        "language_code": translate.lang_code(w.language) or None if full else None,
        "word_count": w.word_count,
        "chapters": len(w.chapters),
        "added_by": _user(w.added_by),
        "created_at": w.created_at,
        "published_at": w.published_at if full else None,
        "source_updated_at": w.source_updated_at if full else None,
        "last_checked_at": w.last_checked_at,
        "last_new_chapter_at": w.last_new_chapter_at,
        "auto_update": w.auto_update,
        "last_error": w.last_error,
        "fandoms": w.fandoms or [],
    }
    if is_following is not None:
        data["is_following"] = is_following
    if can_manage is not None:
        data["can_manage"] = can_manage
    if comment_count is not None:
        data["comment_count"] = comment_count
    if full:
        counts = chapter_comment_counts or {}
        data.update(
            relationships=w.relationships or [],
            characters=w.characters or [],
            tags=w.tags or [],
            warnings=w.warnings or [],
            summary_html=w.summary_html,
            chapter_list=[
                _chapter_summary(c, comment_count=counts.get(c.id, 0)) for c in w.chapters
            ],
        )
    else:
        # drop full-only nulls for list payloads
        data.pop("author_url", None)
        data.pop("language", None)
        data.pop("language_code", None)
        data.pop("published_at", None)
        data.pop("source_updated_at", None)
    return data


def _job(j: ImportJob) -> dict:
    return {
        "id": j.id,
        "kind": j.kind,
        "url": j.url,
        "work_id": j.work_id,
        "work_title": j.work_title,
        "requested_by": _user(j.requested_by),
        "status": j.status,
        "message": j.message,
        "chapters_added": j.chapters_added,
        "created_at": j.created_at,
        "finished_at": j.finished_at,
    }


def _comment(c: Comment, viewer: User, include_replies: bool = True) -> dict:
    replies = []
    if include_replies and c.parent_id is None:
        replies = [_comment(r, viewer, include_replies=False) for r in getattr(c, "replies", [])]
    return {
        "id": c.id,
        "work_id": c.work_id,
        "chapter_id": c.chapter_id,
        "parent_id": c.parent_id,
        "body": None if c.deleted else c.body,
        "spoiler": c.spoiler,
        "deleted": c.deleted,
        "created_at": c.created_at,
        "edited_at": c.edited_at,
        "user": _user(c.user),
        "can_edit": (not c.deleted) and c.user_id == viewer.id,
        "can_delete": (not c.deleted) and (c.user_id == viewer.id or viewer.is_admin),
        "replies": replies,
    }


def _get_work(db: Session, work_id: int) -> Work:
    work = db.get(Work, work_id)
    if not work:
        raise HTTPException(404, "Not Found")
    return work


# ------------------------------------------------------------------ public / auth


@router.get("/info")
def info(db: Session = Depends(get_db)):
    """Public capability probe for mobile clients (no login required)."""
    return {
        "site_name": settings.site_name,
        "api_version": API_VERSION,
        "auth": {
            "local": local_login_available(db),
            "oidc": settings.oidc_enabled,
            "oidc_provider_name": settings.oidc_provider_name if settings.oidc_enabled else None,
        },
        "features": {
            "translation": bool(settings.translate_url),
            "comments": True,
            "epub": True,
        },
    }


@router.post("/login")
def api_login(body: LoginRequest, request: Request, db: Session = Depends(get_db)):
    if not local_login_available(db):
        raise HTTPException(400, "Local username/password sign-in is not available on this server.")
    user = authenticate_local(request, body.username, body.password, db)
    db.commit()
    establish_session(request, user)
    return _me(user)


@router.post("/logout")
def api_logout(request: Request):
    request.session.clear()
    return {"ok": True}


@router.get("/me")
def me(user: User = Depends(current_user)):
    return _me(user)


# ------------------------------------------------------------------ works


@router.get("/works")
def list_works(
    q: str = "",
    site: str = "",
    status: str = "",
    mine: str = "",
    followed: str = "",
    added_by: int | None = None,
    limit: int = 100,
    offset: int = 0,
    db: Session = Depends(get_db),
    user: User = Depends(current_user),
):
    limit = min(max(limit, 1), 500)
    offset = max(offset, 0)
    query = select(Work)
    if q:
        like = f"%{q}%"
        query = query.where(or_(Work.title.ilike(like), Work.author.ilike(like)))
    if site:
        query = query.where(Work.site == site)
    if status == "complete":
        query = query.where(Work.status.ilike("complete%"))
    elif status == "ongoing":
        query = query.where(or_(Work.status.is_(None), ~Work.status.ilike("complete%")))
    if mine:
        query = query.where(Work.added_by_id == user.id)
    if added_by is not None:
        query = query.where(Work.added_by_id == added_by)
    if followed:
        query = query.join(Follow, (Follow.work_id == Work.id) & (Follow.user_id == user.id))

    total = db.scalar(select(func.count()).select_from(query.subquery())) or 0
    works = db.scalars(
        query.order_by(func.coalesce(Work.last_new_chapter_at, Work.created_at).desc())
        .offset(offset)
        .limit(limit)
    ).all()
    followed_ids = set(db.scalars(select(Follow.work_id).where(Follow.user_id == user.id)))
    sites = db.scalars(select(Work.site).distinct().order_by(Work.site)).all()
    return {
        "works": [_work(w, is_following=w.id in followed_ids) for w in works],
        "total": total,
        "limit": limit,
        "offset": offset,
        "sites": list(sites),
    }


@router.get("/works/{work_id}")
def get_work(work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    work = _get_work(db, work_id)
    work_comments = comments.thread(db, work.id, None)
    return _work(
        work,
        full=True,
        is_following=db.get(Follow, (user.id, work.id)) is not None,
        can_manage=user.is_admin or work.added_by_id == user.id,
        chapter_comment_counts=comments.chapter_counts(db, work.id),
        comment_count=comments.visible_count(work_comments),
    )


@router.get("/works/{work_id}/chapters/{number}")
def get_chapter(
    work_id: int, number: int, db: Session = Depends(get_db), user: User = Depends(current_user)
):
    work = _get_work(db, work_id)
    chapters = work.chapters
    index = next((i for i, c in enumerate(chapters) if c.number == number), None)
    if index is None:
        raise HTTPException(404, "Chapter not found")
    chapter = chapters[index]
    chapter_comments = comments.thread(db, work.id, chapter.id)
    return {
        **_chapter_summary(chapter),
        "work_id": work.id,
        "work_title": work.title,
        "work_chapters": len(chapters),
        "work_language": work.language,
        "work_language_code": translate.lang_code(work.language) or None,
        "source_url": chapter.source_url,
        "content_html": chapter.content_html,
        "prev_number": chapters[index - 1].number if index > 0 else None,
        "next_number": chapters[index + 1].number if index + 1 < len(chapters) else None,
        "comment_count": comments.visible_count(chapter_comments),
    }


@router.post("/works/{work_id}/follow")
def follow_work(work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    work = _get_work(db, work_id)
    follow(db, user, work)
    db.commit()
    return {"ok": True, "is_following": True}


@router.post("/works/{work_id}/unfollow")
def unfollow_work(work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    existing = db.get(Follow, (user.id, work_id))
    if existing:
        db.delete(existing)
        db.commit()
    return {"ok": True, "is_following": False}


@router.post("/works/{work_id}/check")
def check_work(work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    work = _get_work(db, work_id)
    job = enqueue_update(db, work, user)
    db.commit()
    return _job(job)


@router.post("/works/{work_id}/auto-update")
def toggle_auto_update(work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    work = _get_work(db, work_id)
    if not (user.is_admin or work.added_by_id == user.id):
        raise HTTPException(403, "Only the importer or an admin can change auto-update.")
    work.auto_update = not work.auto_update
    db.commit()
    return {"ok": True, "auto_update": work.auto_update}


@router.delete("/works/{work_id}")
def delete_work(work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    work = _get_work(db, work_id)
    if not (user.is_admin or work.added_by_id == user.id):
        raise HTTPException(403, "Only the importer or an admin can delete this work.")
    title = work.title
    db.delete(work)
    db.commit()
    return {"ok": True, "title": title}


@router.post("/imports")
def create_imports(body: ImportRequest, db: Session = Depends(get_db), user: User = Depends(current_user)):
    jobs, rejected = [], []
    for url in body.urls:
        if is_supported(url):
            jobs.append(enqueue_import(db, url, user))
        else:
            rejected.append(url)
    db.commit()
    return {"jobs": [_job(j) for j in jobs], "rejected": rejected}


# ------------------------------------------------------------------ activity / users


@router.get("/jobs")
def list_jobs(
    kind: str = "",
    status: str = "",
    user_id: int | None = None,
    limit: int = 50,
    offset: int = 0,
    db: Session = Depends(get_db),
    user: User = Depends(current_user),
):
    limit = min(max(limit, 1), 500)
    offset = max(offset, 0)
    query = select(ImportJob)
    if kind in ("import", "update"):
        query = query.where(ImportJob.kind == kind)
    if status in ("queued", "running", "done", "failed"):
        query = query.where(ImportJob.status == status)
    if user_id is not None:
        if user_id == 0:
            query = query.where(ImportJob.requested_by_id.is_(None))
        else:
            query = query.where(ImportJob.requested_by_id == user_id)
    total = db.scalar(select(func.count()).select_from(query.subquery())) or 0
    jobs = db.scalars(query.order_by(ImportJob.id.desc()).offset(offset).limit(limit)).all()
    return {"jobs": [_job(j) for j in jobs], "total": total, "limit": limit, "offset": offset}


@router.get("/users")
def list_users(db: Session = Depends(get_db), user: User = Depends(current_user)):
    works_added = (
        select(func.count(Work.id)).where(Work.added_by_id == User.id).correlate(User).scalar_subquery()
    )
    chapters_imported = (
        select(func.count(Chapter.id)).where(Chapter.imported_by_id == User.id).correlate(User).scalar_subquery()
    )
    following = (
        select(func.count()).select_from(Follow).where(Follow.user_id == User.id).correlate(User).scalar_subquery()
    )
    rows = db.execute(
        select(User, works_added, chapters_imported, following).order_by(User.created_at)
    ).all()
    auto_chapters = db.scalar(select(func.count(Chapter.id)).where(Chapter.imported_by_id.is_(None))) or 0
    return {
        "users": [
            {
                "id": u.id,
                "name": u.label,
                "email": u.email,
                "is_admin": u.is_admin,
                "works_imported": works_count,
                "chapters_imported": chapters_count,
                "following": following_count,
                "created_at": u.created_at,
                "last_login_at": u.last_login_at,
            }
            for u, works_count, chapters_count, following_count in rows
        ],
        "auto_updater_chapters": auto_chapters,
    }


# ------------------------------------------------------------------ comments


@router.get("/works/{work_id}/comments")
def list_comments(
    work_id: int,
    chapter_id: int | None = None,
    db: Session = Depends(get_db),
    user: User = Depends(current_user),
):
    _get_work(db, work_id)
    thread = comments.thread(db, work_id, chapter_id)
    return {
        "comments": [_comment(c, user) for c in thread],
        "count": comments.visible_count(thread),
    }


@router.post("/works/{work_id}/comments")
def create_comment(
    work_id: int,
    body: CommentCreate,
    db: Session = Depends(get_db),
    user: User = Depends(current_user),
):
    work = _get_work(db, work_id)
    try:
        text = comments.clean_body(body.body)
    except comments.CommentError as exc:
        raise HTTPException(400, str(exc)) from exc

    chapter_id = body.chapter_id
    parent = None
    if body.parent_id is not None:
        parent = db.get(Comment, body.parent_id)
        if parent is None or parent.work_id != work.id or parent.deleted:
            raise HTTPException(400, "Parent comment not found.")
        if parent.parent_id is not None:
            parent = db.get(Comment, parent.parent_id) or parent
        chapter_id = parent.chapter_id

    chapter = None
    if chapter_id is not None:
        chapter = db.get(Chapter, chapter_id)
        if chapter is None or chapter.work_id != work.id:
            raise HTTPException(400, "Chapter does not belong to this work.")

    comment = Comment(
        work_id=work.id,
        chapter_id=chapter.id if chapter else None,
        parent_id=parent.id if parent else None,
        user_id=user.id,
        body=text,
        spoiler=body.spoiler,
    )
    db.add(comment)
    db.commit()
    db.refresh(comment)
    # reload with relationships for response
    thread = comments.thread(db, work.id, comment.chapter_id)
    created = next((c for c in thread if c.id == comment.id), None)
    if created is None and parent:
        for top in thread:
            created = next((r for r in top.replies if r.id == comment.id), None)
            if created:
                break
    return _comment(created or comment, user)


@router.post("/comments/{comment_id}/edit")
def edit_comment(
    comment_id: int,
    body: CommentEdit,
    db: Session = Depends(get_db),
    user: User = Depends(current_user),
):
    comment = db.get(Comment, comment_id)
    if comment is None or comment.deleted:
        raise HTTPException(404, "Comment not found")
    if comment.user_id != user.id:
        raise HTTPException(403, "Only the author can edit this comment.")
    try:
        comment.body = comments.clean_body(body.body)
    except comments.CommentError as exc:
        raise HTTPException(400, str(exc)) from exc
    if body.spoiler is not None:
        comment.spoiler = body.spoiler
    comment.edited_at = utcnow()
    db.commit()
    db.refresh(comment)
    return _comment(comment, user)


@router.post("/comments/{comment_id}/delete")
def delete_comment(
    comment_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)
):
    comment = db.get(Comment, comment_id)
    if comment is None or comment.deleted:
        raise HTTPException(404, "Comment not found")
    if comment.user_id != user.id and not user.is_admin:
        raise HTTPException(403, "Only the author or an admin can delete this comment.")
    if comment.replies:
        comment.deleted = True
        comment.body = ""
    else:
        db.delete(comment)
    db.commit()
    return {"ok": True}


# ------------------------------------------------------------------ settings


@router.get("/settings")
def get_settings(user: User = Depends(current_user)):
    return {
        "notify_email": user.notify_email,
        "notify_email_address": user.notify_email_address,
        "discord_webhook_url": user.discord_webhook_url,
        "apprise_urls": user.apprise_urls,
    }


@router.post("/settings")
def update_settings(
    body: SettingsUpdate, db: Session = Depends(get_db), user: User = Depends(current_user)
):
    if body.notify_email is not None:
        user.notify_email = body.notify_email
    if body.notify_email_address is not None:
        user.notify_email_address = body.notify_email_address.strip() or None
    if body.discord_webhook_url is not None:
        user.discord_webhook_url = body.discord_webhook_url.strip() or None
    if body.apprise_urls is not None:
        user.apprise_urls = body.apprise_urls.strip() or None
    db.commit()
    return {
        "notify_email": user.notify_email,
        "notify_email_address": user.notify_email_address,
        "discord_webhook_url": user.discord_webhook_url,
        "apprise_urls": user.apprise_urls,
    }
