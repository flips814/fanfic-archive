import pytest
import requests
from sqlalchemy import select

from app import translate
from app.config import settings
from app.db import SessionLocal
from app.models import Chapter, ChapterTranslation, Work, WorkTranslation
from app.translate import find_language, split_html
from tests.conftest import drain, login

LT = "http://lt.test"


class Resp:
    def __init__(self, status_code, data):
        self.status_code, self.data = status_code, data

    def json(self):
        return self.data


class FakeLibreTranslate:
    """Stands in for a LibreTranslate server: "translates" English into Spanish by swapping a few words."""

    def __init__(self):
        self.calls: list[tuple[str, dict | None]] = []
        self.down = False
        self.detected = "en"
        # like the real server, each language lists itself among its targets
        self.langs = [
            {"code": "en", "name": "English", "targets": ["en", "es", "fr", "zh-Hans"]},
            {"code": "es", "name": "Spanish", "targets": ["en", "es", "fr"]},
            {"code": "fr", "name": "French", "targets": ["en", "es", "fr"]},
            {"code": "zh-Hans", "name": "Chinese", "targets": ["en", "zh-Hans"]},
        ]

    def request(self, method, url, timeout=None, json=None, **kwargs):
        if self.down:
            raise requests.ConnectionError("refused")
        path = url.removeprefix(LT)
        self.calls.append((path, json))
        if path == "/languages":
            return Resp(200, self.langs)
        if path == "/detect":
            return Resp(200, [{"language": self.detected, "confidence": 90}])
        if path == "/translate":
            text = json["q"].replace("Text of chapter", "Texto del capítulo").replace("Chapter", "Capítulo")
            if json["format"] == "html":
                text += '<script>alert("x")</script>'
            return Resp(200, {"translatedText": text})
        return Resp(404, {"error": "Not found"})

    def translate_calls(self) -> int:
        return sum(1 for path, _ in self.calls if path == "/translate")


@pytest.fixture
def lt(monkeypatch):
    fake = FakeLibreTranslate()
    monkeypatch.setattr(translate.requests, "request", fake.request)
    monkeypatch.setattr(settings, "translate_url", LT)
    translate._languages_cache.update(url=None, data=[])
    return fake


def _import(fake_site, chapters=2) -> int:
    alice = login("alice")
    alice.post("/add", data={"urls": fake_site.add_work("1", "Translated Fic", chapters=chapters)})
    drain()
    with SessionLocal() as db:
        return db.scalar(select(Work.id))


def _translated(client, url: str, to: str) -> dict:
    """Ask for a translation; the background run has finished by the time TestClient returns."""
    resp = client.post(url, data={"to": to})
    assert resp.status_code == 200, resp.text
    return client.get(url, params={"to": resp.json().get("target", to)}).json()


def test_split_html_rejoins_and_opens_big_wrappers():
    para = "<p>" + "Lorem ipsum dolor sit amet. " * 20 + "</p>\n"
    wrapped = '<div class="userstuff">\n' + para * 10 + "</div>"
    for html in [para * 10, wrapped, "intro <em>x</em><br>" + para * 3 + '<img alt="a>b" src="x">' + para, "", "<p>x"]:
        assert "".join(piece for piece, _ in split_html(html)) == html
    batches = split_html(wrapped)
    assert batches[0] == ('<div class="userstuff">', False) and batches[-1] == ("</div>", False)
    assert len(batches) > 3 and all(len(p) <= 2000 for p, ok in batches if ok)
    assert all(p.count("<p>") == p.count("</p>") for p, ok in batches if ok), "each request is balanced HTML"


def test_language_codes_match_server_variants():
    langs = FakeLibreTranslate().langs
    assert find_language("zh", langs)["code"] == "zh-Hans"
    assert find_language("ES", langs)["code"] == "es"
    assert find_language("pt", langs) is None and find_language("", langs) is None
    assert translate.lang_code("Español") == "es" and translate.lang_code("Klingon") == ""


