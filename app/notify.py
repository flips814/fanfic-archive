import logging
import re
import smtplib
from email.message import EmailMessage

import apprise

from app.config import settings
from app.models import User

log = logging.getLogger(__name__)

_DISCORD_RE = re.compile(r"https://(?:\w+\.)?discord(?:app)?\.com/api/webhooks/(\d+)/([\w-]+)")


def discord_to_apprise(url: str) -> str:
    """Accept a raw Discord webhook URL or an Apprise discord:// URL."""
    match = _DISCORD_RE.match(url.strip())
    return f"discord://{match.group(1)}/{match.group(2)}/" if match else url.strip()


def _split(urls: str | None) -> list[str]:
    return [u.strip() for u in re.split(r"[\s,]+", urls or "") if u.strip()]


def _apprise_send(urls: list[str], title: str, body: str) -> bool:
    if not urls:
        return True
    ap = apprise.Apprise()
    for url in urls:
        if not ap.add(url):
            log.warning("Invalid Apprise URL skipped: %s", url.split("://")[0] + "://…")
    if not len(ap):
        return False
    return bool(ap.notify(title=title, body=body, body_format=apprise.NotifyFormat.MARKDOWN))


def send_email(to: str, subject: str, body: str) -> bool:
    if not settings.email_enabled or not to:
        return False
    msg = EmailMessage()
    msg["From"] = settings.smtp_from
    msg["To"] = to
    msg["Subject"] = subject
    msg.set_content(body.replace("**", ""))
    try:
        smtp_cls = smtplib.SMTP_SSL if settings.smtp_ssl else smtplib.SMTP
        with smtp_cls(settings.smtp_host, settings.smtp_port, timeout=30) as smtp:
            if settings.smtp_starttls and not settings.smtp_ssl:
                smtp.starttls()
            if settings.smtp_user:
                smtp.login(settings.smtp_user, settings.smtp_password)
            smtp.send_message(msg)
        return True
    except Exception:
        log.exception("Email to %s failed", to)
        return False


def user_channels(user: User) -> list[str]:
    urls = _split(user.apprise_urls)
    if user.discord_webhook_url:
        urls.append(discord_to_apprise(user.discord_webhook_url))
    return urls


def notify_user(user: User, title: str, body: str) -> None:
    try:
        _apprise_send(user_channels(user), title, body)
    except Exception:
        log.exception("Apprise notification for user %s failed", user.id)
    if user.notify_email:
        send_email(user.notify_email_address or user.email or "", title, body)


def notify_global(title: str, body: str) -> None:
    """Admin/broadcast channels configured via env (APPRISE_URLS, DISCORD_WEBHOOK_URL)."""
    urls = list(settings.apprise_urls)
    if settings.discord_webhook_url:
        urls.append(discord_to_apprise(settings.discord_webhook_url))
    try:
        _apprise_send(urls, title, body)
    except Exception:
        log.exception("Global notification failed")
