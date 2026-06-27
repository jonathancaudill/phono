# AGENTS.md — phono: Unofficial Spotify Client for Light Phone III

Read this entire file before changing anything. This project touches Spotify's internal
protocols, a patched fork of librespot, and a **dual authentication** scheme (Keymaster for
playback, BYO dev-app OAuth for metadata). Most of the hard-won knowledge here is NOT
discoverable from the code alone, and several "obvious" changes will silently break playback
or metadata. When in doubt, do less.

---

## What This Is

A minimal, self-contained Spotify client for the Light Phone III (LightOS). It plays Spotify
Premium audio **without** the official Spotify app and browses the user's library via the
public Web API. Built on a patched fork of **librespot** (Rust playback) plus a Kotlin/Jetpack
Compose UI layer.

**Lineage:** UI and Light Phone product patterns descend from
**[Echo](https://github.com/vandamd/echo)** and
**[Light Template](https://github.com/vandamd/light-template)** (Vandam Dinh). Playback/session
patterns also draw on **psst** and **librespot** itself.

Spotify Premium is REQUIRED. Free accounts are not and will never be supported (librespot
limitation). This is not a bug to fix.

---

## Repository Layout

```
/
├── rust/
│   ├── spotify-core/              # Rust core: librespot playback + daily-mix discovery
│   │   └── src/
│   │       ├── lib.rs             # EngineShared, UniFFI exports, session build, identity init
│   │       ├── auth.rs            # Keymaster OAuth/PKCE (playback bootstrap only)
│   │       ├── library.rs         # Daily Mix discovery via spclient context-resolve only
│   │       └── android_ctx.rs     # Native init (called from Kotlin), identity overrides
│   └── librespot-core-patched/    # PATCHED fork of librespot-core 0.8.0 (see "The Patch")
│       └── src/{config.rs, version.rs, spclient.rs, login5.rs, ...}
├── app/                           # Android app (Kotlin + Jetpack Compose)
│   └── src/main/java/.../data/
│       ├── webapi/                # SpotifyWebApi, WebApiAuth (dev-app OAuth + api.spotify.com)
│       ├── SpotifyRepository.kt   # UI-facing metadata + search cache + ranking
│       └── SearchRanking.kt       # Client-side search relevance + interleave
└── README.md
```

librespot is pinned to `=0.8.0` and patched via `[patch.crates-io]`. **DO NOT bump** — the
patch is written against 0.8.0's exact internal structure.

---

## Dual Authentication (do not conflate)

| Step | Purpose | Client | Redirect URI | Token used for |
|------|---------|--------|--------------|----------------|
| **1 — Playback** | librespot session | Keymaster (`65b708073fc0480ea92a077233ca87bd`) | `http://127.0.0.1:8898/login` | Streaming only |
| **2 — Web API** | Metadata/library | User's dev-app Client ID | `http://127.0.0.1:43821/callback` | `api.spotify.com/v1/*` only |

**Critical:** Never send the Keymaster/Login5 token to `api.spotify.com` — Spotify rate-limits
and locks down that surface for first-party tokens. Metadata **must** use the dev-app OAuth
bearer from `WebApiAuth` (`app/.../webapi/WebApiAuth.kt`).

Username/password auth is dead (Spotify disabled it mid-2024). OAuth WebView flows only.

---

## Playback Identity: Three Surfaces Must Agree (Keymaster)

Spotify decides how to treat librespot traffic based on client identity. For **playback**
(session, spclient, Login5, client-token), all three surfaces must present as Keymaster/desktop:

1. **Session client ID** — `session_config.client_id = auth::CLIENT_ID` (Keymaster). Same ID
   used in Step 1 OAuth (`auth.rs`).

2. **Stored-credential scope** — cached `auth_data` from OAuth is tied to Keymaster.

3. **Client-token client ID** — stock librespot would use `ANDROID_CLIENT_ID` on Android; **the
   patch** forces session (Keymaster) client ID instead.

If these disagree, Login5 fails with opaque errors (`InvalidCredentials`, `FaultyRequest`).

### Why "android" is the enemy

Stock librespot routes off `std::env::consts::OS == "android"` for client-token ID, UA
platform, and Spotify version — each contradicts Keymaster/desktop. We force Linux desktop via
runtime overrides + the patch.

### Runtime overrides (FIRST WRITE WINS — ordering matters)

- `android_ctx.rs`: `set_os_version_override("0")` — Kotlin must call
  `NativeInit.initAndroidContext` after `System.loadLibrary()`, before engine construction.
- `lib.rs` `EngineShared::new()`: `set_http_platform_override("linux")`.
- **DO NOT** add a second `set_os_version_override` in `lib.rs` (OnceLock no-op).

---

## The Patch (`librespot-core-patched`)

Minimal patch against librespot-core 0.8.0:

- **`config.rs`**: `HTTP_PLATFORM_OVERRIDE`, `OS_VERSION_OVERRIDE`, `effective_os()`
- **`spclient.rs`**: client-token uses session (Keymaster) ID on Android; `desktop_linux` platform
- **`version.rs`**: desktop version numbers when presenting as Linux
- **`login5.rs`**: UNCHANGED — do not "fix" it

---

## Metadata — Kotlin Web API (`SpotifyWebApi.kt`)

All library/search/browse reads and writes go through `https://api.spotify.com/v1/...` with the
**dev-app bearer** from `WebApiAuth.currentBearer()`.

| Feature | Implementation |
|---------|----------------|
| Search | Single `GET /search?type=artist,album,track,playlist&market=from_token`; client ranking in `SearchRanking.kt` |
| Liked songs | `GET /me/tracks` (paginated) |
| Saved albums | `GET /me/albums` (paginated, limit up to 500) |
| Album/artist detail | `GET /albums/{id}`, `GET /artists/{id}`, artist albums |
| Save/remove | `PUT`/`DELETE /me/library` |
| is_saved | `GET /me/library/contains` |
| Playlist play | `GET /playlists/{id}/items` |

### Web API request rules

- Headers: `Authorization: Bearer {dev_app_token}`, `Accept: application/json` only.
- **DO NOT** send `client-token`, `app-platform`, or per-request User-Agent to `api.spotify.com`.
- Honor `Retry-After` on HTTP 429 (capped retries in `SpotifyWebApi.executeWithRetry`).
- Use `market=from_token` on search (not `marker`).
- Search responses may contain **null slots** in `items` arrays — use `SearchPagedResponse<T?>` and
  `filterNotNull()`; never treat empty as error.
- Paginate library endpoints in 50-item pages; saved albums caller clamp is `clamp(1, 500)`.

### Search ranking (client-side)

Spotify returns per-type buckets, not a cross-type SERP. `SearchRanking.rank()` picks a top
result (text match + popularity + API rank), then round-robin interleaves the remainder for
variety. Filter chips (All/Songs/Artists/Albums/Playlists) filter cached data — **zero extra
API calls**.

---

## Rust Native Path (playback + daily mixes only)

`library.rs` is **not** the general metadata layer anymore. It only implements Daily Mix /
Made-For-You playlist discovery via `spotify:search:…` context-resolve, with Web API fallback
in `SpotifyRepository.dailyMixes()`.

Do not move general search/library reads back into Rust spclient unless you have a compelling
reason — dev-app Web API is the supported metadata path today.

---

## Playback

librespot streams over TCP to `ap-*.spotify.com`. Android audio uses the **oboe** backend.
Exposed via UniFFI to Kotlin. `MediaSession` / Media3 for lock-screen controls and audio focus.
`PlaybackController` owns audio focus in Kotlin.

---

## Hard Rules — Violating These Breaks The App

- DO NOT bump librespot off `=0.8.0`.
- DO NOT use Keymaster/Login5 token for `api.spotify.com` metadata — dev-app OAuth only.
- DO NOT use Keymaster client ID for the Web API dev-app registration — users bring their own.
- DO NOT mix Step 1 and Step 2 redirect URIs (`8898/login` vs `43821/callback`).
- DO NOT change the three playback identity surfaces out of agreement (Keymaster everywhere).
- DO NOT add a second `set_os_version_override` in `lib.rs`.
- DO NOT reintroduce pathfinder (`api-partner.spotify.com/pathfinder`).
- DO NOT implement username/password auth.
- DO NOT treat empty search/library results as errors.
- DO NOT cross-contaminate redirect URI host: use `127.0.0.1`, never `localhost`.

---

## When Things Break: Diagnostic Map

- **Login5 / playback auth fails** → identity surface disagreement or overrides ran wrong order.
  Check Keymaster client ID, `set_http_platform_override("linux")`, `set_os_version_override("0")`,
  patch client-token alignment.
- **Web API 401/403** → wrong token (Keymaster token on api.spotify.com?) or Step 2 not completed.
- **Web API 429** → missing Retry-After handling or too many sequential requests; search should
  be **one** combined call per query.
- **Search JSON parse error on playlists** → null items in response; ensure nullable list parsing.
- **Search results screen crash** → never force-unwrap `results` inside LazyColumn scope; keep
  stale results while reloading.
- **Saved albums truncated at 50** → caller clamp must be `clamp(1, 500)`.
- **User-Agent wrong in playback logs** → `version.rs` not using `effective_os()`.

---

## Reference Clients

- **[Echo](https://github.com/vandamd/echo)** (Vandam Dinh) — Light Phone Spotify UX, Web API
  metadata patterns, dev-app setup flow. phono's UI descends from Light Template.
- **psst** — librespot playback/session reference; search API structure (single combined `/search`).
- **librespot** — protocol source of truth for session, Login5, spclient, oboe playback.
- **Jetispot** — informative for native Mercury/collection-v2 approaches we deliberately do **not**
  use for metadata today.
