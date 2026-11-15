"""Private vs public work visibility."""

from dataclasses import replace

from sqlalchemy import select

from app import notify
from app.db import SessionLocal
from app.models import ImportJob, Work
from tests.conftest import drain, login, user_id


def _import_private(fake_site, client, work_id="1", title="Secret Fic", chapters=2) -> tuple[str, int]:
    url = fake_site.add_work(work_id, title, chapters=chapters)
    client.post("/add", data={"urls": url, "visibility": "private"})
    assert drain() == 1
    with SessionLocal() as db:
        work = db.scalars(select(Work).where(Work.source_url == url)).one()
        assert work.visibility == "private"
        return url, work.id


def test_private_import_hidden_from_other_users(fake_site, sent):
    alice, bob = login("alice"), login("bob")
    _, work_id = _import_private(fake_site, alice)

    assert "Secret Fic" in alice.get("/").text
    assert "Private" in alice.get("/").text
    assert alice.get(f"/works/{work_id}").status_code == 200
    assert alice.get(f"/works/{work_id}/chapters/1").status_code == 200
    assert alice.get(f"/works/{work_id}/epub").status_code == 200

    assert "Secret Fic" not in bob.get("/").text
    assert bob.get(f"/works/{work_id}").status_code == 404
    assert bob.get(f"/works/{work_id}/chapters/1").status_code == 404
    assert bob.get(f"/works/{work_id}/epub").status_code == 404
    assert bob.post(f"/works/{work_id}/follow").status_code == 404

    listing = bob.get("/api/works").json()
    assert all(w["id"] != work_id for w in listing["works"])
    assert bob.get(f"/api/works/{work_id}").status_code == 404
    assert bob.get(f"/api/works/{work_id}/chapters/1").status_code == 404

    alice_list = alice.get("/api/works").json()
    assert any(w["id"] == work_id and w["visibility"] == "private" for w in alice_list["works"])


def test_owner_can_toggle_private_to_public(fake_site, sent):
    alice, bob = login("alice"), login("bob")
    _, work_id = _import_private(fake_site, alice)

    resp = alice.post(
        f"/works/{work_id}/visibility",
        data={"visibility": "public"},
        follow_redirects=False,
    )
    assert resp.status_code == 303
    assert bob.get(f"/works/{work_id}").status_code == 200
    assert "Secret Fic" in bob.get("/").text
    assert bob.get(f"/api/works/{work_id}").json()["visibility"] == "public"


def test_owner_can_toggle_public_to_private_and_back(fake_site, sent):
    """Shared (public) → only-you (private) → shared again, via web form and API."""
    alice, bob = login("alice"), login("bob")
    url = fake_site.add_work("1", "Toggle Fic", chapters=2)
    alice.post("/add", data={"urls": url, "visibility": "public"})
    assert drain() == 1
    with SessionLocal() as db:
        work = db.scalars(select(Work).where(Work.source_url == url)).one()
        assert work.visibility == "public"
        work_id = work.id

    # Public: everyone on the site can find and read it.
    assert "Toggle Fic" in bob.get("/").text
    assert bob.get(f"/works/{work_id}").status_code == 200
    assert bob.get(f"/works/{work_id}/chapters/1").status_code == 200
    page = alice.get(f"/works/{work_id}").text
    assert "Public" in page
    assert "Make private" in page

    # Switch to private — only the importer can still read it.
    resp = alice.post(
        f"/works/{work_id}/visibility",
        data={"visibility": "private"},
        follow_redirects=False,
    )
    assert resp.status_code == 303
    with SessionLocal() as db:
        assert db.get(Work, work_id).visibility == "private"

    assert "Toggle Fic" in alice.get("/").text
    assert "Private" in alice.get("/").text
    assert alice.get(f"/works/{work_id}").status_code == 200
    assert alice.get(f"/works/{work_id}/chapters/1").status_code == 200
    assert "Make public" in alice.get(f"/works/{work_id}").text

    assert "Toggle Fic" not in bob.get("/").text
    assert bob.get(f"/works/{work_id}").status_code == 404
    assert bob.get(f"/works/{work_id}/chapters/1").status_code == 404
    assert bob.get(f"/api/works/{work_id}").status_code == 404
    assert all(w["id"] != work_id for w in bob.get("/api/works").json()["works"])

    # Switch back to public via API — Bob can see it again.
    assert alice.post(
        f"/api/works/{work_id}/visibility", json={"visibility": "public"}
    ).json()["visibility"] == "public"
    assert bob.get(f"/works/{work_id}").status_code == 200
    assert "Toggle Fic" in bob.get("/").text
    assert bob.get(f"/api/works/{work_id}").json()["visibility"] == "public"
    assert "Make private" in alice.get(f"/works/{work_id}").text


