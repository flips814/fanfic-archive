import logging
import os
from datetime import datetime, timedelta, timezone

import requests
from fastapi import APIRouter, Depends, Form, HTTPException, Request
from fastapi.responses import RedirectResponse
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app import notify, settings_store
from app.auth import configure_oauth, require_admin
from app.config import FIELDS, SECTIONS, settings
from app.db import get_db
from app.jobs import PENDING, enqueue_import, enqueue_update
from app.models import LOCAL_ISSUER, Chapter, Comment, ImportJob, User, Work, utcnow
from app.passwords import check_strength, hash_password

log = logging.getLogger(__name__)
router = APIRouter()


def _render(request: Request, name: str, user: User | None, **context):
    from app.main import render  # late import: main imports this module

    return render(request, name, user, **context)


def _flash(request: Request, message: str) -> None:
    request.session.setdefault("flash", []).append(message)


def _back(path: str) -> RedirectResponse:
    return RedirectResponse(path, status_code=303)


# ------------------------------------------------------------------ first-launch setup

@router.get("/setup")
def setup_form(request: Request, db: Session = Depends(get_db)):
    if not settings_store.setup_needed(db):
        return RedirectResponse("/")
    settings_store.setup_code(db)
    return _render(request, "setup.html", None, suggested_url=str(request.base_url).rstrip("/"),
                   sso_ready=settings.oidc_enabled and bool(settings.admin_emails or settings.oidc_admin_group))


@router.post("/setup")
def setup_submit(
    request: Request,
    code: str = Form(""),
    mode: str = Form("local"),
    site_name: str = Form(""),
    base_url: str = Form(""),
    username: str = Form(""),
    display_name: str = Form(""),
    password: str = Form(""),
    password_confirm: str = Form(""),
    db: Session = Depends(get_db),
):
    if not settings_store.setup_needed(db):
        return RedirectResponse("/", status_code=303)
    errors = []
    if code.strip().upper() != settings_store.setup_code(db):
        errors.append("The setup code doesn't match. Copy it from the web container's log.")
    values = {}
    if site_name.strip():
        values["site_name"] = site_name.strip()
    if base_url.strip():
        if not base_url.strip().startswith(("http://", "https://")):
            errors.append("The public URL must start with http:// or https://.")
        values["base_url"] = base_url.strip().rstrip("/")
    user = None
    if mode == "local":
        username = username.strip().lower()
        if not username or not username.replace("_", "").replace("-", "").replace(".", "").isalnum():
            errors.append("Pick a username using letters, numbers, dots, dashes or underscores.")
        if password != password_confirm:
            errors.append("The two passwords don't match.")
        elif weak := check_strength(password):
            errors.append(weak)
    elif not (settings.oidc_enabled and (settings.admin_emails or settings.oidc_admin_group)):
        errors.append("SSO isn't configured with an admin email or group yet, so create an admin account instead.")
    if errors:
        return _render(request, "setup.html", None, errors=errors, form=dict(
            site_name=site_name, base_url=base_url, username=username, display_name=display_name, mode=mode),
            sso_ready=settings.oidc_enabled and bool(settings.admin_emails or settings.oidc_admin_group),
            status_code=400)

    if mode == "local":
        user = User(oidc_issuer=LOCAL_ISSUER, oidc_sub=username, username=username,
                    display_name=display_name.strip() or username, is_admin=True, admin_override=True,
                    password_hash=hash_password(password), last_login_at=utcnow())
        db.add(user)
        db.flush()
    settings_store.save(db, values, user)
    settings_store.complete_setup(db, user)
    db.commit()
    configure_oauth()
    if user:
        request.session.clear()
        request.session["user_id"] = user.id
        _flash(request, "Welcome! Your archive is ready. Have a look through the settings below.")
        return RedirectResponse("/admin", status_code=303)
    _flash(request, "Setup complete. Sign in with your SSO account; admin emails/groups become admins.")
    return RedirectResponse("/login", status_code=303)


# ------------------------------------------------------------------ overview