def test_reader_translates_a_chapter_once_and_shares_it(fake_site, sent, lt):
    work_id = _import(fake_site)
    alice, bob = login("alice"), login("bob", admin=False)
    page = alice.get(f"/works/{work_id}/chapters/1").text
    assert "data-translate" in page and f'data-url="/works/{work_id}/chapters/1/translation"' in page

    langs = alice.get("/translate/languages?source=en").json()
    assert langs["source"] == "en"
    assert [lang["code"] for lang in langs["languages"]] == ["es", "fr", "zh-Hans"]
    assert len(alice.get("/translate/languages").json()["languages"]) == 4, "unknown source: offer everything"

    url = f"/works/{work_id}/chapters/1/translation"
    assert alice.get(url, params={"to": "es"}).json() == {"status": "none"}
    started = alice.post(url, data={"to": "es"}).json()
    assert started["status"] == "running"
    done = alice.get(url, params={"to": "es"}).json()  # the background task has run by now
    assert done["status"] == "done", done
    assert done["title"] == "Capítulo 1"
    assert "Texto del capítulo 1" in done["content_html"]
    assert "<script" not in done["content_html"], "translations are sanitized"
    assert done["source_name"] == "English" and done["target_name"] == "Spanish"
    assert any(path == "/detect" for path, _ in lt.calls), "work has no language, so it was detected"

    calls = lt.translate_calls()
    assert bob.post(url, data={"to": "es"}).json()["status"] == "done"
    assert lt.translate_calls() == calls, "a second reader gets the stored translation"

    # a chapter that changes at the source is translated again
    with SessionLocal() as db:
        db.scalar(select(Chapter).where(Chapter.number == 1)).content_html = "<p>Text of chapter 1, revised</p>"
        db.commit()
    assert alice.get(url, params={"to": "es"}).json() == {"status": "none"}
    assert "revised" in _translated(alice, url, "es")["content_html"]


def test_known_work_language_skips_detection(fake_site, sent, lt):
    work_id = _import(fake_site)
    with SessionLocal() as db:
        db.get(Work, work_id).language = "English"
        db.commit()
    resp = _translated(login("alice"), f"/works/{work_id}/chapters/1/translation", "zh")
    assert resp["status"] == "done" and resp["target"] == "zh-Hans"
    assert not any(path == "/detect" for path, _ in lt.calls)
    sent_to = [body for path, body in lt.calls if path == "/translate"]
    assert all(body["source"] == "en" and body["target"] == "zh-Hans" for body in sent_to)


def test_translation_failures_are_reported(fake_site, sent, lt):
    work_id = _import(fake_site)
    alice = login("alice")
    url = f"/works/{work_id}/chapters/1/translation"

    lt.detected = "es"
    failed = _translated(alice, url, "es")
    assert failed["status"] == "failed" and "already in Spanish" in failed["error"]

    lt.detected = "en"
    assert _translated(alice, url, "es")["status"] == "done", "failed translations can be retried"

    assert alice.post(url, data={"to": "xx"}).status_code == 400
    assert alice.post(f"/works/{work_id}/chapters/9/translation", data={"to": "es"}).status_code == 404

    lt.down = True
    translate._languages_cache.update(url=None, data=[])
    resp = alice.post(f"/works/{work_id}/chapters/2/translation", data={"to": "es"})
    assert resp.status_code == 502 and "couldn't reach the translation server" in resp.json()["detail"]


def test_stale_running_translation_is_restarted(fake_site, sent, lt):
    from datetime import timedelta

    from app.models import utcnow

    work_id = _import(fake_site)
    with SessionLocal() as db:
        chapter = db.scalar(select(Chapter).where(Chapter.number == 1))
        db.add(ChapterTranslation(chapter_id=chapter.id, target_lang="es", status="running", progress=40,
                                  content_hash=translate.content_hash(chapter),
                                  updated_at=utcnow() - timedelta(hours=1)))
        db.commit()
    url = f"/works/{work_id}/chapters/1/translation"
    alice = login("alice")
    assert alice.get(url, params={"to": "es"}).json()["status"] == "failed"
    assert _translated(alice, url, "es")["status"] == "done"


