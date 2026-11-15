"""Mobile JSON API: info, login, chapters, follow, filtered listing."""

from fastapi.testclient import TestClient

from app.main import app
from app.models import User
from app.passwords import hash_password
from app.db import SessionLocal
from tests.conftest import drain, login


def _local_user(username, password, admin=False, disabled=False):
    with SessionLocal() as db:
        db.add(
            User(
                oidc_issuer="local",
                oidc_sub=username,
                username=username,
                display_name=username.title(),
                password_hash=hash_password(password),
                is_admin=admin,
                admin_override=admin,
                disabled=disabled,
            )
        )
        db.commit()


def test_info_is_public_and_describes_auth():
    info = TestClient(app).get("/api/info").json()
    assert info["api_version"] == 1
    assert "site_name" in info
    assert "base_url" in info and info["base_url"]
    assert info["auth"]["local"] is False
    assert info["auth"]["oidc"] is False
    assert info["auth"]["oidc_provider_name"] is None
    assert info["features"]["epub"] is True

    _local_user("ann", "password123")
    assert TestClient(app).get("/api/info").json()["auth"]["local"] is True


def test_api_login_logout_and_me():
    _local_user("ann", "password123")
    client = TestClient(app)

    bad = client.post("/api/login", json={"username": "ann", "password": "wrong"})
    assert bad.status_code == 401

    ok = client.post("/api/login", json={"username": "ann", "password": "password123"})
    assert ok.status_code == 200
    assert ok.json()["name"] == "Ann"
    assert client.get("/api/me").json()["name"] == "Ann"

    assert client.post("/api/logout").json() == {"ok": True}
    assert client.get("/api/me").status_code == 401


def test_api_login_rejects_disabled_and_missing_local_auth():
    client = TestClient(app)
    none = client.post("/api/login", json={"username": "x", "password": "y"})
    assert none.status_code == 400

    _local_user("ben", "password123", disabled=True)
    disabled = client.post("/api/login", json={"username": "ben", "password": "password123"})
    assert disabled.status_code == 403


def test_chapter_content_follow_and_filters(fake_site, sent):
    alice = login("alice")
    bob = login("bob", admin=False)
    url = fake_site.add_work("1", "Mobile Fic", chapters=2)
    alice.post("/api/imports", json={"urls": [url]})
    drain()

    work_id = alice.get("/api/works").json()["works"][0]["id"]
    detail = alice.get(f"/api/works/{work_id}").json()
    assert detail["is_following"] is True
    assert detail["chapter_list"][0]["id"]
    assert detail["chapter_list"][0]["number"] == 1

    chapter = alice.get(f"/api/works/{work_id}/chapters/1").json()
    assert "<p>Text of chapter 1</p>" in chapter["content_html"]
    assert chapter["prev_number"] is None
    assert chapter["next_number"] == 2
    assert chapter["work_title"] == "Mobile Fic"

    ch2 = alice.get(f"/api/works/{work_id}/chapters/2").json()
    assert ch2["prev_number"] == 1 and ch2["next_number"] is None
    assert alice.get(f"/api/works/{work_id}/chapters/99").status_code == 404

    assert bob.get(f"/api/works/{work_id}").json()["is_following"] is False
    assert bob.post(f"/api/works/{work_id}/follow").json()["is_following"] is True
    assert bob.get(f"/api/works/{work_id}").json()["is_following"] is True
    assert bob.post(f"/api/works/{work_id}/unfollow").json()["is_following"] is False

    listing = alice.get("/api/works", params={"q": "Mobile"}).json()
    assert listing["total"] == 1
    empty = alice.get("/api/works", params={"q": "zzzz"}).json()
    assert empty["total"] == 0 and empty["works"] == []

    mine = alice.get("/api/works", params={"mine": "1"}).json()
    assert mine["total"] == 1
    bob_mine = bob.get("/api/works", params={"mine": "1"}).json()
    assert bob_mine["total"] == 0

    bob.post(f"/api/works/{work_id}/follow")
    followed = bob.get("/api/works", params={"followed": "1"}).json()
    assert followed["total"] == 1

    job = alice.post(f"/api/works/{work_id}/check").json()
    assert job["kind"] == "update" and job["status"] == "queued"

    notes = alice.get("/api/notifications").json()["notifications"]
    kinds = {n["kind"] for n in notes}
    assert "story_added" in kinds
    assert "following" in kinds
    assert any(n["work_id"] == work_id for n in notes)
    assert TestClient(app).get("/api/notifications").status_code == 401
