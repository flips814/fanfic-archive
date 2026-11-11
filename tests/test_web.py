from fastapi.testclient import TestClient
from sqlalchemy import select

from app.db import SessionLocal
from app.importers.tumblr import parse_tumblr_url
from app.main import app
from app.models import Work
from app.notify import discord_to_apprise
from app.sanitize import clean_html, count_words
from tests.conftest import drain, login


def test_pages_require_login():
    client = TestClient(app)
    landing = client.get("/")
    assert landing.status_code == 200 and 'href="/login"' in landing.text
    for path in ["/add", "/activity", "/users", "/settings", "/works/1"]:
        resp = client.get(path, follow_redirects=False)
        assert resp.status_code == 307, path
        assert resp.headers["location"].startswith("/login")
    assert client.get("/api/works").status_code == 401
    assert client.get("/healthz").json() == {"ok": True}


def test_all_pages_render(fake_site, sent):
    alice = login("alice")
    alice.post("/add", data={"urls": fake_site.add_work("1", "Rendered Fic", chapters=2)})
    drain()
    with SessionLocal() as db:
        work_id = db.scalar(select(Work.id))
    for path in [
        "/", "/?q=rendered&site=fake.test&status=ongoing&mine=1", "/?added_by=&site=",
        "/add", f"/works/{work_id}", f"/works/{work_id}/chapters/1", f"/works/{work_id}/chapters/2",
        "/activity", "/activity?user_id=0", "/activity?user_id=&kind=import&status=done",
        "/users", "/settings",
    ]:
        resp = alice.get(path)
        assert resp.status_code == 200, path
    assert "Rendered Fic" in alice.get("/").text
    assert "Text of chapter 2" in alice.get(f"/works/{work_id}/chapters/2").text
    assert alice.get(f"/works/{work_id}/chapters/99").status_code == 404


def test_epub_download(fake_site, sent):
    alice = login("alice")
    alice.post("/add", data={"urls": fake_site.add_work("1", "Epub Fic", chapters=2)})
    drain()
    resp = alice.get("/works/1/epub")
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "application/epub+zip"
    assert resp.content[:2] == b"PK"


def test_unsupported_urls_are_rejected(fake_site, sent):
    alice = login("alice")
    alice.post("/add", data={"urls": "https://example.com/not-a-fic"}, follow_redirects=False)
    assert "Unsupported URL skipped" in alice.get("/activity").text
    body = alice.post("/api/imports", json={"urls": ["https://example.com/x"]}).json()
    assert body == {"jobs": [], "rejected": ["https://example.com/x"]}


def test_api(fake_site, sent):
    alice = login("alice")
    url = fake_site.add_work("1", "Api Fic", chapters=2)
    body = alice.post("/api/imports", json={"urls": [url]}).json()
    assert body["jobs"][0]["status"] == "queued"
    drain()
    works = alice.get("/api/works").json()
    assert works[0]["title"] == "Api Fic" and works[0]["added_by"]["name"] == "Alice"
    detail = alice.get(f"/api/works/{works[0]['id']}").json()
    assert [c["imported_by"]["name"] for c in detail["chapter_list"]] == ["Alice", "Alice"]
    assert alice.get("/api/jobs").json()[0]["requested_by"]["name"] == "Alice"
    assert alice.get("/api/me").json()["name"] == "Alice"


def test_only_owner_or_admin_can_delete(fake_site, sent):
    alice, bob = login("alice", admin=False), login("bob", admin=False)
    alice.post("/add", data={"urls": fake_site.add_work("1", "Mine", chapters=1)})
    drain()
    assert bob.post("/works/1/delete").status_code == 403
    assert bob.post("/works/1/auto-update").status_code == 403
    assert alice.post("/works/1/delete", follow_redirects=False).status_code == 303
    with SessionLocal() as db:
        assert db.query(Work).count() == 0


def test_login_redirect_rejects_open_redirect():
    client = TestClient(app)
    resp = client.get("/login?next=//evil.example", follow_redirects=False)
    assert resp.headers["location"] == "/dev-login"


