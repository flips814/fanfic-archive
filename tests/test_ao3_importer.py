"""Unit tests for the dedicated AO3 importer (ao3-api + HTML download)."""

from datetime import date
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest
from bs4 import BeautifulSoup

from app.importers import IMPORTERS, open_work
from app.importers.ao3 import (
    Ao3Importer,
    _html_from_chapter_soup,
    _parse_download_html,
    work_id_from_url,
)
from app.importers.base import ImportError_


FULL_WORK_HTML = """
<html><body>
<div class="preface group">
  <h2 class="title heading">Test Work</h2>
  <h3 class="byline heading"><a rel="author" href="/users/alice/pseuds/alice">alice</a></h3>
  <blockquote class="userstuff summary"><p>A <em>short</em> summary.</p></blockquote>
</div>
<dl class="work meta group">
  <dd class="rating tags"><ul class="commas"><li><a class="tag">Teen And Up Audiences</a></li></ul></dd>
  <dd class="warning tags"><ul class="commas"><li><a class="tag">No Archive Warnings Apply</a></li></ul></dd>
  <dd class="fandom tags"><ul class="commas"><li><a class="tag">Original Work</a></li></ul></dd>
  <dd class="relationship tags"><ul class="commas"><li><a class="tag">A/B</a></li></ul></dd>
  <dd class="character tags"><ul class="commas"><li><a class="tag">Alice</a></li></ul></dd>
  <dd class="freeform tags"><ul class="commas"><li><a class="tag">Fluff</a></li></ul></dd>
  <dd class="language">English</dd>
  <dd class="stats">
    <dl class="stats">
      <dd class="published">2020-01-15</dd>
      <dd class="status">2021-06-01</dd>
      <dd class="words">1,234</dd>
      <dd class="chapters">2/2</dd>
    </dl>
  </dd>
</dl>
<ul class="work navigation actions">
  <li class="download">
    <a>Download</a>
    <ul>
      <li><a href="/downloads/999/Test%20Work.html?updated_at=1622505600">HTML</a></li>
      <li><a href="/downloads/999/Test%20Work.epub?updated_at=1622505600">EPUB</a></li>
    </ul>
  </li>
</ul>
<div id="chapters">
  <div id="chapter-1" class="chapter">
    <div class="chapter preface group">
      <h3 class="title"><a href="/works/999/chapters/111">1. First</a></h3>
    </div>
    <div class="userstuff module" role="article">
      <h3 class="landmark heading">Chapter Text</h3>
      <p>Hello <strong>one</strong>.</p>
    </div>
  </div>
  <div id="chapter-2" class="chapter">
    <div class="chapter preface group">
      <h3 class="title"><a href="/works/999/chapters/222">2. Second</a></h3>
    </div>
    <div class="userstuff module" role="article">
      <h3 class="landmark heading">Chapter Text</h3>
      <p>Hello two.</p>
    </div>
  </div>
</div>
</body></html>
"""

DOWNLOAD_HTML = b"""<!DOCTYPE html><html><body>
<div id="chapters">
  <h2 class="toc-heading">1. First</h2>
  <div class="userstuff"><p>Download chapter one.</p></div>
  <h2 class="toc-heading">2. Second</h2>
  <div class="userstuff"><p>Download chapter two.</p></div>
</div>
</body></html>"""

ONESHOT_DOWNLOAD_HTML = b"""<!DOCTYPE html><html><body>
<div id="chapters" class="userstuff"><p>Only chapter.</p></div>
</body></html>"""


def test_work_id_from_url():
    assert work_id_from_url("https://archiveofourown.org/works/204271") == 204271
    assert work_id_from_url("https://archiveofourown.org/works/204271/chapters/123") == 204271
    assert work_id_from_url("https://www.archiveofourown.org/works/204271?view_adult=true") == 204271
    assert work_id_from_url("https://archiveofourown.org/tags/Fluff/works") is None
    assert work_id_from_url("https://example.com/works/1") is None


def test_ao3_importer_matches_before_fanficfare():
    importer = Ao3Importer()
    assert importer.matches("https://archiveofourown.org/works/204271")
    assert importer.matches("https://www.archiveofourown.org/works/204271/chapters/1")
    assert not importer.matches("https://archiveofourown.org/users/alice")
    assert not importer.matches("https://www.fanfiction.net/s/1/1/")
    assert IMPORTERS[1].name == "ao3"
    assert any(i.name == "fanficfare" for i in IMPORTERS)


def test_parse_download_html_multi_and_oneshot():
    bodies = _parse_download_html(DOWNLOAD_HTML)
    assert bodies == ["<p>Download chapter one.</p>", "<p>Download chapter two.</p>"]
    assert _parse_download_html(ONESHOT_DOWNLOAD_HTML) == ["<p>Only chapter.</p>"]


def test_html_from_chapter_soup_strips_landmarks():
    soup = BeautifulSoup(FULL_WORK_HTML, "lxml")
    chapter_div = soup.find(id="chapter-1")
    chapter = SimpleNamespace(id=111, _soup=chapter_div)
    html = _html_from_chapter_soup(chapter)
    assert "Hello <strong>one</strong>." in html
    assert "landmark" not in html
    assert "Chapter Text" not in html


def _fake_work(soup_html: str = FULL_WORK_HTML):
    """Build a Work shell with real soup parsing; caller patches AO3.Work / _load_work to return it."""
    from AO3.works import Work as RealWork

    work = RealWork(999, load=False)
    work._soup = BeautifulSoup(soup_html, "lxml")
    work.load_chapters()
    assert len(work.chapters) == 2
    return work


