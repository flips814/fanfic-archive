from sqlalchemy import select
from sqlalchemy.orm import Session

from app.crypto import decrypt, encrypt
from app.models import SiteLogin, User, Work

# Sites users can store their own login for (FanFicFare site domain -> label).
LOGIN_SITES = {"archiveofourown.org": "AO3"}

Logins = dict[str, tuple[str, str]]


def user_logins(db: Session, user: User) -> dict[str, SiteLogin]:
    return {l.site: l for l in db.scalars(select(SiteLogin).where(SiteLogin.user_id == user.id))}


def save_login(db: Session, user: User, site: str, username: str, password: str) -> None:
    login = db.get(SiteLogin, (user.id, site))
    if login is None:
        login = SiteLogin(user_id=user.id, site=site)
        db.add(login)
    login.username = username
    if password:
        login.password_encrypted = encrypt(password)


def delete_login(db: Session, user: User, site: str) -> None:
    login = db.get(SiteLogin, (user.id, site))
    if login:
        db.delete(login)


def logins_for_job(db: Session, requester_id: int | None, work_id: int | None) -> Logins:
    """Pick credentials per site: the requesting user's first, then the work's importer, then its followers."""
    candidates: list[int] = []
    if requester_id:
        candidates.append(requester_id)
    work = db.get(Work, work_id) if work_id else None
    if work:
        if work.added_by_id:
            candidates.append(work.added_by_id)
        candidates.extend(f.user_id for f in work.followers)

    result: Logins = {}
    for user_id in dict.fromkeys(candidates):
        for login in db.scalars(select(SiteLogin).where(SiteLogin.user_id == user_id)):
            if login.site in result:
                continue
            password = decrypt(login.password_encrypted)
            if password is not None:
                result[login.site] = (login.username, password)
    return result