def test_translation_off_hides_everything(fake_site, sent):
    work_id = _import(fake_site)
    alice = login("alice")
    assert "data-translate=" not in alice.get(f"/works/{work_id}/chapters/1").text
    assert "data-translate" not in alice.get(f"/works/{work_id}").text.replace("data-translate-title", "").replace(
        "data-translate-body", "")
    assert alice.get("/translate/languages").status_code == 404
    assert alice.post(f"/works/{work_id}/chapters/1/translation", data={"to": "es"}).status_code == 404
    assert alice.post(f"/works/{work_id}/translation", data={"to": "es"}).status_code == 404


def test_translations_require_login_and_go_with_the_work(fake_site, sent, lt):
    from fastapi.testclient import TestClient

    from app.main import app

    work_id = _import(fake_site)
    url = f"/works/{work_id}/chapters/1/translation"
    assert TestClient(app).post(url, data={"to": "es"}, follow_redirects=False).status_code == 307
    alice = login("alice")
    alice.post(url, data={"to": "es"})
    alice.post("/works/%d/delete" % work_id)
    with SessionLocal() as db:
        assert db.scalar(select(ChapterTranslation.id)) is None


def test_story_page_translates_title_and_summary(fake_site, sent, lt):
    work_id = _import(fake_site)
    with SessionLocal() as db:
        db.get(Work, work_id).title = "Chapter of Text"
        db.commit()
    alice, bob = login("alice"), login("bob", admin=False)
    page = alice.get(f"/works/{work_id}").text
    assert f'data-url="/works/{work_id}/translation"' in page
    assert "data-translate-title" in page and "data-translate-body" in page and "data-translate-status" in page

    url = f"/works/{work_id}/translation"
    assert alice.get(url, params={"to": "es"}).json() == {"status": "none"}
    done = alice.post(url, data={"to": "es"}).json()  # short, so translated right away
    assert done["status"] == "done", done
    assert done["title"] == "Capítulo of Text"
    assert done["content_html"].startswith("<p>Summary</p>") and "<script" not in done["content_html"]
    assert done["source_name"] == "English" and done["target_name"] == "Spanish"

    calls = lt.translate_calls()
    assert bob.get(url, params={"to": "es"}).json()["title"] == "Capítulo of Text"
    assert bob.post(url, data={"to": "es"}).json()["status"] == "done"
    assert lt.translate_calls() == calls, "stored and shared"

    with SessionLocal() as db:
        db.get(Work, work_id).summary_html = "<p>Chapter summary, revised</p>"
        db.commit()
    assert alice.get(url, params={"to": "es"}).json() == {"status": "none"}
    assert "Capítulo summary, revised" in alice.post(url, data={"to": "es"}).json()["content_html"]

    lt.detected = "es"
    with SessionLocal() as db:
        db.get(Work, work_id).title = "Otro título"
        db.commit()
    failed = alice.post(url, data={"to": "es"}).json()
    assert failed["status"] == "failed" and "story is already in Spanish" in failed["error"]
    assert alice.post("/works/999/translation", data={"to": "es"}).status_code == 404

    alice.post(f"/works/{work_id}/delete")
    with SessionLocal() as db:
        assert db.scalar(select(WorkTranslation.id)) is None


def test_admin_translation_settings_and_test_button(lt):
    admin = login("boss")
    page = admin.get("/admin/settings/translation").text
    assert "LibreTranslate URL" in page and "Test translation" in page
    assert "Translation (LibreTranslate)" in admin.get("/admin").text
    result = admin.post("/admin/test/translate", data={"translate_url": LT, "translate_timeout_seconds": "60"}).text
    assert "LibreTranslate is working with 4 languages" in result and "in Spanish" in result
    assert settings.translate_url == LT and settings.translate_timeout_seconds == 60
