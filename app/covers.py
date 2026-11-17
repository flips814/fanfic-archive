"""Store and serve work cover images when the source site provides one."""

from __future__ import annotations

import logging
import mimetypes
from pathlib import Path

import requests

from app.config import settings

log = logging.getLogger(__name__)

_EXT_FOR_MIME = {
    "image/jpeg": ".jpg",
    "image/jpg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
    "image/gif": ".gif",
}


def covers_dir() -> Path:
    path = Path(settings.covers_dir)
    path.mkdir(parents=True, exist_ok=True)
    return path


def cover_path(work_id: int) -> Path | None:
    """Return the on-disk cover file for ``work_id``, if any."""
    directory = covers_dir()
    for path in directory.glob(f"{work_id}.*"):
        if path.is_file() and path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp", ".gif"}:
            return path
    return None


def has_cover(work_id: int) -> bool:
    return cover_path(work_id) is not None


def cover_url_path(work_id: int) -> str | None:
    """Relative URL path for API/clients (works with any archive base URL)."""
    if not has_cover(work_id):
        return None
    return f"/works/{work_id}/cover"


def public_cover_url(work_id: int) -> str | None:
    path = cover_url_path(work_id)
    return f"{settings.base_url}{path}" if path else None


def fetch_cover_bytes(url: str, timeout: int = 30) -> tuple[bytes | None, str | None]:
    """Download a cover image. Returns (data, mime) or (None, None)."""
    try:
        response = requests.get(
            url,
            timeout=timeout,
            headers={"User-Agent": "FanficArchive/1.0 (+cover fetch)"},
            stream=True,
        )
        response.raise_for_status()
        mime = (response.headers.get("Content-Type") or "").split(";")[0].strip().lower() or None
        data = response.content
        if not data or (mime and not mime.startswith("image/")):
            return None, None
        if len(data) > 8_000_000:
            log.warning("Cover too large (%d bytes), skipping: %s", len(data), url)
            return None, None
        return data, mime
    except Exception:
        log.warning("Failed to fetch cover from %s", url, exc_info=True)
        return None, None


def persist_meta_cover(work_id: int, meta) -> str | None:
    """Save cover from importer metadata when present. Returns filename or None."""
    data = getattr(meta, "cover_data", None)
    ext = getattr(meta, "cover_ext", None)
    mime = getattr(meta, "cover_mime", None)
    source_url = getattr(meta, "cover_url", None)
    if not data and source_url:
        data, fetched_mime = fetch_cover_bytes(source_url)
        mime = mime or fetched_mime
    if not data:
        return None
    return save_cover(work_id, data, ext=ext, mime=mime, source_url=source_url)


def save_cover(
    work_id: int,
    data: bytes,
    *,
    ext: str | None = None,
    mime: str | None = None,
    source_url: str | None = None,
) -> str | None:
    """Write cover bytes for ``work_id``. Returns the relative filename, or None on failure."""
    if not data:
        return None
    suffix = None
    if ext:
        suffix = ext if ext.startswith(".") else f".{ext}"
    elif mime:
        suffix = _EXT_FOR_MIME.get(mime.lower()) or mimetypes.guess_extension(mime)
    if not suffix:
        suffix = ".jpg"
    suffix = suffix.lower().replace(".jpeg", ".jpg")
    if suffix not in {".jpg", ".png", ".webp", ".gif"}:
        suffix = ".jpg"

    directory = covers_dir()
    # Replace any previous extension for this work.
    for old in directory.glob(f"{work_id}.*"):
        try:
            old.unlink()
        except OSError:
            log.warning("Could not remove old cover %s", old)
    path = directory / f"{work_id}{suffix}"
    try:
        path.write_bytes(data)
    except OSError:
        log.exception("Failed to write cover for work %s", work_id)
        return None
    log.info("Saved cover for work %s (%d bytes%s)", work_id, len(data), f" from {source_url}" if source_url else "")
    return path.name


def delete_cover(work_id: int) -> None:
    for path in covers_dir().glob(f"{work_id}.*"):
        try:
            path.unlink()
        except OSError:
            log.warning("Could not delete cover %s", path)


def extract_cover_from_fff_story(story) -> tuple[str | None, bytes | None, str | None, str | None]:
    """Return (source_url, data, ext, mime) from a FanFicFare Story after metadata+cover fetch."""

    def _from_info(info) -> tuple[str | None, bytes | None, str | None, str | None] | None:
        if isinstance(info, dict) and info.get("data"):
            return info.get("url"), info["data"], info.get("ext"), info.get("mime")
        return None

    # FanFicFare stores the fetched cover on ImageStore; story.cover is often just the href.
    img_store = getattr(story, "img_store", None)
    if img_store is not None:
        found = _from_info(getattr(img_store, "cover", None))
        if found:
            return found
        try:
            imgs = img_store.get_imgs()
        except Exception:
            imgs = []
        for info in imgs or []:
            newsrc = (info.get("newsrc") or "").lower() if isinstance(info, dict) else ""
            if "cover" in newsrc:
                found = _from_info(info)
                if found:
                    return found

    cover = getattr(story, "cover", None)
    found = _from_info(cover)
    if found:
        return found
    return None, None, None, None
