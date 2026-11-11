import io
import re
from html import escape

from ebooklib import epub

from app.importers import site_label
from app.models import Work


def _slug(text: str) -> str:
    return re.sub(r"[^\w-]+", "_", text).strip("_")[:80] or "work"


def build_epub(work: Work) -> tuple[str, bytes]:
    book = epub.EpubBook()
    book.set_identifier(f"fanfic-archive-{work.id}")
    book.set_title(work.title)
    book.set_language((work.language or "en")[:2].lower() if work.language else "en")
    if work.author:
        book.add_author(work.author)

    info = epub.EpubHtml(title="Info", file_name="info.xhtml")
    tags = ", ".join(work.fandoms + work.relationships + work.characters + work.tags)
    info.content = (
        f"<h1>{escape(work.title)}</h1><p>by {escape(work.author or 'unknown')}</p>"
        f"<p>Source: <a href='{escape(work.source_url)}'>{escape(site_label(work.site))}</a></p>"
        f"<p>Status: {escape(work.status or '?')} · Rating: {escape(work.rating or '?')}</p>"
        f"<p>{escape(tags)}</p><div>{work.summary_html or ''}</div>"
    )
    book.add_item(info)

    items = [info]
    for chapter in work.chapters:
        item = epub.EpubHtml(title=chapter.title, file_name=f"chap_{chapter.number:04d}_{chapter.id}.xhtml")
        item.content = f"<h2>{escape(chapter.title)}</h2>{chapter.content_html}"
        book.add_item(item)
        items.append(item)

    book.toc = items
    book.spine = ["nav", *items]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())

    buffer = io.BytesIO()
    epub.write_epub(buffer, book)
    return f"{_slug(work.title)}.epub", buffer.getvalue()
