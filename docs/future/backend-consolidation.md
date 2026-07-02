# Future: Phase D — backend consolidation

**Status:** Deferred until Phase C field sign-off on Light Phone III.

## Goal

Move `AndroidAudioTrackSink` from `spotify-core` into `librespot-playback-patched/src/audio_backend/android.rs` behind an `android-backend` feature flag (Outify / librespot naming alignment).

## Benefits

- Sink lives next to other `audio_backend` implementations (rodio, subprocess)
- Cleaner feature matrix: `audiotrack-sink` on playback crate, not spotify-core
- Easier upstream comparison with Outify's android backend

## Non-goals

- Changing Keymaster identity or session auth
- Replacing UniFFI control plane
- Bumping librespot off 0.8.0

## Prerequisites

- Phase C test matrix (T/F/S/R) passed on hardware
- Stable JNI registration order documented in AGENTS.md

## Rollback

Keep `USE_AUDIOTRACK_SINK` build flag; rodio/cpal path remains for emulator fallback.
