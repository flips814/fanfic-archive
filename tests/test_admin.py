import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from app import main, settings_store
from app.auth import _upsert_user
from app.config import settings
from app.db import SessionLocal
from app.importers import fanficfare_importer
from app.models import AppSetting, ImportJob, User
from app.passwords import hash_password
from tests.conftest import drain, login, user_id


def _local_user(username, password, admin=False, disabled=False):
    with SessionLocal() as db:
        db.add(User(oidc_issuer="local", oidc_sub=username, username=username, display_name=username.title(),
                    password_hash=hash_password(password), is_admin=admin, admin_override=admin, disabled=disabled))
        db.commit()


def _local_login(username, password):
    client = TestClient(main.app)
    resp = client.post("/login/local", data={"username": username, "password": password, "next": "/add"},
                       follow_redirects=False)
    return client, resp


# ------------------------------------------------------------------ first launch

@pytest.fixture
def fresh_install(monkeypatch):
    with SessionLocal() as db:
        settings_store.delete_state(db, settings_store.SETUP_COMPLETE)
        db.commit()
    monkeypatch.setattr(main, "_setup_done", False)


def test_first_launch_sends_everything_to_setup(fresh_install):
    client = TestClient(main.app)
    for path in ["/", "/add", "/login", "/admin"]:
        resp = client.get(path, follow_redirects=False)
        assert resp.status_code == 307 and resp.headers["location"] == "/setup", path
    assert client.get("/api/works").status_code == 503
    page = client.get("/setup")
    assert page.status_code == 200 and "Setup code" in page.text
    assert client.get("/healthz").status_code == 200


def test_setup_requires_code_and_creates_admin(fresh_install):
    client = TestClient(main.app)
    client.get("/setup")
    with SessionLocal() as db:
        code = settings_store.get_state(db, settings_store.SETUP_CODE)
    form = dict(mode="local", site_name="Our Fics", base_url="https://fics.example.com", username="Jake",
                display_name="Jake", password="correct horse", password_confirm="correct horse")

    bad = client.post("/setup", data={**form, "code": "WRONG"})
    assert bad.status_code == 400 and "setup code doesn" in bad.text
    short = client.post("/setup", data={**form, "code": code, "password": "short", "password_confirm": "short"})
    assert short.status_code == 400 and "at least 8 characters" in short.text

    done = client.post("/setup", data={**form, "code": code.lower()}, follow_redirects=False)
    assert done.status_code == 303 and done.headers["location"] == "/admin"
    assert settings.site_name == "Our Fics" and settings.base_url == "https://fics.example.com"
    assert "Setup checklist" in client.get("/admin").text

    with SessionLocal() as db:
        jake = db.scalar(select(User).where(User.oidc_sub == "jake"))
        assert jake.is_admin and jake.is_local and jake.password_hash != "correct horse"
        assert settings_store.get_state(db, settings_store.SETUP_CODE) is None
    assert client.get("/setup", follow_redirects=False).headers["location"] == "/", "setup can't be re-run"


# ------------------------------------------------------------------ local sign-in

def test_local_login_success_failure_and_disabled():
    _local_user("ann", "password123")
    _local_user("ben", "password123", disabled=True)
    client, resp = _local_login("ann", "password123")
    assert resp.status_code == 303 and resp.headers["location"] == "/add"
    assert client.get("/add").status_code == 200

    _, wrong = _local_login("ann", "nope-nope")
    assert wrong.status_code == 401 and "password don&#39;t match" in wrong.text
    _, off = _local_login("ben", "password123")
    assert off.status_code == 403 and "disabled" in off.text


def test_login_page_offers_password_form_when_local_accounts_exist():
    _local_user("ann", "password123")
    page = TestClient(main.app).get("/login")
    assert page.status_code == 200 and 'action="/login/local"' in page.text


def test_password_guessing_is_throttled():
    _local_user("ann", "password123")
    for _ in range(10):
        _local_login("ann", "wrong-password")
    _, blocked = _local_login("ann", "password123")
    assert blocked.status_code == 429


def test_disabling_signs_user_out():
    admin = login("boss")
    _local_user("ann", "password123")
    ann, _ = _local_login("ann", "password123")
    assert ann.get("/add").status_code == 200
    admin.post(f"/admin/users/{user_id('ann')}/disable")
    assert ann.get("/add", follow_redirects=False).status_code == 307


