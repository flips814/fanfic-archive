from dataclasses import replace
from datetime import timedelta

from sqlalchemy import select

from app import notify
from app.db import SessionLocal
from app.jobs import schedule_due_updates
from app.models import Chapter, Follow, ImportJob, User, Work, utcnow
from tests.conftest import drain, login, user_id


def _work() -> Work:
    with SessionLocal() as db:
        work = db.scalars(select(Work)).one()
        _ = work.chapters, work.followers  # load before session closes
        return work


def _jobs() -> list[ImportJob]:
    with SessionLocal() as db:
        return db.scalars(select(ImportJob).order_by(ImportJob.id)).all()


def _make_due() -> None:
    with SessionLocal() as db:
        for work in db.scalars(select(Work)):
            work.last_checked_at = utcnow() - timedelta(days=30)
        db.commit()


def test_import_records_who_imported_and_follows(fake_site, sent):
    url = fake_site.add_work("1", "Test Fic", chapters=3)
    alice = login("alice")

    resp = alice.post("/add", data={"urls": url}, follow_redirects=False)
    assert resp.status_code == 303
    assert drain() == 1

    work = _work()
    assert work.title == "Test Fic"
    assert work.added_by_id == user_id("alice")
    assert [c.title for c in work.chapters] == ["Chapter 1", "Chapter 2", "Chapter 3"]
    assert all(c.imported_by_id == user_id("alice") for c in work.chapters)
    assert "<script>" not in work.summary_html
    assert [f.user_id for f in work.followers] == [user_id("alice")]

    job = _jobs()[0]
    assert (job.kind, job.status, job.chapters_added) == ("import", "done", 3)
    assert job.requested_by_id == user_id("alice")


def test_scheduled_update_imports_only_new_chapters_and_notifies_followers(fake_site, sent):
    url = fake_site.add_work("1", "Test Fic", chapters=2)
    alice, bob = login("alice"), login("bob")
    alice.post("/add", data={"urls": url})
    drain()
    work_id = _work().id
    bob.post(f"/works/{work_id}/follow")
    alice.post("/settings", data={"notify_email": "true", "notify_email_address": "alice@test.local",
                                  "discord_webhook_url": "https://discord.com/api/webhooks/1/tok",
                                  "apprise_urls": ""})
    bob.post("/settings", data={"notify_email": "false", "apprise_urls": "ntfy://ntfy/bob"})

    fake_site.add_chapter("1", "The Big Reveal")
    fake_site.fetches.clear()
    _make_due()
    with SessionLocal() as db:
        assert schedule_due_updates(db) == 1
        db.commit()
    assert drain() == 1

    work = _work()
    assert len(work.chapters) == 3
    assert len(fake_site.fetches) == 1, "only the new chapter should be downloaded"
    new = work.chapters[-1]
    assert new.title == "The Big Reveal"
    assert new.imported_by_id is None  # auto-updater
    assert work.last_new_chapter_at is not None

    update_job = _jobs()[-1]
    assert (update_job.kind, update_job.status, update_job.chapters_added) == ("update", "done", 1)
    assert update_job.requested_by_id is None

    targets = [u for n in sent["apprise"] for u in n["urls"]]
    assert "discord://1/tok/" in targets
    assert "ntfy://ntfy/bob" in targets
    assert any("The Big Reveal" in n["body"] for n in sent["apprise"])
    assert [e["to"] for e in sent["email"]] == ["alice@test.local"]


def test_no_new_chapters_sends_nothing(fake_site, sent):
    url = fake_site.add_work("1", "Test Fic", chapters=2)
    alice = login("alice")
    alice.post("/settings", data={"notify_email": "false", "apprise_urls": "ntfy://ntfy/alice"})
    alice.post("/add", data={"urls": url})
    drain()
    _make_due()
    with SessionLocal() as db:
        schedule_due_updates(db)
        db.commit()
    drain()
    assert _jobs()[-1].message == "No new chapters"
    assert sent["apprise"] == []


