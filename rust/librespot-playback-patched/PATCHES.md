# librespot-playback 0.8.0 patches (phono)

## BufferCurrentToEnd (Fix 4A)

- `PlayerCommand::BufferCurrentToEnd` — switch current track to random-access mode and fetch full file
- `Player::buffer_current_to_end()` — public API
- `Player::is_current_fully_buffered()` — query via oneshot command
- `PlayerCommand::QueryCurrentBuffered` — internal query handler

Upstream: crates.io `librespot-playback-0.8.0`
