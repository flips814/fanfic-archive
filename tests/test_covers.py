"""Work cover storage and serving."""

from sqlalchemy import select

from app import covers
from app.db import SessionLocal
from app.importers.base import WorkMeta
from app.models import Work
from tests.conftest import drain, login


def test_save_and_load_cover(tmp_path, monkeypatch):
    monkeypatch.setattr(covers.settings, "covers_dir", str(tmp_path))
    assert covers.has_cover(7) is False
    name = covers.save_cover(7, b"\xff\xd8\xfffakejpeg", ext="jpg", source_url="https://example.test/c.jpg")
    assert name == "7.jpg"
    assert covers.has_cover(7) is True
    path = covers.cover_path(7)
    assert path is not None and path.read_bytes().startswith(b"\xff\xd8")
    assert covers.cover_url_path(7) == "/works/7/cover"
    covers.delete_cover(7)
    assert covers.has_cover(7) is False


def test_save_cover_replaces_previous_extension(tmp_path, monkeypatch):
    monkeypatch.setattr(covers.settings, "covers_dir", str(tmp_path))
    covers.save_cover(3, b"pngdata", ext="png")
    covers.save_cover(3, b"jpgdata", ext="jpg")
    assert list(tmp_path.glob("3.*")) == [tmp_path / "3.jpg"]


def test_extract_cover_from_fff_img_store():
    class ImgStore:
        cover = {"url": "https://ex/c.jpg", "data": b"bytes", "ext": "jpg", "mime": "image/jpeg"}

        def get_imgs(self):
            return [self.cover]

    class Story:
        img_store = ImgStore()
        cover = "images/cover.jpg"

    url, data, ext, mime = covers.extract_cover_from_fff_story(Story())
    assert url == "https://ex/c.jpg"
    assert data == b"bytes"
    assert ext == "jpg"
    assert mime == "image/jpeg"


def test_fff_configuration_pulls_covers_only():
    from app.importers import fanficfare_importer as ffi

    config = ffi._configuration("https://www.wattpad.com/story/1")
    assert config.get("overrides", "include_images") == "coveronly"
    assert config.get("overrides", "never_make_cover") == "false"
    assert config.get("overrides", "make_firstimage_cover") == "false"
    assert config.get("overrides", "include_chapter_banner_images") == "false"


def test_wattpad_cover_url_upgrades_thumbnail(monkeypatch):
    class Resp:
        def raise_for_status(self):
            return None

        def json(self):
            return {"cover": "https://img.wattpad.com/cover/9-256-k.jpg"}

    monkeypatch.setattr(covers.requests, "get", lambda *a, **k: Resp())
    assert covers.wattpad_cover_url("9") == "https://img.wattpad.com/cover/9-512-k.jpg"
    assert covers.wattpad_cover_url("") is None


def test_fetch_wattpad_cover_skips_when_missing(monkeypatch):
    class Resp:
        def raise_for_status(self):
            return None

        def json(self):
            return {"cover": ""}

    monkeypatch.setattr(covers.requests, "get", lambda *a, **k: Resp())
    assert covers.fetch_wattpad_cover("9") == (None, None, None, None)


def test_fetch_wattpad_cover_downloads_when_present(monkeypatch):
    calls = []

    class ApiResp:
        def raise_for_status(self):
            return None

        def json(self):
            return {"cover": "https://img.wattpad.com/cover/9-256-k.jpg"}

    class ImgResp:
        headers = {"Content-Type": "image/jpeg"}
        content = b"\xff\xd8\xffcover"

        def raise_for_status(self):
            return None

    def fake_get(url, *a, **k):
        calls.append(url)
        if "api/v3/stories" in url:
            return ApiResp()
        return ImgResp()

    monkeypatch.setattr(covers.requests, "get", fake_get)
    url, data, ext, mime = covers.fetch_wattpad_cover("9")
    assert url == "https://img.wattpad.com/cover/9-512-k.jpg"
    assert data == b"\xff\xd8\xffcover"
    assert ext == "jpg"
    assert mime == "image/jpeg"
    assert any("api/v3/stories/9" in u for u in calls)


def test_persist_meta_cover_and_http(tmp_path, monkeypatch, fake_site, sent):
    monkeypatch.setattr(covers.settings, "covers_dir", str(tmp_path))
    meta = WorkMeta(
        site="fake.test",
        url="https://fake.test/works/1",
        title="Covered",
        cover_data=b"\x89PNGfakecover",
        cover_ext="png",
        cover_mime="image/png",
    )
    assert covers.persist_meta_cover(42, meta) == "42.png"
    assert (tmp_path / "42.png").read_bytes().startswith(b"\x89PNG")

    alice = login("alice")
    url = fake_site.add_work("9", "With Cover", chapters=1)
    # Inject cover after import by saving against the created work id.
    alice.post("/add", data={"urls": url, "visibility": "public"})
    drain()
    with SessionLocal() as db:
        work_id = db.scalars(select(Work)).one().id
    covers.save_cover(work_id, b"\xff\xd8\xffcover", ext="jpg")

    listing = alice.get("/api/works").json()["works"]
    match = next(w for w in listing if w["id"] == work_id)
    assert match["cover_url"] == f"/works/{work_id}/cover"

    resp = alice.get(f"/works/{work_id}/cover")
    assert resp.status_code == 200
    assert resp.content.startswith(b"\xff\xd8")
    assert "With Cover" in alice.get(f"/works/{work_id}").text
    assert f"/works/{work_id}/cover" in alice.get("/").text
