"""Machine translation of chapters through a LibreTranslate server (open source, self-hosted).

A translation is made once per chapter and language, in the background, a few paragraphs per
request so long chapters don't hit timeouts; every reader who asks for the same language after
that gets the stored copy.
"""

import hashlib
import logging
import re
import threading
import time
from datetime import datetime, timedelta, timezone
from html.parser import HTMLParser

import requests
from fastapi import APIRouter, BackgroundTasks, Depends, Form, HTTPException
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.auth import current_user
from app.config import settings
from app.db import SessionLocal, get_db
from app.models import Chapter, ChapterTranslation, User, utcnow
from app.sanitize import clean_html

log = logging.getLogger(__name__)
router = APIRouter()

BATCH_CHARS = 2000
_LANGUAGES_TTL = 600
_concurrent = threading.BoundedSemaphore(2)  # don't swamp a CPU-bound translation server
_TAG_RE = re.compile(r"<[^>]+>")
_VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"}

# FanFicFare reports language names; LibreTranslate and screen readers want BCP 47 codes.
_LANG_CODES = {
    "english": "en", "spanish": "es", "español": "es", "french": "fr", "français": "fr", "german": "de",
    "deutsch": "de", "portuguese": "pt", "português": "pt", "italian": "it", "italiano": "it",
    "russian": "ru", "русский": "ru", "chinese": "zh", "中文": "zh", "japanese": "ja", "日本語": "ja",
    "korean": "ko", "polish": "pl", "polski": "pl", "dutch": "nl", "indonesian": "id",
    "bahasa indonesia": "id", "turkish": "tr", "vietnamese": "vi", "tagalog": "tl", "filipino": "fil",
}


class TranslateError(Exception):
    pass


def lang_code(language: str | None) -> str:
    if not language:
        return ""
    language = language.strip().lower()
    return _LANG_CODES.get(language, language if len(language) in (2, 3) else "")


# --------------------------------------------------------------- LibreTranslate client

def _call(method: str, path: str, timeout: int | None = None, **kwargs):
    if not settings.translate_url:
        raise TranslateError("translation isn't set up on this archive.")
    try:
        resp = requests.request(method, settings.translate_url + path,
                                timeout=timeout or settings.translate_timeout_seconds or 120, **kwargs)
    except requests.RequestException as e:
        raise TranslateError(f"couldn't reach the translation server ({e.__class__.__name__}).") from e
    try:
        data = resp.json()
    except ValueError:
        data = None
    if resp.status_code >= 400 or data is None:
        detail = data.get("error") if isinstance(data, dict) else None
        raise TranslateError(detail or f"the translation server answered HTTP {resp.status_code}.")
    return data


def _post(path: str, **payload):
    if settings.translate_api_key:
        payload["api_key"] = settings.translate_api_key
    return _call("POST", path, json=payload)


_languages_cache: dict = {"url": None, "at": 0.0, "data": []}


def languages() -> list[dict]:
    """[{code, name, targets}] offered by the server, cached for a few minutes."""
    cache = _languages_cache
    if cache["url"] == settings.translate_url and cache["data"] and time.monotonic() - cache["at"] < _LANGUAGES_TTL:
        return cache["data"]
    data = _call("GET", "/languages", timeout=15)
    langs = [
        {"code": lang["code"], "name": lang.get("name") or lang["code"], "targets": lang.get("targets") or []}
        for lang in data if isinstance(lang, dict) and lang.get("code")
    ]
    if not langs:
        raise TranslateError("the translation server has no languages installed yet.")
    cache.update(url=settings.translate_url, at=time.monotonic(), data=langs)
    return langs


def find_language(code: str | None, langs: list[dict]) -> dict | None:
    """The server's language for a code, so "zh" or "pt-br" match "zh-Hans" or "pt"."""
    if not code:
        return None
    code = code.lower()
    for lang in langs:
        if lang["code"].lower() == code:
            return lang
    base = code.split("-")[0]
    return next((lang for lang in langs if lang["code"].lower().split("-")[0] == base), None)


def language_name(code: str | None) -> str:
    try:
        lang = find_language(code, languages())
    except TranslateError:
        lang = None
    return lang["name"] if lang else (code or "")


def detect(text: str) -> str | None:
    result = _post("/detect", q=text[:2000])
    if isinstance(result, list) and result and isinstance(result[0], dict):
        return result[0].get("language")
    return None


def translate_text(text: str, source: str, target: str, fmt: str = "text") -> str:
    result = _post("/translate", q=text, source=source, target=target, format=fmt)
    if not isinstance(result, dict) or not isinstance(result.get("translatedText"), str):
        raise TranslateError("the translation server sent an unexpected answer.")
    return result["translatedText"]


