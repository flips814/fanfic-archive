"""Tumblr via the legacy v1 read API (BLOG.tumblr.com/api/read/json); no API key needed.

Supported URLs:
  * a single post  -> one-chapter work
      https://BLOG.tumblr.com/post/123/slug
      https://www.tumblr.com/BLOG/123/slug
  * a blog tag     -> multi-chapter work, one chapter per post (oldest first);
                      new posts under the tag become new chapters
      https://BLOG.tumblr.com/tagged/my-fic-tag
      https://www.tumblr.com/BLOG/tagged/my-fic-tag

Blogs that are only visible to logged-in Tumblr users can't be read this way.
"""

import html
import json
import re
import time
from datetime import datetime, timezone
from urllib.parse import quote, unquote, urlparse

import requests

from app.importers.base import ChapterRef, Importer, ImportError_, WorkMeta, WorkSource

API = "https://{blog}.tumblr.com/api/read/json"
PAGE_SIZE = 50  # v1 maximum
MAX_TAG_POSTS = 2000
HEADERS = {"User-Agent": "Mozilla/5.0 (compatible; fanfic-archive)"}

_RESERVED_PATHS = {"tagged", "search", "explore", "dashboard", "blog", "settings", "likes"}
_JSONP_RE = re.compile(r"^\s*var\s+tumblr_api_read\s*=\s*(.*?);?\s*$", re.S)
_TAG_RE = re.compile(r"<[^>]+>")
_REBLOG_CREDIT_RE = re.compile(r'<p>\s*<a[^>]*class="tumblr_blog"[^>]*>.*?</a>\s*:\s*</p>', re.S)


def parse_tumblr_url(url: str) -> tuple[str, str, str] | None:
    """Return (blog, kind, value) where kind is 'post' or 'tag'."""
    parsed = urlparse(url if "://" in url else f"https://{url}")
    host = (parsed.hostname or "").lower()
    parts = [unquote(p) for p in parsed.path.split("/") if p]

    if host in ("tumblr.com", "www.tumblr.com"):
        if parts[:2] == ["blog", "view"]:
            parts = parts[2:]
        if not parts or parts[0] in _RESERVED_PATHS:
            return None
        blog, rest = parts[0], parts[1:]
    elif host.endswith(".tumblr.com"):
        blog, rest = host.removesuffix(".tumblr.com"), parts
        if rest and rest[0] == "post":
            rest = rest[1:]
    else:
        return None

    if len(rest) >= 2 and rest[0] == "tagged":
        return blog, "tag", rest[1]
    if rest and rest[0].isdigit():
        return blog, "post", rest[0]
    return None


def _api(blog: str, **params) -> dict:
    try:
        resp = requests.get(API.format(blog=blog), params=params, headers=HEADERS, timeout=30)
    except requests.RequestException as e:
        raise ImportError_(f"Couldn't reach Tumblr: {e}") from e
    if resp.status_code == 404:
        raise ImportError_(f"Tumblr blog or post not found: {blog}")
    if resp.status_code in (401, 403):
        raise ImportError_(
            f"Tumblr refused access to {blog}: the blog doesn't exist, or is private / visible to logged-in users only"
        )
    if resp.status_code == 429:
        raise ImportError_("Tumblr is rate-limiting us; it will be retried on the next update check")
    resp.raise_for_status()
    match = _JSONP_RE.match(resp.text)
    if not match:
        raise ImportError_(f"Unexpected response from Tumblr for {blog}")
    return json.loads(match.group(1))


