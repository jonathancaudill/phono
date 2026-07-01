# librespot Patch Strategy: Buffering, Reconnect, Prefetch

Investigation of librespot v0.8.0 internals to enable the functionality the bug list needs.
Several fixes do NOT require patching librespot at all — they're config or app-layer. The ones
that do are scoped here with difficulty ratings. IMPORTANT STRUCTURAL FACT: the repo currently
patches ONLY `librespot-core` (`[patch.crates-io] librespot-core = path ../librespot-core-patched`).
The `audio` and `playback` crates come from crates.io unpatched. Some fixes below require adding
a SECOND patched crate (`librespot-audio` and/or `librespot-playback`), which is more build/setup
work — call this out before starting.

---

## FIX 1 — "Starts playing out of nowhere" — NO PATCH NEEDED (app-layer)

This is purely your `build_active` calling `player.load(uri, true, pos)` on reconnect rebuild.
librespot's `Player::load(track_id, start_playing, position_ms)` honors the `start_playing` flag
exactly (player.rs:544 → `PlayerCommand::Load { play: start_playing }`). So pass the
pre-disconnect play state instead of hardcoded `true`. No librespot change.

Also: set `SessionConfig.autoplay = Some(false)` (config.rs:31) so Spotify's server-side autoplay
(continuing with "radio" after your queue ends) can't independently start playback. This is a
config field already exposed — no patch.

Difficulty: TRIVIAL. Do this first.

---

## FIX 2 — Stale connection not detected — NO PATCH NEEDED (use librespot's keepalive)

You assumed `is_invalid()` misses half-open sockets. It does — BUT librespot already has a
keepalive watchdog that turns a stale connection INTO an invalid one. From session.rs dispatch
(~929): librespot runs a Ping/Pong/PongAck keepalive with a 60s cadence + 20s safety margin; if a
Ping/PongAck is missed, it calls `session.shutdown()` (which flips `is_invalid()` to true) and
returns a TimedOut error. So a stale connection DOES eventually become invalid — within ~80s.

So the real issue is either (a) your monitor's 5s poll + the ~80s keepalive timeout = up to ~85s
before reconnect kicks in (feels like "stuck"), or (b) the monitor isn't running/observing.

Options, cheapest first:
- App-layer: when Android reports a network change (ConnectivityManager callback), proactively
  call a "force reconnect check" rather than waiting for the 80s keepalive. You know the network
  changed before librespot's Ping times out. This is the high-value, no-patch fix.
- If you want faster intrinsic detection, the keepalive timeout constants are in `core` (already
  patched) — you could shorten the safety margin. But shortening risks false disconnects on
  legitimately slow networks. Prefer the app-layer network-change trigger.

Difficulty: LOW (app-layer network-change listener). The keepalive you wanted already exists.

---

## FIX 3 — Transparent reconnect / "new network = total teardown" — LIBRESPOT CANNOT DO THIS (0.8.0)

This is the hard truth from the source. session.rs:127 states plainly:
> "Session instances cannot yet be reused once invalidated."
And the disconnect path (session.rs:932) has a literal `// TODO: Optionally reconnect (with
cached/last credentials?)`.

So in librespot 0.8.0 there is NO in-session transparent reconnect. Once the session is invalid,
the ONLY supported recovery is to build a new session — which is exactly what your
`build_active` rebuild does. Your approach is not wrong; it's the only thing the library
supports. The bugs ("queue disappears", "total dis/reconnect") are about how DESTRUCTIVE the
rebuild is, not about avoiding it.

Two paths:

### 3a. Make the rebuild non-destructive (no patch, app/Rust-layer) — RECOMMENDED
Keep rebuilding the session+player, but preserve everything around it:
- Persist the FULL queue (not just `restore_linear`) across rebuilds — store the complete
  `QueueState` (upcoming + context + index + position) in `snapshot_resume()` and restore it
  verbatim. This fixes "queue disappears."