# --------------------------------------------------------------- splitting chapters

class _TopLevelElements(HTMLParser):
    """Finds the top-level elements of an HTML fragment as (tag, start, inner_start, inner_end, end) offsets."""

    def __init__(self, html: str):
        super().__init__(convert_charrefs=False)
        self.html = html
        self.line_starts = [0] + [m.end() for m in re.finditer("\n", html)]
        self.depth = 0
        self.open: tuple[str, int, int] | None = None
        self.elements: list[tuple[str, int, int, int, int]] = []

    def _offset(self) -> int:
        line, col = self.getpos()
        return self.line_starts[line - 1] + col

    def _start_tag_span(self) -> tuple[int, int]:
        start = self._offset()
        return start, start + len(self.get_starttag_text())

    def handle_starttag(self, tag, attrs):
        if tag in _VOID:
            self.handle_startendtag(tag, attrs)
            return
        if self.depth == 0:
            self.open = (tag, *self._start_tag_span())
        self.depth += 1

    def handle_startendtag(self, tag, attrs):
        if self.depth == 0:
            start, end = self._start_tag_span()
            self.elements.append((tag, start, end, end, end))

    def handle_endtag(self, tag):
        if tag in _VOID or self.depth == 0:
            return
        self.depth -= 1
        if self.depth == 0 and self.open:
            tag, start, inner_start = self.open
            inner_end = self._offset()
            self.elements.append((tag, start, inner_start, inner_end, self.html.index(">", inner_end) + 1))
            self.open = None


# Big wrappers (AO3 puts a whole chapter in one <div>) are opened up and their children sent separately.
_CONTAINERS = {"div", "section", "article", "main", "blockquote", "center", "aside", "header", "footer",
               "ul", "ol", "dl", "table", "thead", "tbody", "tfoot", "details"}


def _pieces(html: str, limit: int):
    """Yield (html, translatable) pieces that join back into ``html``; translatable ones are balanced HTML."""
    parser = _TopLevelElements(html)
    parser.feed(html)
    parser.close()
    pos = 0
    for tag, start, inner_start, inner_end, end in parser.elements:
        if start > pos:
            yield html[pos:start], True
        if end - start > limit and tag in _CONTAINERS and inner_end > inner_start:
            yield html[start:inner_start], False
            yield from _pieces(html[inner_start:inner_end], limit)
            yield html[inner_end:end], False
        else:
            yield html[start:end], True
        pos = end
    if pos < len(html):
        yield html[pos:], True


def split_html(html: str, limit: int = BATCH_CHARS) -> list[tuple[str, bool]]:
    """Cut chapter HTML into (piece, translatable) batches of about ``limit`` characters, between elements."""
    batches: list[tuple[str, bool]] = []
    current = ""
    for piece, translatable in _pieces(html, limit):
        if not translatable or (current and len(current) + len(piece) > limit):
            if current:
                batches.append((current, True))
            current = ""
        if translatable:
            current += piece
        else:
            batches.append((piece, False))
    if current:
        batches.append((current, True))
    return batches


def _has_text(html: str) -> bool:
    return bool(_TAG_RE.sub("", html).strip())


# --------------------------------------------------------------- stored translations

def content_hash(chapter: Chapter) -> str:
    return hashlib.sha256((chapter.title + "\0" + chapter.content_html).encode()).hexdigest()


def _aware(value: datetime) -> datetime:
    return value if value.tzinfo else value.replace(tzinfo=timezone.utc)


def _stale(t: ChapterTranslation) -> bool:
    """A run that stopped reporting progress (e.g. the web container restarted mid-translation)."""
    limit = timedelta(seconds=max(600, 3 * (settings.translate_timeout_seconds or 120)))
    return utcnow() - _aware(t.updated_at) > limit


def _find(db: Session, chapter: Chapter, target: str) -> ChapterTranslation | None:
    return db.scalar(select(ChapterTranslation).where(
        ChapterTranslation.chapter_id == chapter.id, ChapterTranslation.target_lang == target))


def start(db: Session, chapter: Chapter, target: str, user: User | None) -> tuple[ChapterTranslation, bool]:
    """Find or (re)start a translation. Returns it and whether a background run is needed."""
    digest = content_hash(chapter)
    t = _find(db, chapter, target)
    if t is not None and t.content_hash == digest:
        if t.status == "done" or (t.status == "running" and not _stale(t)):
            return t, False
    if t is None:
        t = ChapterTranslation(chapter_id=chapter.id, target_lang=target)
        db.add(t)
    t.status, t.progress, t.error, t.content_hash = "running", 0, None, digest
    t.requested_by_id = user.id if user else None
    t.updated_at = utcnow()
    try:
        db.commit()
    except IntegrityError:  # another reader started the same one a moment ago
        db.rollback()
        return _find(db, chapter, target), False
    return t, True


