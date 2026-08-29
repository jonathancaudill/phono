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
| [download-rate-limiting.md](download-rate-limiting.md) | Pin-queue pacing vs other downloaders; BASE + jitter, Fast/Balanced/Careful, circuit breaker. |
| [self-update.md](self-update.md) | GitHub-release self-updater; what the release process must guarantee. |

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
| [future/playback-continuity.md](future/playback-continuity.md) | Keep Player/sink when AP TCP dies; copy java/go contract in rust. Phased: field loop → stop ConnectivityManager teardown → session-only rebuild → longer bank → decoder retry. |
| [future/session-reconnect.md](future/session-reconnect.md) | Stub. In-place rust `Session.reconnect()` is optional Phase 5 of playback-continuity, not the plan. |
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

**Session recovery (today):** Full `Active` rebuild with queue/position restore. **Planned:** keep the Player when remaining audio is local; replace only the AP session. See [future/playback-continuity.md](future/playback-continuity.md).

**Audio recovery:** `recreateAudioSink()` (Rust) + Kotlin coordinator (DEAD_OBJECT / stalled playhead). Orthogonal to session rebuild.