def _checklist(db: Session) -> list[dict]:
    heartbeat = settings_store.last_heartbeat(db)
    busy = db.scalar(select(func.count(ImportJob.id)).where(ImportJob.status == "running"))
    worker_ok = bool(busy) or (heartbeat is not None and datetime.now(timezone.utc) - heartbeat < timedelta(minutes=3))
    local_admins = db.scalar(select(func.count(User.id)).where(
        User.oidc_issuer == LOCAL_ISSUER, User.is_admin.is_(True), User.disabled.is_(False)))
    is_localhost = "://localhost" in settings.base_url or "://127.0.0.1" in settings.base_url
    return [
        dict(ok=worker_ok, title="Background worker",
             detail="Busy importing right now." if busy
             else f"Last seen {heartbeat:%H:%M:%S UTC}." if heartbeat and worker_ok
             else f"Not seen since {heartbeat:%Y-%m-%d %H:%M UTC}; is the worker container running?" if heartbeat
             else "Hasn't reported in yet; is the worker container running?",
             href=None),
        dict(ok=not is_localhost, warn=is_localhost, title="Public URL",
             detail=f"{settings.base_url}" + (" (fine for testing on this computer; set your real address before sharing)" if is_localhost else ""),
             href="/admin/settings/general"),
        dict(ok=settings.oidc_enabled, warn=not settings.oidc_enabled, title="Sign-in (SSO)",
             detail="Connected to " + settings.oidc_provider_name if settings.oidc_enabled
             else "Not set up; people can only use accounts you create under Users.",
             href="/admin/settings/signin"),
        dict(ok=bool(local_admins), warn=not local_admins, title="Backup admin login",
             detail=f"{local_admins} admin account(s) with a password, so you can get in if SSO breaks." if local_admins
             else "Every admin signs in through SSO. Consider creating a password admin under Users as a backup.",
             href="/admin/users"),
        dict(ok=settings.email_enabled, warn=not settings.email_enabled, title="Email",
             detail=f"Sending from {settings.smtp_from}" if settings.email_enabled else "Not set up (optional).",
             href="/admin/settings/email"),
        dict(ok=bool(settings.flaresolverr_url), warn=not settings.flaresolverr_url, title="FanFiction.net (FlareSolverr)",
             detail=settings.flaresolverr_url or "Not set; FanFiction.net imports will likely be blocked by Cloudflare.",
             href="/admin/settings/importers"),
    ]


@router.get("/admin")
def admin_overview(request: Request, db: Session = Depends(get_db), user: User = Depends(require_admin)):
    week_ago = utcnow() - timedelta(days=7)
    stats = dict(
        works=db.scalar(select(func.count(Work.id))),
        chapters=db.scalar(select(func.count(Chapter.id))),
        users=db.scalar(select(func.count(User.id))),
        comments=db.scalar(select(func.count(Comment.id)).where(Comment.deleted.is_(False))),
        queued=db.scalar(select(func.count(ImportJob.id)).where(ImportJob.status == "queued")),
        running=db.scalar(select(func.count(ImportJob.id)).where(ImportJob.status == "running")),
        failed_week=db.scalar(select(func.count(ImportJob.id)).where(
            ImportJob.status == "failed", ImportJob.created_at >= week_ago)),
    )
    failed = db.scalars(select(ImportJob).where(ImportJob.status == "failed", ImportJob.created_at >= week_ago)
                        .order_by(ImportJob.created_at.desc()).limit(20)).all()
    queued = db.scalars(select(ImportJob).where(ImportJob.status.in_(PENDING))
                        .order_by(ImportJob.created_at).limit(20)).all()
    errored_works = db.scalars(select(Work).where(Work.last_error.is_not(None)).order_by(Work.title)).all()
    return _render(request, "admin/overview.html", user, checklist=_checklist(db), stats=stats,
                   failed=failed, queued=queued, errored_works=errored_works, admin_section="overview")


# ------------------------------------------------------------------ settings

@router.get("/admin/settings/{section}")
def admin_settings(request: Request, section: str, db: Session = Depends(get_db), user: User = Depends(require_admin)):
    if section not in SECTIONS:
        raise HTTPException(404)
    return _render(request, "admin/settings.html", user, section=section, title=SECTIONS[section][0],
                   description=SECTIONS[section][1], fields=[f for f in FIELDS if f.section == section],
                   overridden=settings_store.overridden_keys(db),
                   env_keys={f.key for f in FIELDS if os.getenv(f.key.upper())}, admin_section=section)


