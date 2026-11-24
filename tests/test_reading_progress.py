"""Synced reading progress across web API clients."""

from tests.conftest import drain, login


def test_reading_progress_save_get_clear(fake_site, sent):
    alice = login("alice")
    url = fake_site.add_work("1", "Bookmark Me", chapters=3)
    alice.post("/add", data={"urls": url, "visibility": "public"})
    drain()
    work_id = alice.get("/api/works").json()["works"][0]["id"]

    assert alice.get(f"/api/works/{work_id}/reading-progress").json()["progress"] is None

    saved = alice.put(
        f"/api/works/{work_id}/reading-progress",
        json={"chapter_number": 2, "scroll_fraction": 0.4},
    ).json()["progress"]
    assert saved["work_id"] == work_id
    assert saved["chapter_number"] == 2
    assert abs(saved["scroll_fraction"] - 0.4) < 0.001

    listing = alice.get("/api/reading-progress").json()["progress"]
    match_progress = next(p for p in listing if p["work_id"] == work_id)
    assert match_progress["chapter_number"] == 2
    assert match_progress["work"]["id"] == work_id
    assert match_progress["work"]["title"] == "Bookmark Me"

    detail = alice.get(f"/api/works/{work_id}").json()
    assert detail["resume_chapter"] == 2
    assert detail["reading_progress"]["chapter_number"] == 2

    works = alice.get("/api/works").json()["works"]
    match = next(w for w in works if w["id"] == work_id)
    assert match["resume_chapter"] == 2

    assert match_progress["caught_up"] is False
    assert match_progress["last_chapter"] == 3

    continue_only = alice.get("/api/works", params={"continue_reading": "1"}).json()
    assert continue_only["total"] >= 1
    assert any(w["id"] == work_id for w in continue_only["works"])

    library = alice.get("/")
    assert library.status_code == 200
    body = library.text
    assert "Continue reading" in body
    assert "Continue · Ch 2" in body

    # Reach the final chapter → Finished / Caught up.
    alice.put(
        f"/api/works/{work_id}/reading-progress",
        json={"chapter_number": 3, "scroll_fraction": 1},
    )
    done = alice.get("/api/reading-progress").json()["progress"]
    done_row = next(p for p in done if p["work_id"] == work_id)
    assert done_row["caught_up"] is True
    assert done_row["catch_up_label"] in ("Caught up", "Finished")

    finished_only = alice.get("/api/works", params={"finished": "1"}).json()
    assert any(w["id"] == work_id for w in finished_only["works"])
    assert alice.get("/api/works", params={"continue_reading": "1"}).json()["total"] == 0

    finished_page = alice.get("/")
    assert "Finished" in finished_page.text or "caught up" in finished_page.text.lower()

    assert alice.delete(f"/api/works/{work_id}/reading-progress").json()["ok"] is True
    assert alice.get(f"/api/works/{work_id}/reading-progress").json()["progress"] is None
    assert alice.get(f"/api/works/{work_id}").json()["resume_chapter"] is None


def test_reading_progress_rejects_bad_chapter(fake_site, sent):
    alice = login("alice")
    url = fake_site.add_work("2", "Short", chapters=1)
    alice.post("/add", data={"urls": url})
    drain()
    work_id = alice.get("/api/works").json()["works"][0]["id"]

    bad = alice.put(
        f"/api/works/{work_id}/reading-progress",
        json={"chapter_number": 9, "scroll_fraction": 0},
    )
    assert bad.status_code == 400


def test_reading_progress_private_visibility(fake_site, sent):
    alice = login("alice")
    bob = login("bob")
    url = fake_site.add_work("3", "Private Read", chapters=2)
    alice.post("/add", data={"urls": url, "visibility": "private"})
    drain()
    work_id = alice.get("/api/works").json()["works"][0]["id"]

    alice.put(
        f"/api/works/{work_id}/reading-progress",
        json={"chapter_number": 1, "scroll_fraction": 0.1},
    )
    assert bob.get(f"/api/works/{work_id}/reading-progress").status_code == 404
    assert bob.put(
        f"/api/works/{work_id}/reading-progress",
        json={"chapter_number": 1, "scroll_fraction": 0},
    ).status_code == 404
