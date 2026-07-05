<img width="480" height="640" alt="1000000482" src="https://github.com/user-attachments/assets/783e74e2-c3a2-4014-a990-ffedd4189a9d" />
# Phono — Spotify Client for Light Phone III

Thanks to **[Vandam Dinh](https://github.com/vandamd)** — especially
[Echo](https://github.com/vandamd/echo) — for the Light Phone UI
patterns and product direction this client builds on.

An independent, minimal Spotify client for LightOS. **Playback** runs in-process via
a patched fork of librespot (Rust). **Metadata** (search, library, albums, artists,
playlists) uses the official Spotify Web API with **your own** developer-app credentials.

> Requires a Spotify **Premium** account. This is not something we have
> *any* interest in working around, so please do not ask!

**New developer? Agent? (ugh)**: Read [docs/README.md](docs/README.md) and [AGENTS.md](AGENTS.md) before
changing code.

## How is this different from Echo?


vandam rocks. Basically, this has a few extra features, less album art and doesn't require the Spotify app to be installed.


# Setup (read this please!)

The app uses **dual authentication**:

1. **Step 1 — Playback (librespot):** WebView login with Spotify's first-party Keymaster
   client for audio streaming. No developer dashboard setup needed for this step.
2. **Step 2 — Web API:** Create your own app at
   [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) and enter the
   **Client ID** and **Client Secret** on the **Step 2** gate screen after playback login.

### Create your Spotify Developer app

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
2. Click **Create App**
3. Fill in app name and description
4. Set **Redirect URI** to `http://127.0.0.1:43821/callback` (must match exactly!!)
5. Select **Web API** under "Which API/SDKs are you planning to use?"
6. Accept terms and click **Save**
7. Open **Settings** and copy your **Client ID** and **Client Secret**
8. Click **Save**

### Configure the app

1. Complete **Step 1** (playback login) in the app
2. On **Step 2**, enter your Client ID and Client Secret:
   - **Type manually**, or
   - On a computer, open **[jonathancaudill.github.io/phono](https://jonathancaudill.github.io/phono/)** to generate a QR code, then tap **Scan QR** on the phone
3. Tap **Connect Web API** and authorize when prompted

The setup page runs entirely in your browser — credentials are never sent to a server. The QR
code encodes your client secret in plain text; generate and scan in private.

**PLEASE NOTE:** Credentials may expire around 6 months. Just rotate the secret and redo steps 2-3!


## Repository layout

```
rust/
  spotify-core/                 # UniFFI engine: session, player, queue, AudioTrack sink
  librespot-core-patched/       # Keymaster/desktop identity (PATCHES.md)
  librespot-playback-patched/   # Buffering API, sink lifecycle (PATCHES.md)
  librespot-audio-patched/      # CDN fetch resilience (PATCHES.md)
app/                            # Android (Kotlin Web API + Jetpack Compose)
setup/                          # GitHub Pages: Web API credential QR generator
docs/                           # Architecture, field tests, future research
scripts/build-rust.sh           # Cross-compile + UniFFI Kotlin bindings
```

All librespot crates are pinned to **=0.8.0**. Do not bump without re-validating every patch.

## Architecture

### Playback (Rust)

- `LibrespotEngine` (UniFFI) owns session, player, and queue.
- Keymaster OAuth via WebView (`http://127.0.0.1:8898/login`). Three client-identity surfaces
  (session, stored credentials, client-token) must agree as Keymaster/desktop — see `AGENTS.md`.
- **Audio output (Path C):** decode on the librespot player thread → SPSC ring → dedicated
  drain thread → JNI → Kotlin `PhonoAudioTrackSink` → `AudioTrack` (`USAGE_MEDIA`).
  Details: [docs/audio-sink.md](docs/audio-sink.md).
- **Session recovery:** seamless `Active` rebuild with queue/position restore (not
  librespot-java in-place reconnect). Details:
  [docs/future/session-reconnect.md](docs/future/session-reconnect.md).

### Android (Kotlin)

- `PlaybackService` creates the native engine and calls `startForeground()` immediately.
- `PlaybackController` — audio focus, network-tier streaming policy, stall UX, DelayMs for
  lock-screen position.
- `SpotifyWebApi` + `SpotifyRepository` — metadata via dev-app OAuth
  (`http://127.0.0.1:43821/callback`). Single combined `/search` per query; client-side
  ranking (`SearchRanking.kt`).
- Library writes via Web API (`PUT`/`DELETE /me/library`).
- Daily mixes: native librespot `context-resolve` with Web API fallback.

`NativeInit` order: `loadLibrary` → `initAndroidContext` → `registerAudioSink` (Path C).

## Caching

### Library (Room)

Liked tracks, saved albums, playlists — head-check delta sync, parallel page fill. UI reads from
disk via `Flow`.

### Detail cache

Pinned Room cache for saved albums / owned playlists (24 h TTL); ephemeral in-memory for browsed
content.

### Search

Per-query in-memory cache (5 min); filter chips reuse cached response.

### Auth tokens

- Playback: librespot stored credentials in `filesDir/spotify-cache/`
- Web API: `EncryptedSharedPreferences` with proactive refresh

### Audio stream cache

Ogg chunks under `filesDir/spotify-cache/`. **Opportunistic buffering** on good Wi‑Fi:
`buffer_current_to_end()` + `prefetch_upcoming()` via patched librespot player API.

## Build

Prerequisites:

- Rust (rustup) with Android targets: `rustup target add aarch64-linux-android x86_64-linux-android`
- `cargo install cargo-ndk`
- Android NDK installed; export `ANDROID_NDK_HOME`
- JDK 17, Android SDK (compileSdk 35), Gradle

```bash
# Cross-compile Rust + generate Kotlin bindings (AudioTrack backend by default)
bash scripts/build-rust.sh

# Build and install
./gradlew :app:installDebug
```

**Rodio fallback** (emulator debug): `USE_AUDIOTRACK_SINK=0 ./scripts/build-rust.sh` and match
`USE_AUDIOTRACK_SINK` in `app/build.gradle.kts`.

**Host tests** (ring buffer): `cd rust/spotify-core && cargo test pcm_ring`

## Key gotchas

- Call `NativeInit.initAndroidContext` before constructing the engine (`ndk_context` / identity).
- **Do not mix redirect URIs:** Step 1 → `127.0.0.1:8898/login`; Step 2 → `127.0.0.1:43821/callback`.
- **Do not use the Keymaster token for Web API** — metadata must use the dev-app bearer.
- **minSdk 26.** Audio focus in `PlaybackController`.
- `PlaybackService` must `startForeground()` within seconds of `startForegroundService()`.

## Reliability

| Layer | Mechanism |
|-------|-----------|
| Session | Monitor + seamless rebuild; `force_reconnect_check()` on network change |
| Decode buffer | `buffer_current_to_end`, `prefetch_upcoming`, network-tier presets |
| Audio output | Ring + drain; `recreateAudioSink()`; Kotlin DEAD_OBJECT / stall recovery |
| Web API | Token refresh, HTTP 429 `Retry-After`, `invalid_grant` handling |

Field validation: [docs/audio-sink-baseline-metrics.md](docs/audio-sink-baseline-metrics.md)

## Documentation index

| Doc | Contents |
|-----|----------|
| [AGENTS.md](AGENTS.md) | Hard rules, auth, diagnostics — read before coding |
| [docs/README.md](docs/README.md) | Developer onboarding index |
| [docs/audio-sink.md](docs/audio-sink.md) | Phase C AudioTrack architecture |
| [docs/future/](docs/future/) | Researched future work (session reconnect, backend move) |
