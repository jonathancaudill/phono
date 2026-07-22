### ⚠️ WARNING: THIS REPOSITORY IS RAPIDLY CHANGING AFTER THE DISCOVERY OF A FEW BUGS.
### ⚠️ IT IS HIGHLY ADVISABLE YOU WAIT FOR V0.0.2 AT LEAST

# Phono — Spotify / TIDAL Client for Light Phone III

<img width="480" height="580" alt="1000000482" src="https://github.com/user-attachments/assets/783e74e2-c3a2-4014-a990-ffedd4189a9d" />

Thanks to **[Vandam Dinh](https://github.com/vandamd)** — especially
[Echo](https://github.com/vandamd/echo) — for the Light Phone UI
patterns and product direction this client builds on.

An independent, minimal music client for LightOS. Pick **Spotify** or **TIDAL** at
first launch (one backend per install; logout returns to the service picker). Shared
UI for library, search, queue, and offline downloads.

| Backend | Playback | Metadata | Account |
|---------|----------|----------|---------|
| **Spotify** | Patched librespot (Rust) | Web API + native spclient | Premium required |
| **TIDAL** | Media3 / ExoPlayer (clear BTS/DASH) | TIDAL REST API | Subscription that can serve clear streams (no Widevine path) |

> Spotify **Premium** is required for the Spotify backend. TIDAL needs a plan that
> can deliver clear AAC/FLAC. Please do not ask for free-tier workarounds.

**New developer? Agent?** Read [docs/README.md](docs/README.md) and [AGENTS.md](AGENTS.md)
before changing Spotify/librespot code.

## How is this different from Echo?

vandam rocks. Basically: backend choice (Spotify or TIDAL), offline pins, less album
art, and Spotify does not require the official Spotify app.

---

# Setup

On first launch, choose **Spotify** or **TIDAL**. Setup differs by backend.

## Spotify (dual authentication)

1. **Step 1 — Playback (librespot):** WebView login with Spotify’s first-party Keymaster
   client for audio streaming. No developer dashboard setup for this step.
   Redirect: `http://127.0.0.1:8898/login`.
2. **Step 2 — Web API:** Create your own app at
   [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) and enter the
   **Client ID** and **Client Secret** on the Step 2 gate after playback login.
   Redirect: `http://127.0.0.1:43821/callback`.

### Create your Spotify Developer app

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
2. Click **Create App**
3. Fill in app name and description
4. Set **Redirect URI** to `http://127.0.0.1:43821/callback` (must match exactly)
5. Select **Web API** under “Which API/SDKs are you planning to use?”
6. Accept terms and click **Save**
7. Open **Settings** and copy your **Client ID** and **Client Secret**
8. Click **Save**

### Configure the app (Spotify)

1. Complete **Step 1** (playback login)
2. On **Step 2**, enter your Client ID and Client Secret:
   - Type manually, or
   - On a computer, open **[jonathancaudill.github.io/phono](https://jonathancaudill.github.io/phono/)** to generate a QR code, then tap **Scan QR** on the phone
3. Tap **Connect Web API** and authorize when prompted

The setup page runs entirely in your browser — credentials are never sent to a server. The QR
code encodes your client secret in plain text; generate and scan in private.

**Note:** Credentials may expire around 6 months. Rotate the secret and redo steps 2–3.

## TIDAL (single login)

No developer dashboard. Open the in-app WebView, sign in with your TIDAL account
(PKCE). Redirect is intercepted at `https://tidal.com/android/login/auth`. Tokens are
stored in encrypted prefs. There is no Step 2.

---

## Repository layout

```
rust/
  spotify-core/                 # UniFFI engine: session, player, queue, Spotify downloads
  librespot-core-patched/       # Keymaster/desktop identity (PATCHES.md)
  librespot-playback-patched/   # Buffering API, sink lifecycle (PATCHES.md)
  librespot-audio-patched/      # CDN fetch resilience (PATCHES.md)
app/                            # Android (Kotlin + Jetpack Compose; Spotify + TIDAL)
setup/                          # GitHub Pages: Spotify Web API credential QR generator
docs/                           # Architecture, offline downloads, field tests
scripts/build-rust.sh           # Cross-compile + UniFFI Kotlin bindings
```

Librespot crates are pinned to **=0.8.0**. Do not bump without re-validating every patch.

## Architecture

### Backend selection

- `BackendPickerScreen` → `BackendPreferences` (`phono_backend_choice`).
- `PlaybackController` binds one `PlaybackBackend` + `MusicRepository`:
  - Spotify → librespot UniFFI + `SpotifyRepository` / Web API + spclient
  - TIDAL → `TidalPlaybackBackend` (Media3) + `TidalRepository` / `TidalApiClient`
- Soft feature gates via `BackendCapabilities` (downloads, quality UI).

### Spotify playback (Rust)

- `LibrespotEngine` (UniFFI) owns session, player, and queue.
- Keymaster OAuth via WebView. Three client-identity surfaces must agree as
  Keymaster/desktop — see `AGENTS.md`.
- **Audio output (Path C):** decode → SPSC ring → drain thread → JNI →
  `PhonoAudioTrackSink` → `AudioTrack`. Details: [docs/audio-sink.md](docs/audio-sink.md).
- **Session recovery:** seamless rebuild with queue/position restore.
  [docs/future/session-reconnect.md](docs/future/session-reconnect.md).

### TIDAL playback (Media3)

- ExoPlayer with clear AAC/FLAC (BTS/DASH). Widevine/encrypted paths are skipped.
- Stream LRU under `cacheDir/tidal-stream` (~256 MiB); offline pins under
  `filesDir/tidal-downloads`.

### Android (shared)

- `PlaybackService` + MediaSession; `PlaybackController` owns audio focus, network
  policy, stall UX, and the offline-download façade.
- **StreamingPolicy:** network tiers (OFFLINE → GOOD_UNMETERED) bank the rest of the
  current track, then prefetch. Wi‑Fi must stay visible **2 minutes** before it is
  preferred over cellular (avoids blip handoffs).
- Spotify metadata: Web API + `NativeMetadataGateway` (playlists/artists via spclient).
- TIDAL metadata: REST via `TidalApiClient`.

`NativeInit` order (Spotify): `loadLibrary` → `initAndroidContext` → `registerAudioSink`.

## Offline downloads

Pin albums/playlists from headers, hold menus, or the **Downloads** tab. Shared Room
index; backend-specific engines:

| | TIDAL | Spotify |
|---|-------|---------|
| Engine | Media3 `DownloadManager` | UniFFI decrypt-to-Ogg + FGS |
| On disk | `filesDir/tidal-downloads` | `filesDir/spotify-downloads/{id}_{quality}.ogg` |

Streaming quality and download quality are independent (changing download quality does
not rewrite existing pins). Clear Cache wipes stream LRUs only — pins stay.

**TOS guard:** if Phono has not seen a network for **30+ days**, offline pins are wiped
(`OfflinePinHygiene`). Credentials and stream cache are untouched.

Details: [docs/offline-downloads.md](docs/offline-downloads.md).

## Caching

### Library (Room)

Liked tracks, saved albums, playlists — head-check delta sync, parallel page fill.

### Detail / search

Pinned Room detail cache (24 h TTL for saved/owned); ephemeral browse cache. Search
per-query in-memory (5 min); filter chips reuse the cached response.

### Auth tokens

- Spotify playback: librespot credentials in `filesDir/spotify-cache/`
- Spotify Web API / TIDAL: `EncryptedSharedPreferences` with refresh

### Audio

- **Spotify stream:** Ogg under `filesDir/spotify-cache/` (`buffer_current_to_end` /
  `prefetch_upcoming` on good networks)
- **TIDAL stream:** `cacheDir/tidal-stream` LRU
- **Pins:** `spotify-downloads` / `tidal-downloads` (not cleared by Clear Cache)

## Build

Prerequisites:

- Rust (rustup) with Android targets: `rustup target add aarch64-linux-android x86_64-linux-android`
- `cargo install cargo-ndk`
- Android NDK; export `ANDROID_NDK_HOME`
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

- Call `NativeInit.initAndroidContext` before constructing the Spotify engine.
- **Do not mix Spotify redirect URIs:** Step 1 → `127.0.0.1:8898/login`; Step 2 → `127.0.0.1:43821/callback`.
- **Do not use the Keymaster token for Web API** — metadata must use the BYO dev-app bearer.
- Playlist/artist screens on Spotify require Step 1 (native spclient).
- TIDAL has no Step 2; logout clears backend choice and returns to the service picker.
- **minSdk 26.** Audio focus in `PlaybackController`.
- `PlaybackService` must `startForeground()` promptly after `startForegroundService()`.

## Reliability

| Layer | Mechanism |
|-------|-----------|
| Session (Spotify) | Monitor + seamless rebuild; `force_reconnect_check()` on network change |
| Network policy | StreamingPolicy tiers + 2‑minute Wi‑Fi preference gate |
| Decode / bank | Spotify buffer/prefetch; TIDAL CacheWriter / DashDownloader |
| Audio output | Ring + drain (Spotify); ExoPlayer (TIDAL); stall recovery |
| APIs | Token refresh, HTTP 429 `Retry-After` where applicable |

Field validation: [docs/audio-sink-baseline-metrics.md](docs/audio-sink-baseline-metrics.md)

## Documentation index

| Doc | Contents |
|-----|----------|
| [AGENTS.md](AGENTS.md) | Hard rules, Spotify auth, diagnostics — read before coding |
| [docs/README.md](docs/README.md) | Developer onboarding index |
| [docs/offline-downloads.md](docs/offline-downloads.md) | Offline pins (Spotify + TIDAL) |
| [docs/audio-sink.md](docs/audio-sink.md) | Phase C AudioTrack architecture |
| [docs/future/](docs/future/) | Researched future work (session reconnect, backend move) |
