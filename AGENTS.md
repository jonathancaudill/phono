# AGENTS.md — mono: Unofficial Spotify Client for Light Phone III

Read this entire file before changing anything. This project touches Spotify's internal
protocols, a patched fork of librespot, and a subtle multi-identity authentication scheme.
Most of the hard-won knowledge here is NOT discoverable from the code alone, and several
"obvious" changes will silently break playback or trigger rate-limiting. When in doubt, do less.

---

## What This Is

A minimal, self-contained Spotify client for the Light Phone III (an Android-based minimalist
phone running LightOS). It plays Spotify Premium audio and browses the user's library WITHOUT
requiring the official Spotify app to be installed. It is built around a patched fork of
librespot (Rust) with a Kotlin/Android UI layer.

Reference implementation: **Jetispot** (https://github.com/iTaysonLab/jetispot) for native library
reads (collection v2 paging, Mercury pub/sub). **psst** (https://github.com/jpochyla/psst) remains
useful for playback/session patterns. We deliberately do NOT use `api.spotify.com` for metadata
reads — Spotify rate-limits Login5 tokens on that surface (429) as of late 2025.

Spotify Premium is REQUIRED. Free accounts are not and will never be supported (librespot
limitation). This is not a bug to fix.

---

## Repository Layout

```
/
├── rust/
│   ├── spotify-core/              # Our Rust core: session, playback, library metadata
│   │   └── src/
│   │       ├── lib.rs             # EngineShared, JNI exports, session build, identity init
│   │       ├── auth.rs            # OAuth/PKCE flow, reqwest http_client(), CLIENT_ID, scopes
│   │       ├── library.rs         # Library reads (spclient/Mercury) + writes (Mercury)
│   │       └── android_ctx.rs     # JNI native init (called from Kotlin), identity overrides
│   └── librespot-core-patched/    # PATCHED fork of librespot-core 0.8.0 (see "The Patch")
│       └── src/{config.rs, version.rs, spclient.rs, login5.rs, ...}
├── app/                           # Android app (Kotlin + Jetpack Compose)
└── README.md
```

librespot is pinned to `=0.8.0` and patched via `[patch.crates-io]` pointing at
`librespot-core-patched`. DO NOT bump the version — the patch is written against 0.8.0's exact
internal structure and a bump will silently invalidate it.

---

## The Single Most Important Concept: Three Client Identities Must Agree

Spotify decides how to treat a request based on WHICH CLIENT it appears to come from. Our entire
auth architecture exists to make every request look like Spotify's own **first-party desktop
client** (the "Keymaster" client, ID `65b708073fc0480ea92a077233ca87bd`).

There are THREE identity surfaces, and they must all present as Keymaster/desktop or
authentication fails with opaque errors (`InvalidCredentials`, `FaultyRequest`). Metadata reads
use spclient/Mercury (not the public Web API), which is unaffected by the Login5 429 lockdown:

1. **Session client ID** — set in `lib.rs`: `session_config.client_id = auth::CLIENT_ID`
   (Keymaster). This is also the client ID our OAuth flow in `auth.rs` uses.

2. **Stored-credential scope** — when we connect with an OAuth token, librespot does the access-
   point handshake and caches a reusable credentials blob ("auth_data"). That blob is implicitly
   tied to the client ID the OAuth token was for — Keymaster, because `auth.rs` uses CLIENT_ID.

3. **Client-token client ID** — the `client-token` (from `clienttoken.spotify.com`) is a
   required header on spclient/Login5 traffic. Stock librespot picks this client ID based on the
   compile-time OS. On Android, stock librespot would use ANDROID_CLIENT_ID
   (`9a8d2f0ce77a4e248bb71fefcb557637`) — a DIFFERENT client. THE PATCH fixes this to use the
   session (Keymaster) client ID instead. See "The Patch."

If these three ever disagree, Login5 fails. This is the bug that consumed an enormous amount of
debugging. Do not reintroduce it by changing client IDs, OS detection, or the patch.

### Why "android" is the enemy here

`std::env::consts::OS` is `"android"` at compile time. Stock librespot routes off this value in
THREE places — the client-token client ID, the User-Agent platform string, and the Spotify
version number — and each Android branch produces a value that contradicts our Keymaster/desktop
identity. We force everything to present as Linux desktop via runtime overrides + the patch.

---

## The Patch (`librespot-core-patched`)

We patch librespot-core 0.8.0 because the identity logic we need is keyed off compile-time
constants we cannot otherwise change. The patch is intentionally minimal. Key changes:

- **`config.rs`**: adds `HTTP_PLATFORM_OVERRIDE` and `OS_VERSION_OVERRIDE` (`OnceLock`s) and an
  `effective_os()` helper. `effective_os()` returns the *presented* OS (respects the override),
  as opposed to `OS` (the real compile-time target). Also `http_platform_label()` for the UA
  platform string.
- **`spclient.rs`**: the client-token request uses the session (Keymaster) client ID on Android
  instead of ANDROID_CLIENT_ID (via a `use_keymaster_desktop` check), and sends `desktop_linux`
  platform data. This is what aligns identity surface #3.
- **`version.rs`**: `spotify_version()` / `spotify_semantic_version()` use `effective_os()` so
  that when we present as Linux, the version number is the DESKTOP version, not the mobile one.
  (Stock returns the mobile version on Android, producing an impossible "mobile version + Linux
  platform" User-Agent.)
- **`login5.rs`**: UNCHANGED from upstream. Its StoredCredential path already uses the session
  client ID (Keymaster), which is correct. Do not "fix" it.

### Runtime overrides (set in Rust, BEFORE the session/HTTP client is built)

These are `OnceLock`s — FIRST WRITE WINS; later writes silently no-op. Ordering matters.

- `android_ctx.rs` sets `set_os_version_override("0")` (the value a real Linux desktop reports).
  This runs FIRST because it is a `#[no_mangle]` native method
  (`Java_..._NativeInit_initAndroidContext`) that **Kotlin calls explicitly** right after
  `System.loadLibrary()` and before constructing the engine. NOTE: it is NOT `JNI_OnLoad`; it
  does not auto-fire at library load — Kotlin must call it.
- `lib.rs` `EngineShared::new()` sets `set_http_platform_override("linux")`.
- DO NOT add a second `set_os_version_override(...)` in `lib.rs` — it will no-op because
  `android_ctx` already set it. (An early draft of a plan suggested this; it was wrong.)

The `os_version = "0"` choice is COUPLED to the Keymaster/Linux identity. If future work ever
switches to a native Android client ID (the android platform branch), `"0"` becomes WRONG —
that branch needs a real Android SDK int (>= 21). Documented in `android_ctx.rs`; respect it.

---

## Authentication Flow (one-time, then cached)

1. First launch: a WebView opens Spotify's `accounts.spotify.com/authorize` page (OAuth 2.0 with
   PKCE), using `CLIENT_ID` (Keymaster) and `REDIRECT_URI = http://127.0.0.1:8898/login`.
   - The redirect URI MUST be `127.0.0.1` (not `localhost`) and must match what is registered for
     the Keymaster client, or Spotify returns "redirect_uri: Not matching configuration".
   - The WebView intercepts navigation to the redirect URI (`shouldOverrideUrlLoading`), extracts
     `?code=`, and never actually loads the loopback URL. There is no local server.
2. The code is exchanged (PKCE verifier) for an OAuth token. We connect the librespot session
   with it; librespot caches a reusable stored-credentials blob.
3. After that, the session is the source of truth. The OAuth token is essentially a bootstrap.

### Username/password auth is DEAD

Spotify disabled it in mid-2024 and contacted the librespot maintainers directly. Do not attempt
to implement or suggest password login. OAuth (as above) is the only path.

---

## Metadata Reads — Native Channels (NO api.spotify.com)

We do **NOT** call `https://api.spotify.com/v1/...` for library/search/browse metadata. Spotify
started returning 429 on that surface for Login5/Keymaster tokens in late 2025. All reads go
through Spotify's internal channels authenticated by the librespot session (Login5 + client-token):

| Feature | Channel | Implementation |
|---------|---------|----------------|
| Search | spclient `context-resolve` | `spotify:search:…` → `get_context` (`library.rs`) |
| Liked songs | spclient `context-resolve` | `spotify:user:<id>:collection` |
| Saved albums | context-resolve → collection v2 → Mercury | fallback chain in `fetch_saved_albums_native` |
| Album/artist detail | spclient context-resolve + extended-metadata | `Album::get`, `Artist::get`, `Track::get` |
| is_saved checks | spclient `/your-library/v1/contains` | `check_contains` |
| Save/remove | Mercury `hm://collection/...` + spclient `/collection/...` | `collection_mutate` |

### Saved albums fallback chain (Jetispot model)

1. Try `get_context` on `spotify:user:{username}:collection:albums`
2. Fall back to spclient **collection v2** POST `/collection/v2/paging` (protobuf `PageRequest` /
   `PageResponse`, content-type `application/vnd.collection-v2.spotify.proto`) — filter URIs
   starting with `spotify:album:`, enrich via `Album::get`
3. Fall back to Mercury GET `hm://collection/collection/{username}` and parse album URIs from
   the protobuf payload

### OAuth token retention

`auth.rs` OAuth/PKCE machinery is kept for session bootstrap and as a **future BYO-client-id
fallback** if native reads prove unreliable. It is NOT used for metadata reads today.

### DO NOT reintroduce

- `api.spotify.com/v1/*` metadata calls (429 lockdown)
- pathfinder (`api-partner.spotify.com/pathfinder`) — brittle persisted-query hashes
- spclient saved-album fallback chains (the 12-endpoint guess cascade)

---

## OAuth / reqwest client (`auth.rs`)

The reqwest `http_client()` with desktop UA is used only for OAuth token exchange
(`accounts.spotify.com/api/token`), not for metadata. Keep exactly ONE `.user_agent(...)`
definition in the tree.

---

## Library — Mercury Reads and Writes

**Writes** (save/remove albums and tracks) go over Mercury via `collection_mutate` / `mercury_send`.
These work and must not be casually refactored.

**Reads** also use Mercury (GET) and spclient collection v2 where needed — see "Metadata Reads"
above. `mercury_get` (read) is separate from `mercury_send` (fire-and-forget write).

---

## Playback

librespot handles playback over its proprietary TCP protocol to `ap-*.spotify.com`. Audio output
on Android uses the `oboe` backend (NOT rodio/ALSA — those do not work on Android). Playback is
exposed to Kotlin via JNI. A `MediaSession` is registered for OS lock-screen controls and audio
focus. This layer is separate from metadata fetching.

---

## Hard Rules — Violating These Breaks The App

- DO NOT bump librespot off `=0.8.0`.
- DO NOT reintroduce `api.spotify.com/v1/*` metadata reads (Login5 tokens are 429'd).
- DO NOT use a Spotify developer-app client ID/registration for metadata (future BYO fallback only).
- DO NOT reintroduce pathfinder (`api-partner.spotify.com`) or spclient saved-album guess cascades.
- DO NOT change the three identity surfaces out of agreement: session client ID, stored-credential
  scope, and client-token client ID must all be Keymaster.
- DO NOT add a second `set_os_version_override` in `lib.rs` (OnceLock no-op).
- DO NOT implement username/password auth.
- DO NOT treat empty results as errors.
- DO NOT touch Mercury write code (`collection_mutate`, `mercury_send`) unless that is the task.
- DO NOT cross-contaminate the redirect URI: it is `http://127.0.0.1:8898/login`, `127.0.0.1`
  (never `localhost`), matching the Keymaster registration.

---

## When Things Break: Diagnostic Map

- **Login5 returns `InvalidCredentials` / `NoStoredCredentials`** → identity surface disagreement
  or overrides ran in wrong order. Check Keymaster client ID, `set_http_platform_override("linux")`,
  `set_os_version_override("0")`, patch client-token alignment.
- **Saved albums empty** → check logcat for which fallback succeeded (context-resolve, collection
  v2, mercury). Collection v2 protobuf parse may need updating if Spotify changed the schema.
- **Search returns nothing** → spclient `context-resolve` on `spotify:search:…` failed; check
  session/login5. NOT a Web API issue.
- **Saved albums truncated at 50** → caller clamp must be `clamp(1, 500)` for saved albums.
- **User-Agent looks wrong in logs** → `version.rs` not using `effective_os()`, or stray UA constant.

---

## Debug Probe

`probe_login5_token` in `lib.rs` logs whether Login5 and client-token mint succeed after connect.
It does NOT hit `api.spotify.com`.

---

## Reference Clients (for comparison when designing fetch behavior)

- **Jetispot** (Android, librespot-java) — collection v2 paging, Mercury pub/sub for library sync.
  Our saved-albums implementation follows this model.
- **psst** (Rust, desktop) — playback/session gold standard; Web API metadata path is now broken
  (429) so do not restore psst's `api.spotify.com` reads here.
- **librespot** itself — spclient `get_context`, Mercury, extended-metadata; read v0.8.0 source
  when reasoning about session, Login5, spclient, or Mercury behavior.
