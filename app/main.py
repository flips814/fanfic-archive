import hashlib
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path

from fastapi import BackgroundTasks, Depends, FastAPI, Form, HTTPException, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session
from starlette.middleware.sessions import SessionMiddleware

from app import admin, api, auth, comments, notify, settings_store
from app.auth import NotAuthenticated, current_user, current_user_optional
from app.config import settings
from app.db import SessionLocal, get_db, init_db
from app.epub import build_epub
from app.importers import is_supported, site_label
from app.jobs import enqueue_import, enqueue_update, follow
from app.logins import LOGIN_SITES, delete_login, save_login, user_logins
from app.models import Chapter, Comment, Follow, ImportJob, User, Work, utcnow

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("web")

BASE_DIR = Path(__file__).parent
PAGE_SIZE = 30
_SETUP_EXEMPT = ("/setup", "/static/", "/healthz")


def bootstrap() -> None:
    """Prepare the database, secret key and saved settings before the app (and its session cookie) starts."""
    init_db()
    with SessionLocal() as db:
        settings_store.ensure_secret_key(db)
        settings_store.load(db)
        if settings_store.setup_needed(db):
            code = settings_store.setup_code(db)
            log.warning(
                "\n\n  ==== First-time setup ====\n  Setup code: %s\n"
                "  Open /setup on this server (e.g. %s/setup) and enter the code.\n\n",
                code, settings.base_url,
            )
    auth.configure_oauth()


bootstrap()


@asynccontextmanager
async def lifespan(_: FastAPI):
    yield


app = FastAPI(title="Fanfic Archive", lifespan=lifespan)
_setup_done = False


@app.middleware("http")
async def _first_launch_setup(request: Request, call_next):
    # Until an admin exists, every page leads to the setup wizard.
    global _setup_done
    if not _setup_done and not request.url.path.startswith(_SETUP_EXEMPT):
        with SessionLocal() as db:
            _setup_done = not settings_store.setup_needed(db)
        if not _setup_done:
            if request.url.path.startswith("/api/"):
                return JSONResponse({"detail": "Setup required"}, status_code=503)
            return RedirectResponse("/setup")
    return await call_next(request)


@app.middleware("http")
async def _no_cache_pages(request: Request, call_next):
    response = await call_next(request)
    # Pages are per-user: don't let browsers show stale or someone else's page after logout.
    if response.headers.get("content-type", "").startswith("text/html"):
        response.headers["Cache-Control"] = "no-store"
    return response


app.add_middleware(
    SessionMiddleware,
    secret_key=settings.secret_key,
    same_site="lax",
    https_only=settings.base_url.startswith("https://"),
    max_age=60 * 60 * 24 * 30,
)
app.mount("/static", StaticFiles(directory=BASE_DIR / "static"), name="static")
app.include_router(auth.router)
app.include_router(admin.router)
app.include_router(api.router)

templates = Jinja2Templates(directory=BASE_DIR / "templates")

# FanFicFare reports language names; the reader needs BCP 47 codes for screen readers / hyphenation.
_LANG_CODES = {
    "english": "en", "spanish": "es", "español": "es", "french": "fr", "français": "fr", "german": "de",
    "deutsch": "de", "portuguese": "pt", "português": "pt", "italian": "it", "italiano": "it",
    "russian": "ru", "русский": "ru", "chinese": "zh", "中文": "zh", "japanese": "ja", "日本語": "ja",
    "korean": "ko", "polish": "pl", "polski": "pl", "dutch": "nl", "indonesian": "id",
    "bahasa indonesia": "id", "turkish": "tr", "vietnamese": "vi", "tagalog": "tl", "filipino": "fil",
}


def _aware(value: datetime) -> datetime:
    return value if value.tzinfo else value.replace(tzinfo=timezone.utc)


def _fmt_dt(value: datetime | None) -> str:
    if not value:
        return "—"
    return _aware(value).astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