def _post_html(post: dict) -> str:
    kind = post.get("type")
    if kind == "regular":
        return post.get("regular-body") or ""
    if kind == "photo":
        img = post.get("photo-url-1280") or post.get("photo-url-500")
        return (f'<p><img src="{html.escape(img)}" alt=""></p>' if img else "") + (post.get("photo-caption") or "")
    if kind == "quote":
        return f"<blockquote>{post.get('quote-text', '')}</blockquote>{post.get('quote-source', '')}"
    if kind == "link":
        url = html.escape(post.get("link-url") or "")
        text = post.get("link-text") or url
        return f'<p><a href="{url}">{text}</a></p>{post.get("link-description") or ""}'
    if kind == "conversation":
        lines = post.get("conversation") or []
        return "".join(
            f"<p><strong>{html.escape(l.get('label', ''))}</strong> {html.escape(l.get('phrase', ''))}</p>"
            for l in lines
        ) or f"<pre>{html.escape(post.get('conversation-text', ''))}</pre>"
    if kind == "answer":
        return f"<blockquote>{post.get('question', '')}</blockquote>{post.get('answer', '')}"
    return post.get("video-caption") or post.get("audio-caption") or ""


def _post_title(post: dict, fallback: str) -> str:
    title = (
        post.get("regular-title")
        or post.get("conversation-title")
        or post.get("link-text")
        or _TAG_RE.sub(" ", _REBLOG_CREDIT_RE.sub("", _post_html(post)))
    )
    title = re.sub(r"\s+", " ", html.unescape(title or "")).strip()
    return (title[:80].rstrip() + "…") if len(title) > 80 else (title or fallback)


def _ts(post: dict) -> datetime | None:
    ts = post.get("unix-timestamp")
    return datetime.fromtimestamp(int(ts), tz=timezone.utc) if ts else None


class TumblrSource(WorkSource):
    def __init__(self, url: str):
        parsed = parse_tumblr_url(url)
        if not parsed:
            raise ImportError_(f"Unrecognised Tumblr URL: {url}")
        blog, kind, value = parsed

        if kind == "post":
            posts = _api(blog, id=value).get("posts") or []
            if not posts:
                raise ImportError_(f"Tumblr post not found: {url}")
            post = posts[0]
            work_url = post.get("url-with-slug") or post.get("url") or url
            title = _post_title(post, f"{blog} post {value}")
            tags = post.get("tags") or []
        else:
            posts = self._all_tagged(blog, value)
            if not posts:
                raise ImportError_(f"No posts tagged '{value}' on {blog}")
            work_url = f"https://{blog}.tumblr.com/tagged/{quote(value)}"
            title = value.replace("-", " ").title()
            tags = sorted({t for p in posts for t in p.get("tags") or []})

        self.posts = {str(p["id"]): p for p in posts}
        self.meta = WorkMeta(
            site="tumblr.com",
            url=work_url,
            source_id=f"{blog}/{kind}/{value}",
            title=title,
            author=blog,
            author_url=f"https://{blog}.tumblr.com/",
            summary_html=None if kind == "post" else f"Posts tagged <em>{html.escape(value)}</em> on {blog}",
            tags=tags,
            published_at=_ts(posts[0]),
            updated_at=_ts(posts[-1]),
            status="Completed" if kind == "post" else "In-Progress",
            chapters=[
                ChapterRef(key=str(p["id"]), title=_post_title(p, f"Part {i + 1}"),
                           url=p.get("url-with-slug") or p.get("url"))
                for i, p in enumerate(posts)
            ],
        )

    @staticmethod
    def _all_tagged(blog: str, tag: str) -> list[dict]:
        posts: list[dict] = []
        start = 0
        while start < MAX_TAG_POSTS:
            page = _api(blog, tagged=tag, num=PAGE_SIZE, start=start)
            batch = page.get("posts") or []
            posts.extend(batch)
            start += PAGE_SIZE
            if len(batch) < PAGE_SIZE or start >= int(page.get("posts-total") or 0):
                break
            time.sleep(1)
        return sorted(posts, key=lambda p: int(p.get("unix-timestamp") or 0))

    def fetch_chapter(self, index: int, ref: ChapterRef) -> str:
        return _post_html(self.posts[ref.key])


class TumblrImporter(Importer):
    name = "tumblr"

    def matches(self, url: str) -> bool:
        host = (urlparse(url if "://" in url else f"https://{url}").hostname or "").lower()
        return host == "tumblr.com" or host.endswith(".tumblr.com")

    def open(self, url: str, logins: dict[str, tuple[str, str]] | None = None) -> WorkSource:
        return TumblrSource(url)
