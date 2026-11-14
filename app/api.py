from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

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
from app.models import Chapter, Follow, ImportJob, User, Work

router = APIRouter(prefix="/api")

API_VERSION = 1


class ImportRequest(BaseModel):
    urls: list[str]


class LoginRequest(BaseModel):
    username: str = ""
    password: str = ""


def _user(u: User | None) -> dict | None:
    return {"id": u.id, "name": u.label} if u else None


def _me(u: User) -> dict:
    return {**_user(u), "email": u.email, "is_admin": u.is_admin}


def _chapter_summary(c: Chapter) -> dict:
    return {
        "id": c.id,
        "number": c.number,
        "title": c.title,
        "word_count": c.word_count,
        "imported_at": c.imported_at,
        "imported_by": _user(c.imported_by),
        "removed_from_source": c.removed_from_source,
    }


def _work(w: Work, full: bool = False, is_following: bool | None = None) -> dict:
    data = {
        "id": w.id,
        "title": w.title,
        "author": w.author,
        "site": w.site,
        "source_url": w.source_url,
        "status": w.status,
        "rating": w.rating,
        "word_count": w.word_count,
        "chapters": len(w.chapters),
        "added_by": _user(w.added_by),
        "created_at": w.created_at,
        "last_checked_at": w.last_checked_at,
        "last_new_chapter_at": w.last_new_chapter_at,
        "auto_update": w.auto_update,
        "last_error": w.last_error,
    }
    if is_following is not None:
        data["is_following"] = is_following
    if full:
        data.update(
            fandoms=w.fandoms or [],
            relationships=w.relationships or [],
            characters=w.characters or [],
            tags=w.tags or [],
            warnings=w.warnings or [],
            summary_html=w.summary_html,
            chapter_list=[_chapter_summary(c) for c in w.chapters],
        )
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
    if followed:
        query = query.join(Follow, (Follow.work_id == Work.id) & (Follow.user_id == user.id))

    total = db.scalar(select(func.count()).select_from(query.subquery())) or 0
    works = db.scalars(
        query.order_by(func.coalesce(Work.last_new_chapter_at, Work.created_at).desc())
        .offset(offset)
        .limit(limit)
    ).all()
    followed_ids = set(db.scalars(select(Follow.work_id).where(Follow.user_id == user.id)))
    return {
        "works": [_work(w, is_following=w.id in followed_ids) for w in works],
        "total": total,
        "limit": limit,
        "offset": offset,
    }


@router.get("/works/{work_id}")
def get_work(work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    work = _get_work(db, work_id)
    return _work(work, full=True, is_following=db.get(Follow, (user.id, work.id)) is not None)


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
    return {
        **_chapter_summary(chapter),
        "work_id": work.id,
        "work_title": work.title,
        "source_url": chapter.source_url,
        "content_html": chapter.content_html,
        "prev_number": chapters[index - 1].number if index > 0 else None,
        "next_number": chapters[index + 1].number if index + 1 < len(chapters) else None,
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


@router.get("/jobs")
def list_jobs(limit: int = 100, db: Session = Depends(get_db), user: User = Depends(current_user)):
    jobs = db.scalars(select(ImportJob).order_by(ImportJob.id.desc()).limit(min(limit, 500))).all()
    return [_job(j) for j in jobs]