def run(translation_id: int) -> None:
    """Translate a chapter; meant for a background task."""
    with _concurrent, SessionLocal() as db:
        t = db.get(ChapterTranslation, translation_id)
        if t is None or t.status != "running":
            return
        try:
            _translate(db, t)
        except TranslateError as e:
            db.rollback()
            t.status, t.error = "failed", f"Couldn't translate: {e}"
        except Exception:
            log.exception("Translating chapter %s failed", t.chapter_id)
            db.rollback()
            t.status, t.error = "failed", "Something went wrong while translating; the web container log has details."
        db.commit()


def _translate(db: Session, t: ChapterTranslation) -> None:
    chapter = t.chapter
    langs = languages()
    target = find_language(t.target_lang, langs)
    if target is None:
        raise TranslateError(f"the translation server doesn't offer “{t.target_lang}”.")
    source = find_language(lang_code(chapter.work.language), langs)
    if source is None:
        sample = chapter.title + "\n" + _TAG_RE.sub(" ", chapter.content_html)
        source = find_language(detect(sample), langs)
        if source is None:
            raise TranslateError("couldn't tell which language this chapter is written in.")
    if source["code"] == target["code"]:
        raise TranslateError(f"this chapter is already in {target['name']}.")
    if target["code"] not in source["targets"]:
        raise TranslateError(f"the translation server can't translate {source['name']} into {target['name']}.")

    src, dst = source["code"], target["code"]
    title = translate_text(chapter.title, src, dst)
    batches = split_html(chapter.content_html)
    out = []
    for i, (batch, translatable) in enumerate(batches, 1):
        out.append(translate_text(batch, src, dst, "html") if translatable and _has_text(batch) else batch)
        t.progress, t.updated_at = int(i * 100 / len(batches)), utcnow()
        db.commit()
    t.title, t.content_html, t.source_lang = title, clean_html("".join(out)), src
    t.status, t.progress, t.updated_at = "done", 100, utcnow()


def status(t: ChapterTranslation | None, chapter: Chapter) -> dict:
    if t is None or t.content_hash != content_hash(chapter):
        return {"status": "none"}
    if t.status == "running" and _stale(t):
        return {"status": "failed", "error": "The translation stopped part-way. Try again."}
    data = {"status": t.status, "target": t.target_lang, "target_name": language_name(t.target_lang)}
    if t.status == "running":
        data["progress"] = t.progress
    elif t.status == "failed":
        data["error"] = t.error
    else:
        data.update(title=t.title, content_html=t.content_html, source=t.source_lang,
                    source_name=language_name(t.source_lang))
    return data


# --------------------------------------------------------------- routes

def _require_enabled() -> None:
    if not settings.translate_url:
        raise HTTPException(404, "Translation is turned off on this archive.")


def _chapter(db: Session, work_id: int, number: int) -> Chapter:
    chapter = db.scalar(select(Chapter).where(Chapter.work_id == work_id, Chapter.number == number)
                        .order_by(Chapter.id).limit(1))
    if chapter is None:
        raise HTTPException(404, "Chapter not found")
    return chapter


@router.get("/translate/languages")
def list_languages(source: str = "", user: User = Depends(current_user)):
    """Languages a chapter can be translated into, given the code of the language it's written in."""
    _require_enabled()
    try:
        langs = languages()
    except TranslateError as e:
        raise HTTPException(502, f"Translation is unavailable: {e}")
    src = find_language(source, langs)
    targets = set(src["targets"]) if src else None
    return {
        "source": src["code"] if src else None,
        "languages": [{"code": lang["code"], "name": lang["name"]} for lang in langs
                      if lang is not src and (targets is None or lang["code"] in targets)],
    }


@router.get("/works/{work_id}/chapters/{number}/translation")
def get_translation(work_id: int, number: int, to: str, db: Session = Depends(get_db),
                    user: User = Depends(current_user)):
    _require_enabled()
    chapter = _chapter(db, work_id, number)
    return status(_find(db, chapter, to), chapter)


@router.post("/works/{work_id}/chapters/{number}/translation")
def request_translation(work_id: int, number: int, background: BackgroundTasks, to: str = Form(...),
                        db: Session = Depends(get_db), user: User = Depends(current_user)):
    _require_enabled()
    chapter = _chapter(db, work_id, number)
    try:
        target = find_language(to, languages())
    except TranslateError as e:
        raise HTTPException(502, f"Translation is unavailable: {e}")
    if target is None:
        raise HTTPException(400, "The translation server doesn't offer that language.")
    t, needs_run = start(db, chapter, target["code"], user)
    if needs_run:
        background.add_task(run, t.id)
    return status(t, chapter)
