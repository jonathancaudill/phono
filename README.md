# Spotify Client for Light Phone III

An independent, minimal Spotify Premium client. No official Spotify SDK and no
dependency on the Spotify app: streaming is handled entirely in-process by
[`librespot`](https://github.com/librespot-org/librespot) (Rust), wrapped behind
a stable UniFFI surface and driven by a Kotlin + Jetpack Compose host.

> Requires a Spotify **Premium** account. This is a protocol-level requirement of
> librespot, not something that can be worked around.

## Layout

```
rust/spotify-core/   # Rust backend: librespot (=0.8.0) behind a UniFFI API
app/                 # Android app (Kotlin + Jetpack Compose, Media3)
scripts/build-rust.sh# Cross-compile + generate Kotlin bindings
```

## Architecture (one paragraph)

`rust/spotify-core` owns a tokio runtime, a librespot `Session`, a `Player`
(audio via `rodio` → `cpal` → AAudio), and a manual queue. It exposes a small
`LibrespotEngine` object plus a `PlayerEventListener` callback over UniFFI. On
Android the app calls `NativeInit.initAndroidContext(applicationContext)` once at
startup so cpal can reach the JVM/`Context` via `ndk_context`. `PlaybackController`
(Kotlin) owns the engine, handles audio focus, and exposes a `StateFlow` to the
Compose UI; `PlaybackService` hosts a Media3 `MediaSession` for lock-screen
controls. Web API metadata uses the login5-derived token (no developer client ID).

## Build

Prerequisites:

- Rust (rustup) with Android targets: `rustup target add aarch64-linux-android x86_64-linux-android`
- `cargo install cargo-ndk`
- Android NDK installed; export `ANDROID_NDK_HOME`
- JDK 17, Android SDK (compileSdk 35), `gradle` (or generate the wrapper: `gradle wrapper`)

Then:

```bash
# 1) Cross-compile the Rust backend and generate the Kotlin bindings.
bash scripts/build-rust.sh

# 2) Build the app (also runs step 1 via the cargoBuild Gradle task).
gradle :app:assembleDebug
```

The Rust feature set is pinned in `rust/spotify-core/Cargo.toml`:
`--no-default-features --features "rustls-tls-webpki-roots,rodio-backend,with-libmdns"`.

## Key gotchas (read before debugging)

- **`librespot` breaks when Spotify changes their backend.** Before assuming a
  local bug, check the [librespot issues tracker](https://github.com/librespot-org/librespot/issues).
  We pin `=0.8.0`; bumping it should only ever touch `rust/spotify-core`.
- **`ndk_context` must be initialized.** Without `NativeInit.initAndroidContext`,
  the first audio call panics with "android context was not initialized".
- **Audio focus is handled in Kotlin** (`PlaybackController`), because cpal/rodio
  does not participate in Android audio focus. Headphone-unplug / output-route
  changes are the most likely cause of rodio glitches; if they prove unreliable
  on device, the escalation is a custom `librespot::audio_backend::Sink` backed
  by a Kotlin `AudioTrack` (see the plan).
- **minSdk is 26** (AAudio requirement).
- **Web API scope:** search, `/me/tracks`, albums work with the login5 token;
  some personalized home/browse sections return 403. Treat such failures as
  "section unavailable" rather than errors.
- **Non-premium accounts:** librespot's `check_catalogue` calls `exit()` on a
  non-premium account. Premium is required by design.

## Reliability features

- Auto-reconnect with exponential backoff using cached credentials (librespot
  sessions can't be reused once invalidated, so a fresh `Session`/`Player` is
  rebuilt and the queue/position restored).
- login5 access token is cached and refreshed on expiry; Web API retries once on
  401.
- A typed FFI error taxonomy (`SpotifyError`) distinguishes auth / premium /
  network / track-unavailable / internal failures.