- Don't reset UI-observed state on `on_connection_lost`; only swap the underlying session. The
  queue/now-playing the UI observes should be owned by your `EngineShared`, NOT by the per-session
  `Active`, so a session swap doesn't blank them.
- Preserve play/pause + position (Fix 1) so the rebuild is seamless.
Result: the rebuild becomes invisible — same queue, same position, same play state, just a fresh
underlying socket. This is the realistic "transparent reconnect."

### 3b. Patch librespot for true session reuse — HARD, NOT RECOMMENDED
Implementing the `// TODO` (reconnect an invalidated session in place) means patching the core
connection/dispatch loop to re-handshake and resume the Shannon cipher state without tearing
down `Session`. This is deep protocol work, touches the most fragile part of librespot, and the
maintainers themselves haven't done it. High risk of breaking auth/playback. Avoid unless 3a
proves insufficient.

Difficulty: 3a = MEDIUM (Rust, your code). 3b = VERY HARD (core patch, not advised).

---

## FIX 4 — Runtime-adaptive buffering — REQUIRES PATCHING librespot-audio (MEDIUM-HARD)

The blocker you correctly identified: `AudioFetchParams` is a process-global `OnceLock`
(audio/src/fetch/mod.rs:120, `get_or_init`), set once, never changeable. So you cannot raise
read-ahead at runtime when signal degrades.

### The seam that DOES exist: StreamLoaderController
librespot exposes a per-track `StreamLoaderController` (audio/src/fetch/mod.rs) with RUNTIME
methods:
- `fetch(range: Range)` — force-fetch an arbitrary byte range NOW
- `fetch_blocking(range)` / `fetch_next_and_wait(...)` — fetch and wait
- `range_available(range)` / `range_to_end_available()` — query what's buffered
- `set_random_access_mode()` / `set_stream_mode()` — switch fetch strategy
- `ping_time()` — current measured latency

The Player holds a `StreamLoaderController` per active track (player.rs:667). The problem:
**the Player does NOT publicly expose its current track's controller.** So today you can't reach
`fetch()` from your code.

### Patch option A (RECOMMENDED): expose the controller + a force-buffer method
Patch `librespot-playback` to add a public method on `Player` that proxies to the current
track's `StreamLoaderController`, e.g.:
```rust
// in playback/src/player.rs, on Player
pub fn fetch_ahead_to_end(&self) { /* send a command that calls
    stream_loader_controller.set_random_access_mode(); ...fetch(0..len) on current track */ }
pub fn buffered_range_to_end(&self) -> bool { /* query range_to_end_available */ }
```
Then from your Rust layer, when Android reports good signal/WiFi/unmetered, call
`fetch_ahead_to_end()` to opportunistically download the rest of the current track (and you
already preload the next). When signal is poor, you've already banked it.

This is the cleanest "intelligent opportunistic lookahead": the buffer params stay fixed, but you
DRIVE extra fetching through the controller when conditions are good. Requires adding
`librespot-playback` as a patched crate and threading a new command through the player's command
channel (Load/Preload/Play already use this pattern — follow it).

### Patch option B (SIMPLER, COARSER): make AudioFetchParams mutable
Patch `librespot-audio` to replace the `OnceLock<AudioFetchParams>` with an
`ArcSwap<AudioFetchParams>` (or `RwLock`), and add `AudioFetchParams::update(params)`. Then you
can raise `read_ahead_during_playback` at runtime. CAVEAT: many call sites read
`AudioFetchParams::get()` once at track-load time (e.g. computing read-ahead in bytes,
fetch/mod.rs:565), so a mid-track change may only take effect on the NEXT track. Coarser than
option A, but a much smaller patch (one type swap + an update fn).

### No-patch partial mitigation (do this regardless)
- Bump the default `read_ahead_during_playback` for the mobile preset from 12s to ~30s. Bigger
  static buffer is the cheapest skip mitigation. No patch — it's your `settings.rs` numbers.
- Pre-warm: on good signal, you already `preload(next)`. That's a no-patch single-track lookahead.

