from datetime import timedelta

from sqlalchemy import select

from app.crypto import decrypt
from app.db import SessionLocal
from app.jobs import schedule_due_updates
from app.models import SiteLogin, Work, utcnow
from tests.conftest import drain, login, user_id

AO3 = "archiveofourown.org"


def _save(client, username, password="", remove=""):
    return client.post(
        f"/settings/logins/{AO3}",
        data={"username": username, "password": password, "remove": remove},
        follow_redirects=False,
    )


def _stored(name: str) -> SiteLogin | None:
    with SessionLocal() as db:
        return db.get(SiteLogin, (user_id(name), AO3))


def test_login_is_stored_encrypted_and_never_shown(sent):
    alice = login("alice")
    assert _save(alice, "alice_ao3", "hunter2").status_code == 303

    stored = _stored("alice")
    assert stored.username == "alice_ao3"
    assert "hunter2" not in stored.password_encrypted
    assert decrypt(stored.password_encrypted) == "hunter2"

    page = alice.get("/settings").text
    assert "alice_ao3" in page
    assert "hunter2" not in page
    assert "Leave blank to keep the saved password" in page


def test_blank_password_keeps_existing_and_remove_deletes(sent):
    alice = login("alice")
    _save(alice, "alice_ao3", "hunter2")
    _save(alice, "alice_renamed", "")
    stored = _stored("alice")
    assert stored.username == "alice_renamed"
    assert decrypt(stored.password_encrypted) == "hunter2"

    _save(alice, "", remove="1")
    assert _stored("alice") is None


def test_new_login_requires_password_and_known_site(sent):
    alice = login("alice")
    _save(alice, "alice_ao3", "")
    assert _stored("alice") is None
    assert "Enter your AO3 password" in alice.get("/settings").text
    assert alice.post("/settings/logins/evil.example", data={"username": "x", "password": "y"}).status_code == 404


def test_logins_are_private_per_user(sent):
    alice, bob = login("alice"), login("bob")
    _save(alice, "alice_ao3", "hunter2")
    assert "alice_ao3" not in bob.get("/settings").text


def test_import_uses_requesting_users_login(fake_site, sent):
    alice, bob = login("alice"), login("bob")
    _save(alice, "alice_ao3", "a-pass")
    _save(bob, "bob_ao3", "b-pass")
    bob.post("/add", data={"urls": fake_site.add_work("1", "Locked", chapters=1)})
    drain()
    assert fake_site.logins_seen == [("bob_ao3", "b-pass")]


def test_import_without_any_login(fake_site, sent):
    login("alice").post("/add", data={"urls": fake_site.add_work("1", "Open", chapters=1)})
    drain()
    assert fake_site.logins_seen == [None]


def test_scheduled_update_uses_importer_then_follower_login(fake_site, sent):
    alice, bob = login("alice"), login("bob")
    alice.post("/add", data={"urls": fake_site.add_work("1", "Locked", chapters=1)})
    drain()
    with SessionLocal() as db:
        work_id = db.scalar(select(Work.id))
    bob.post(f"/works/{work_id}/follow")

    def run_scheduled():
        with SessionLocal() as db:
            db.get(Work, work_id).last_checked_at = utcnow() - timedelta(days=30)
            db.commit()
            schedule_due_updates(db)
            db.commit()
        drain()

    _save(bob, "bob_ao3", "b-pass")
    run_scheduled()
    assert fake_site.logins_seen[-1] == ("bob_ao3", "b-pass"), "falls back to a follower's login"

    _save(alice, "alice_ao3", "a-pass")
    run_scheduled()
    assert fake_site.logins_seen[-1] == ("alice_ao3", "a-pass"), "prefers whoever imported the work"


def test_manual_check_uses_clicking_users_login(fake_site, sent):
    alice, bob = login("alice"), login("bob")
    _save(alice, "alice_ao3", "a-pass")
    _save(bob, "bob_ao3", "b-pass")
    alice.post("/add", data={"urls": fake_site.add_work("1", "Locked", chapters=1)})
    drain()
    bob.post("/works/1/check")
    drain()
    assert fake_site.logins_seen[-1] == ("bob_ao3", "b-pass")


def test_unreadable_password_is_skipped(fake_site, sent):
    alice = login("alice")
    _save(alice, "alice_ao3", "a-pass")
    with SessionLocal() as db:
        db.get(SiteLogin, (user_id("alice"), AO3)).password_encrypted = "not-a-valid-token"
        db.commit()
    alice.post("/add", data={"urls": fake_site.add_work("1", "Locked", chapters=1)})
    drain()
    assert fake_site.logins_seen == [None]