# ------------------------------------------------------------------ permissions

def test_admin_pages_are_admin_only():
    member = login("member", admin=False)
    for path in ["/admin", "/admin/users", "/admin/settings/general", "/admin/settings/signin"]:
        assert member.get(path).status_code == 403, path
    assert member.post("/admin/settings/general", data={"site_name": "Pwned"}).status_code == 403
    assert "/admin" not in member.get("/").text.split("<main")[0], "no Admin link for members"
    admin = login("boss")
    for path in ["/admin", "/admin/users", "/admin/settings/general", "/admin/settings/signin",
                 "/admin/settings/email", "/admin/settings/notifications", "/admin/settings/updates",
                 "/admin/settings/importers"]:
        assert admin.get(path).status_code == 200, path


# ------------------------------------------------------------------ settings

def test_settings_save_apply_and_persist():
    admin = login("boss")
    admin.post("/admin/settings/updates", data={"update_interval_minutes": "30",
                                                "completed_update_interval_minutes": "0",
                                                "delay_between_works_seconds": "2", "worker_poll_seconds": "5"})
    assert settings.update_interval_minutes == 30 and settings.completed_update_interval_minutes == 0

    settings.apply({})  # simulate another process starting fresh...
    assert settings.update_interval_minutes == 60
    with SessionLocal() as db:
        settings_store.load(db)  # ...and loading from the database
    assert settings.update_interval_minutes == 30


def test_invalid_settings_are_rejected():
    admin = login("boss")
    page = admin.post("/admin/settings/general", data={"site_name": "X", "base_url": "not a url"}).text
    assert "Not saved" in page and "must start with http" in page
    assert settings.site_name != "X"


def test_secrets_are_encrypted_kept_when_blank_and_clearable():
    admin = login("boss")
    form = {"smtp_host": "smtp.test", "smtp_port": "587", "smtp_user": "u", "smtp_from": "a@b.c",
            "smtp_starttls": "1"}
    admin.post("/admin/settings/email", data={**form, "smtp_password": "hunter2"})
    assert settings.smtp_password == "hunter2" and settings.email_enabled
    with SessionLocal() as db:
        assert "hunter2" not in db.get(AppSetting, "smtp_password").value
    page = admin.get("/admin/settings/email").text
    assert "hunter2" not in page and "Saved; leave blank to keep it" in page

    admin.post("/admin/settings/email", data={**form, "smtp_password": ""})
    assert settings.smtp_password == "hunter2", "blank keeps the saved secret"
    admin.post("/admin/settings/email", data={**form, "smtp_password__clear": "1"})
    assert settings.smtp_password == ""
    assert settings.smtp_starttls is True
    admin.post("/admin/settings/email", data={k: v for k, v in form.items() if k != "smtp_starttls"})
    assert settings.smtp_starttls is False, "unchecked switch saves False"


def test_reset_returns_to_environment_values():
    admin = login("boss")
    admin.post("/admin/settings/general", data={"site_name": "Renamed", "base_url": "https://x.example"})
    assert settings.site_name == "Renamed"
    admin.post("/admin/settings/general", data={"reset": "1"})
    assert settings.site_name == "Fanfic Archive" and settings.base_url == "http://testserver"


def test_signin_settings_enable_sso_button():
    admin = login("boss")
    admin.post("/admin/settings/signin", data={
        "oidc_provider_name": "Authentik", "oidc_issuer": "https://auth.example.com/app/", "oidc_client_id": "abc",
        "oidc_client_secret": "s3cret", "oidc_scopes": "openid email profile", "admin_emails": "Me@Example.com",
        "oidc_admin_group": "", "oidc_groups_claim": "groups", "oidc_discovery_url": "",
        "oidc_authorize_url": "", "oidc_end_session_url": ""})
    assert settings.oidc_enabled and settings.oidc_issuer == "https://auth.example.com/app"
    assert settings.admin_emails == ["me@example.com"]
    _local_user("ann", "password123")
    page = TestClient(main.app).get("/login").text
    assert "Sign in with Authentik" in page and 'action="/login/local"' in page
    assert "/auth/callback" in admin.get("/admin/settings/signin").text


