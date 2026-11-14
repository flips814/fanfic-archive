# Fanfic Archive

A self-hosted, multi-user fanfiction library that runs in Docker.

- **Import** works from AO3, FanFiction.net, Wattpad, Tumblr, and ~100 more sites (via [FanFicFare](https://github.com/JimmXinu/FanFicFare)) by pasting URLs.
- **Auto-update**: a background worker re-checks works on a schedule and imports new chapters.
- **Notify** followers of a work when new chapters arrive: Discord webhook, email, or any [Apprise](https://github.com/caronc/apprise) target (Telegram, ntfy, Pushover, Slack, Matrix, ...).
- **OIDC login** (Authentik, Authelia, Keycloak, Zitadel, Pocket ID, Google, ...), so every import is tied to a real user.
- **Who imported what**: works and chapters record the user who imported them; the Activity page is a full audit log of every import and update check; the Users page shows per-user totals.
- Read in the browser or **download EPUB**. Small JSON API under `/api`.
- **Translate** any chapter in the reader, using a bundled [LibreTranslate](https://github.com/LibreTranslate/LibreTranslate) server (open source, runs on your machine, no text leaves your server).

## Quick start

No configuration is needed to start:

```bash
git clone https://github.com/flips814/fanfic-archive.git && cd fanfic-archive
docker compose up -d --build
docker compose ps                                        # db, web, worker, flaresolverr, libretranslate: all "healthy"
docker compose logs web | grep -A2 "First-time setup"   # copy the setup code
```

The stack is five containers under the `fanfic-archive` project: **db** (PostgreSQL, data in the `pgdata` volume), **web** (the site), **worker** (imports and update checks), **flaresolverr** (gets past Cloudflare for FanFiction.net) and **libretranslate** (machine translation for the reader). Each has a health check, restarts automatically, and keeps at most 30 MB of logs.

Open `http://localhost:8000/setup`, enter the setup code, name your archive, and create the admin account. You'll land in the **Admin** panel, which has a setup checklist and every setting:

| Admin page | What you can change |
| --- | --- |
| Overview | Setup checklist, worker status, totals, waiting and failed jobs (retry / cancel), "check every work now" |
| Users | Make or remove admins, disable or enable accounts, create username/password accounts, reset passwords |
| General | Site name, public URL |
| Sign-in | OIDC provider, client ID/secret, admin emails/group, with a "Test connection" button and the callback URL to copy |
| Email | SMTP server, with "Send me a test email" |
| Notifications | Server-wide Discord webhook and Apprise URLs, with a test button |
| Update checks | How often works are checked, politeness delay |
| Importers | 18+ checks, FlareSolverr (with a test button), extra FanFicFare INI settings |
| Translation | LibreTranslate server and API key, with a "Test translation" button |

Changes apply immediately; the worker picks them up within a minute. Settings saved in the UI override `.env`, and each section has "Reset to environment values". Secrets (client secret, SMTP password, webhook) are stored encrypted.

The admin account created during setup signs in with a username and password, so you can always get in, even before SSO is configured or if it breaks. Password sign-in is rate-limited.

`.env` is optional (see `.env.example`). It's useful for infrastructure settings (`DATABASE_URL`, `SECRET_KEY`, ports) or if you prefer config files. If `SECRET_KEY` is empty, one is generated on first launch and kept in the database.

### Configure OIDC

At your identity provider, create an OIDC client (confidential, authorization-code flow):

| Setting | Value |
| --- | --- |
| Redirect URI | `${BASE_URL}/auth/callback` |
| Post-logout redirect URI | `${BASE_URL}/` |
| Scopes | `openid email profile` (+ `groups` if you use `OIDC_ADMIN_GROUP`) |

Then enter the issuer, client ID and secret under **Admin → Sign-in** (or set `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET` in `.env`). Admins are users whose email is in the admin emails list, who are in the admin group, or whom an admin promoted under **Admin → Users**; promotions made by an admin survive later SSO sign-ins.

The **web** container must be able to reach the issuer URL (for discovery and token exchange), and so must your browser. If your IdP runs on the same Docker host, use its public hostname rather than `localhost`.

To try the app without an IdP, set `DEV_LOGIN=true` and visit `/dev-login?as=alice`. Don't leave this on for anything exposed to the internet.

### Notifications

- **Per user**: each user sets their own Discord webhook, email, and Apprise URLs under *Settings* in the UI.
- **Server-wide**: `DISCORD_WEBHOOK_URL` / `APPRISE_URLS` in `.env` receive every "new work imported" and "new chapter" event (e.g. a shared #fanfic channel).
- **Email** needs `SMTP_*` set in `.env`.

### Sites

| Site | Notes |
| --- | --- |
| AO3 | For works restricted to logged-in users, each user adds their own AO3 login under *Settings → Site logins* (stored encrypted with `SECRET_KEY`). An import or "Check now" uses the clicking user's login; scheduled update checks use the login of whoever imported the work, then any follower's. A login under *Admin → Importers → Extra FanFicFare settings* (or in `config/personal.ini`) is only a server-wide fallback. AO3 rate-limits aggressively and may block cloud/VPN IPs (HTTP 418/429). |
| FanFiction.net | Behind Cloudflare, so requests go through the bundled [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) container. It starts with the rest of the stack and is used automatically (set `FLARESOLVERR_URL=` empty in `.env`, or clear it under *Admin → Importers*, to stop using it); `FLARESOLVERR_SITES` lists which sites use it (FanFiction.net and FictionPress by default). Each page takes several seconds through FlareSolverr, and Cloudflare can still block some IPs. |
| Wattpad | Works out of the box. |
| Tumblr | No account or API key needed (uses Tumblr's legacy v1 read API, which Tumblr could retire without notice; blogs visible only to logged-in users can't be read). Paste a single post URL (one-chapter work) or a blog tag page like `https://BLOG.tumblr.com/tagged/my-fic` (every post under that tag becomes a chapter, oldest first, and new posts become new chapters). |
| Others | Anything [FanFicFare supports](https://github.com/JimmXinu/FanFicFare/wiki/SupportedSites). |

### Translation

Every chapter has a **Translate** button next to *Reading settings*. Pick a language and the chapter (title and text, formatting kept) is translated by the bundled [LibreTranslate](https://github.com/LibreTranslate/LibreTranslate) container, an open-source (AGPL) translation server built on [Argos Translate](https://github.com/argosopentech/argos-translate) models. Nothing is sent to an outside service.

- The chosen language sticks: following chapters open already translated, and the next chapter is translated in the background while you read. **Show original** switches back and turns it off.
- Each chapter is translated once per language and stored in the database, so the next reader who asks gets it instantly. A chapter that changes at the source is translated again.
- The source language comes from the work's metadata, or is detected when the site doesn't say.
- Speed depends on your CPU: roughly 1,000 words every 15–20 seconds. The reader shows progress while it works.
- `TRANSLATE_LANGUAGES` in `.env` picks which languages the container downloads and offers (default: `en,es,fr,de,it,pt,ru,zh,ja,ko,pl,nl,id,tr,uk,vi`; English is always needed, as other pairs go through it). Each language costs a few hundred MB of disk and memory. The **first start downloads the models**, which takes several minutes (10–20 for the default list); `docker compose ps` shows `libretranslate` as "health: starting" until it's ready, and "Test translation" can't connect until then. The models are kept in the `translate-models` volume. Run `docker compose up -d` again after changing the list.
- To use another LibreTranslate server (e.g. libretranslate.com), set its URL and API key under **Admin → Translation**. To turn translation off, clear the URL there, or set `TRANSLATE_URL=` (empty) in `.env`; you can then also remove the `libretranslate` service from `docker-compose.yml`.
- Machine translation gets names, slang and tone wrong at times; the reader says so above every translated chapter.

## How updates work

- The worker queues an update check for every work with auto-update on when it's due: in-progress works every `UPDATE_INTERVAL_MINUTES` (default 6h), completed works every `COMPLETED_UPDATE_INTERVAL_MINUTES` (default 1 week, `0` = never).
- A check fetches the work's chapter list and downloads only chapters it doesn't have yet (matched by chapter URL / Tumblr post ID). Metadata (title, tags, status, ...) is refreshed too. Chapters that disappear from the source are kept and marked "removed from source".
- Imports a user requests jump ahead of scheduled checks. The worker pauses `DELAY_BETWEEN_WORKS_SECONDS` between works, and FanFicFare's `slow_down_sleep_time` between pages.
- Chapters fetched by a scheduled check are credited to the **auto-updater**; chapters from imports or "Check now" are credited to the user who clicked.
- Run a single worker. The job queue uses row locks, so several workers won't double-run jobs, but they'll hit the sites harder.

## API

All endpoints use the same login session as the UI.

| Method | Path | |
| --- | --- | --- |
| GET | `/api/me` | current user |
| GET | `/api/works?limit=&offset=` | list works |
| GET | `/api/works/{id}` | work with chapter list and who imported each chapter |
| POST | `/api/works/{id}/check` | queue an update check |
| POST | `/api/imports` | `{"urls": ["..."]}` queue imports |
| GET | `/api/jobs` | recent import/update jobs (audit log) |

## Testing

On Windows without Docker Desktop, install Docker Engine into WSL 2 once:

```powershell
.\scripts\setup-docker-wsl.ps1
```

Then start the test stack. It includes a fake SSO provider, Mailpit to catch emails, and ntfy for push notifications, so no real accounts are needed:

```powershell
.\scripts\test-stack.ps1 up      # app on http://localhost:8000
.\scripts\test-stack.ps1 test    # automated test suite (pytest)
.\scripts\test-stack.ps1 logs    # follow web + worker logs
.\scripts\test-stack.ps1 reset   # wipe the test database
```

| Service | URL | Use |
| --- | --- | --- |
| App | http://localhost:8000 | |
| Fake SSO | http://localhost:8080 | Log in as any username. Paste claims like `{"name":"Alice","preferred_username":"alice","email":"alice@test.local","groups":["admins"]}` to set a name/email/admin. |
| Mailpit | http://localhost:8025 | Every email the app sends |
| ntfy | http://localhost:8090/fanfic-all | Server-wide notifications. Users can add `ntfy://ntfy/<topic>` in their settings and watch `http://localhost:8090/<topic>`. |

The test stack's LibreTranslate only downloads English, Spanish, French and Portuguese, so it's ready within a few minutes.

WSL normally shuts Ubuntu (and Docker) down when no `wsl` command is running. `up` therefore starts a per-user scheduled task, `FanficArchive-WSL-KeepAlive` (no admin needed), which keeps it running; `down`/`reset` stop that task.

The test stack checks for updates every 5 minutes. To simulate an author posting a new chapter, delete a work's last chapter, then click **Check for updates now**:

```powershell
wsl docker compose exec db psql -U fanfic -c "DELETE FROM chapters WHERE id = (SELECT max(id) FROM chapters WHERE work_id = 1);"
```

## Development without Docker

```bash
python -m venv .venv && .venv/bin/pip install -r requirements.txt   # Windows: .venv\Scripts\pip
export DATABASE_URL=sqlite:///./dev.db DEV_LOGIN=true FANFICFARE_PERSONAL_INI=config/personal.ini
uvicorn app.main:app --reload      # terminal 1
python -m app.worker               # terminal 2
```

## Backups

All data, including stored translations, lives in the `pgdata` volume (the `translate-models` volume only holds downloadable models):

```bash
docker compose exec db pg_dump -U fanfic fanfic | gzip > fanfic-$(date +%F).sql.gz
```

## A note on site terms

Use this for personal archiving of works you read. Respect authors' wishes and each site's terms. Don't re-host other people's works publicly.
