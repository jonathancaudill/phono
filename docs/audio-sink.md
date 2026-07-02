# Audio output — Phase C (AudioTrack)

**Status:** Implemented (2026). Release builds default to `audiotrack-sink`.

## Why not rodio/cpal?

rodio → cpal → AAudio sits outside Android media routing. Bluetooth and route changes cause AAudio stream disconnects; recovery is app-level and unreliable compared to `AudioTrack` + `USAGE_MEDIA` (what ExoPlayer and Jetispot use).

## Data plane

```
librespot player thread          PhonoAudioDrain (Rust)         Kotlin
────────────────────────         ─────────────────────         ──────
decode → S16LE → ring.push  →    ring.pop → JNI copy  →    AudioTrack.write (BLOCKING)
                                 (dedicated thread)          (drain thread only)
```

The **player thread never calls AudioTrack**. Backpressure is the SPSC ring high-water mark (~450 ms), not Kotlin sleep on the decoder thread.

## Key files

| Layer | Path |
|-------|------|
| Ring | `rust/spotify-core/src/pcm_ring.rs` |
| Drain thread | `rust/spotify-core/src/audio_drain.rs` |
| Producer sink | `rust/spotify-core/src/android_audiotrack_sink.rs` |
| JNI | `rust/spotify-core/src/audio_sink_jni.rs` |
| AudioTrack owner | `app/.../audio/PhonoAudioTrackSink.kt` |
| Position delay | `PlaybackController.audiblePositionMs()` |

## Buffer tiers

| Tier | Size | Role |
|------|------|------|
| SPSC ring | 112 KiB (~635 ms cap) | Jitter absorber between decode and drain |
| HIGH_WATER | 79,380 B (~450 ms) | Block producer when full |
| AudioTrack HAL | `max(minBuf×4, ~500 ms)` | ExoPlayer-style device buffer |

## Lifecycle rules

- **Gapless:** no ring flush at track boundaries — only on seek, stop, or explicit recreate.
- **Seek:** `sink.flush()` → drain barrier (discard ring) → `AudioTrack.flush()` → reset frame counter.
- **Transport pause:** `AudioTrack.pause()` only — ring and drain stay alive.
- **Stop/teardown:** join drain → stop → flush → release.

## Routing recovery (capabilities-first)

`OnRoutingChangedListener` updates device id only — **no immediate recreate**. Recreate when:

- `ERROR_DEAD_OBJECT` on write
- Playhead stalled ≥200 ms while playing (BT connect failure pattern)
- `recreateAudioSink()` / L6 `PlaybackController` fallback when `deadObjectCount > 0`

## Build flags

```bash
# Default: AudioTrack (Path C)
USE_AUDIOTRACK_SINK=1 ./scripts/build-rust.sh

# Fallback: rodio/cpal/AAudio (emulator debug)
USE_AUDIOTRACK_SINK=0 ./scripts/build-rust.sh
```

Set matching `USE_AUDIOTRACK_SINK` in `app/build.gradle.kts`.

## Field validation

See [audio-sink-baseline-metrics.md](audio-sink-baseline-metrics.md) for logcat tags, metrics fields, and the T/F/S/R test matrix (Light Phone III sign-off).

## Research references

- **Jetispot / librespot-android** — `AndroidSinkOutput` shape (blocking write on output thread).
- **ExoPlayer `DefaultAudioSink`** — buffer sizing, position tracker, recoverable write errors.
- **psst / go-librespot** — decode/output thread separation (ring or output loop).

## Deferred (Phase D)

Move `AndroidAudioTrackSink` into `librespot-playback-patched` as an `android-backend` feature (Outify alignment). See [future/backend-consolidation.md](future/backend-consolidation.md).
