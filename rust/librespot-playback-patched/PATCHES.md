# librespot-playback 0.8.0 patches (phono)

## BufferCurrentToEnd (Fix 4A)

- `PlayerCommand::BufferCurrentToEnd` — switch current track to random-access mode and fetch full file
- `Player::buffer_current_to_end()` — public API
- `Player::is_current_fully_buffered()` — query via oneshot command
- `PlayerCommand::QueryCurrentBuffered` — internal query handler

## RecreateSink (Phase 3 — audio route recovery)

- `PlayerCommand::RecreateSink` — stop and reopen cpal/rodio sink without reloading track
- `Player::recreate_audio_sink()` — public API
- `Player::new` takes `Arc<dyn Fn() -> Box<dyn Sink> + Send + Sync>` factory (reusable)
- `sink.stop()` failure logs and continues instead of `exit(1)`

Upstream: crates.io `librespot-playback-0.8.0`
