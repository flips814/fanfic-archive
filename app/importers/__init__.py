from app.importers.base import ChapterRef, Importer, ImportError_, WorkMeta, WorkSource
from app.importers.fanficfare_importer import FanFicFareImporter
from app.importers.tumblr import TumblrImporter

# Order matters: specific importers before the FanFicFare catch-all.
IMPORTERS: list[Importer] = [TumblrImporter(), FanFicFareImporter()]

SITE_LABELS = {
    "archiveofourown.org": "AO3",
    "www.fanfiction.net": "FanFiction.net",
    "www.fictionpress.com": "FictionPress",
    "www.wattpad.com": "Wattpad",
    "tumblr.com": "Tumblr",
    "www.royalroad.com": "Royal Road",
    "forums.spacebattles.com": "SpaceBattles",
    "forums.sufficientvelocity.com": "Sufficient Velocity",
    "forum.questionablequesting.com": "Questionable Questing",
}


def site_label(site: str) -> str:
    return SITE_LABELS.get(site, site)


def open_work(url: str, logins: dict[str, tuple[str, str]] | None = None) -> WorkSource:
    url = url.strip()
    for importer in IMPORTERS:
        if importer.matches(url):
            return importer.open(url, logins or {})
    raise ImportError_(f"No importer supports this URL: {url}")


def is_supported(url: str) -> bool:
    return any(importer.matches(url.strip()) for importer in IMPORTERS)


__all__ = [
    "ChapterRef",
    "Importer",
    "ImportError_",
    "WorkMeta",
    "WorkSource",
    "IMPORTERS",
    "open_work",
    "is_supported",
    "site_label",
]