def _fmt_iso(value: datetime | None) -> str:
    return _aware(value).isoformat() if value else ""


def _fmt_ago(value: datetime | None) -> str:
    if not value:
        return "never"
    seconds = (datetime.now(timezone.utc) - _aware(value)).total_seconds()
    if seconds < 60:
        return "just now"
    for limit, size, unit in ((3600, 60, "minute"), (86400, 3600, "hour"), (86400 * 30, 86400, "day")):
        if seconds < limit:
            n = int(seconds // size)
            return f"{n} {unit}{'' if n == 1 else 's'} ago"
    value = _aware(value)
    return f"{value:%b} {value.day}, {value.year}"


def _fmt_num(value: int | None) -> str:
    return f"{value:,}" if value else "—"


def _lang_code(language: str | None) -> str:
    if not language:
        return ""
    language = language.strip().lower()
    return _LANG_CODES.get(language, language if len(language) in (2, 3) else "")


def _is_new_chapter(chapter: Chapter, work: Work, days: int = 7) -> bool:
    """Arrived after the initial import, within the last ``days`` days."""
    imported, created = _aware(chapter.imported_at), _aware(work.created_at)
    return (imported - created).total_seconds() > 120 and (
        datetime.now(timezone.utc) - imported
    ).days < days


templates.env.filters.update(
    dt=_fmt_dt, iso=_fmt_iso, ago=_fmt_ago, num=_fmt_num, lang_code=_lang_code, comment_body=comments.render_body
)
templates.env.globals.update(
    settings=settings,
    site_label=site_label,
    is_new_chapter=_is_new_chapter,
    comment_max_length=comments.MAX_LENGTH,
    static_version=hashlib.sha256(
        b"".join(p.read_bytes() for p in sorted((BASE_DIR / "static").iterdir()))
    ).hexdigest()[:12],
)


@app.exception_handler(NotAuthenticated)
async def _not_authenticated(request: Request, exc: NotAuthenticated):
    if request.url.path.startswith("/api/"):
        return JSONResponse({"detail": "Not authenticated"}, status_code=401)
    return RedirectResponse(f"/login?next={request.url.path}")


def flash(request: Request, message: str) -> None:
    request.session.setdefault("flash", []).append(message)


def render(request: Request, name: str, user: User | None, status_code: int = 200, **context):
    messages = request.session.pop("flash", [])
    return templates.TemplateResponse(
        request, name, {"user": user, "messages": messages, **context}, status_code=status_code
    )


def _int_or_none(value: str) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _get_work(db: Session, work_id: int) -> Work:
    work = db.get(Work, work_id)
    if not work:
        raise HTTPException(404, "Work not found")
    return work


# --------------------------------------------------------------------- pages

@app.get("/healthz")
def healthz():
    return {"ok": True}


@app.get("/login-unavailable")
def login_unavailable(request: Request):
    return render(request, "login_unavailable.html", None)


@app.get("/")
def library(
    request: Request,
    q: str = "",
    site: str = "",
    added_by: str = "",
    status: str = "",
    mine: str = "",
    page: int = 1,
    db: Session = Depends(get_db),
    user: User | None = Depends(current_user_optional),
):
    if user is None:
        return render(request, "landing.html", None)
    added_by_id = _int_or_none(added_by)
    chapter_count = (
        select(func.count(Chapter.id)).where(Chapter.work_id == Work.id).correlate(Work).scalar_subquery()
    )
    query = select(Work, chapter_count.label("chapter_count"))
    if q:
        like = f"%{q}%"
        query = query.where(or_(Work.title.ilike(like), Work.author.ilike(like)))
    if site:
        query = query.where(Work.site == site)
    if added_by_id:
        query = query.where(Work.added_by_id == added_by_id)
    if status == "complete":
        query = query.where(Work.status.ilike("complete%"))
    elif status == "ongoing":
        query = query.where(or_(Work.status.is_(None), ~Work.status.ilike("complete%")))
    if mine:
        query = query.join(Follow, (Follow.work_id == Work.id) & (Follow.user_id == user.id))

    total = db.scalar(select(func.count()).select_from(query.subquery()))
    rows = db.execute(
        query.order_by(func.coalesce(Work.last_new_chapter_at, Work.created_at).desc())
        .offset((max(page, 1) - 1) * PAGE_SIZE)
        .limit(PAGE_SIZE)
    ).all()
    followed = set(db.scalars(select(Follow.work_id).where(Follow.user_id == user.id)))
    sites = db.scalars(select(Work.site).distinct().order_by(Work.site)).all()
    users = db.scalars(select(User).order_by(User.display_name)).all()
    return render(
        request,
        "library.html",
        user,
        rows=rows,
        total=total,
        page=page,
        pages=max(1, -(-total // PAGE_SIZE)),
        followed=followed,
        sites=sites,
        users=users,
        filters={"q": q, "site": site, "added_by": added_by_id, "status": status, "mine": bool(mine)},
    )


@app.get("/add")
def add_form(request: Request, user: User = Depends(current_user)):
    return render(request, "add.html", user)


@app.post("/add")
def add_submit(
    request: Request,
    urls: str = Form(...),
    db: Session = Depends(get_db),
    user: User = Depends(current_user),
):
    queued, rejected = 0, []
    for line in urls.splitlines():
        url = line.strip()
        if not url:
            continue
        if not is_supported(url):
            rejected.append(url)
            continue
        enqueue_import(db, url, user)
        queued += 1
    db.commit()
    if queued:
        flash(request, f"Queued {queued} import(s). They will appear in the library shortly.")
    for url in rejected:
        flash(request, f"Unsupported URL skipped: {url}")
    return RedirectResponse("/activity?user_id=%d" % user.id, status_code=303)


@app.get("/works/{work_id}")
def work_detail(
    request: Request, work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)
):
    work = _get_work(db, work_id)
    jobs = db.scalars(
        select(ImportJob).where(ImportJob.work_id == work.id).order_by(ImportJob.created_at.desc()).limit(25)
    ).all()
    work_comments = comments.thread(db, work.id, None)
    return render(
        request,
        "work.html",
        user,
        work=work,
        jobs=jobs,
        is_following=db.get(Follow, (user.id, work.id)) is not None,
        can_manage=user.is_admin or work.added_by_id == user.id,
        comments=work_comments,
        comment_count=comments.visible_count(work_comments),
        chapter_comment_counts=comments.chapter_counts(db, work.id),
    )


@app.post("/works/{work_id}/follow")
def work_follow(request: Request, work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    follow(db, user, _get_work(db, work_id))
    db.commit()
    flash(request, "You'll be notified when new chapters are imported.")
    return RedirectResponse(f"/works/{work_id}", status_code=303)


@app.post("/works/{work_id}/unfollow")
def work_unfollow(request: Request, work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    existing = db.get(Follow, (user.id, work_id))
    if existing:
        db.delete(existing)
        db.commit()
    return RedirectResponse(f"/works/{work_id}", status_code=303)


@app.post("/works/{work_id}/check")
def work_check(request: Request, work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    enqueue_update(db, _get_work(db, work_id), user)
    db.commit()
    flash(request, "Update check queued.")
    return RedirectResponse(f"/works/{work_id}", status_code=303)


@app.post("/works/{work_id}/auto-update")
def work_toggle_auto(request: Request, work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    work = _get_work(db, work_id)
    if not (user.is_admin or work.added_by_id == user.id):
        raise HTTPException(403)
    work.auto_update = not work.auto_update
    db.commit()
    return RedirectResponse(f"/works/{work_id}", status_code=303)


@app.post("/works/{work_id}/delete")
def work_delete(request: Request, work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    work = _get_work(db, work_id)
    if not (user.is_admin or work.added_by_id == user.id):
        raise HTTPException(403)
    flash(request, f"Deleted “{work.title}”.")
    db.delete(work)
    db.commit()
    return RedirectResponse("/", status_code=303)


@app.get("/works/{work_id}/epub")
def work_epub(work_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    filename, data = build_epub(_get_work(db, work_id))
    return Response(
        data,
        media_type="application/epub+zip",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@app.get("/works/{work_id}/chapters/{number}")
def read_chapter(
    request: Request, work_id: int, number: int, db: Session = Depends(get_db), user: User = Depends(current_user)
):
    work = _get_work(db, work_id)
    chapters = work.chapters
    index = next((i for i, c in enumerate(chapters) if c.number == number), None)
    if index is None:
        raise HTTPException(404, "Chapter not found")
    chapter_comments = comments.thread(db, work.id, chapters[index].id)
    return render(
        request,
        "chapter.html",
        user,
        work=work,
        chapter=chapters[index],
        prev=chapters[index - 1] if index > 0 else None,
        next=chapters[index + 1] if index + 1 < len(chapters) else None,
        comments=chapter_comments,
        comment_count=comments.visible_count(chapter_comments),
    )


# ------------------------------------------------------------------ comments

def _comment_redirect(work: Work, chapter: Chapter | None, anchor: str = "comments") -> RedirectResponse:
    base = f"/works/{work.id}" + (f"/chapters/{chapter.number}" if chapter else "")
    return RedirectResponse(f"{base}#{anchor}", status_code=303)


@app.post("/works/{work_id}/comments")
def comment_create(
    request: Request,
    work_id: int,
    background: BackgroundTasks,
    body: str = Form(""),
    chapter_id: str = Form(""),
    parent_id: str = Form(""),
    spoiler: str = Form(""),
    db: Session = Depends(get_db),
    user: User = Depends(current_user),
):
    work = _get_work(db, work_id)
    chapter = db.get(Chapter, _int_or_none(chapter_id)) if chapter_id else None
    if chapter is not None and chapter.work_id != work.id:
        raise HTTPException(404, "Chapter not found")
    parent = db.get(Comment, _int_or_none(parent_id)) if parent_id else None
    if parent is not None:
        if parent.work_id != work.id or parent.chapter_id != (chapter.id if chapter else None):
            raise HTTPException(404, "Comment not found")
        parent = parent.parent or parent  # replies stay one level deep
    try:
        text = comments.clean_body(body)
    except comments.CommentError as e:
        flash(request, str(e))
        return _comment_redirect(work, chapter)
    comment = Comment(
        work_id=work.id,
        chapter_id=chapter.id if chapter else None,
        parent_id=parent.id if parent else None,
        user_id=user.id,
        body=text,
        spoiler=bool(spoiler),
    )
    db.add(comment)
    db.commit()
    if parent is not None:
        background.add_task(comments.notify_reply, comment.id)
    return _comment_redirect(work, chapter, f"comment-{comment.id}")


def _own_comment(db: Session, comment_id: int, user: User, allow_admin: bool) -> Comment:
    comment = db.get(Comment, comment_id)
    if comment is None or comment.deleted:
        raise HTTPException(404, "Comment not found")
    if comment.user_id != user.id and not (allow_admin and user.is_admin):
        raise HTTPException(403)
    return comment


@app.post("/comments/{comment_id}/edit")
def comment_edit(
    request: Request,
    comment_id: int,
    body: str = Form(""),
    spoiler: str = Form(""),
    db: Session = Depends(get_db),
    user: User = Depends(current_user),
):
    comment = _own_comment(db, comment_id, user, allow_admin=False)
    try:
        comment.body = comments.clean_body(body)
    except comments.CommentError as e:
        flash(request, str(e))
        return _comment_redirect(comment.work, comment.chapter, f"comment-{comment.id}")
    comment.spoiler = bool(spoiler)
    comment.edited_at = utcnow()
    db.commit()
    return _comment_redirect(comment.work, comment.chapter, f"comment-{comment.id}")


@app.post("/comments/{comment_id}/delete")
def comment_delete(
    request: Request, comment_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)
):
    comment = _own_comment(db, comment_id, user, allow_admin=True)
    work, chapter = comment.work, comment.chapter
    if comment.replies:
        # keep the thread readable: replies stay, the comment becomes a placeholder
        comment.deleted, comment.body = True, ""
    else:
        db.delete(comment)
    db.commit()
    flash(request, "Comment deleted.")
    return _comment_redirect(work, chapter)


@app.get("/activity")
def activity(
    request: Request,
    user_id: str = "",
    status: str = "",
    kind: str = "",
    page: int = 1,
    db: Session = Depends(get_db),
    user: User = Depends(current_user),
):
    user_id = _int_or_none(user_id)
    query = select(ImportJob)
    if user_id == 0:
        query = query.where(ImportJob.requested_by_id.is_(None))
    elif user_id:
        query = query.where(ImportJob.requested_by_id == user_id)
    if status:
        query = query.where(ImportJob.status == status)
    if kind:
        query = query.where(ImportJob.kind == kind)
    total = db.scalar(select(func.count()).select_from(query.subquery()))
    jobs = db.scalars(
        query.order_by(ImportJob.created_at.desc()).offset((max(page, 1) - 1) * 50).limit(50)
    ).all()
    users = db.scalars(select(User).order_by(User.display_name)).all()
    return render(
        request,
        "activity.html",
        user,
        jobs=jobs,
        users=users,
        page=page,
        pages=max(1, -(-total // 50)),
        filters={"user_id": user_id, "status": status, "kind": kind},
    )


@app.get("/users")
def users_page(request: Request, db: Session = Depends(get_db), user: User = Depends(current_user)):
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
    auto_chapters = db.scalar(select(func.count(Chapter.id)).where(Chapter.imported_by_id.is_(None)))
    return render(request, "users.html", user, rows=rows, auto_chapters=auto_chapters)


@app.get("/settings")
def settings_page(request: Request, db: Session = Depends(get_db), user: User = Depends(current_user)):
    return render(request, "settings.html", user, login_sites=LOGIN_SITES, logins=user_logins(db, user))


@app.post("/settings/logins/{site}")
def settings_login_save(
    request: Request,
    site: str,
    username: str = Form(""),
    password: str = Form(""),
    remove: str = Form(""),
    db: Session = Depends(get_db),
    user: User = Depends(current_user),
):
    if site not in LOGIN_SITES:
        raise HTTPException(404)
    label = LOGIN_SITES[site]
    if remove:
        delete_login(db, user, site)
        flash(request, f"Removed your {label} login.")
    elif not username.strip():
        flash(request, f"Enter your {label} username.")
    elif not password and site not in user_logins(db, user):
        flash(request, f"Enter your {label} password.")
    else:
        save_login(db, user, site, username.strip(), password)
        flash(request, f"Saved your {label} login.")
    db.commit()
    return RedirectResponse("/settings", status_code=303)


@app.post("/settings")
def settings_save(
    request: Request,
    notify_email: bool = Form(False),
    notify_email_address: str = Form(""),
    discord_webhook_url: str = Form(""),
    apprise_urls: str = Form(""),
    db: Session = Depends(get_db),
    user: User = Depends(current_user),
):
    user.notify_email = notify_email
    user.notify_email_address = notify_email_address.strip() or None
    user.discord_webhook_url = discord_webhook_url.strip() or None
    user.apprise_urls = apprise_urls.strip() or None
    db.commit()
    flash(request, "Notification settings saved.")
    return RedirectResponse("/settings", status_code=303)


@app.post("/settings/test")
def settings_test(request: Request, user: User = Depends(current_user)):
    notify.notify_user(
        user,
        f"{settings.site_name}: test notification",
        f"Hi {user.label}, notifications are working. You'll get messages like this when works you follow update.",
    )
    flash(request, "Test notification sent to your configured channels.")
    return RedirectResponse("/settings", status_code=303)