def test_manual_check_is_credited_to_user(fake_site, sent):
    url = fake_site.add_work("1", "Test Fic", chapters=1)
    alice, bob = login("alice"), login("bob")
    alice.post("/add", data={"urls": url})
    drain()
    fake_site.add_chapter("1")
    bob.post(f"/works/{_work().id}/check")
    drain()
    assert _work().chapters[-1].imported_by_id == user_id("bob")


def test_duplicate_import_becomes_update_and_follows(fake_site, sent):
    url = fake_site.add_work("1", "Test Fic", chapters=2)
    alice, bob = login("alice"), login("bob")
    alice.post("/add", data={"urls": url})
    drain()
    bob.post("/add", data={"urls": url})
    drain()

    with SessionLocal() as db:
        assert db.query(Work).count() == 1
        assert db.get(Follow, (user_id("bob"), _work().id)) is not None
    assert [j.kind for j in _jobs()] == ["import", "update"]
    assert _work().added_by_id == user_id("alice")


def test_removed_chapter_is_kept_and_flagged(fake_site, sent):
    url = fake_site.add_work("1", "Test Fic", chapters=3)
    login("alice").post("/add", data={"urls": url})
    drain()
    del fake_site.works["1"]["chapters"][1]
    _make_due()
    with SessionLocal() as db:
        schedule_due_updates(db)
        db.commit()
    drain()
    chapters = _work().chapters
    assert len(chapters) == 3
    assert [c.removed_from_source for c in chapters] == [False, True, False]


def test_failed_import_is_logged(fake_site, sent):
    fake_site.fail["9"] = "Work not found (deleted or restricted?)"
    login("alice").post("/add", data={"urls": "https://fake.test/works/9"})
    drain()
    job = _jobs()[0]
    assert job.status == "failed"
    assert "deleted or restricted" in job.message


def test_failed_update_sets_work_error(fake_site, sent):
    url = fake_site.add_work("1", "Test Fic", chapters=1)
    login("alice").post("/add", data={"urls": url})
    drain()
    fake_site.fail["1"] = "Site is down"
    _make_due()
    with SessionLocal() as db:
        schedule_due_updates(db)
        db.commit()
    drain()
    assert _work().last_error == "Site is down"
    assert _jobs()[-1].status == "failed"


def test_scheduler_intervals(fake_site, sent):
    ongoing = fake_site.add_work("1", "Ongoing", chapters=1)
    done = fake_site.add_work("2", "Done", chapters=1, status="Completed")
    alice = login("alice")
    alice.post("/add", data={"urls": f"{ongoing}\n{done}"})
    drain()

    with SessionLocal() as db:
        assert schedule_due_updates(db) == 0, "just checked; nothing is due"
        for work in db.scalars(select(Work)):
            work.last_checked_at = utcnow() - timedelta(hours=2)  # past 60 min, before 1440 min
        db.commit()
        assert schedule_due_updates(db) == 1, "only the in-progress work is due"
        db.commit()
        assert schedule_due_updates(db) == 0, "no duplicate jobs while one is pending"


def test_auto_update_off_is_not_scheduled(fake_site, sent):
    url = fake_site.add_work("1", "Test Fic", chapters=1)
    alice = login("alice")
    alice.post("/add", data={"urls": url})
    drain()
    alice.post(f"/works/{_work().id}/auto-update")
    _make_due()
    with SessionLocal() as db:
        assert schedule_due_updates(db) == 0


def test_new_import_notifies_global_channel(fake_site, sent, monkeypatch):
    monkeypatch.setattr(notify, "settings", replace(notify.settings, apprise_urls=["ntfy://ntfy/all"]))
    url = fake_site.add_work("1", "Test Fic", chapters=2)
    login("alice").post("/add", data={"urls": url})
    drain()
    assert any(n["urls"] == ["ntfy://ntfy/all"] and "Alice imported" in n["body"] for n in sent["apprise"])


def test_users_page_counts(fake_site, sent):
    alice = login("alice")
    alice.post("/add", data={"urls": fake_site.add_work("1", "A", chapters=2)})
    drain()
    page = alice.get("/users").text
    assert "Alice" in page
    with SessionLocal() as db:
        assert db.query(Chapter).filter(Chapter.imported_by_id == user_id("alice")).count() == 2
        assert db.get(User, user_id("alice")).label == "Alice"
