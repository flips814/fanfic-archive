import os
import tempfile

_tmpdir = tempfile.mkdtemp(prefix="fanfic-tests-")
os.environ.update(
    DATABASE_URL=f"sqlite:///{_tmpdir}/test.db",
    DEV_LOGIN="true",
    SECRET_KEY="test",
    BASE_URL="http://testserver",
    FANFICFARE_PERSONAL_INI="",
    OIDC_ISSUER="",
    OIDC_DISCOVERY_URL="",
    OIDC_CLIENT_ID="",
    APPRISE_URLS="",
    DISCORD_WEBHOOK_URL="",
    SMTP_HOST="",
    UPDATE_INTERVAL_MINUTES="60",
    COMPLETED_UPDATE_INTERVAL_MINUTES="1440",
    TRANSLATE_URL="",
    TRANSLATE_API_KEY="",
)

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import select  # noqa: E402

from app import importers, notify  # noqa: E402
from app.db import Base, SessionLocal, engine, init_db  # noqa: E402
from app.importers.base import ChapterRef, Importer, ImportError_, WorkMeta, WorkSource  # noqa: E402
from app.jobs import claim_next_job, run_job  # noqa: E402
from app.main import app  # noqa: E402
from app.models import User  # noqa: E402

FAKE_PREFIX = "https://fake.test/works/"


class FakeSite:
    """In-memory fanfic site. Tests add works/chapters and the importer reads them."""

    def __init__(self):
        self.works: dict[str, dict] = {}
        self.fetches: list[str] = []
        self.fail: dict[str, str] = {}
        self.logins_seen: list[tuple[str, str] | None] = []

    def add_work(self, work_id: str, title: str, chapters: int, status: str = "In-Progress") -> str:
        self.works[work_id] = {
            "title": title,
            "status": status,
            "chapters": [(f"c{i}", f"Chapter {i}", f"<p>Text of chapter {i}</p>") for i in range(1, chapters + 1)],
        }
        return FAKE_PREFIX + work_id

    def add_chapter(self, work_id: str, title: str | None = None) -> None:
        chapters = self.works[work_id]["chapters"]
        n = len(chapters) + 1
        chapters.append((f"c{n}-{len(self.fetches)}", title or f"Chapter {n}", f"<p>New chapter {n}</p>"))


class FakeSource(WorkSource):
    def __init__(self, site: FakeSite, url: str):
        work_id = url.removeprefix(FAKE_PREFIX).strip("/")
        if work_id in site.fail:
            raise ImportError_(site.fail[work_id])
        if work_id not in site.works:
            raise ImportError_(f"Work not found: {url}")
        data = site.works[work_id]
        self.site, self.data = site, data
        self.meta = WorkMeta(
            site="fake.test",
            url=FAKE_PREFIX + work_id,
            source_id=work_id,
            title=data["title"],
            author="Test Author",
            summary_html="<p>Summary</p><script>alert(1)</script>",
            status=data["status"],
            fandoms=["Testing Fandom"],
            tags=["fluff"],
            chapters=[ChapterRef(key=f"{work_id}/{key}", title=title, url=f"{FAKE_PREFIX}{work_id}/{key}")
                      for key, title, _ in data["chapters"]],
        )

    def fetch_chapter(self, index: int, ref: ChapterRef) -> str:
        self.site.fetches.append(ref.key)
        return self.data["chapters"][index][2]


class FakeImporter(Importer):
    name = "fake"

    def __init__(self, site: FakeSite):
        self.site = site

    def matches(self, url: str) -> bool:
        return url.startswith(FAKE_PREFIX)

    def open(self, url: str, logins=None) -> WorkSource:
        self.site.logins_seen.append((logins or {}).get("archiveofourown.org"))
        return FakeSource(self.site, url)


@pytest.fixture(autouse=True)
def fresh_db():
    from app import auth, settings_store
    from app.config import settings

    Base.metadata.drop_all(engine)
    init_db()
    with SessionLocal() as db:
        settings_store.set_state(db, settings_store.SETUP_COMPLETE, True)
        db.commit()
    settings.apply({})
    auth.configure_oauth()
    auth._FAILED.clear()
    yield
    settings.apply({})


@pytest.fixture
def fake_site():
    site = FakeSite()
    importer = FakeImporter(site)
    importers.IMPORTERS.insert(0, importer)
    yield site
    importers.IMPORTERS.remove(importer)


@pytest.fixture
def sent(monkeypatch):
    """Capture outgoing notifications instead of sending them."""
    log = {"apprise": [], "email": []}

    def fake_apprise(urls, title, body):
        if urls:
            log["apprise"].append({"urls": list(urls), "title": title, "body": body})
        return True

    def fake_email(to, subject, body):
        if to:
            log["email"].append({"to": to, "subject": subject, "body": body})
        return True

    monkeypatch.setattr(notify, "_apprise_send", fake_apprise)
    monkeypatch.setattr(notify, "send_email", fake_email)
    return log


def login(name: str, admin: bool = True) -> TestClient:
    client = TestClient(app)
    client.get(f"/dev-login?as={name}")
    if not admin:
        with SessionLocal() as db:
            user = db.scalar(select(User).where(User.oidc_sub == name))
            user.is_admin = False
            db.commit()
    return client


def user_id(name: str) -> int:
    with SessionLocal() as db:
        return db.scalar(select(User.id).where(User.oidc_sub == name))


def drain() -> int:
    """Run queued jobs like the worker does; returns how many ran."""
    ran = 0
    while True:
        with SessionLocal() as db:
            job_id = claim_next_job(db)
        if job_id is None:
            return ran
        run_job(job_id)
        ran += 1
