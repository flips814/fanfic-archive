"""Archive of Our Own via ao3-api + AO3's official HTML download.

Uses ao3-api for login, metadata, and the full-work page (view_full_work).
Chapter bodies prefer AO3's Download → HTML (one extra request for the whole
work); if that fails or mismatches chapter count, falls back to HTML extracted
from the already-loaded full-work page (no extra requests).
"""

from __future__ import annotations

import logging
import re
import time
from datetime import date, datetime, timezone
from urllib.parse import urlparse

import AO3
import requests
from AO3 import utils as ao3_utils
from bs4 import BeautifulSoup

from app.config import settings
from app.importers.base import ChapterRef, Importer, ImportError_, WorkMeta, WorkSource

log = logging.getLogger(__name__)

SITE = "archiveofourown.org"
# ao3-api docs: ~12 requests/minute before AO3 429s.
ao3_utils.set_rqtw(12)
ao3_utils.set_timew(60)

_LANDMARK_RE = re.compile(r"\blandmark\b")


def work_id_from_url(url: str) -> int | None:
    host = (urlparse(url if "://" in url else f"https://{url}").hostname or "").lower()
    if host not in (SITE, f"www.{SITE}"):
        return None
    try:
        work_id = ao3_utils.workid_from_url(url.strip())
    except (IndexError, TypeError, ValueError):
        return None
    return int(work_id) if work_id else None


def _aware(value: date | datetime | None) -> datetime | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    return datetime(value.year, value.month, value.day, tzinfo=timezone.utc)


def _tag_list(values) -> list[str]:
    return [str(v) for v in (values or []) if v]


def _summary_html(work: AO3.Work) -> str | None:
    preface = work._soup.find("div", class_="preface group") if work._soup else None
    if preface is None:
        return None
    block = preface.find("blockquote", class_="userstuff")
    if block is None:
        return None
    html = block.decode_contents().strip()
    return html or None


def _body_html(node) -> str:
    """Return inner HTML of a chapter body node, without AO3 landmark headings."""
    if node is None:
        return ""
    clone = BeautifulSoup(str(node), "lxml")
    root = clone.find("div") or clone.body or clone
    for landmark in root.find_all(class_=_LANDMARK_RE):
        landmark.decompose()
    return root.decode_contents().strip()


def _html_from_chapter_soup(chapter: AO3.Chapter) -> str:
    """Extract chapter body HTML from the full-work page soup ao3-api already loaded."""
    soup = chapter._soup
    if soup is None:
        return ""
    if chapter.id is not None:
        article = soup.find("div", attrs={"role": "article"})
    else:
        article = soup.find("div", attrs={"role": "article"}) or soup.find("div", class_="userstuff")
    return _body_html(article)


def _parse_download_html(content: bytes) -> list[str]:
    """Split AO3's official HTML download into per-chapter body HTML."""
    soup = BeautifulSoup(content, "lxml")
    root = soup.find(id="chapters")
    if root is None:
        return []

    headings = root.find_all("h2", class_="toc-heading")
    if headings:
        bodies: list[str] = []
        for heading in headings:
            sibling = heading.find_next_sibling()
            while sibling is not None and getattr(sibling, "name", None) != "div":
                sibling = sibling.find_next_sibling()
            if sibling is not None and "userstuff" in (sibling.get("class") or []):
                bodies.append(_body_html(sibling))
            else:
                bodies.append("")
        return bodies

    numbered = root.find_all("div", id=re.compile(r"^chapter-\d+$"))
    if numbered:
        bodies = []
        for chapter in numbered:
            article = chapter.find("div", attrs={"role": "article"}) or chapter.find(
                "div", class_="userstuff"
            )
            bodies.append(_body_html(article))
        return bodies

    if "userstuff" in (root.get("class") or []):
        return [_body_html(root)]

    userstuff = root.find("div", class_="userstuff")
    if userstuff is not None:
        return [_body_html(userstuff)]

    html = root.decode_contents().strip()
    return [html] if html else []


_CF_STATUSES = {520, 521, 522, 523, 524, 525, 526, 530}


