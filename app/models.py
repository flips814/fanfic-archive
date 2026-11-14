from datetime import datetime, timezone

from sqlalchemy import (
    JSON,
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class User(Base):
    __tablename__ = "users"
    __table_args__ = (UniqueConstraint("oidc_issuer", "oidc_sub"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    oidc_issuer: Mapped[str] = mapped_column(String(500))
    oidc_sub: Mapped[str] = mapped_column(String(255))
    username: Mapped[str | None] = mapped_column(String(255))
    display_name: Mapped[str | None] = mapped_column(String(255))
    email: Mapped[str | None] = mapped_column(String(320))
    is_admin: Mapped[bool] = mapped_column(Boolean, default=False)
    # None = follow SSO rules (admin emails/group); True/False = set by an admin, survives re-login
    admin_override: Mapped[bool | None] = mapped_column(Boolean)
    disabled: Mapped[bool] = mapped_column(Boolean, default=False, server_default=text("false"))
    password_hash: Mapped[str | None] = mapped_column(String(255))  # local accounts only
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    notify_email: Mapped[bool] = mapped_column(Boolean, default=True)
    notify_email_address: Mapped[str | None] = mapped_column(String(320))
    discord_webhook_url: Mapped[str | None] = mapped_column(String(500))
    apprise_urls: Mapped[str | None] = mapped_column(Text)

    @property
    def label(self) -> str:
        return self.display_name or self.username or self.email or f"user-{self.id}"

    @property
    def is_local(self) -> bool:
        return self.oidc_issuer == LOCAL_ISSUER


LOCAL_ISSUER = "local"


class AppSetting(Base):
    """Admin-edited settings (override environment defaults) and small bits of app state."""

    __tablename__ = "app_settings"

    key: Mapped[str] = mapped_column(String(100), primary_key=True)
    value: Mapped[str] = mapped_column(Text)  # JSON
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)
    updated_by_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))


class Work(Base):
    __tablename__ = "works"

    id: Mapped[int] = mapped_column(primary_key=True)
    site: Mapped[str] = mapped_column(String(100), index=True)
    source_url: Mapped[str] = mapped_column(String(1000), unique=True)
    source_id: Mapped[str | None] = mapped_column(String(255))
    title: Mapped[str] = mapped_column(String(1000))
    author: Mapped[str | None] = mapped_column(String(1000))
    author_url: Mapped[str | None] = mapped_column(String(1000))
    summary_html: Mapped[str | None] = mapped_column(Text)
    rating: Mapped[str | None] = mapped_column(String(100))
    status: Mapped[str | None] = mapped_column(String(100))
    language: Mapped[str | None] = mapped_column(String(100))
    word_count: Mapped[int | None] = mapped_column(Integer)
    fandoms: Mapped[list] = mapped_column(JSON, default=list)
    relationships: Mapped[list] = mapped_column(JSON, default=list)
    characters: Mapped[list] = mapped_column(JSON, default=list)
    tags: Mapped[list] = mapped_column(JSON, default=list)
    warnings: Mapped[list] = mapped_column(JSON, default=list)
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    source_updated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    added_by_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    last_checked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), index=True)
    last_new_chapter_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    auto_update: Mapped[bool] = mapped_column(Boolean, default=True)
    last_error: Mapped[str | None] = mapped_column(Text)

    added_by: Mapped[User | None] = relationship()
    comments: Mapped[list["Comment"]] = relationship(back_populates="work", cascade="all, delete-orphan")
    chapters: Mapped[list["Chapter"]] = relationship(
        back_populates="work",
        cascade="all, delete-orphan",
        order_by="(Chapter.number, Chapter.id)",
    )
    followers: Mapped[list["Follow"]] = relationship(back_populates="work", cascade="all, delete-orphan")
    translations: Mapped[list["WorkTranslation"]] = relationship(back_populates="work", cascade="all, delete-orphan")

    @property
    def is_complete(self) -> bool:
        return (self.status or "").lower().startswith("complete")


class Chapter(Base):
    __tablename__ = "chapters"

    id: Mapped[int] = mapped_column(primary_key=True)
    work_id: Mapped[int] = mapped_column(ForeignKey("works.id", ondelete="CASCADE"), index=True)
    number: Mapped[int] = mapped_column(Integer)
    title: Mapped[str] = mapped_column(String(1000))
    source_key: Mapped[str] = mapped_column(String(1000))
    source_url: Mapped[str | None] = mapped_column(String(1000))
    content_html: Mapped[str] = mapped_column(Text)
    word_count: Mapped[int] = mapped_column(Integer, default=0)
    removed_from_source: Mapped[bool] = mapped_column(Boolean, default=False)
    imported_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    imported_by_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    job_id: Mapped[int | None] = mapped_column(ForeignKey("import_jobs.id", ondelete="SET NULL"))

    work: Mapped[Work] = relationship(back_populates="chapters")
    imported_by: Mapped[User | None] = relationship()
    translations: Mapped[list["ChapterTranslation"]] = relationship(
        back_populates="chapter", cascade="all, delete-orphan"
    )


