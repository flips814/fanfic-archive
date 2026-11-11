"""AO3, FanFiction.net, Wattpad and ~100 other sites via FanFicFare."""

import configparser
import logging
import os
from datetime import datetime, timezone
from io import StringIO
from urllib.parse import urlparse

import fanficfare
from fanficfare import adapters
from fanficfare import exceptions as fff_exceptions
from fanficfare.configurable import Configuration

from app.config import settings
from app.importers.base import ChapterRef, Importer, ImportError_, WorkMeta, WorkSource

log = logging.getLogger(__name__)

_DEFAULTS_INI = os.path.join(os.path.dirname(fanficfare.__file__), "defaults.ini")


def _configuration(url: str) -> Configuration:
    config = Configuration(adapters.getConfigSectionsFor(url), "html")
    files = [_DEFAULTS_INI]
    if settings.fanficfare_personal_ini and os.path.exists(settings.fanficfare_personal_ini):
        files.append(settings.fanficfare_personal_ini)
    config.read(files)
    if settings.fanficfare_ini.strip():
        config.read_file(StringIO(settings.fanficfare_ini))
    try:
        config.add_section("overrides")
    except configparser.DuplicateSectionError:
        pass
    config.set("overrides", "include_images", "false")
    config.set("overrides", "never_make_cover", "true")
    if settings.fanficfare_is_adult:
        config.set("overrides", "is_adult", "true")
    if settings.flaresolverr_url and config.site in settings.flaresolverr_sites:
        proxy = urlparse(settings.flaresolverr_url)
        config.set("overrides", "use_flaresolverr_proxy", "true")
        config.set("overrides", "flaresolverr_proxy_protocol", proxy.scheme or "http")
        config.set("overrides", "flaresolverr_proxy_address", proxy.hostname or "flaresolverr")
        config.set("overrides", "flaresolverr_proxy_port", str(proxy.port or 8191))
        config.set("overrides", "flaresolverr_proxy_timeout", str(settings.flaresolverr_timeout_ms))
    return config


_HTTP_HINTS = {
    403: "the site refused the request (often Cloudflare bot protection; add the site to FLARESOLVERR_SITES)",
    418: "the site is blocking this server's IP address as a bot (AO3 does this to many cloud/VPN IPs)",
    429: "the site is rate-limiting us; it will be retried on the next update check",
    503: "the site is down or showing a Cloudflare challenge",
}


def _http_error(e: fff_exceptions.HTTPErrorFFF) -> ImportError_:
    hint = _HTTP_HINTS.get(e.status_code, "the site returned an error")
    return ImportError_(f"HTTP {e.status_code} from {e.url}: {hint}")


def _aware(value) -> datetime | None:
    if not isinstance(value, datetime):
        return None
    return value if value.tzinfo else value.replace(tzinfo=timezone.utc)


def _int(value) -> int | None:
    try:
        return int(str(value).replace(",", "").strip())
    except (TypeError, ValueError):
        return None


class FanFicFareSource(WorkSource):
    def __init__(self, url: str, logins: dict[str, tuple[str, str]]):
        try:
            config = _configuration(url)
            self.adapter = adapters.getAdapter(config, url)
            site = self.adapter.getSiteDomain()
            if site in logins:
                username, password = logins[site]
                config.set("overrides", "username", username)
                config.set("overrides", "password", password)
                self.adapter.username, self.adapter.password = username, password
            story = self.adapter.getStoryMetadataOnly(get_cover=False)
        except fff_exceptions.UnknownSite as e:
            raise ImportError_(f"Unsupported site: {url}") from e
        except fff_exceptions.StoryDoesNotExist as e:
            raise ImportError_(f"Work not found (deleted or restricted?): {url}") from e
        except fff_exceptions.AdultCheckRequired as e:
            raise ImportError_("Adult check required; set FANFICFARE_IS_ADULT=true") from e
        except fff_exceptions.FailedToLogin as e:
            raise ImportError_(
                "Login to the site failed; check the username/password under Settings → Site logins"
            ) from e
        except fff_exceptions.FailedToDownload as e:
            if "registered users" in str(e):
                raise ImportError_(
                    "This work is only visible to logged-in users. Add your login for this site under "
                    "Settings → Site logins (whoever imported or follows the work can provide it)."
                ) from e
            raise ImportError_(str(e)) from e
        except fff_exceptions.InvalidStoryURL as e:
            raise ImportError_(f"Not a valid work URL for this site: {url}") from e
        except fff_exceptions.AccessDenied as e:
            raise ImportError_(f"Access denied by the site: {e}") from e
        except fff_exceptions.HTTPErrorFFF as e:
            raise _http_error(e) from e

        def meta(key: str) -> str | None:
            try:
                return story.getMetadata(key) or None
            except Exception:
                return None

        def lst(key: str) -> list[str]:
            try:
                return [v for v in story.getList(key) if v]
            except Exception:
                return []

        self.meta = WorkMeta(
            site=self.adapter.getSiteDomain(),
            url=meta("storyUrl") or self.adapter.url,
            source_id=meta("storyId"),
            title=meta("title") or "Untitled",
            author=meta("author"),
            author_url=(lst("authorUrl") or [None])[0],
            summary_html=meta("description"),
            rating=meta("rating"),
            status=meta("status"),
            language=meta("language"),
            word_count=_int(meta("numWords")),
            fandoms=lst("category"),
            relationships=lst("ships"),
            characters=lst("characters"),
            tags=lst("freeformtags") + lst("genre") + lst("extratags"),
            warnings=lst("warnings"),
            published_at=_aware(story.getMetadataRaw("datePublished")),
            updated_at=_aware(story.getMetadataRaw("dateUpdated")),
            chapters=[
                ChapterRef(key=c["url"], title=c.get("title") or f"Chapter {i + 1}", url=c["url"])
                for i, c in enumerate(self.adapter.get_chapters())
            ],
        )

    def fetch_chapter(self, index: int, ref: ChapterRef) -> str:
        try:
            html = self.adapter.getChapterTextNum(ref.url, index)
        except fff_exceptions.HTTPErrorFFF as e:
            raise _http_error(e) from e
        return html if isinstance(html, str) else html.decode("utf-8", "replace")


class FanFicFareImporter(Importer):
    name = "fanficfare"

    def matches(self, url: str) -> bool:
        try:
            adapters.getConfigSectionsFor(url)
            return True
        except fff_exceptions.UnknownSite:
            return False

    def open(self, url: str, logins: dict[str, tuple[str, str]] | None = None) -> WorkSource:
        return FanFicFareSource(url, logins or {})