@patch("app.importers.ao3._download_html_bytes", return_value=DOWNLOAD_HTML)
@patch("app.importers.ao3._load_work")
def test_open_prefers_html_download(mock_load, mock_download):
    mock_load.return_value = _fake_work()

    source = open_work("https://archiveofourown.org/works/999")
    assert source.meta.title == "Test Work"
    assert source.meta.author == "alice"
    assert source.meta.source_id == "999"
    assert source.meta.site == "archiveofourown.org"
    assert source.meta.status == "Completed"
    assert source.meta.language == "English"
    assert source.meta.word_count == 1234
    assert source.meta.fandoms == ["Original Work"]
    assert "Fluff" in source.meta.tags
    assert "<em>short</em>" in (source.meta.summary_html or "")
    assert [c.title for c in source.meta.chapters] == ["First", "Second"]
    assert source.meta.chapters[0].key.endswith("/chapters/111")
    assert source.fetch_chapter(0, source.meta.chapters[0]) == "<p>Download chapter one.</p>"
    assert source.fetch_chapter(1, source.meta.chapters[1]) == "<p>Download chapter two.</p>"
    mock_download.assert_called_once()


@patch("app.importers.ao3._download_html_bytes", side_effect=ImportError_("nope"))
@patch("app.importers.ao3._load_work")
def test_open_falls_back_to_full_work_page_html(mock_load, _mock_download):
    mock_load.return_value = _fake_work()

    source = open_work("https://archiveofourown.org/works/999")
    body = source.fetch_chapter(0, source.meta.chapters[0])
    assert "Hello <strong>one</strong>." in body
    assert "Chapter Text" not in body


@patch("app.importers.ao3._download_html_bytes", return_value=ONESHOT_DOWNLOAD_HTML)
@patch("app.importers.ao3._load_work")
def test_open_falls_back_when_download_chapter_count_mismatches(mock_load, _mock_download):
    mock_load.return_value = _fake_work()

    source = open_work("https://archiveofourown.org/works/999")
    assert "Hello <strong>one</strong>." in source.fetch_chapter(0, source.meta.chapters[0])
    assert "Hello two." in source.fetch_chapter(1, source.meta.chapters[1])


@patch("app.importers.ao3._load_work")
@patch("app.importers.ao3.AO3.Session")
def test_open_passes_session_when_login_present(mock_session_cls, mock_load):
    session = MagicMock(name="session")
    mock_session_cls.return_value = session
    mock_load.return_value = _fake_work()

    open_work("https://archiveofourown.org/works/999", {"archiveofourown.org": ("u", "p")})
    mock_session_cls.assert_called_once_with("u", "p")
    mock_load.assert_called_once_with(999, session)


@patch("app.importers.ao3.AO3.Session")
def test_bad_login_raises_import_error(mock_session_cls):
    import AO3

    mock_session_cls.side_effect = AO3.utils.LoginError("bad")
    with pytest.raises(ImportError_, match="Login to AO3 failed"):
        open_work(
            "https://archiveofourown.org/works/999",
            {"archiveofourown.org": ("u", "wrong")},
        )


@patch("app.importers.ao3._load_work")
@patch("app.importers.ao3.AO3.Session")
def test_blocked_login_form_continues_without_session(mock_session_cls, mock_load):
    """ao3-api raises TypeError when Cloudflare strips the authenticity_token input."""
    mock_session_cls.side_effect = TypeError("'NoneType' object is not subscriptable")
    mock_load.return_value = _fake_work()

    with patch("app.importers.ao3._download_html_bytes", side_effect=ImportError_("skip")):
        source = open_work(
            "https://archiveofourown.org/works/999",
            {"archiveofourown.org": ("u", "p")},
        )
    assert source.meta.title == "Test Work"
    mock_load.assert_called_once_with(999, None)


@patch("app.importers.ao3._load_work")
def test_rate_limit_raises_import_error(mock_load):
    import AO3

    mock_load.side_effect = AO3.utils.HTTPError("rate limited")
    with pytest.raises(ImportError_, match="rate-limiting"):
        open_work("https://archiveofourown.org/works/999")


def test_load_work_maps_cloudflare_status(monkeypatch):
    from app.importers import ao3 as ao3_mod

    monkeypatch.setattr(ao3_mod.settings, "flaresolverr_url", "")
    work = MagicMock()
    resp = MagicMock(status_code=525, ok=False)
    work.get.return_value = resp
    with patch.object(ao3_mod.AO3, "Work", return_value=work):
        with pytest.raises(ImportError_, match="525"):
            ao3_mod._load_work(999, None)


def test_load_work_falls_back_to_flaresolverr(monkeypatch):
    from app.importers import ao3 as ao3_mod
    from AO3.works import Work as RealWork

    monkeypatch.setattr(ao3_mod.settings, "flaresolverr_url", "http://flaresolverr:8191")
    shell = RealWork(999, load=False)
    shell.get = MagicMock(return_value=MagicMock(status_code=525, ok=False, content=b""))

    with patch.object(ao3_mod.AO3, "Work", return_value=shell):
        with patch.object(ao3_mod, "_flaresolverr_get", return_value=(200, FULL_WORK_HTML.encode())):
            work = ao3_mod._load_work(999, None)
    assert len(work.chapters) == 2
    assert work.title == "Test Work"