def _map_error(exc: Exception) -> ImportError_:
    if isinstance(exc, ao3_utils.HTTPError):
        return ImportError_(
            "AO3 is rate-limiting us; it will be retried on the next update check"
        )
    if isinstance(exc, ao3_utils.LoginError):
        return ImportError_(
            "Login to AO3 failed; check the username/password under Settings → Site logins"
        )
    if isinstance(exc, ao3_utils.AuthError):
        return ImportError_(
            "This work is only visible to logged-in users. Add your AO3 login under "
            "Settings → Site logins (whoever imported or follows the work can provide it)."
        )
    if isinstance(exc, ao3_utils.InvalidIdError):
        return ImportError_(f"Work not found (deleted or restricted?): {exc}")
    if isinstance(exc, ao3_utils.DownloadError):
        return ImportError_(f"AO3 download failed: {exc}")
    if isinstance(exc, ImportError_):
        return exc
    return ImportError_(str(exc) or exc.__class__.__name__)


def _http_status_error(status: int, url: str) -> ImportError_:
    if status == 429:
        return ImportError_(
            "AO3 is rate-limiting us; it will be retried on the next update check"
        )
    if status in (403, 418) or status in _CF_STATUSES:
        return ImportError_(
            f"HTTP {status} from {url}: AO3/Cloudflare is blocking this server "
            "(common for cloud/VPN IPs); try again later or from another network"
        )
    if status == 404:
        return ImportError_(f"Work not found (deleted or restricted?): {url}")
    return ImportError_(f"HTTP {status} from {url}")


def _session(logins: dict[str, tuple[str, str]]):
    """Return an ao3-api session, or ``None`` to continue as a guest.

    ao3-api's login does ``soup.find(...)['value']`` with no null check, so a
    Cloudflare/waiting-room login page raises ``TypeError: 'NoneType' object is
    not subscriptable`` instead of LoginError.
    """
    creds = logins.get(SITE)
    if not creds:
        return None
    username, password = creds
    try:
        return AO3.Session(username, password)
    except ao3_utils.LoginError as e:
        raise _map_error(e) from e
    except ao3_utils.HTTPError as e:
        raise _map_error(e) from e
    except (TypeError, AttributeError, KeyError) as e:
        log.warning(
            "AO3 login form missing or blocked for %s (%s); continuing without login",
            username,
            e,
        )
        return None
    except requests.RequestException as e:
        log.warning("AO3 login request failed for %s (%s); continuing without login", username, e)
        return None


def _prop(work: AO3.Work, name: str, default=None):
    """Read an ao3-api cached property without letting its fragile soup access crash the import."""
    try:
        value = getattr(work, name)
    except Exception as e:
        log.debug("AO3 work.%s unavailable: %s", name, e)
        return default
    return default if value is None else value


def _work_complete(work: AO3.Work) -> bool:
    try:
        return bool(work.complete)
    except Exception:
        chapters = work._soup.find("dd", class_="chapters") if work._soup else None
        text = (chapters.get_text() if chapters else "") or ""
        parts = [p.strip() for p in text.split("/") if p.strip()]
        return len(parts) == 2 and parts[0] == parts[1] and parts[0].isdigit()


def _work_date(work: AO3.Work, *names: str) -> datetime | None:
    for name in names:
        value = _prop(work, name, None)
        aware = _aware(value if isinstance(value, (date, datetime)) else None)
        if aware is not None:
            return aware
    return None


def _flaresolverr_get(url: str) -> tuple[int, bytes]:
    """Fetch ``url`` through FlareSolverr. Returns (http_status, body_bytes)."""
    base = (settings.flaresolverr_url or "").rstrip("/")
    if not base:
        raise ImportError_("FlareSolverr is not configured")
    endpoint = f"{base}/v1"
    timeout_ms = max(settings.flaresolverr_timeout_ms, 1000)
    last_error: Exception | None = None
    resp = None
    for attempt in range(4):
        try:
            resp = requests.post(
                endpoint,
                json={"cmd": "request.get", "url": url, "maxTimeout": timeout_ms},
                timeout=(timeout_ms / 1000) + 30,
            )
            break
        except requests.RequestException as e:
            last_error = e
            time.sleep(2 * (attempt + 1))
    if resp is None:
        raise ImportError_(f"FlareSolverr unreachable at {endpoint}: {last_error}") from last_error
    try:
        data = resp.json()
    except ValueError as e:
        raise ImportError_(f"FlareSolverr returned non-JSON ({resp.status_code})") from e
    if data.get("status") != "ok":
        raise ImportError_(f"FlareSolverr failed: {data.get('message') or data}")
    solution = data.get("solution") or {}
    status = int(solution.get("status") or 0)
    html = solution.get("response") or ""
    body = html.encode("utf-8", "replace") if isinstance(html, str) else bytes(html)
    return status, body