def test_test_buttons_save_first_and_report(monkeypatch):
    admin = login("boss")
    calls = []

    class Resp:
        def raise_for_status(self):
            pass

        def json(self):
            return {"issuer": "https://idp.test", "authorization_endpoint": "a", "token_endpoint": "t", "jwks_uri": "j"}

    monkeypatch.setattr("app.admin.requests.get", lambda url, timeout: calls.append(url) or Resp())
    page = admin.post("/admin/test/oidc", data={
        "oidc_provider_name": "IdP", "oidc_issuer": "https://idp.test", "oidc_client_id": "cid",
        "oidc_scopes": "openid", "admin_emails": "", "oidc_admin_group": ""}).text
    assert settings.oidc_issuer == "https://idp.test", "typed values were saved before testing"
    assert calls == ["https://idp.test/.well-known/openid-configuration"]
    assert "Connected to https://idp.test" in page

    page = admin.post("/admin/test/email", data={"smtp_host": "", "smtp_port": "587", "smtp_from": ""}).text
    assert "Test failed" in page and "SMTP server" in page


def test_fanficfare_ini_from_admin_is_applied():
    admin = login("boss")
    admin.post("/admin/settings/importers", data={
        "flaresolverr_url": "", "flaresolverr_sites": "www.fanfiction.net", "flaresolverr_timeout_ms": "59000",
        "fanficfare_ini": "[archiveofourown.org]\nusername:fallback_user\n"})
    config = fanficfare_importer._configuration("https://archiveofourown.org/works/1")
    assert config.get("archiveofourown.org", "username") == "fallback_user"
    assert settings.fanficfare_is_adult is False, "switch left unchecked"


# ------------------------------------------------------------------ users

def test_role_changes_and_last_admin_protection():
    boss = login("boss")
    login("member", admin=False)
    boss.post(f"/admin/users/{user_id('member')}/role")
    with SessionLocal() as db:
        member = db.get(User, user_id("member"))
        assert member.is_admin and member.admin_override is True
        # an SSO re-login must not undo an admin's decision
        _upsert_user(db, "dev-login", {"sub": "member"})
        assert db.get(User, user_id("member")).is_admin

    boss.post(f"/admin/users/{user_id('member')}/role")
    page = boss.post(f"/admin/users/{user_id('boss')}/role").text
    with SessionLocal() as db:
        assert db.get(User, user_id("boss")).is_admin, "can't remove the last admin"
    assert "remove the last admin" in page
    assert "can&#39;t disable your own" in boss.post(f"/admin/users/{user_id('boss')}/disable").text


def test_create_local_user_and_reset_password():
    boss = login("boss")
    boss.post("/admin/users/create", data={"username": "Kid", "password": "kidpassword", "display_name": "Kiddo"})
    _, ok = _local_login("kid", "kidpassword")
    assert ok.status_code == 303
    assert "already an account" in boss.post("/admin/users/create",
                                             data={"username": "kid", "password": "whatever123"}).text
    boss.post(f"/admin/users/{user_id('kid')}/password", data={"password": "newpassword"})
    _, old = _local_login("kid", "kidpassword")
    _, new = _local_login("kid", "newpassword")
    assert old.status_code == 401 and new.status_code == 303


# ------------------------------------------------------------------ jobs

def test_retry_cancel_and_check_all(fake_site, sent):
    boss = login("boss")
    fake_site.fail["9"] = "Site is down"
    boss.post("/add", data={"urls": "https://fake.test/works/9"})
    drain()
    with SessionLocal() as db:
        failed = db.scalar(select(ImportJob).where(ImportJob.status == "failed"))
    assert "Site is down" in boss.get("/admin").text

    del fake_site.fail["9"]
    fake_site.add_work("9", "Back Online", chapters=1)
    boss.post(f"/admin/jobs/{failed.id}/retry")
    drain()
    with SessionLocal() as db:
        assert db.scalar(select(ImportJob).order_by(ImportJob.id.desc())).status == "done"

    boss.post("/admin/jobs/check-all")
    with SessionLocal() as db:
        queued = db.scalar(select(ImportJob).where(ImportJob.status == "queued"))
        assert queued and queued.kind == "update"
    boss.post(f"/admin/jobs/{queued.id}/cancel")
    with SessionLocal() as db:
        cancelled = db.get(ImportJob, queued.id)
        assert cancelled.status == "failed" and "Cancelled by Boss" in cancelled.message
