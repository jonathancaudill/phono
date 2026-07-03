# Future: in-place session transport reconnect (Path A)

**Status:** Researched — **no-go** on librespot 0.8.0 without major core surgery.  
**Current production approach:** seamless `Active` rebuild (see below).

## librespot-java vs phono

| | librespot-java (Jetispot) | phono (Rust 0.8.0) |
|--|---------------------------|---------------------|
| AP disconnect | In-place `Session.reconnect()` | `session.shutdown()` → new `Session` + `Player` |
| Queue/position | Java player state survives | `snapshot_resume()` + `pending_queue` across rebuild |
| User-visible gap | Smaller (transport only) | Larger (full player rebuild) |

Jetispot delegates reconnect to librespot-java and only owns the **audio sink** on Android. phono owns the full Rust stack — we cannot adopt java reconnect without replacing the engine.

## Why Rust 0.8.0 cannot reconnect in place

`SessionInternal` uses `OnceLock` for dispatch, mercury, channel, login5, spclient, etc. `Session::shutdown()` sets `invalid = true` and tears down mercury/channel permanently. The session object is not reusable (upstream comment: instances cannot be reused once invalidated).

Implementing librespot-java-style reconnect means resetting connection-path `OnceLock`s without invalidating login5/spclient — high risk, touches the most fragile protocol code.

## What phono does instead (shipped)

- `spawn_monitor` — polls invalid session, triggers rebuild with cached credentials
- `snapshot_resume()` / `pending_queue` — preserve queue, position, **was-playing** flag
- `build_active()` — restores track at saved position; only auto-plays if user was playing
- `force_reconnect_check()` — debounced proactive shutdown on Android network change (don't wait ~80s for keepalive)
- `recreateAudioSink()` — **audio-only** recovery without session reload (BT / DEAD_OBJECT)

## When to revisit Path A

Field test **T1/T10** (cellular handoff, overnight reconnect) if audible gap or re-buffer after network change is still unacceptable **after** audio sink (Phase C) is stable.

### Possible scope (if pursued)

- Split `transport_lost()` from full `invalidate()` on connection path only
- Do **not** reset login5/spclient OnceLocks on transient AP drop
- Spike against `librespot-core-patched` with device-driven acceptance criteria

## Orthogonal to audio sink work

Phase C fixes **output routing** (AudioTrack, BT). Path A fixes **TCP/session rebuild disruption**. Both may be needed; neither replaces the other.
