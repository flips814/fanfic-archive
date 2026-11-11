from sqlalchemy import select

from app.db import SessionLocal
from app.models import Chapter, Comment, Work
from tests.conftest import drain, login, user_id


def _setup(fake_site, *names):
    clients = [login(n, admin=False) for n in names]
    clients[0].post("/add", data={"urls": fake_site.add_work("1", "Talky Fic", chapters=2)})
    drain()
    with SessionLocal() as db:
        work = db.scalars(select(Work)).one()
        chapter = db.scalars(select(Chapter).where(Chapter.number == 2)).one()
        return clients, work.id, chapter.id


def _comments():
    with SessionLocal() as db:
        return db.scalars(select(Comment).order_by(Comment.id)).all()


def test_post_on_work_and_chapter(fake_site, sent):
    (alice,), work_id, chapter_id = _setup(fake_site, "alice")
    r = alice.post(f"/works/{work_id}/comments", data={"body": "Loved this!"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"].startswith(f"/works/{work_id}#comment-")
    r = alice.post(f"/works/{work_id}/comments", data={"body": "That twist!", "chapter_id": chapter_id},
                   follow_redirects=False)
    assert r.headers["location"].startswith(f"/works/{work_id}/chapters/2#comment-")

    work_page = alice.get(f"/works/{work_id}").text
    assert "Loved this!" in work_page and "That twist!" not in work_page
    assert "1 comment<span" in work_page, "chapter list shows the chapter's comment count"
    chapter_page = alice.get(f"/works/{work_id}/chapters/2").text
    assert "That twist!" in chapter_page and "Loved this!" not in chapter_page
    assert "Alice" in chapter_page and "imported this" in chapter_page


def test_comment_text_is_escaped_and_links_are_safe(fake_site, sent):
    (alice,), work_id, _ = _setup(fake_site, "alice")
    alice.post(f"/works/{work_id}/comments",
               data={"body": '<script>alert(1)</script>\n\nsee https://example.com/x?a=1&b=2.'})
    page = alice.get(f"/works/{work_id}").text
    assert "<script>alert(1)</script>" not in page
    assert "&lt;script&gt;alert(1)&lt;/script&gt;" in page
    assert '<a href="https://example.com/x?a=1&amp;b=2" rel="nofollow noopener noreferrer" target="_blank">' in page


def test_empty_and_too_long_comments_rejected(fake_site, sent):
    (alice,), work_id, _ = _setup(fake_site, "alice")
    assert "Write something before posting." in alice.post(f"/works/{work_id}/comments", data={"body": "   "}).text
    assert "at most 5,000 characters" in alice.post(f"/works/{work_id}/comments", data={"body": "x" * 5001}).text
    assert _comments() == []


def test_replies_are_one_level_and_notify_parent_author(fake_site, sent):
    (alice, bob), work_id, _ = _setup(fake_site, "alice", "bob")
    alice.post("/settings", data={"notify_email": "false", "apprise_urls": "ntfy://ntfy/alice"})
    alice.post(f"/works/{work_id}/comments", data={"body": "Thoughts?"})
    top = _comments()[0]
    bob.post(f"/works/{work_id}/comments", data={"body": "So good", "parent_id": top.id})
    reply = _comments()[1]
    alice.post(f"/works/{work_id}/comments", data={"body": "Agreed", "parent_id": reply.id})

    replies = [c for c in _comments() if c.parent_id]
    assert [c.parent_id for c in replies] == [top.id, top.id], "reply to a reply attaches to the top comment"
    assert len(sent["apprise"]) == 1, "only Bob's reply notifies (Alice replying in her own thread doesn't)"
    note = sent["apprise"][0]
    assert note["urls"] == ["ntfy://ntfy/alice"]
    assert "Bob replied to your comment on Talky Fic" in note["title"]
    assert f"/works/{work_id}#comment-{reply.id}" in note["body"]


def test_spoiler_comment_is_hidden_until_opened(fake_site, sent):
    (alice, bob), work_id, _ = _setup(fake_site, "alice", "bob")
    alice.post("/settings", data={"notify_email": "false", "apprise_urls": "ntfy://ntfy/alice"})
    alice.post(f"/works/{work_id}/comments", data={"body": "Q"})
    bob.post(f"/works/{work_id}/comments", data={"body": "The butler did it", "spoiler": "1",
                                                 "parent_id": _comments()[0].id})
    page = alice.get(f"/works/{work_id}").text
    assert '<details class="spoiler">' in page and "Spoiler: show comment" in page
    assert "The butler did it" not in sent["apprise"][0]["body"], "spoilers aren't leaked into notifications"


def test_only_author_can_edit_and_author_or_admin_can_delete(fake_site, sent):
    (alice, bob), work_id, _ = _setup(fake_site, "alice", "bob")
    alice.post(f"/works/{work_id}/comments", data={"body": "Original"})
    cid = _comments()[0].id

    assert bob.post(f"/comments/{cid}/edit", data={"body": "Hacked"}).status_code == 403
    assert bob.post(f"/comments/{cid}/delete").status_code == 403
    alice.post(f"/comments/{cid}/edit", data={"body": "Fixed typo"})
    edited = _comments()[0]
    assert edited.body == "Fixed typo" and edited.edited_at is not None
    assert "(edited" in alice.get(f"/works/{work_id}").text

    admin = login("carol")  # dev logins are admins
    assert admin.post(f"/comments/{cid}/delete", follow_redirects=False).status_code == 303
    assert _comments() == []


def test_deleting_comment_with_replies_keeps_thread(fake_site, sent):
    (alice, bob), work_id, _ = _setup(fake_site, "alice", "bob")
    alice.post(f"/works/{work_id}/comments", data={"body": "Parent text"})
    parent_id = _comments()[0].id
    bob.post(f"/works/{work_id}/comments", data={"body": "Child text", "parent_id": parent_id})
    alice.post(f"/comments/{parent_id}/delete")

    page = alice.get(f"/works/{work_id}").text
    assert "Parent text" not in page
    assert "This comment was deleted." in page and "Child text" in page

    bob.post(f"/comments/{_comments()[1].id}/delete")
    page = alice.get(f"/works/{work_id}").text
    assert "This comment was deleted." not in page, "empty deleted thread disappears"


def test_cross_work_ids_are_rejected(fake_site, sent):
    (alice,), work_id, chapter_id = _setup(fake_site, "alice")
    alice.post("/add", data={"urls": fake_site.add_work("2", "Other", chapters=1)})
    drain()
    with SessionLocal() as db:
        other_id = db.scalar(select(Work.id).where(Work.title == "Other"))
    assert alice.post(f"/works/{other_id}/comments", data={"body": "x", "chapter_id": chapter_id}).status_code == 404
    alice.post(f"/works/{work_id}/comments", data={"body": "hi"})
    assert alice.post(f"/works/{other_id}/comments",
                      data={"body": "x", "parent_id": _comments()[0].id}).status_code == 404


def test_deleting_work_removes_its_comments(fake_site, sent):
    (alice,), work_id, chapter_id = _setup(fake_site, "alice")
    alice.post(f"/works/{work_id}/comments", data={"body": "a"})
    alice.post(f"/works/{work_id}/comments", data={"body": "b", "chapter_id": chapter_id})
    alice.post(f"/works/{work_id}/comments", data={"body": "c", "parent_id": 1})
    login("carol").post(f"/works/{work_id}/delete")
    assert _comments() == []
    assert user_id("alice"), "users are unaffected"
