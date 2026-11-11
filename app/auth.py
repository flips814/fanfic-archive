import logging
import time
from collections import defaultdict, deque
from urllib.parse import urlencode

from authlib.integrations.starlette_client import OAuth, OAuthError
from fastapi import APIRouter, Depends, Form, HTTPException, Request
from fastapi.responses import RedirectResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import settings
from app.db import get_db
from app.models import LOCAL_ISSUER, User, utcnow
from app.passwords import verify_password

log = logging.getLogger(__name__)
router = APIRouter()

_oauth: OAuth | None = None


def configure_oauth() -> None:
    """(Re)build the OIDC client from current settings; called at startup and when an admin saves."""
    global _oauth
    _oauth = None
    if settings.oidc_enabled:
        oauth = OAuth()
        oauth.register(
            "oidc",
            server_metadata_url=settings.oidc_metadata_url,
            client_id=settings.oidc_client_id,
            client_secret=settings.oidc_client_secret or None,
            authorize_url=settings.oidc_authorize_url or None,
            client_kwargs={"scope": settings.oidc_scopes or "openid email profile", "code_challenge_method": "S256"},
        )
        _oauth = oauth


def _oidc():
    return _oauth.oidc if _oauth else None


class NotAuthenticated(Exception):
    pass


def current_user_optional(request: Request, db: Session = Depends(get_db)) -> User | None:
    user_id = request.session.get("user_id")
    if not user_id:
        return None
    user = db.get(User, user_id)
    if user is None or user.disabled:
        request.session.clear()
        return None
    return user


def current_user(user: User | None = Depends(current_user_optional)) -> User:
    if user is None:
        raise NotAuthenticated()
    return user


def require_admin(user: User = Depends(current_user)) -> User:
    if not user.is_admin:
        raise HTTPException(403, "Admins only")
    return user


def _safe_next(value: str | None) -> str:
    return value if value and value.startswith("/") and not value.startswith("//") else "/"


def _is_admin(claims: dict, email: str | None) -> bool:
    if email and email.lower() in settings.admin_emails:
        return True
    if settings.oidc_admin_group:
        groups = claims.get(settings.oidc_groups_claim) or []
        if isinstance(groups, str):
            groups = [groups]
        return settings.oidc_admin_group in groups
    return False


def _upsert_user(db: Session, issuer: str, claims: dict) -> User:
    user = db.scalar(select(User).where(User.oidc_issuer == issuer, User.oidc_sub == claims["sub"]))
    if user is None:
        user = User(oidc_issuer=issuer, oidc_sub=claims["sub"])
        db.add(user)
    user.username = claims.get("preferred_username") or claims.get("nickname") or user.username
    user.display_name = claims.get("name") or user.display_name
    user.email = claims.get("email") or user.email
    user.is_admin = user.admin_override if user.admin_override is not None else _is_admin(claims, user.email)
    user.last_login_at = utcnow()
    db.commit()
    return user


def local_login_available(db: Session) -> bool:
    return db.scalar(select(User.id).where(User.oidc_issuer == LOCAL_ISSUER).limit(1)) is not None


def _login_page(request: Request, db: Session, error: str | None = None, status: int = 200):
    from app.main import render  # late import: main imports this module

    return render(
        request, "login.html", None, local_available=local_login_available(db),
        next=_safe_next(request.query_params.get("next")), error=error, status_code=status,
    )


# ------------------------------------------------------------------ routes

@router.get("/login")
async def login(request: Request, db: Session = Depends(get_db)):
    next_url = _safe_next(request.query_params.get("next"))
    if not settings.oidc_enabled and not local_login_available(db):
        return RedirectResponse("/dev-login" if settings.dev_login else "/login-unavailable")
    if settings.oidc_enabled and not local_login_available(db):
        return RedirectResponse(f"/login/sso?{urlencode({'next': next_url})}")
    return _login_page(request, db)