class ChapterTranslation(Base):
    """A machine translation of one chapter, shared by every reader who asks for that language."""

    __tablename__ = "chapter_translations"
    __table_args__ = (UniqueConstraint("chapter_id", "target_lang"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    chapter_id: Mapped[int] = mapped_column(ForeignKey("chapters.id", ondelete="CASCADE"), index=True)
    target_lang: Mapped[str] = mapped_column(String(20))
    source_lang: Mapped[str | None] = mapped_column(String(20))
    status: Mapped[str] = mapped_column(String(20), default="running")  # running | done | failed
    progress: Mapped[int] = mapped_column(Integer, default=0)  # percent
    error: Mapped[str | None] = mapped_column(Text)
    title: Mapped[str | None] = mapped_column(String(1000))
    content_html: Mapped[str | None] = mapped_column(Text)
    # sha256 of the chapter text it was made from; a re-imported chapter gets translated again
    content_hash: Mapped[str] = mapped_column(String(64))
    requested_by_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)

    chapter: Mapped[Chapter] = relationship(back_populates="translations")


class WorkTranslation(Base):
    """A machine translation of a work's title and summary."""

    __tablename__ = "work_translations"
    __table_args__ = (UniqueConstraint("work_id", "target_lang"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    work_id: Mapped[int] = mapped_column(ForeignKey("works.id", ondelete="CASCADE"), index=True)
    target_lang: Mapped[str] = mapped_column(String(20))
    source_lang: Mapped[str | None] = mapped_column(String(20))
    title: Mapped[str] = mapped_column(String(1000))
    summary_html: Mapped[str | None] = mapped_column(Text)
    # sha256 of the title and summary it was made from
    content_hash: Mapped[str] = mapped_column(String(64))
    requested_by_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    work: Mapped[Work] = relationship(back_populates="translations")


class Comment(Base):
    """Discussion between archive users, on a work (chapter_id NULL) or on one chapter."""

    __tablename__ = "comments"

    id: Mapped[int] = mapped_column(primary_key=True)
    work_id: Mapped[int] = mapped_column(ForeignKey("works.id", ondelete="CASCADE"), index=True)
    chapter_id: Mapped[int | None] = mapped_column(ForeignKey("chapters.id", ondelete="CASCADE"), index=True)
    parent_id: Mapped[int | None] = mapped_column(ForeignKey("comments.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    body: Mapped[str] = mapped_column(Text)
    spoiler: Mapped[bool] = mapped_column(Boolean, default=False)
    deleted: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, index=True)
    edited_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    user: Mapped[User | None] = relationship()
    work: Mapped["Work"] = relationship(back_populates="comments")
    chapter: Mapped["Chapter | None"] = relationship()
    parent: Mapped["Comment | None"] = relationship(remote_side="Comment.id", back_populates="replies")
    replies: Mapped[list["Comment"]] = relationship(
        back_populates="parent", order_by="Comment.created_at", cascade="all, delete-orphan"
    )


class SiteLogin(Base):
    """A user's own account on a fanfic site (e.g. AO3), used to fetch restricted works."""

    __tablename__ = "site_logins"

    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    site: Mapped[str] = mapped_column(String(100), primary_key=True)
    username: Mapped[str] = mapped_column(String(255))
    password_encrypted: Mapped[str] = mapped_column(Text)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)


class Follow(Base):
    __tablename__ = "follows"

    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    work_id: Mapped[int] = mapped_column(ForeignKey("works.id", ondelete="CASCADE"), primary_key=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    user: Mapped[User] = relationship()
    work: Mapped[Work] = relationship(back_populates="followers")


class ImportJob(Base):
    """Every import/update attempt. Doubles as the audit trail of who imported what."""

    __tablename__ = "import_jobs"

    id: Mapped[int] = mapped_column(primary_key=True)
    kind: Mapped[str] = mapped_column(String(20))  # "import" | "update"
    url: Mapped[str] = mapped_column(String(1000))
    work_id: Mapped[int | None] = mapped_column(ForeignKey("works.id", ondelete="SET NULL"), index=True)
    work_title: Mapped[str | None] = mapped_column(String(1000))
    requested_by_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"), index=True)
    status: Mapped[str] = mapped_column(String(20), default="queued", index=True)
    message: Mapped[str | None] = mapped_column(Text)
    chapters_added: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, index=True)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    work: Mapped[Work | None] = relationship()
    requested_by: Mapped[User | None] = relationship()
