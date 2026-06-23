# Spotify Client for Light Phone III

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

| | **mono** | **Echo** |
|---|---|---|
| Playback | librespot in-process | Spotify Android SDK (official app installed) |
| Metadata | Web API (your dev app) | Web API (your dev app) |
| Spotify app required | No | Yes |

Both need a Spotify Developer app for library/search/browse. mono additionally needs a
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
rust/spotify-core/     # Rust backend: librespot playback + daily-mix native discovery
app/                   # Android app (Kotlin Web API client + Jetpack Compose UI)
scripts/build-rust.sh  # Cross-compile + generate Kotlin bindings
```

## Architecture

- **Playback:** `LibrespotEngine` (UniFFI) owns session, player, queue. Keymaster OAuth via
  WebView (`http://127.0.0.1:8898/login`). Three client-identity surfaces (session, stored
  credentials, client-token) must all agree as Keymaster/desktop — see `AGENTS.md`.
- **Metadata:** Kotlin `SpotifyWebApi` + `SpotifyRepository` call `api.spotify.com/v1/...`
  with tokens from your dev-app OAuth (`http://127.0.0.1:43821/callback`). Search uses one
  combined `/search` request per query; results are ranked and interleaved client-side
  (`SearchRanking.kt`).
- **Library writes:** Web API (`PUT`/`DELETE /me/library`) — save/remove tracks and albums.
- **Daily mixes:** Hybrid — native librespot `context-resolve` search when possible; fallback
  to editorial playlist names in your library via Web API.

`PlaybackController` handles audio focus and exposes `StateFlow` to Compose;
`PlaybackService` hosts Media3 for lock-screen controls. UI follows the Light Template
aesthetic (black canvas, Public Sans, minimal chrome).

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

## Reliability

- Auto-reconnect with cached librespot credentials and queue restore
- Web API token refresh with `invalid_grant` handling (6-month refresh token expiry)
- HTTP 429 honored via `Retry-After` with capped retries
- Search results cached in memory (5 min TTL); filter chips reuse cached data with no extra API calls
