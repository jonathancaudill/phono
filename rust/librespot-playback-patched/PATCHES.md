# librespot-playback 0.8.0 patches (phono)

## BufferCurrentToEnd (opportunistic buffering)

- `PlayerCommand::BufferCurrentToEnd` — switch current track to random-access mode and fetch full file
- `Player::buffer_current_to_end()` — public API
- `Player::is_current_fully_buffered()` — query via oneshot command
- `PlayerCommand::QueryCurrentBuffered` — internal query handler

Used by `StreamingPolicy` on good Wi‑Fi to bank the current track; combined with `prefetch_upcoming()` for multi-track cache warming in `spotify-core`.

## RecreateSink (audio route recovery)

- `PlayerCommand::RecreateSink` — stop and reopen sink without reloading track
- `Player::recreate_audio_sink()` — public API
- `Player::new` takes `Arc<dyn Fn() -> Box<dyn Sink> + Send + Sync>` factory (reusable)
- `sink.stop()` failure logs and continues instead of `exit(1)`

On Android Path C, recreates the ring + drain + Kotlin `AudioTrack` via the sink factory.

## Flush + transport pause

- `Sink::flush()` default no-op; seek path calls flush after successful seek
- `Sink::pause()` default calls `stop()`; Android audiotrack sink overrides to `AudioTrack.pause()`
- `ensure_sink_stopped(temporarily: true)` calls `sink.pause()` instead of `stop()`

## Discontinuous (user-initiated) load flush

- `PlayerCommand::Load` gains a `flush: bool` field
- `Player::load_discontinuous()` — public API; sends `flush = true`
- `Player::load()` unchanged (sends `flush = false`, used for gapless auto-advance
  and fresh-session resume)
- `handle_command_load` flushes the sink when `flush = true` and the sink is
  running, so a user skip/play during a slow load cannot let the outgoing track's
  buffered PCM tail overlap the incoming track ("two sections at once" garble that
  gapless mode otherwise allowed). `spotify-core` wires user `play_uris`/`skip`
  to `load_discontinuous`; EndOfTrack auto-advance keeps the gapless path.

## Android AudioTrack sink (implemented in spotify-core)

Not in this crate — see `spotify-core` with `audiotrack-sink` feature:

- SPSC ring (`pcm_ring.rs`, 112 KiB) + Rust drain thread (`audio_drain.rs`)
- Producer on librespot player thread; `PhonoAudioDrain` → JNI → Kotlin `PhonoAudioTrackSink`
- Drain thread uses `WRITE_BLOCKING` on `AudioTrack` (player thread never blocks on I/O)

Docs: [docs/audio-sink.md](../../docs/audio-sink.md), [docs/audio-sink-baseline-metrics.md](../../docs/audio-sink-baseline-metrics.md)

Upstream: crates.io `librespot-playback-0.8.0`
