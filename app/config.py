import copy
import os
from dataclasses import dataclass, field


def _bool(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None or value == "":
        return default
    return value.strip().lower() in ("1", "true", "yes", "on")


def _int(name: str, default: int) -> int:
    value = os.getenv(name)
    return int(value) if value else default


def _list(name: str) -> list[str]:
    raw = os.getenv(name, "")
    return [item.strip() for item in raw.replace("\n", ",").split(",") if item.strip()]


@dataclass
class Settings:
    """Environment provides defaults; admins can override the editable ones (see FIELDS) from the UI."""

    # environment-only (needed before the database is reachable)
    database_url: str = os.getenv("DATABASE_URL", "postgresql+psycopg://fanfic:fanfic@db:5432/fanfic")
    secret_key: str = os.getenv("SECRET_KEY", "")
    dev_login: bool = _bool("DEV_LOGIN")
    fanficfare_personal_ini: str = os.getenv("FANFICFARE_PERSONAL_INI", "/config/personal.ini")

    # general
    site_name: str = os.getenv("SITE_NAME", "Fanfic Archive")
    base_url: str = os.getenv("BASE_URL", "http://localhost:8000").rstrip("/")

    # sign-in
    oidc_provider_name: str = os.getenv("OIDC_PROVIDER_NAME", "SSO")
    oidc_issuer: str = os.getenv("OIDC_ISSUER", "").rstrip("/")
    oidc_discovery_url: str = os.getenv("OIDC_DISCOVERY_URL", "")
    oidc_client_id: str = os.getenv("OIDC_CLIENT_ID", "")
    oidc_client_secret: str = os.getenv("OIDC_CLIENT_SECRET", "")
    oidc_scopes: str = os.getenv("OIDC_SCOPES", "openid email profile")
    oidc_groups_claim: str = os.getenv("OIDC_GROUPS_CLAIM", "groups")
    oidc_admin_group: str = os.getenv("OIDC_ADMIN_GROUP", "")
    admin_emails: list[str] = field(default_factory=lambda: [e.lower() for e in _list("ADMIN_EMAILS")])
    # Browser-facing endpoint overrides, for IdPs whose discovery document advertises
    # a hostname only reachable from inside the Docker network.
    oidc_authorize_url: str = os.getenv("OIDC_AUTHORIZE_URL", "")
    oidc_end_session_url: str = os.getenv("OIDC_END_SESSION_URL", "")

    # update checks
    update_interval_minutes: int = _int("UPDATE_INTERVAL_MINUTES", 360)
    completed_update_interval_minutes: int = _int("COMPLETED_UPDATE_INTERVAL_MINUTES", 10080)
    worker_poll_seconds: int = _int("WORKER_POLL_SECONDS", 10)
    delay_between_works_seconds: int = _int("DELAY_BETWEEN_WORKS_SECONDS", 5)

    # importers
    fanficfare_is_adult: bool = _bool("FANFICFARE_IS_ADULT", True)
    fanficfare_ini: str = os.getenv("FANFICFARE_INI", "")
    flaresolverr_url: str = os.getenv("FLARESOLVERR_URL", "")
    flaresolverr_sites: list[str] = field(
        default_factory=lambda: _list("FLARESOLVERR_SITES") or ["www.fanfiction.net", "www.fictionpress.com"]
    )
    flaresolverr_timeout_ms: int = _int("FLARESOLVERR_TIMEOUT_MS", 59000)

    # notifications
    apprise_urls: list[str] = field(default_factory=lambda: _list("APPRISE_URLS"))
    discord_webhook_url: str = os.getenv("DISCORD_WEBHOOK_URL", "")
    smtp_host: str = os.getenv("SMTP_HOST", "")
    smtp_port: int = _int("SMTP_PORT", 587)
    smtp_user: str = os.getenv("SMTP_USER", "")
    smtp_password: str = os.getenv("SMTP_PASSWORD", "")
    smtp_from: str = os.getenv("SMTP_FROM", "")
    smtp_starttls: bool = _bool("SMTP_STARTTLS", True)
    smtp_ssl: bool = _bool("SMTP_SSL", False)

    @property
    def oidc_metadata_url(self) -> str:
        if self.oidc_discovery_url:
            return self.oidc_discovery_url
        if self.oidc_issuer:
            return f"{self.oidc_issuer}/.well-known/openid-configuration"
        return ""

    @property
    def oidc_enabled(self) -> bool:
        return bool(self.oidc_metadata_url and self.oidc_client_id)

    @property
    def email_enabled(self) -> bool:
        return bool(self.smtp_host and self.smtp_from)

    def apply(self, overrides: dict) -> None:
        """Set every editable field to its override, or back to the environment default."""
        for f in FIELDS:
            value = overrides[f.key] if f.key in overrides else getattr(_ENV_DEFAULTS, f.key)
            setattr(self, f.key, copy.deepcopy(value))
        self.base_url = self.base_url.rstrip("/")
        self.oidc_issuer = self.oidc_issuer.rstrip("/")
        self.admin_emails = [e.lower() for e in self.admin_emails]


@dataclass(frozen=True)
class Field:
    key: str
    label: str
    section: str
    kind: str = "str"  # str | url | int | bool | list | secret | text
    help: str = ""
    placeholder: str = ""
    advanced: bool = False


SECTIONS = {
    "general": ("General", "Your archive's name and the address people use to reach it."),
    "signin": ("Sign-in (SSO)", "Let people sign in with an OpenID Connect provider such as Authentik, Authelia, Keycloak, Pocket ID or Google."),
    "email": ("Email", "Outgoing mail for update notifications."),
    "notifications": ("Server-wide notifications", "Channels that hear about every new work and every new chapter, e.g. a shared Discord channel."),
    "updates": ("Update checks", "How often works are re-checked for new chapters."),
    "importers": ("Importers", "Settings for downloading from fanfic sites."),
}

FIELDS: list[Field] = [
    Field("site_name", "Site name", "general"),
    Field("base_url", "Public URL", "general", "url",
          "The address people type to reach this site, e.g. https://fics.example.com. Used for sign-in redirects and links in notifications.",
          "http://localhost:8000"),

    Field("oidc_provider_name", "Button label", "signin", help='Shown on the sign-in button as "Sign in with …".', placeholder="SSO"),
    Field("oidc_issuer", "Issuer URL", "signin", "url", "From your provider's app/client settings.",
          "https://auth.example.com/application/o/fanfic-archive"),
    Field("oidc_client_id", "Client ID", "signin"),
    Field("oidc_client_secret", "Client secret", "signin", "secret"),
    Field("oidc_scopes", "Scopes", "signin", placeholder="openid email profile"),
    Field("admin_emails", "Admin emails", "signin", "list",
          "People signing in with these emails become admins. One per line."),
    Field("oidc_admin_group", "Admin group", "signin", help="Members of this group (from the groups claim) become admins."),
    Field("oidc_groups_claim", "Groups claim", "signin", placeholder="groups", advanced=True),
    Field("oidc_discovery_url", "Discovery URL", "signin", "url",
          "Only if it isn't at <issuer>/.well-known/openid-configuration.", advanced=True),
    Field("oidc_authorize_url", "Browser authorize URL", "signin", "url",
          "Only if the provider's discovery document uses an address browsers can't reach.", advanced=True),
    Field("oidc_end_session_url", "Browser logout URL", "signin", "url", advanced=True),

    Field("smtp_host", "SMTP server", "email", placeholder="smtp.example.com"),
    Field("smtp_port", "Port", "email", "int", placeholder="587"),
    Field("smtp_user", "Username", "email"),
    Field("smtp_password", "Password", "email", "secret"),
    Field("smtp_from", "From address", "email", placeholder="Fanfic Archive <fanfic@example.com>"),
    Field("smtp_starttls", "Use STARTTLS (usually port 587)", "email", "bool"),
    Field("smtp_ssl", "Use SSL/TLS (usually port 465)", "email", "bool"),

    Field("discord_webhook_url", "Discord webhook URL", "notifications", "secret",
          "Channel settings → Integrations → Webhooks → New Webhook → Copy URL."),
    Field("apprise_urls", "Apprise URLs", "notifications", "list",
          "One per line: Telegram, ntfy, Pushover, Slack, Matrix and 100+ more."),

    Field("update_interval_minutes", "Check in-progress works every (minutes)", "updates", "int", placeholder="360"),
    Field("completed_update_interval_minutes", "Check completed works every (minutes, 0 = never)", "updates", "int",
          placeholder="10080"),
    Field("delay_between_works_seconds", "Pause between works (seconds)", "updates", "int",
          "Be polite to the sites; a few seconds is plenty.", "5"),
    Field("worker_poll_seconds", "Worker poll interval (seconds)", "updates", "int", placeholder="10", advanced=True),

    Field("fanficfare_is_adult", "Skip “are you 18+?” checks", "importers", "bool",
          "Needed for explicit works on AO3 and others."),
    Field("flaresolverr_url", "FlareSolverr URL", "importers", "url",
          "Gets past Cloudflare on FanFiction.net. Usually http://flaresolverr:8191 with the bundled container.",
          "http://flaresolverr:8191"),
    Field("flaresolverr_sites", "Sites that use FlareSolverr", "importers", "list", "One FanFicFare site domain per line."),
    Field("flaresolverr_timeout_ms", "FlareSolverr timeout (ms)", "importers", "int", advanced=True),
    Field("fanficfare_ini", "Extra FanFicFare settings (personal.ini)", "importers", "text",
          "Advanced: any FanFicFare INI options, e.g. a server-wide fallback login under [archiveofourown.org].",
          "[defaults]\nslow_down_sleep_time:2", advanced=True),
]
FIELD_MAP = {f.key: f for f in FIELDS}

settings = Settings()
_ENV_DEFAULTS = copy.deepcopy(settings)