@router.get("/login/sso")
async def login_sso(request: Request):
    client = _oidc()
    if client is None:
        return RedirectResponse("/login")
    request.session["next"] = _safe_next(request.query_params.get("next"))
    return await client.authorize_redirect(request, f"{settings.base_url}/auth/callback")


# Failed password attempts per client IP: (timestamps); simple sliding-window throttle.
_FAILED: dict[str, deque] = defaultdict(deque)
_MAX_FAILURES, _WINDOW = 10, 600


def _throttled(ip: str) -> bool:
    attempts = _FAILED[ip]
    while attempts and attempts[0] < time.monotonic() - _WINDOW:
        attempts.popleft()
    return len(attempts) >= _MAX_FAILURES


@router.post("/login/local")
async def login_local(
    request: Request,
    username: str = Form(""),
    password: str = Form(""),
    next: str = Form("/"),
    db: Session = Depends(get_db),
):
    ip = request.client.host if request.client else "unknown"
    if _throttled(ip):
        return _login_page(request, db, "Too many failed attempts. Wait 10 minutes and try again.", 429)
    user = db.scalar(select(User).where(User.oidc_issuer == LOCAL_ISSUER, User.oidc_sub == username.strip().lower()))
    if user is None or not verify_password(password, user.password_hash):
        _FAILED[ip].append(time.monotonic())
        return _login_page(request, db, "That username and password don't match.", 401)
    if user.disabled:
        return _login_page(request, db, "This account has been disabled. Ask an admin.", 403)
    _FAILED.pop(ip, None)
    user.last_login_at = utcnow()
    db.commit()
    request.session.clear()
    request.session["user_id"] = user.id
    return RedirectResponse(_safe_next(next), status_code=303)


@router.get("/auth/callback")
async def auth_callback(request: Request, db: Session = Depends(get_db)):
    client = _oidc()
    if client is None:
        return RedirectResponse("/login")
    try:
        token = await client.authorize_access_token(request)
    except OAuthError as e:
        log.warning("OIDC login failed: %s", e)
        request.session["flash"] = [f"Sign-in failed: {e.description or e.error}"]
        return RedirectResponse("/")
    claims = dict(token.get("userinfo") or {})
    try:
        claims.update(await client.userinfo(token=token))
    except Exception:
        pass  # id_token claims are enough
    metadata = await client.load_server_metadata()
    user = _upsert_user(db, metadata.get("issuer", settings.oidc_issuer), claims)
    next_url = request.session.pop("next", "/")
    if user.disabled:
        request.session.clear()
        request.session["flash"] = ["Your account has been disabled. Ask an admin."]
        return RedirectResponse("/")
    request.session["user_id"] = user.id
    request.session["id_token"] = token.get("id_token")
    return RedirectResponse(_safe_next(next_url))


@router.get("/dev-login")
async def dev_login(request: Request, db: Session = Depends(get_db)):
    if not settings.dev_login:
        return RedirectResponse("/")
    name = request.query_params.get("as", "dev")
    user = _upsert_user(
        db, "dev-login", {"sub": name, "preferred_username": name, "name": name.title(), "email": None}
    )
    if user.admin_override is None:
        user.is_admin = True
    db.commit()
    request.session["user_id"] = user.id
    return RedirectResponse("/")


@router.get("/logout")
async def logout(request: Request):
    id_token = request.session.get("id_token")
    request.session.clear()
    client = _oidc()
    if client is not None and id_token:
        try:
            metadata = await client.load_server_metadata()
            end_session = settings.oidc_end_session_url or metadata.get("end_session_endpoint")
            if end_session:
                params = {"post_logout_redirect_uri": f"{settings.base_url}/", "client_id": settings.oidc_client_id,
                          "id_token_hint": id_token}
                return RedirectResponse(f"{end_session}?{urlencode(params)}")
        except Exception:
            log.exception("Could not load OIDC metadata for logout")
    return RedirectResponse("/")
