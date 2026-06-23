# Spotify Client for Light Phone III

An independent, minimal Spotify Premium client. No official Spotify SDK and no
dependency on the Spotify app: **playback** is handled in-process by
[`librespot`](https://github.com/librespot-org/librespot) (Rust). **Metadata**
(search, library, albums, artists) uses the official Spotify Web API with your
own developer-app credentials.

> Requires a Spotify **Premium** account. This is a protocol-level requirement of
> librespot, not something that can be worked around.

## Setup (required before first use)

The app uses **dual authentication**:

1. **Step 1 — Playback (librespot):** WebView login with Spotify's first-party
   client for audio streaming. No developer dashboard setup needed.
2. **Step 2 — Web API:** You must create your own app at
   [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) and
   enter the **Client ID** and **Client Secret** in the app.

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

## Layout

```
rust/spotify-core/   # Rust backend: librespot playback + daily-mix native discovery
app/                 # Android app (Kotlin Web API client + Jetpack Compose UI)
scripts/build-rust.sh# Cross-compile + generate Kotlin bindings
```

## Architecture

- **Playback:** `LibrespotEngine` (UniFFI) owns session, player, queue. Keymaster
  OAuth via WebView (`http://127.0.0.1:8898/login`).
- **Metadata:** Kotlin `SpotifyWebApi` (OkHttp + kotlinx.serialization) calls
  `api.spotify.com` with tokens from your dev-app OAuth (`http://127.0.0.1:43821/callback`).
- **Daily mixes:** Hybrid — native librespot context-resolve when possible;
  fallback to Daily Mix playlists in your library via Web API.

`PlaybackController` handles audio focus and exposes `StateFlow` to Compose;
`PlaybackService` hosts Media3 for lock-screen controls.

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
```

## Key gotchas

- **`ndk_context` must be initialized** via `NativeInit.initAndroidContext` before
  constructing the engine.
- **Audio focus** is handled in Kotlin (`PlaybackController`).
- **minSdk is 26** (AAudio requirement).
- **Artist top tracks, recommendations, new releases** are not available on new
  dev-mode Web API apps (Spotify Feb 2026 restrictions).
- **Non-premium accounts:** librespot requires Premium for playback.

## Reliability

- Auto-reconnect with cached librespot credentials and queue restore
- Web API token refresh with `invalid_grant` handling (6-month refresh token expiry)
- HTTP 429 honored via `Retry-After` with capped retries