def test_tumblr_url_parsing():
    assert parse_tumblr_url("https://foo.tumblr.com/post/123/slug") == ("foo", "post", "123")
    assert parse_tumblr_url("https://www.tumblr.com/foo/123/slug") == ("foo", "post", "123")
    assert parse_tumblr_url("https://foo.tumblr.com/tagged/my-fic") == ("foo", "tag", "my-fic")
    assert parse_tumblr_url("https://www.tumblr.com/foo/tagged/my%20fic") == ("foo", "tag", "my fic")
    assert parse_tumblr_url("https://www.tumblr.com/tagged/x") is None
    assert parse_tumblr_url("https://example.com/post/1") is None


def test_tumblr_v1_post_parsing(monkeypatch):
    from app.importers import tumblr

    pages = {
        None: {"posts-total": 2, "posts": [
            {"id": "2", "type": "photo", "unix-timestamp": 200, "photo-url-1280": "https://x/p.jpg",
             "photo-caption": "<p>Part two</p>", "tags": ["fic"], "url-with-slug": "https://b.tumblr.com/post/2"},
            {"id": "1", "type": "regular", "unix-timestamp": 100, "regular-title": "",
             "regular-body": '<p><a class="tumblr_blog" href="#">someone</a>:</p><blockquote><p>Once upon a time</p></blockquote>',
             "tags": ["fic", "fluff"], "url-with-slug": "https://b.tumblr.com/post/1"},
        ]},
    }

    class Resp:
        status_code = 200

        def __init__(self, data):
            self.text = "var tumblr_api_read = " + __import__("json").dumps(data) + ";"

        def raise_for_status(self):
            pass

    monkeypatch.setattr(tumblr.requests, "get", lambda url, params, **kw: Resp(pages[None]))
    source = tumblr.TumblrSource("https://b.tumblr.com/tagged/fic")
    assert [c.key for c in source.meta.chapters] == ["1", "2"], "oldest post first"
    assert source.meta.chapters[0].title == "Once upon a time"
    assert source.meta.chapters[1].title == "Part two"
    assert '<img src="https://x/p.jpg"' in source.fetch_chapter(1, source.meta.chapters[1])
    assert source.meta.tags == ["fic", "fluff"]


def test_flaresolverr_only_for_listed_sites(monkeypatch):
    from dataclasses import replace

    from app.importers import fanficfare_importer as ffi

    monkeypatch.setattr(ffi, "settings", replace(ffi.settings, flaresolverr_url="http://flaresolverr:8191"))
    ffn = ffi._configuration("https://www.fanfiction.net/s/1234567/1/")
    assert ffn.get("overrides", "use_flaresolverr_proxy") == "true"
    assert ffn.get("overrides", "flaresolverr_proxy_address") == "flaresolverr"
    assert ffn.get("overrides", "flaresolverr_proxy_port") == "8191"
    ao3 = ffi._configuration("https://archiveofourown.org/works/1")
    assert not ao3.has_option("overrides", "use_flaresolverr_proxy")

    monkeypatch.setattr(ffi, "settings", replace(ffi.settings, flaresolverr_url=""))
    assert not ffi._configuration("https://www.fanfiction.net/s/1/1/").has_option("overrides", "use_flaresolverr_proxy")


def test_accessibility_landmarks(fake_site, sent):
    alice = login("alice")
    alice.post("/add", data={"urls": fake_site.add_work("1", "A11y Fic", chapters=2)})
    drain()
    for path in ["/", "/works/1", "/works/1/chapters/1", "/activity", "/users", "/settings", "/add"]:
        page = alice.get(path).text
        assert 'class="skip-link" href="#main"' in page, path
        assert 'id="main"' in page, path
        assert 'aria-current="page"' in page, path
        assert page.count("<h1") == 1, f"{path} should have exactly one h1"
    assert 'role="status" aria-live="polite"' in alice.get("/").text
    assert '<th scope="col" role="columnheader">' in alice.get("/activity").text
    assert 'data-nav="next"' in alice.get("/works/1/chapters/1").text


def test_sanitize_and_helpers():
    cleaned = clean_html('<p align="center" onclick="x()">Hi <script>bad()</script><em>there</em></p>')
    assert "<script>" not in cleaned and "onclick" not in cleaned
    assert 'align="center"' in cleaned and "<em>there</em>" in cleaned
    assert count_words("<p>It's a well-known fact.</p>") == 4
    assert discord_to_apprise("https://discord.com/api/webhooks/123/abc-DEF") == "discord://123/abc-DEF/"
    assert discord_to_apprise("tgram://x/y") == "tgram://x/y"
