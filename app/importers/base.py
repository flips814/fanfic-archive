from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime


class ImportError_(Exception):
    """Raised when a work can't be fetched; message is shown to users."""


@dataclass
class ChapterRef:
    key: str  # stable identity on the source site (usually the chapter URL)
    title: str
    url: str | None = None


@dataclass
class WorkMeta:
    site: str
    url: str
    title: str
    source_id: str | None = None
    author: str | None = None
    author_url: str | None = None
    summary_html: str | None = None
    rating: str | None = None
    status: str | None = None
    language: str | None = None
    word_count: int | None = None
    fandoms: list[str] = field(default_factory=list)
    relationships: list[str] = field(default_factory=list)
    characters: list[str] = field(default_factory=list)
    tags: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    published_at: datetime | None = None
    updated_at: datetime | None = None
    chapters: list[ChapterRef] = field(default_factory=list)


class WorkSource(ABC):
    """An opened work: metadata is loaded, chapters can be fetched on demand."""

    meta: WorkMeta

    @abstractmethod
    def fetch_chapter(self, index: int, ref: ChapterRef) -> str:
        """Return chapter HTML. ``index`` is 0-based position in ``meta.chapters``."""


class Importer(ABC):
    name: str

    @abstractmethod
    def matches(self, url: str) -> bool: ...

    @abstractmethod
    def open(self, url: str, logins: dict[str, tuple[str, str]] | None = None) -> WorkSource:
        """``logins`` maps site domain -> (username, password) to use if the site needs one."""
