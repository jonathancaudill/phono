# AGENTS.md — phono: Spotify & TIDAL client for Light Phone III

Read this entire file before changing anything. Dual Spotify auth, three patched
librespot crates, and a two-backend playback split hide most of the hard-won
knowledge. Obvious changes silently break playback or metadata. When in doubt, do less.

Architecture index: [docs/README.md](docs/README.md).

---

## What this is

A minimal LightOS (Light Phone III) music client. The user picks **one** backend
at first launch (`BackendPickerScreen` → `BackendPreferences`). Switching
backends means signing out and re-picking.

Spotify **Premium** or an active TIDAL account is required. Free Spotify is a
librespot limitation, not a bug. Do not work around it.

**Lineage:** UI from [Echo](https://github.com/vandamd/echo) and
[Light Template](https://github.com/vandamd/light-template) (Vandam Dinh).
Spotify playback/session patterns from **psst** and **librespot**.

---

## Repository

```
rust/spotify-core/                          UniFFI engine: session, player, queue, Spotify pins
rust/librespot-core-patched/                Keymaster/desktop identity (PATCHES.md)
rust/librespot-playback-patched/            Buffering API, sink lifecycle, seek/skip flush
rust/librespot-audio-patched/               CDN 429 + parallel range slots
app/                                        Kotlin + Compose (Spotify + TIDAL)
setup/                                      GitHub Pages QR helper for Spotify Step 2
docs/                                       Architecture, offline pins, field tests
scripts/build-rust.sh                       Cross-compile + UniFFI Kotlin bindings
```

All three librespot crates are pinned to **`=0.8.0`** via `[patch.crates-io]` in
`rust/spotify-core/Cargo.toml`. Bump none of them without re-validating every patch.

Shared Kotlin seams: `PlaybackBackend` + `MusicRepository`. Domain models are the
`Spotify*` DTOs for **both** services, distinguished by `spotify:` / `tidal:` URIs.

`minSdk` is **33**. Native init order (Spotify): `loadLibrary` →
`initAndroidContext` → `registerAudioSink` (audiotrack builds).

---

## Backend split

`PlaybackController` binds one `PlaybackBackend` + one `MusicRepository`.

| | Spotify | TIDAL |
|---|---|---|
| Playback | `LibrespotPlaybackBackend` (UniFFI) | `TidalPlaybackBackend` (Media3 ExoPlayer) |
| Metadata | Dev-app Web API + Login5 spclient | `TidalApiClient` REST |
| Auth | Dual OAuth (below) | `TidalAuth` PKCE WebView (device-grant exists) |
| Audio | Path C AudioTrack sink | ExoPlayer; **clear** AAC/FLAC only (skip Widevine) |
| Pins | Rust decrypt-to-Ogg | Media3 `DownloadManager` |

`PlaybackController` owns audio focus, `StreamingPolicy`, stall UX, and the
`OfflineDownloadCenter` façade. TIDAL logout clears backend choice and returns
to the picker. TIDAL has no Spotify-style Step 2.

---

## Dual authentication (Spotify — do not conflate)

| Step | Purpose | Client | Redirect URI | Token used for |
|------|---------|--------|--------------|----------------|
| **1 — Playback** | librespot session | Keymaster (`65b708073fc0480ea92a077233ca87bd`) | `http://127.0.0.1:8898/login` | Streaming + spclient only |
| **2 — Web API** | Metadata/library | User's own dev-app Client ID | `http://127.0.0.1:43821/callback` | `api.spotify.com/v1/*` only |

Token routing:

- `api.spotify.com` ← **only** `WebApiAuth.currentBearer()` (dev-app).
- spclient / Login5 / streaming ← **only** the Keymaster session.

Never send the Keymaster/Login5 token to `api.spotify.com`. Never send the
dev-app bearer to spclient hosts.

Step 1 capture: WebView intercept **plus** `SpotifyOAuthLoopback` on `:8898`.
The loopback server exists because email-OTP often skips
`shouldOverrideUrlLoading` — do not remove it. Host is `127.0.0.1`, never
`localhost`.

Username/password auth is dead (Spotify disabled it mid-2024). OAuth WebView only.

TIDAL OAuth is a separate stack (`TidalAuth.REDIRECT_URI` =
`https://tidal.com/android/login/auth`). Do not reuse Spotify redirect URIs or
tokens. TIDAL client IDs rotate; `TidalAuth` accepts a persisted override.

---

## Playback identity: three surfaces must agree (Keymaster)

For **Spotify** session, spclient, Login5, and client-token, all three surfaces
must present as Keymaster/desktop Linux:

1. **Session client ID** — `session_config.client_id = auth::CLIENT_ID` (same ID
   as Step 1 OAuth in `auth.rs`).
2. **Stored-credential scope** — cached `auth_data` is tied to Keymaster.
3. **Client-token client ID** — stock librespot would use `ANDROID_CLIENT_ID` on
   Android; **the core patch** forces the session (Keymaster) ID instead.

If these disagree, Login5 fails with opaque errors (`InvalidCredentials`,
`FaultyRequest`).

Stock librespot routes off `std::env::consts::OS == "android"` for client-token
ID, UA platform, and Spotify version — each contradicts Keymaster/desktop.
Runtime overrides + the core patch force Linux desktop.

### Runtime overrides (OnceLock — first write wins)

1. `android_ctx.rs` via `NativeInit.initAndroidContext`: `set_os_version_override("0")`.
   Kotlin must call this after `System.loadLibrary()`, before engine construction.
2. `lib.rs` `EngineShared::new()`: `set_http_platform_override("linux")`.

Do not add a second `set_os_version_override` in `lib.rs` (silent no-op).

### The three patches

- **core** — Keymaster/desktop identity; `login5.rs` is **unchanged** on purpose.
- **playback** — `BufferCurrentToEnd`, `RecreateSink`, discontinuous `load` flush
  (user skip/play must not overlap the outgoing PCM tail).
- **audio** — two concurrent CDN range slots; drop the download permit before
  429 `Retry-After` sleep.

Details live in each crate's `PATCHES.md`. AudioTrack sink itself lives in
`spotify-core` (`pcm_ring.rs`, `audio_drain.rs`, JNI → `PhonoAudioTrackSink`).

---

## Spotify metadata — dual path

**Playlists and artists** use native spclient (Login5) via `NativeMetadataGateway`
→ UniFFI (`playlist.rs`, `artist.rs`, `user_profile.rs`). Step 1 session must be
active. Playlist writes: [docs/native-playlist-writes.md](docs/native-playlist-writes.md).

**Search, liked songs, saved albums, album detail, library save/contains** use
Kotlin `SpotifyWebApi` with the **dev-app bearer**. Library lists land in Room
(`LibraryRepository`) with head-check delta sync and parallel 50-item page fill.

| Feature | Implementation |
|---------|----------------|
| Search | Web API `GET /search?type=artist,album,track,playlist&market=from_token`; rank in `SearchRanking.kt` |
| Liked songs / saved albums | Web API `/me/tracks`, `/me/albums` (50-item pages → Room) |
| Album detail | Web API `GET /albums/{id}` |
| Save / remove / is_saved | Web API `PUT`/`DELETE`/`GET` `/me/library` (+ `/contains`) |
| Playlist browse/edit | Native spclient: `get_playlist`, continuation pagination, `ListChanges` |
| Artist detail | Native spclient extended-metadata |
| Playlist owner names | spclient `user-profile-view`; Web API `/users/{id}` fallback |
| Daily mixes | Native context-resolve (`library.rs`); Web API fallback in repository |
| User playlists sync | Native rootlist when Step 1 is live; Web API `savedPlaylistsPage` fallback only |

Do not move search or liked/saved-album reads into Rust.

### Web API request rules

- Headers: `Authorization: Bearer {dev_app_token}`, `Accept: application/json` only.
- Do not send `client-token`, `app-platform`, or a per-request User-Agent to
  `api.spotify.com`.
- Honor `Retry-After` on HTTP 429 (`SpotifyWebApi.executeWithRetry`).
- Use `market=from_token` (not `marker`).
- Search/library `items` arrays may contain **null slots** — parse
  `SearchPagedResponse<T?>` and `filterNotNull()`. Empty results are valid.
- Search is **one** combined call per query. Filter chips (All/Songs/Artists/Albums/Playlists)
  filter the in-memory cache — zero extra API calls.

`SearchRanking.rank()` picks a top result (text match + popularity + API rank),
then round-robin interleaves the remainder.

---

## Playback

Spotify: librespot TCP to `ap-*.spotify.com`. Android output is native
**AudioTrack** (Path C: SPSC ring + Rust drain thread → JNI
`PhonoAudioTrackSink`). Drain uses `WRITE_BLOCKING`; the player thread only
pushes the ring. [docs/audio-sink.md](docs/audio-sink.md).

Release builds default to `audiotrack-sink`. Rodio/cpal/AAudio fallback:
`USE_AUDIOTRACK_SINK=0` in `build-rust.sh` **and** `USE_AUDIOTRACK_SINK=false`
in `app/build.gradle.kts` — both must match.

Session recovery is a full `Active` rebuild with queue/position restore — not
librespot-java in-place reconnect.
[docs/future/session-reconnect.md](docs/future/session-reconnect.md).

`StreamingPolicy` banks the current track then prefetches on good networks.
Wi‑Fi must stay visible **30 seconds** before it is preferred over cellular.

`MediaSession` / Media3 for lock-screen controls. `PlaybackController` owns
audio focus. `PlaybackService` must `startForeground()` promptly after
`startForegroundService()`.

TIDAL: ExoPlayer + stream LRU under `cacheDir/tidal-stream`. Offline pins under
`filesDir/tidal-downloads`.

---

## Offline pins

UI talks only to `OfflineDownloadCenter`. Engines differ; Room index is shared.
[docs/offline-downloads.md](docs/offline-downloads.md).

- Streaming quality and download quality are **independent**. Changing download
  quality never rewrites completed pins.
- Settings → Clear Cache wipes stream LRUs only (`spotify-cache/audio`,
  `tidal-stream`). Pins stay.
- If Phono has seen no network for **30+ days**, `OfflinePinHygiene` wipes pins
  (TOS guard). Credentials and stream cache are left alone.
- Pin-queue pacing: [docs/download-rate-limiting.md](docs/download-rate-limiting.md).
- Airplane mode: Spotify builds an **offline Active** (`Session::new` without
  connect) so completed Ogg pins still play.

---

## Self-update

GitHub-release APK installer in `app/.../update/`.
[docs/self-update.md](docs/self-update.md). Release APKs must be signed with the
same key and ship **exactly one** `.apk` asset.

---

## Hard rules — violating these breaks the app

- Keep librespot at `=0.8.0` on all three patched crates.
- Use the dev-app bearer for `api.spotify.com`; use Keymaster for streaming/spclient.
- Users bring their own Web API Client ID — never register Keymaster as the Step 2 app.
- Keep Step 1 and Step 2 redirect URIs distinct (`8898/login` vs `43821/callback`).
- Keep the three playback identity surfaces in agreement (Keymaster everywhere).
- Set `os_version` override only in `android_ctx.rs`.
- Leave `login5.rs` unchanged.
- Keep pathfinder (`api-partner.spotify.com/pathfinder`) out of the tree.
- Use OAuth WebView only — no username/password.
- Treat empty search/library results as valid.
- Use `127.0.0.1` in Spotify redirect URIs, never `localhost`.
- Recover Spotify sessions by rebuilding `Active`, not `Session.reconnect()`.
- Keep the 30-day offline pin wipe.
- Keep streaming vs download quality independent; Clear Cache must not delete pins.

---

## When things break

- **Login5 / playback auth fails** → identity surfaces disagree, or overrides ran
  in the wrong order. Check Keymaster client ID, `set_http_platform_override("linux")`,
  `set_os_version_override("0")`, core-patch client-token alignment.
- **Web API 401/403** → Keymaster token sent to `api.spotify.com`, or Step 2 not done.
- **Web API 429** → missing `Retry-After`, or more than one search call per query.
- **Search JSON parse error on playlists** → null `items` slots; keep nullable lists.
- **Search results screen crash** → never force-unwrap `results` inside LazyColumn;
  keep stale results while reloading.
- **Saved albums look truncated** → Room fill stalled (empty page / head-check),
  not a 50-item API cap. Pages are 50; `fillRemainingParallel` walks the rest.
- **User-Agent wrong in playback logs** → `version.rs` not using `effective_os()`.
- **Overlapping audio on skip/reconnect** → user loads must use
  `load_discontinuous` (flush); check `sink_epoch_rejected_writes` /
  `stale_load_suppressed` in `PlaybackDebugMetrics`.
- **Spotify Step 1 WebView never returns** → loopback server on `:8898` not running.
- **Offline pins vanished** → 30-day hygiene, not Clear Cache.
- **TIDAL track silent / error** → Widevine/encrypted path; only clear BTS/DASH plays.
- **Self-update needs a tap** → signing-key mismatch or more than one APK asset.

---

## Reference

- **Echo** — Light Phone UX, Web API metadata patterns, dev-app setup flow.
- **psst** — librespot playback/session; combined `/search` (we use `market`, not its `marker` typo).
- **librespot** — session, Login5, spclient, playback protocol.
- **Jetispot** — Android sink + reconnect history; we rebuild instead.
  [docs/future/session-reconnect.md](docs/future/session-reconnect.md).

Reach for these when the matching work is in scope:

- [docs/audio-sink.md](docs/audio-sink.md) — AudioTrack threading and recovery
- [docs/offline-downloads.md](docs/offline-downloads.md) — pins, TOS wipe, airplane mode
- [docs/download-rate-limiting.md](docs/download-rate-limiting.md) — pin-queue pacing
- [docs/self-update.md](docs/self-update.md) — GitHub release updater / signing
- [docs/native-playlist-writes.md](docs/native-playlist-writes.md) — spclient playlist mutations
- [docs/playback-stability-field-tests.md](docs/playback-stability-field-tests.md) — reconnect / overlap counters
- `rust/librespot-*-patched/PATCHES.md` — patch internals