def _parse_work_page(work: AO3.Work, content: bytes, page_url: str) -> None:
    work._soup = BeautifulSoup(content, "lxml")
    heading = work._soup.find("h2", class_="heading")
    if heading is not None and "Error 404" in heading.get_text():
        raise ImportError_(f"Work not found (deleted or restricted?): {page_url}")
    if work._soup.find(id="chapters") is None:
        text = work._soup.get_text(" ", strip=True).lower()
        if "waiting room" in text or "cloudflare" in text:
            raise ImportError_(
                f"AO3/Cloudflare challenge page for {page_url}; "
                "configure FlareSolverr or try again later"
            )
        if "log in" in text and ("restricted" in text or "registered user" in text):
            raise ImportError_(
                "This work is only visible to logged-in users. Add your AO3 login under "
                "Settings → Site logins (whoever imported or follows the work can provide it)."
            )
        raise ImportError_(
            f"Couldn't parse AO3 work page (unexpected response from {page_url})"
        )
    work.load_chapters()


def _direct_blocked(status: int) -> bool:
    return status == 429 or status in _CF_STATUSES or status in (403, 418) or status >= 500


def _download_html_bytes(work: AO3.Work) -> bytes:
    """Fetch AO3's official HTML download with timeouts (ao3-api's get has none)."""
    download_btn = work._soup.find("li", {"class": "download"}) if work._soup else None
    if download_btn is None:
        raise ImportError_("AO3 download menu not found on work page")
    href = None
    for item in download_btn.find_all("li"):
        link = item.find("a")
        if link is not None and (link.get_text() or "").strip().upper() == "HTML":
            href = link.get("href")
            break
    if not href:
        raise ImportError_("AO3 HTML download is not available for this work")
    url = href if href.startswith("http") else f"https://archiveofourown.org{href}"

    try:
        resp = requests.get(url, timeout=25, headers={"User-Agent": "Mozilla/5.0 (compatible; fanfic-archive)"})
        if resp.ok and not _direct_blocked(resp.status_code) and b"chapters" in resp.content:
            return resp.content
        direct_status = resp.status_code
    except requests.RequestException as e:
        direct_status = e

    if settings.flaresolverr_url:
        log.info("AO3 HTML download direct fetch failed (%s); trying FlareSolverr", direct_status)
        status, body = _flaresolverr_get(url)
        if status < 400 and body:
            return body
        raise _http_status_error(status, url)

    if isinstance(direct_status, Exception):
        raise ImportError_(f"AO3 HTML download failed: {direct_status}") from direct_status
    raise _http_status_error(int(direct_status), url)


def _load_work(work_id: int, session) -> AO3.Work:
    """Load a work without relying on ao3-api's fragile 404 check (crashes on CF pages)."""
    work = AO3.Work(work_id, session=session, load=False)
    page_url = f"https://archiveofourown.org/works/{work_id}?view_adult=true&view_full_work=true"

    direct_error: Exception | None = None
    try:
        resp = work.get(page_url)
        if not _direct_blocked(resp.status_code) and resp.ok:
            try:
                _parse_work_page(work, resp.content, page_url)
                return work
            except ImportError_ as e:
                # Challenge/waiting-room HTML sometimes arrives as HTTP 200.
                direct_error = e
        else:
            direct_error = _http_status_error(resp.status_code, page_url)
    except ao3_utils.HTTPError as e:
        direct_error = _map_error(e)

    if settings.flaresolverr_url:
        log.info("AO3 direct fetch failed for %s (%s); trying FlareSolverr", page_url, direct_error)
        status, body = _flaresolverr_get(page_url)
        if _direct_blocked(status) or status >= 400:
            raise _http_status_error(status, page_url)
        _parse_work_page(work, body, page_url)
        return work

    if direct_error:
        raise direct_error
    raise ImportError_(f"Couldn't load AO3 work: {page_url}")


