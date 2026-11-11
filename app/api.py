from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth import current_user
from app.db import get_db
from app.importers import is_supported
from app.jobs import enqueue_import, enqueue_update
from app.models import ImportJob, User, Work

router = APIRouter(prefix="/api")


class ImportRequest(BaseModel):
    urls: list[str]


def _user(u: User | None) -> dict | None:
    return {"id": u.id, "name": u.label} if u else None


def _work(w: Work, full: bool = False) -> dict:
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
    if full:
        data.update(
            fandoms=w.fandoms,
            relationships=w.relationships,
            characters=w.characters,
            tags=w.tags,
            warnings=w.warnings,
            summary_html=w.summary_html,
            chapter_list=[
                {
                    "number": c.number,
                    "title": c.title,
                    "word_count": c.word_count,
                    "imported_at": c.imported_at,
                    "imported_by": _user(c.imported_by),
                    "removed_from_source": c.removed_from_source,
                }
                for c in w.chapters
            ],
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


@router.get("/me")
def me(user: User = Depends(current_user)):
    return {**_user(user), "email": user.email, "is_admin": user.is_admin}


@router.get("/works")
def list_works(limit: int = 100, offset: int = 0, db: Session = Depends(get_db), user: User = Depends(current_user)):
    works = db.scalars(select(Work).order_by(Work.id.desc()).offset(offset).limit(min(limit, 500))).all()
    return [_work(w) for w in works]


@router.get("/works/{work_id}")
def get_work(work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    work = db.get(Work, work_id)
    if not work:
        raise HTTPException(404)
    return _work(work, full=True)


@router.post("/works/{work_id}/check")
def check_work(work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    work = db.get(Work, work_id)
    if not work:
        raise HTTPException(404)
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
