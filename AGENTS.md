# AGENTS.md

## Cursor Cloud specific instructions

This repo is a single Android product (Spotify client for the Light Phone III):
a Kotlin/Jetpack Compose app (`app/`) with an embedded Rust/`librespot` backend
(`rust/spotify-core/`) exposed via UniFFI. There is no server or database.

### Pre-installed toolchain (persisted in the VM snapshot)

- JDK 17 at `/usr/lib/jvm/java-17-openjdk-amd64`, Android SDK at `/opt/android-sdk`
  (`ANDROID_HOME`), NDK `27.0.12077973`, Rust stable with the `aarch64-linux-android`
  / `x86_64-linux-android` targets, and `cargo-ndk`.
- These plus `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_NDK_HOME`, and `PATH` are exported
  in `~/.bashrc`, so a login shell (`bash -l` / `source ~/.bashrc`) has everything.
- The host UniFFI binding build pulls in `alsa-sys` on Linux (via `rodio-backend`),
  so `libasound2-dev` + `pkg-config` are installed. Without them `scripts/build-rust.sh`
  fails at the host `cargo build` step with "Package alsa was not found".

### Build / lint (commands documented in `README.md`)

- Build: `bash scripts/build-rust.sh` then `./gradlew :app:assembleDebug`. Gradle's
  `preBuild` auto-runs the Rust build via the `cargoBuild` task, so `assembleDebug`
  alone also works. Run gradle with JDK 17 (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`);
  the box's default `java` is 21.
- The NDK version must match AGP's default (`27.0.12077973`) or every native task
  prints a `CXX1104` mismatch warning; `local.properties` and `ANDROID_NDK_HOME`
  already point at it. `local.properties` is gitignored — recreate it with
  `sdk.dir=/opt/android-sdk` + `ndk.dir=/opt/android-sdk/ndk/27.0.12077973` if absent.
- Lint: `./gradlew :app:lintDebug`. It currently FAILS on pre-existing `NewApi`
  errors inside the generated bindings (`ffi/spotify_core.kt` uses `java.lang.ref.Cleaner`,
  API 33, while `minSdk` is 26). These are generated code, not environment problems.
- No unit or instrumentation tests exist; `cargo test` has no tests in `spotify-core`.
- Generated artifacts (`app/src/main/jniLibs/`, `app/src/main/java/com/lightphone/spotify/ffi/`)
  are gitignored. If they go missing, regenerate with `bash scripts/build-rust.sh`.

### Running the app (emulator caveats)

- Running requires an Android emulator. The cloud VM has **no `/dev/kvm`**, so the
  emulator must run in software mode: `emulator -avd <name> -no-window -no-audio
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel off`. This is slow
  (boot takes several minutes) and CPU-heavy.
- The `google_apis` system image overloads and crashes `system_server` under
  software emulation (`pm`/`activity` services die). Use the lighter
  `system-images;android-30;default;x86_64` image (AVD `lp3light` is already created).
- Even then `SystemUI` may show "System UI isn't responding" while the CPU is
  saturated — tap "Wait" / dismiss and relaunch `com.lightphone.spotify/.MainActivity`.
- Full functionality (playback) requires logging in with a **Spotify Premium**
  account — a protocol-level `librespot` requirement that cannot be worked around.
  Without credentials you can still build, install, launch the app, and reach the
  live Spotify OAuth login WebView (the app's entry flow).
