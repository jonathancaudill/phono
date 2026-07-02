# librespot-core 0.8.0 patches (phono)

Minimal patches so Android builds present as **Keymaster desktop Linux** for playback auth. Metadata uses a separate dev-app Web API path in Kotlin — do not conflate.

## Identity (`config.rs`, `spclient.rs`, `version.rs`)

- `HTTP_PLATFORM_OVERRIDE` / `OS_VERSION_OVERRIDE` — runtime `OnceLock`s; first write wins
- `effective_os()` — presented OS (override or compile-time `OS`)
- **client-token** uses session (Keymaster) client ID on Android instead of `ANDROID_CLIENT_ID`
- `spotify_version()` uses desktop version when presenting as Linux (avoids mobile version + Linux UA mismatch)

## Runtime overrides (set before session build)

| Order | Where | Value |
|-------|-------|-------|
| 1 | `android_ctx.rs` via `NativeInit.initAndroidContext` | `os_version = "0"` |
| 2 | `spotify-core` `EngineShared::new` | `http_platform = "linux"` |

**Do not** add a second `set_os_version_override` in `lib.rs`.

## Unchanged on purpose

- `login5.rs` — stored-credential path already uses session client ID

## Session reconnect

In-place `Session.reconnect()` is **not** supported in 0.8.0. See [docs/future/session-reconnect.md](../../docs/future/session-reconnect.md).

Upstream: crates.io `librespot-core-0.8.0`