class Ao3Source(WorkSource):
    def __init__(self, url: str, logins: dict[str, tuple[str, str]]):
        work_id = work_id_from_url(url)
        if not work_id:
            raise ImportError_(f"Not a valid AO3 work URL: {url}")

        session = _session(logins)
        try:
            work = _load_work(work_id, session)
        except Exception as e:
            raise _map_error(e) from e

        if not work.chapters:
            if getattr(work, "restricted", False) and session is None:
                raise ImportError_(
                    "This work is only visible to logged-in users. Add your AO3 login under "
                    "Settings → Site logins (whoever imported or follows the work can provide it)."
                )
            raise ImportError_(f"Work not found or has no chapters: {url}")

        authors = _prop(work, "authors", []) or []
        author = getattr(authors[0], "username", None) if authors else None
        author_url = getattr(authors[0], "url", None) if authors else None

        chapters_meta: list[ChapterRef] = []
        for i, chapter in enumerate(work.chapters):
            try:
                # ao3-api strips "N." via [2:], which leaves a leading space (" First").
                title = (chapter.title or "").strip() or f"Chapter {i + 1}"
            except Exception:
                title = f"Chapter {i + 1}"
            if chapter.id is not None:
                chapter_url = f"https://archiveofourown.org/works/{work.id}/chapters/{chapter.id}"
                key = chapter_url
            else:
                chapter_url = f"https://archiveofourown.org/works/{work.id}"
                key = chapter_url
            chapters_meta.append(ChapterRef(key=key, title=title, url=chapter_url))

        self._bodies = self._load_bodies(work)
        if len(self._bodies) != len(chapters_meta):
            log.warning(
                "AO3 chapter body count mismatch for %s: got %d bodies for %d chapters; "
                "falling back to full-work page HTML",
                work.id,
                len(self._bodies),
                len(chapters_meta),
            )
            self._bodies = [_html_from_chapter_soup(c) for c in work.chapters]

        language = _prop(work, "language", None)
        words = _prop(work, "words", None)
        self.meta = WorkMeta(
            site=SITE,
            url=f"https://archiveofourown.org/works/{work.id}",
            source_id=str(work.id),
            title=(_prop(work, "title", None) or "Untitled"),
            author=author,
            author_url=author_url,
            summary_html=_summary_html(work),
            rating=_prop(work, "rating", None),
            status="Completed" if _work_complete(work) else "In-Progress",
            language=None if language in (None, "Unknown") else language,
            word_count=int(words) if words else None,
            fandoms=_tag_list(_prop(work, "fandoms", [])),
            relationships=_tag_list(_prop(work, "relationships", [])),
            characters=_tag_list(_prop(work, "characters", [])),
            tags=_tag_list(_prop(work, "tags", [])) + _tag_list(_prop(work, "categories", [])),
            warnings=_tag_list(_prop(work, "warnings", [])),
            published_at=_work_date(work, "date_published"),
            updated_at=_work_date(work, "date_updated", "date_published"),
            chapters=chapters_meta,
        )

    def _load_bodies(self, work: AO3.Work) -> list[str]:
        page_bodies = [_html_from_chapter_soup(c) for c in work.chapters]
        try:
            content = _download_html_bytes(work)
            bodies = _parse_download_html(content)
            if bodies and len(bodies) == len(work.chapters):
                log.info("AO3 HTML download used for %s (%d chapters)", work.url, len(bodies))
                return bodies
            if bodies:
                log.info(
                    "AO3 HTML download chapter count mismatch for %s (%d vs %d); using page HTML",
                    work.url,
                    len(bodies),
                    len(work.chapters),
                )
        except Exception as e:
            log.info("AO3 HTML download unavailable for %s (%s); using full-work page HTML", work.url, e)
        return page_bodies

    def fetch_chapter(self, index: int, ref: ChapterRef) -> str:
        try:
            html = self._bodies[index]
        except IndexError as e:
            raise ImportError_(f"Chapter index out of range: {index}") from e
        if not html:
            raise ImportError_(f"Empty chapter body for {ref.title}")
        return html


class Ao3Importer(Importer):
    name = "ao3"

    def matches(self, url: str) -> bool:
        return work_id_from_url(url) is not None

    def open(self, url: str, logins: dict[str, tuple[str, str]] | None = None) -> WorkSource:
        return Ao3Source(url, logins or {})
