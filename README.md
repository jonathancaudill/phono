# phono — Spotify Client for Light Phone III

Thanks to **[Vandam Dinh](https://github.com/vandamd)** — especially
[Echo](https://github.com/vandamd/echo) and the
[Light Template](https://github.com/vandamd/light-template) — for the Light Phone UI
patterns and product direction this client builds on. Thanks to
**[librespot](https://github.com/librespot-org/librespot)** for the open-source Spotify
protocol work that makes in-process Premium playback possible.

An independent, minimal Spotify Premium client for LightOS. **Playback** runs in-process via
a patched fork of librespot (Rust). **Metadata** (search, library, albums, artists,
playlists) uses the official Spotify Web API with **your own** developer-app credentials.
No official Spotify app required.

> Requires a Spotify **Premium** account. Playback is a protocol-level requirement of
> librespot, not something that can be worked around.

## How this differs from Echo

| | **phono** | **Echo** |
|---|---|---|
| Playback | librespot in-process | Spotify Android SDK (official app installed) |
| Metadata | Web API (your dev app) | Web API (your dev app) |
| Spotify app required | No | Yes |

Both need a Spotify Developer app for library/search/browse. phono additionally needs a
one-time Keymaster OAuth login (Step 1) for streaming.

## Setup (required before first use)

The app uses **dual authentication**:

1. **Step 1 — Playback (librespot):** WebView login with Spotify's first-party Keymaster
   client for audio streaming. No developer dashboard setup needed for this step.
2. **Step 2 — Web API:** Create your own app at
   [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) and enter the
   **Client ID** and **Client Secret** in the app (Settings → Web API setup).

### Create your Spotify Developer app

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
2. Click **Create App**
3. Fill in app name and description
4. Set **Redirect URI** to `http://127.0.0.1:43821/callback` (must match exactly — no trailing slash)
5. Select **Web API** under "Which API/SDKs are you planning to use?"
6. Accept terms and click **Save**
7. Open **Settings** and copy your **Client ID** and **Client Secret**
8. Under **Android package**, add:
   - **Package name:** `com.lightphone.spotify`
   - **SHA1 fingerprint:** (from your signing key — run `keytool` on your keystore)
9. Click **Save**

### Configure the app

1. Complete **Step 1** (playback login) in the app
2. On **Step 2**, paste your Client ID and Client Secret
3. Tap **Connect Web API** and authorize when prompted

**Notes:**

- Development Mode apps require the **app owner** to have Spotify Premium
- Each dev app allows up to **5 authorized users** (add yourself in the dashboard)
- Refresh tokens expire after **6 months** — re-run Step 2 if metadata stops working
- New dev-mode apps may lack some endpoints (e.g. artist top tracks) due to Spotify API policy changes

## Layout

```
rust/
  spotify-core/              # UniFFI engine: playback, queue, session recovery
  librespot-core-patched/    # Keymaster/desktop identity patch (0.8.0)
  librespot-playback-patched/  # buffer_current_to_end, full-track query
  librespot-audio-patched/     # download_slots, 429 permit release, CDN retry
app/                         # Android app (Kotlin Web API + Jetpack Compose UI)
scripts/build-rust.sh        # Cross-compile + generate Kotlin bindings
```

## Architecture

- **Playback:** `LibrespotEngine` (UniFFI) owns session, player, and queue. Keymaster OAuth via
  WebView (`http://127.0.0.1:8898/login`). Three client-identity surfaces (session, stored
  credentials, client-token) must all agree as Keymaster/desktop — see `AGENTS.md`.
- **Service ownership:** `PlaybackService` creates the native engine and calls `startForeground()`
  immediately (before engine init). `PlaybackEngineHolder` wires the engine to
  `PlaybackController` after attach.
- **Metadata:** Kotlin `SpotifyWebApi` + `SpotifyRepository` call `api.spotify.com/v1/...`
  with tokens from your dev-app OAuth (`http://127.0.0.1:43821/callback`). Search uses one
  combined `/search` request per query; results are ranked and interleaved client-side
  (`SearchRanking.kt`).
- **Library writes:** Web API (`PUT`/`DELETE /me/library`) — save/remove tracks and albums.
- **Daily mixes:** Hybrid — native librespot `context-resolve` search when possible; fallback
  to editorial playlist names in your library via Web API.

`PlaybackController` handles audio focus, network-tier streaming policy, stall buffering UX, and
exposes `StateFlow` to Compose. `PlaybackService` hosts Media3 for lock-screen controls. UI
follows the Light Template aesthetic (black canvas, Public Sans, minimal chrome).

## Caching

phono uses several cache layers with different lifetimes and purposes. Nothing here replaces
the Web API as the source of truth for metadata — caches reduce API calls and improve
perceived speed.

### 1. Library (Room / SQLite)

Persistent on-disk cache for browsable library data (`phono_library.db`):

| Resource | Storage | Sync strategy |
|----------|---------|---------------|
| Liked tracks | `liked_tracks` + sync metadata | Head-check delta on refresh; background parallel page fill |
| Saved albums | `saved_albums` + sync metadata | Same pattern |
| User playlists | `playlists` + sync metadata | Same pattern |

`LibrarySync` skips a full re-download when the remote head (total count + first item) matches
local state. `fillRemainingParallel` drains remaining pages in parallel so the local DB becomes a
complete offline copy over time. UI lists read from Room `Flow`s and paginate from disk.

### 2. Detail cache (Room + in-memory)

Album and playlist detail screens use a two-tier lookup in `SpotifyRepository`:

- **Pinned (Room):** Saved albums and user-owned playlists get JSON blobs in
  `album_details` / `playlist_details` (24 h TTL). Playlist track URIs are indexed in
  `playlist_track_uris` for fast “is this track in my playlist?” checks (context menus).
- **Ephemeral (in-memory):** Browsed-but-not-library albums/playlists are cached in bounded
  LRU-style maps (20 albums / 10 playlists, 5 min TTL).

Artist detail is not cached yet — always fetched from the Web API.

### 3. Search and light metadata (in-memory)

- **Search:** Per-query results cached 5 minutes. Filter chips (All/Songs/Artists/…) reuse the
  same cached response with zero extra API calls.
- **Daily mixes:** In-memory, 5 min TTL.
- **Current user ID:** Cached for the session after first `/me` call.

### 4. Auth tokens (encrypted disk)

- **Playback (Step 1):** librespot stored credentials + volume in `filesDir/spotify-cache/`.
  OAuth bootstrap token also persisted for early metadata probes.
- **Web API (Step 2):** Access + refresh tokens in `EncryptedSharedPreferences`; proactive
  refresh before expiry; `invalid_grant` clears tokens for re-auth.

### 5. Audio stream cache (librespot disk)

Downloaded Ogg/Vorbis chunks live under `filesDir/spotify-cache/` (audio + tmp dirs). Settings
→ **Clear Cache** deletes audio files only; credentials and library DB are kept.

**Opportunistic buffering** (`StreamingPolicy` + patched librespot):

- On good Wi‑Fi: `buffer_current_to_end()` banks the playing track; `prefetch_upcoming()` warms
  the next 1–3 queue tracks.
- On stall: bank current track if battery allows.
- `NetworkBufferPreset` (Low / Normal / High) tunes read-ahead at engine init.

### Future caching improvements

- **Artist detail cache** — same pinned/ephemeral split as albums.
- **Snapshot-aware playlist invalidation** — refresh pinned playlist detail when
  `snapshot_id` changes instead of relying on TTL alone.
- **Image disk cache** — album art URLs are re-fetched by Coil on every screen; a bounded
  disk cache would help offline browsing.
- **Incremental library sync** — today a head mismatch triggers a full local rewrite for that
  resource; a merge/diff path would reduce churn on large libraries.
- **Search persistence** — optional disk cache for recent queries across app restarts.
- **Offline playback policy** — gate prefetch/banking on metered vs unmetered + user setting;
  surface “fully buffered” state in the UI via `is_current_fully_buffered()`.

## Build

Prerequisites:

- Rust (rustup) with Android targets: `rustup target add aarch64-linux-android x86_64-linux-android`
- `cargo install cargo-ndk`
- Android NDK installed; export `ANDROID_NDK_HOME`
- JDK 17, Android SDK (compileSdk 35), Gradle

Then:

```bash
# 1) Cross-compile the Rust backend and generate Kotlin bindings.
bash scripts/build-rust.sh

# 2) Build the app (also runs step 1 via the cargoBuild Gradle task).
./gradlew :app:assembleDebug

# Install on a connected device/emulator
./gradlew :app:installDebug
```

## Key gotchas

- **`ndk_context` must be initialized** via `NativeInit.initAndroidContext` before
  constructing the engine.
- **Audio focus** is handled in Kotlin (`PlaybackController`).
- **minSdk is 26** (AAudio requirement).
- **Do not mix redirect URIs:** Step 1 uses `127.0.0.1:8898/login` (Keymaster); Step 2 uses
  `127.0.0.1:43821/callback` (your dev app).
- **Do not use the Keymaster OAuth token for Web API calls** — metadata must use the dev-app
  bearer from Step 2.
- **Non-premium accounts:** librespot requires Premium for playback.
- **Foreground service:** `PlaybackService` must call `startForeground()` within seconds of
  `startForegroundService()` — engine init alone is too slow to rely on Media3 promotion alone.

## Reliability

- **Session recovery:** Cached librespot credentials + queue restore across seamless rebuilds;
  `ensure_playback_ready` rebuilds session/player when the access point drops.
- **Network awareness:** `ConnectivityManager` callbacks with debounced reconnect and handoff
  grace; stall watchdog surfaces buffering without tearing down the session mid-play.
- **Streaming policy:** Network-tier prefetch and full-track banking via patched librespot
  (`buffer_current_to_end`, `prefetch_upcoming`).
- **Web API:** Token refresh with `invalid_grant` handling (6-month refresh token expiry).
- **HTTP 429:** Honored via `Retry-After` with capped retries in `SpotifyWebApi`.
