# Fanfic Archive for Android

Open-source Jetpack Compose client for a [self-hosted Fanfic Archive](../README.md) server.

Point the app at your archive URL, sign in with **SSO** (OIDC) or a **local** username/password, then browse the library, read chapters, follow works, and queue URL imports.

## Requirements

- Android 8.0+ (API 26)
- A running Fanfic Archive Docker stack with SSO and/or at least one local account
- Network reachability to that server (HTTPS preferred; LAN HTTP supported)

When `/api/info` reports OIDC, the login screen offers **Sign in with …** and opens the archive’s SSO flow in an in-app browser, then reuses the session cookie for the API.

## Build

Use **JDK 17** (Android Studio’s embedded JBR 21+ can break the Android Gradle Plugin). Then open `android/` in [Android Studio](https://developer.android.com/studio) or:

```bash
cd android
./gradlew assembleDebug          # Linux / macOS
.\gradlew.bat assembleDebug      # Windows
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## Connect to your server

1. Start the archive: `docker compose up -d` in the repo root.
2. Create a local admin (or user) during setup / in Admin → Users.
3. In the app, enter your public URL, e.g. `https://fics.example.com`.
4. For a phone on the same LAN as Docker, use `http://192.168.x.x:8000` and enable **Allow HTTP (LAN)**.
5. Sign in with SSO (if configured) or a local username and password.

`BASE_URL` on the server should match the URL clients use (especially behind a reverse proxy). Your OIDC provider must allow the archive callback URL; the in-app WebView follows the normal browser redirect.

## Features

UI mirrors the website (Pico Violet): bottom nav for **Library**, **Add works**, **Activity**, and **Users**, with work cards, site badges, and status pills.

- Server URL profile (DataStore)
- SSO via in-app WebView (`/login/sso`) and local login via `POST /api/login`
- Library search, site/status/following filters, card layout
- Add works (batch URL import)
- Activity log with kind/status filters
- Users list with import stats
- Work detail: follow, update check, auto-update, EPUB share, comments, delete, title/summary translation
- Chapter reader: full-page scroll, collapsible Translate + Reading settings, LibreTranslate
- Local reading bookmarks (last chapter + scroll position) with Continue on work/library cards
- Settings notifications feed (new stories, updates, comments, follows, import/update progress)

Not yet: site-login credentials, admin.

## F-Droid / free software notes

- Dependencies are AndroidX, OkHttp, Retrofit, and Kotlin libraries only (no Play Services).
- Cleartext HTTP is permitted so LAN installs work; prefer HTTPS on the public internet.
- Application id: `org.fanficarchive.android`

## License

Same as the parent project.

## Project layout

```text
android/app/src/main/java/org/fanficarchive/android/
  FanficApp.kt / MainActivity.kt
  data/          # Retrofit API, DTOs, cookie jar, DataStore, repository
  screens/
    setup/ login/ library/ work/ reader/ importing/ settings/
    components/  # shared Compose helpers + HTML WebView
    theme/
```