Difficulty: bump numbers = TRIVIAL. Option B = MEDIUM (audio patch, coarse). Option A = MEDIUM-HARD
(audio+playback patch, but the RIGHT design for opportunistic adaptation).

---

## FIX 5 — Multi-track prefetch — PART NO-PATCH, PART MEDIUM PATCH

Today `refresh_next_preload` calls `player.preload(next_uri)` — exactly ONE track, and librespot
only tracks one `PlayerPreload` slot (player.rs:78). So you can't preload track+2, +3 via the
built-in preload.

### No-patch approach: prefetch via the audio cache
librespot writes fetched audio to the on-disk `Cache`. You can warm the cache for upcoming tracks
WITHOUT the player by opening their `AudioFile` / issuing range fetches through the audio layer
directly for the next 2-3 queue URIs when on good signal. This reuses the same cache the player
reads from, so when the player reaches those tracks they're already local. More plumbing but no
player patch.

### Patch approach: multi-slot preload
Patch `librespot-playback` to support a small ring of preload slots instead of one
(`PlayerPreload` → `Vec`/fixed array). More invasive (touches the player state machine and the
"suggested_to_preload_next_track" logic, player.rs:182). Higher risk; only if the cache-warming
approach proves insufficient.

### Combine with Fix 4A
The cleanest whole design: keep librespot's single `preload(next)` for gapless, AND use the
StreamLoaderController `fetch()` (Fix 4A) + direct cache-warming to bank the current track and
the next 2-3 when signal is good. The app layer orchestrates "how aggressively to pre-cache"
based on Android `NetworkCapabilities`.

Difficulty: cache-warming = MEDIUM (no patch). Multi-slot preload = MEDIUM-HARD (playback patch).

---

## Summary table

| Fix | Needs librespot patch? | Which crate | Difficulty |
|---|---|---|---|
| 1. No autoplay on reconnect | No (app/config) | — | Trivial |
| 1b. Disable server autoplay | No (SessionConfig.autoplay=false) | — | Trivial |
| 2. Stale detection | No (Android net-change trigger; keepalive exists) | — | Low |
| 3a. Non-destructive rebuild | No (your Rust/queue persistence) | — | Medium |
| 3b. True session reuse | Yes (deep core protocol) | core | VERY HARD — avoid |
| 4. Bigger static buffer | No (settings numbers) | — | Trivial |
| 4A. Opportunistic fetch via controller | Yes | audio + playback (NEW patched crates) | Medium-Hard |
| 4B. Mutable AudioFetchParams | Yes | audio (NEW patched crate) | Medium |
| 5. Cache-warm next 2-3 tracks | No (audio layer from app) | — | Medium |
| 5b. Multi-slot preload | Yes | playback (NEW patched crate) | Medium-Hard |

## Recommended sequence
1. Ship the NO-PATCH wins first: Fix 1 (+1b autoplay off), Fix 2 (net-change trigger), Fix 4
   buffer bump, Fix 3a (full-queue persistence + EngineShared-owned UI state). These resolve the
   alarming/structural bugs with zero new patched crates.
2. THEN, if skipping on poor cell signal persists, add the audio/playback patched crates for Fix
   4A (controller-driven opportunistic fetch) + Fix 5 (multi-track cache warming). This is the
   real fix for "intelligent opportunistic lookahead" but costs you a second (and maybe third)
   patched librespot crate and the build/maintenance overhead that implies.
3. Do NOT pursue Fix 3b (true in-session reconnect). librespot doesn't support it, the maintainers
   left it as a TODO, and 3a achieves the same user-visible result far more safely.

## Build/setup note before any patch
Adding `librespot-audio` / `librespot-playback` patches means: copy the matching v0.8.0 crate
source into `rust/`, add `[patch.crates-io] librespot-audio = { path = ... }` (and playback),
and keep them pinned to 0.8.0 exactly like core. Each new patched crate is ongoing maintenance
surface — only add them when the no-patch mitigations are proven insufficient.
