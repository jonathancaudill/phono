# librespot-audio 0.8.0 patches (phono)

## Mobile CDN resilience

- `download_slots`: `Semaphore::new(2)` (was 1) — pipeline two concurrent range fetches
- HTTP 429: drop download permit before `Retry-After` sleep, re-acquire after
- Transient non-206 responses: retry up to 3 times with backoff before surfacing to decoder
- Export `Range` from crate root for playback patch
- `wait_timeout_count()` + `continuity: decoder_wait_timeout` log when a range wait
  hits `download_timeout` (Phase 0 field capture; does not change retry behaviour)

Upstream: crates.io `librespot-audio-0.8.0`