def test_bob_cannot_reimport_alices_private_url(fake_site, sent):
    alice, bob = login("alice"), login("bob")
    url, _ = _import_private(fake_site, alice)

    bob.post("/add", data={"urls": url, "visibility": "public"})
    # Collision fails immediately at enqueue — no worker run needed.
    with SessionLocal() as db:
        job = db.scalars(select(ImportJob).order_by(ImportJob.id.desc())).first()
        assert job.status == "failed"
        assert "private work" in (job.message or "").lower()
        assert db.scalars(select(Work)).one().added_by_id == user_id("alice")


def test_public_import_still_shared(fake_site, sent):
    alice, bob = login("alice"), login("bob")
    url = fake_site.add_work("1", "Shared Fic", chapters=1)
    alice.post("/add", data={"urls": url, "visibility": "public"})
    drain()
    with SessionLocal() as db:
        work = db.scalars(select(Work)).one()
        assert work.visibility == "public"
        work_id = work.id
    assert "Shared Fic" in bob.get("/").text
    assert bob.get(f"/works/{work_id}").status_code == 200


def test_private_import_skips_global_notify(fake_site, sent, monkeypatch):
    monkeypatch.setattr(
        notify,
        "settings",
        replace(
            notify.settings,
            apprise_urls=["ntfy://ntfy/all"],
            discord_webhook_url="https://discord.com/api/webhooks/1/token",
        ),
    )
    alice = login("alice")
    _import_private(fake_site, alice, title="Quiet Fic")
    assert sent["apprise"] == []


def test_public_import_sends_server_announcement(fake_site, sent, monkeypatch):
    monkeypatch.setattr(
        notify,
        "settings",
        replace(
            notify.settings,
            apprise_urls=["ntfy://ntfy/all"],
            discord_webhook_url="https://discord.com/api/webhooks/1/token",
        ),
    )
    alice = login("alice")
    url = fake_site.add_work("1", "Loud Fic", chapters=1)
    alice.post("/add", data={"urls": url, "visibility": "public"})
    drain()
    assert any("Loud Fic" in n.get("title", "") or "Loud Fic" in n.get("body", "") for n in sent["apprise"])
    assert any("discord://" in u for n in sent["apprise"] for u in n["urls"])


def test_library_visibility_filter(fake_site, sent):
    alice = login("alice")
    public_url = fake_site.add_work("1", "Public Filter Fic", chapters=1)
    private_url = fake_site.add_work("2", "Private Filter Fic", chapters=1)
    alice.post("/add", data={"urls": public_url, "visibility": "public"})
    alice.post("/add", data={"urls": private_url, "visibility": "private"})
    assert drain() == 2

    public_page = alice.get("/?visibility=public").text
    assert "Public Filter Fic" in public_page
    assert "Private Filter Fic" not in public_page

    private_page = alice.get("/?visibility=private").text
    assert "Private Filter Fic" in private_page
    assert "Public Filter Fic" not in private_page

    api_public = alice.get("/api/works", params={"visibility": "public"}).json()
    assert {w["title"] for w in api_public["works"]} == {"Public Filter Fic"}
    api_private = alice.get("/api/works", params={"visibility": "private"}).json()
    assert {w["title"] for w in api_private["works"]} == {"Private Filter Fic"}


def test_private_import_only_on_importers_activity(fake_site, sent):
    alice, bob = login("alice"), login("bob")
    url, _ = _import_private(fake_site, alice, title="Quiet Activity")
    alice_activity = alice.get("/activity").text
    bob_activity = bob.get("/activity").text
    assert "Quiet Activity" in alice_activity
    assert "Private" in alice_activity
    assert "Quiet Activity" not in bob_activity
    assert url not in bob_activity
    assert all(j.get("work_title") != "Quiet Activity" for j in bob.get("/api/jobs").json()["jobs"])


def test_private_import_not_in_others_notifications(fake_site, sent):
    alice, bob = login("alice"), login("bob")
    _import_private(fake_site, alice)
    notes = bob.get("/api/notifications").json()["notifications"]
    assert not any("Secret Fic" in (n.get("body") or "") or "Secret Fic" in (n.get("work_title") or "") for n in notes)


def test_api_import_visibility(fake_site, sent):
    alice, bob = login("alice"), login("bob")
    url = fake_site.add_work("1", "Api Private", chapters=1)
    jobs = alice.post("/api/imports", json={"urls": [url], "visibility": "private"}).json()
    assert jobs["jobs"][0]["visibility"] == "private"
    drain()
    work_id = alice.get("/api/works").json()["works"][0]["id"]
    assert alice.get(f"/api/works/{work_id}").json()["visibility"] == "private"
    assert bob.get(f"/api/works/{work_id}").status_code == 404
    assert alice.post(f"/api/works/{work_id}/visibility", json={"visibility": "public"}).json()["visibility"] == "public"
    assert bob.get(f"/api/works/{work_id}").status_code == 200