@router.post("/admin/settings/{section}")
async def admin_settings_save(request: Request, section: str, db: Session = Depends(get_db),
                              user: User = Depends(require_admin)):
    if section not in SECTIONS:
        raise HTTPException(404)
    form = await request.form()
    if form.get("reset"):
        settings_store.reset(db, [f.key for f in FIELDS if f.section == section])
        db.commit()
        _flash(request, f"{SECTIONS[section][0]}: back to the values from the environment.")
    else:
        try:
            values = settings_store.parse_form(section, form)
        except settings_store.SettingsError as e:
            _flash(request, f"Not saved. {e}")
            return _back(f"/admin/settings/{section}")
        settings_store.save(db, values, user)
        db.commit()
        _flash(request, f"{SECTIONS[section][0]} saved. Changes are live now.")
    if section == "signin":
        configure_oauth()
    return _back(f"/admin/settings/{section}")


@router.post("/admin/test/{what}")
async def admin_test(request: Request, what: str, db: Session = Depends(get_db), user: User = Depends(require_admin)):
    section = {"oidc": "signin", "email": "email", "notify": "notifications", "flaresolverr": "importers"}.get(what)
    if section is None:
        raise HTTPException(404)
    form = await request.form()
    if form:  # the test buttons live in the section's form: save what was typed, then test it
        try:
            settings_store.save(db, settings_store.parse_form(section, form), user)
            db.commit()
        except settings_store.SettingsError as e:
            _flash(request, f"Not saved. {e}")
            return _back(f"/admin/settings/{section}")
        if section == "signin":
            configure_oauth()
    try:
        _flash(request, _run_test(what, user))
    except Exception as e:  # report any failure to the admin rather than erroring
        _flash(request, f"Test failed: {e}")
    return _back(f"/admin/settings/{section}")


def _run_test(what: str, user: User) -> str:
    if what == "oidc":
        if not settings.oidc_metadata_url:
            raise ValueError("enter an issuer URL first.")
        resp = requests.get(settings.oidc_metadata_url, timeout=10)
        resp.raise_for_status()
        meta = resp.json()
        missing = [k for k in ("issuer", "authorization_endpoint", "token_endpoint", "jwks_uri") if not meta.get(k)]
        if missing:
            raise ValueError(f"the discovery document is missing {', '.join(missing)}.")
        if not settings.oidc_client_id:
            return f"Found {meta['issuer']}. Now add the client ID and secret."
        return f"Connected to {meta['issuer']}. Use “Sign in with {settings.oidc_provider_name}” in a private window to try it."
    if what == "email":
        if not settings.email_enabled:
            raise ValueError("set the SMTP server and From address first.")
        to = user.notify_email_address or user.email
        if not to:
            raise ValueError("your account has no email address; add one under Settings → Notifications.")
        if not notify.send_email(to, f"{settings.site_name}: test email", "Email from your fanfic archive works."):
            raise ValueError("the mail server refused the message; check the web container log for details.")
        return f"Test email sent to {to}."
    if what == "notify":
        if not (settings.apprise_urls or settings.discord_webhook_url):
            raise ValueError("add a Discord webhook or an Apprise URL first.")
        notify.notify_global(f"{settings.site_name}: test", "Server-wide notifications are working.")
        return "Test notification sent to the server-wide channels."
    if not settings.flaresolverr_url:
        raise ValueError("enter the FlareSolverr URL first.")
    msg = requests.get(settings.flaresolverr_url, timeout=10).json().get("msg", "")
    return f"FlareSolverr replied: {msg or 'OK'}"


# ------------------------------------------------------------------ users

@router.get("/admin/users")
def admin_users(request: Request, db: Session = Depends(get_db), user: User = Depends(require_admin)):
    users = db.scalars(select(User).order_by(User.disabled, User.created_at)).all()
    return _render(request, "admin/users.html", user, users=users, admin_section="users")


def _active_admins(db: Session) -> int:
    return db.scalar(select(func.count(User.id)).where(User.is_admin.is_(True), User.disabled.is_(False)))


def _target(db: Session, user_id: int) -> User:
    target = db.get(User, user_id)
    if target is None:
        raise HTTPException(404)
    return target


@router.post("/admin/users/{user_id}/role")
def admin_toggle_role(request: Request, user_id: int, db: Session = Depends(get_db), user: User = Depends(require_admin)):
    target = _target(db, user_id)
    if target.is_admin and _active_admins(db) <= 1:
        _flash(request, "You can't remove the last admin. Make someone else an admin first.")
        return _back("/admin/users")
    target.is_admin = not target.is_admin
    target.admin_override = target.is_admin
    db.commit()
    _flash(request, f"{target.label} is {'now an admin' if target.is_admin else 'no longer an admin'}.")
    return _back("/admin/users")


