# In-place `Session.reconnect()` (rust)

**Superseded.** The playback plan is [playback-continuity.md](playback-continuity.md).

Rust librespot 0.8.0 still has no in-place `Session.reconnect()` (`session.rs`
keep-alive path is `TODO: Optionally reconnect`). That remains an **optional
Phase 5** if keeping the Player plus a *new* Session object is not enough for
unbanked mid-track CDN fetches.

Do not start there. Phase 2 of playback-continuity is: new Session, same Player,
`set_session`, no `load()`, no UI pause, gated on remaining audio already local.
