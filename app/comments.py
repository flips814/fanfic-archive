import html
import re

from markupsafe import Markup
from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from app import notify
from app.config import settings
from app.db import session_scope
from app.models import Comment, User

MAX_LENGTH = 5000

_URL_RE = re.compile(r"https?://[^\s<>\"']+[^\s<>\"'.,;:!?)\]]")


class CommentError(ValueError):
    pass


def clean_body(body: str) -> str:
    body = (body or "").replace("\r\n", "\n").strip()
    if not body:
        raise CommentError("Write something before posting.")
    if len(body) > MAX_LENGTH:
        raise CommentError(f"Comments can be at most {MAX_LENGTH:,} characters.")
    return body


def render_body(body: str) -> Markup:
    """Plain text -> safe HTML: paragraphs, line breaks, clickable links. Nothing else."""
    paragraphs = []
    for para in re.split(r"\n\s*\n", body.strip()):
        escaped = html.escape(para)
        linked = _URL_RE.sub(
            lambda m: f'<a href="{m.group(0)}" rel="nofollow noopener noreferrer" target="_blank">{m.group(0)}</a>',
            escaped,
        )
        paragraphs.append("<p>" + linked.replace("\n", "<br>") + "</p>")
    return Markup("".join(paragraphs))


def thread(db: Session, work_id: int, chapter_id: int | None) -> list[Comment]:
    """Top-level comments (oldest first) with replies loaded; deleted ones without replies are dropped."""
    query = (
        select(Comment)
        .where(Comment.work_id == work_id, Comment.parent_id.is_(None))
        .options(selectinload(Comment.user), selectinload(Comment.replies).selectinload(Comment.user))
        .order_by(Comment.created_at)
    )
    query = query.where(Comment.chapter_id.is_(None) if chapter_id is None else Comment.chapter_id == chapter_id)
    return [c for c in db.scalars(query) if not c.deleted or any(not r.deleted for r in c.replies)]


def visible_count(comments: list[Comment]) -> int:
    return sum((not c.deleted) + sum(not r.deleted for r in c.replies) for c in comments)


def chapter_counts(db: Session, work_id: int) -> dict[int, int]:
    rows = db.execute(
        select(Comment.chapter_id, func.count(Comment.id))
        .where(Comment.work_id == work_id, Comment.chapter_id.is_not(None), Comment.deleted.is_(False))
        .group_by(Comment.chapter_id)
    )
    return dict(rows.all())


def comment_url(comment: Comment) -> str:
    base = f"/works/{comment.work_id}"
    if comment.chapter is not None:
        base += f"/chapters/{comment.chapter.number}"
    return f"{base}#comment-{comment.id}"


def notify_reply(comment_id: int) -> None:
    """Tell the parent comment's author someone replied (run as a background task)."""
    with session_scope() as db:
        reply = db.get(Comment, comment_id)
        if not reply or not reply.parent or not reply.parent.user or reply.parent.user_id == reply.user_id:
            return
        author: User = reply.parent.user
        who = reply.user.label if reply.user else "Someone"
        where = reply.chapter.title if reply.chapter else "the work page"
        work_title = reply.work.title
        preview = reply.body if len(reply.body) <= 300 else reply.body[:300] + "…"
        if reply.spoiler:
            preview = "(marked as a spoiler; open the link to read it)"
        notify.notify_user(
            author,
            f"{who} replied to your comment on {work_title}",
            f"{who} replied to your comment on {work_title} ({where}):\n\n{preview}\n\n"
            f"{settings.base_url}{comment_url(reply)}",
        )
