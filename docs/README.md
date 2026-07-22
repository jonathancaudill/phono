# phono — developer documentation

Start here after skimming the root [README.md](../README.md).

**End users:** Web API credential setup (including QR generation) lives at
**[jonathancaudill.github.io/phono](https://jonathancaudill.github.io/phono/)** — see the root README Step 2.

## Essential reading

| Doc | When to read |
|-----|----------------|
| [../AGENTS.md](../AGENTS.md) | **Before any code change.** Auth identity, hard rules, diagnostic map. |
| [audio-sink.md](audio-sink.md) | Playback output (Phase C AudioTrack), threading, recovery layers. |
| [audio-sink-baseline-metrics.md](audio-sink-baseline-metrics.md) | Field-test checklist + BT/routing acceptance matrix. |
| [playback-stability-field-tests.md](playback-stability-field-tests.md) | Bad-network reconnect stability + precaching test matrix and new debug counters. |
| [offline-downloads.md](offline-downloads.md) | Platform-agnostic offline pins (Spotify Ogg + TIDAL Media3). |

## Patched librespot (all pinned to **0.8.0**)

| Crate | Doc | Purpose |
|-------|-----|---------|
| `librespot-core-patched` | [PATCHES.md](../rust/librespot-core-patched/PATCHES.md) | Keymaster/desktop identity on Android |
| `librespot-playback-patched` | [PATCHES.md](../rust/librespot-playback-patched/PATCHES.md) | Buffering API, sink lifecycle, seek flush |
| `librespot-audio-patched` | [PATCHES.md](../rust/librespot-audio-patched/PATCHES.md) | CDN fetch resilience (429, parallel slots) |
| `spotify-core` | [AGENTS.md](../AGENTS.md) + [audio-sink.md](audio-sink.md) | UniFFI engine, JNI AudioTrack sink, session rebuild |

**Do not bump librespot** without re-validating every patch.

## Future work (researched, not scheduled)

| Doc | Topic |
|-----|--------|
| [future/session-reconnect.md](future/session-reconnect.md) | Why in-place `Session.reconnect()` is a no-go in Rust 0.8.0; seamless rebuild today; Path A if needed |
| [future/backend-consolidation.md](future/backend-consolidation.md) | Phase D: move AudioTrack sink into `librespot-playback-patched` |

## Architecture at a glance

```
Kotlin UI (Compose) ── Web API (dev-app OAuth) ──► api.spotify.com
        │
        ▼
PlaybackController / Media3 ── UniFFI ──► spotify-core (Rust)
        │                                      │
        │                                      ├─ librespot session (Keymaster)
        │                                      ├─ Player + queue
        │                                      └─ AndroidAudioTrackSink
        │                                             ring → drain thread → JNI
        ▼
PhonoAudioTrackSink (Kotlin) ──► AudioTrack (USAGE_MEDIA)
```

**Two auth flows:** Keymaster OAuth for streaming; separate dev-app OAuth for metadata. Never mix tokens or redirect URIs.

**Session recovery:** Full `Active` rebuild with queue/position restore — not librespot-java in-place reconnect. See [future/session-reconnect.md](future/session-reconnect.md).

**Audio recovery:** `recreateAudioSink()` (Rust) + Kotlin coordinator (DEAD_OBJECT / stalled playhead). Orthogonal to session rebuild.