@router.post("/admin/users/{user_id}/sso-role")
def admin_role_follow_sso(request: Request, user_id: int, db: Session = Depends(get_db), user: User = Depends(require_admin)):
    target = _target(db, user_id)
    target.admin_override = None
    db.commit()
    _flash(request, f"{target.label}'s admin role will follow your SSO admin emails/group from their next sign-in.")
    return _back("/admin/users")


@router.post("/admin/users/{user_id}/disable")
def admin_toggle_disabled(request: Request, user_id: int, db: Session = Depends(get_db), user: User = Depends(require_admin)):
    target = _target(db, user_id)
    if target.id == user.id:
        _flash(request, "You can't disable your own account.")
        return _back("/admin/users")
    if not target.disabled and target.is_admin and _active_admins(db) <= 1:
        _flash(request, "You can't disable the last admin.")
        return _back("/admin/users")
    target.disabled = not target.disabled
    db.commit()
    _flash(request, f"{target.label} {'is disabled and signed out' if target.disabled else 'can sign in again'}.")
    return _back("/admin/users")


@router.post("/admin/users/create")
def admin_create_user(
    request: Request,
    username: str = Form(""),
    display_name: str = Form(""),
    email: str = Form(""),
    password: str = Form(""),
    make_admin: str = Form(""),
    db: Session = Depends(get_db),
    user: User = Depends(require_admin),
):
    username = username.strip().lower()
    if not username or not username.replace("_", "").replace("-", "").replace(".", "").isalnum():
        _flash(request, "Pick a username using letters, numbers, dots, dashes or underscores.")
    elif db.scalar(select(User.id).where(User.oidc_issuer == LOCAL_ISSUER, User.oidc_sub == username)):
        _flash(request, f"There's already an account called {username}.")
    elif weak := check_strength(password):
        _flash(request, weak)
    else:
        db.add(User(oidc_issuer=LOCAL_ISSUER, oidc_sub=username, username=username,
                    display_name=display_name.strip() or username, email=email.strip() or None,
                    is_admin=bool(make_admin), admin_override=bool(make_admin), password_hash=hash_password(password)))
        db.commit()
        _flash(request, f"Created {username}. They sign in with the username and password you chose.")
    return _back("/admin/users")


@router.post("/admin/users/{user_id}/password")
def admin_reset_password(request: Request, user_id: int, password: str = Form(""), db: Session = Depends(get_db),
                         user: User = Depends(require_admin)):
    target = _target(db, user_id)
    if not target.is_local:
        _flash(request, f"{target.label} signs in through SSO; change their password with your provider.")
    elif weak := check_strength(password):
        _flash(request, weak)
    else:
        target.password_hash = hash_password(password)
        db.commit()
        _flash(request, f"Password changed for {target.label}.")
    return _back("/admin/users")


# ------------------------------------------------------------------ jobs

@router.post("/admin/jobs/{job_id}/retry")
def admin_retry_job(request: Request, job_id: int, db: Session = Depends(get_db), user: User = Depends(require_admin)):
    job = db.get(ImportJob, job_id)
    if job is None:
        raise HTTPException(404)
    work = db.get(Work, job.work_id) if job.work_id else None
    if work:
        enqueue_update(db, work, user)
    else:
        enqueue_import(db, job.url, user)
    db.commit()
    _flash(request, f"Queued a new attempt for {job.work_title or job.url}.")
    return _back("/admin")


@router.post("/admin/jobs/{job_id}/cancel")
def admin_cancel_job(request: Request, job_id: int, db: Session = Depends(get_db), user: User = Depends(require_admin)):
    job = db.get(ImportJob, job_id)
    if job is None:
        raise HTTPException(404)
    if job.status == "queued":
        job.status, job.finished_at, job.message = "failed", utcnow(), f"Cancelled by {user.label}"
        db.commit()
        _flash(request, "Job cancelled.")
    else:
        _flash(request, "Only queued jobs can be cancelled.")
    return _back("/admin")


@router.post("/admin/jobs/check-all")
def admin_check_all(request: Request, db: Session = Depends(get_db), user: User = Depends(require_admin)):
    count = sum(1 for work in db.scalars(select(Work)) if enqueue_update(db, work, user) is not None)
    db.commit()
    _flash(request, f"Queued update checks for {count} work(s).")
    return _back("/admin")
