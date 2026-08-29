# Playback continuity — keep the Player when the AP socket dies

**Status:** Phase 0 field capture shipped in 0.1.6 (logging + counters only; no
product playback change). Do not start Phase 1+ until a captured log names H1,
H2, or H3.

**Contract:** a brief network blip, Wi‑Fi roam, or default-network / source-IP
change must not pause music if the current track still has enough encoded audio
on disk. Android owns routing. The access-point TCP socket is allowed to die;
the decoder, sink, and already-downloaded bytes are not.

This is the librespot-java / go-librespot outcome, implemented in rust without
switching engines and without in-place `Session.reconnect()` as a prerequisite.

Related: [playback-stability-field-tests.md](../playback-stability-field-tests.md),
[audio-sink.md](../audio-sink.md), `rust/spotify-core/src/playback_continuity.rs`.
The old [session-reconnect.md](session-reconnect.md) stub is superseded by this
doc. In-place rust `Session.reconnect()` remains an optional later phase, not the
plan.

---

## Why this, not a Java/Go rewrite

librespot-java (`Session.reconnect()`) and go-librespot (`Accesspoint.reconnect`)
replace the AP TCP socket **inside the same Session**. The Player, CDN chunk
buffers, and AudioTrack keep running. Missing chunks **wait and retry**
(`AbsChunkedInputStream`); they do not skip the track.

Rust librespot 0.8.0 has no such reconnect. `session.shutdown()` sets `invalid`
forever (`session.rs`; keep-alive timeout still says `TODO: Optionally reconnect`).
Phono then drops the whole `Active` (Session **and** Player **and** sink) and
`load`s the track again. That is an audible Pause→Play even when the Ogg is
already on disk. `playback_continuity.rs` already documents this for the monitor
path.

A Java or Go engine swap would buy that reconnect, and cost dual-auth identity,
UniFFI metadata, Ogg pins, Path C sink rules, and every 0.8.0 patch. Go on
Android is gomobile (JNI + a Go runtime). Stay on rust; copy the **contract**.

---

## Diagnosis (what Phase 0 must confirm)

Two sockets, three buffers, one mistake: we couple Player lifetime to AP TCP.

| Layer | Typical depth | Survives a 2–3s roam? |
|-------|---------------|------------------------|
| PCM ring + AudioTrack HAL | ~0.5s + ~0.5s | only that ~1s |
| Encoded Ogg in the stream-loader tempfile | **~5s** ahead by default, or **rest of track** after `buffer_current_to_end` | **yes**, if those bytes are in the `RangeSet` |
| `Session` AP TCP (`ap-*.spotify.com`) | n/a | **no** |

`AudioFileStreaming::read` only blocks when the byte at the read cursor is not
downloaded. Cached remaining audio **can** play through a dead AP — if we leave
the Player alone.

### Ranked hypotheses

**H1 (most likely for “tons of pause/play”).** AP TCP dies (roam, DHCP, default
`Network` object change). Monitor sees `session.is_invalid()`, emits
`on_connection_lost` (`isPlaying = false`), drops `Active`, rebuilds, `load`s,
emits Playing. Independent of cache. TIDAL on the same roam does not blip
(`TidalPlaybackBackend.forceReconnectCheck` is a no-op).

**H2.** Kotlin `registerDefaultNetworkCallback` treats default-network flaps as
session events. `onAvailable` + dead session → `debouncedForceReconnect` →
teardown unless `is_current_fully_buffered()`. Same-SSID roam that takes the
default network away for >3s.

**H3.** Encoded read-ahead is only ~5s; Player kept. Audible hole, icon may stay
on pause, `WaitTimeout` (8s) then rust **skips the track** (`EndOfTrack` on
decode error). Sounds like a skip or a stall, not icon flicker.

**H4.** CDN URL expired / fetch task died (`TODO: refresh cdn_url when the token
expired` in `librespot-audio-patched`).

**H5.** Audio focus / `ACTION_AUDIO_BECOMING_NOISY` — rule out with log tags.

### What already tries to help (and where it stops)

- `ReconnectPolicy.shouldTearDownOnTransportHandoff` / `should_teardown_on_force_reconnect`:
  **keep** a fully-banked playing Player on Kotlin-driven handoff.
- Monitor / `orchestrate_rebuild`: **always** drops `Active`. This is the hole.
- `StreamingPolicy` already banks the current track on any non-OFFLINE playing
  tier (battery gate aside). Prefetch depth is tiered; banking is not the 30s
  Wi‑Fi prefer gate. The gate only delays GOOD_UNMETERED and cellular→Wi‑Fi
  *handoff confirmation*.
- `Player::set_session` exists in `librespot-playback-patched` and is **never
  called**. It swaps `PlayerInternal.session` for future loads; it does **not**
  rebind the live `AudioFileFetch` (that cloned the old `Session`).

---

## Target architecture

Today `Active` is one object. `Drop` stops the player. `orchestrate_rebuild`
`take()`s it before building a new Session+Player and `load`ing.

```
Active { session, player, mixer, queue, event_task, offline }
        └─ Drop → player.stop()  +  event_task.abort()
```

Target: **Session is replaceable; Player is not**, whenever remaining audio from
the read cursor is local (or above a threshold).

```
session dies
    │
    ├─ remaining encoded audio local? ── yes ──► keep Player, sink, ring, decoder,
    │                                            queue, event_task
    │                                            new Session::connect
    │                                            player.set_session(new)
    │                                            swap Active.session + monitor
    │                                            no load(), no UI pause
    │
    └─ no ──► existing full rebuild (Pause→Play is then honest:
              we would have stalled anyway)
```

Same identity rules as today: new Session is Keymaster/desktop Linux,
`os_version` / `http_platform` OnceLocks already set, stored credentials,
`spclient().clear_client_token()` after connect. Do not touch `login5.rs`.
Do not send the Keymaster token to `api.spotify.com`.

**One Player, one sink, always.** The overlap work (`rebuild_inflight`, sink
epoch, `load_discontinuous`) stays. Session-only rebuild must never construct a
second `Player` or a second AudioTrack. If a session-only rebuild fails, fall
back to full rebuild **after** the live Player is stopped — never two at once.

TIDAL is out of scope. Media3 already matches this contract.

---

## Non-goals

- Replacing rust librespot with librespot-java or go-librespot.
- In-place `Session.reconnect()` in rust 0.8.0 as the first delivery (Phase 5,
  optional).
- `bindProcessToNetwork` / binding sockets to an interface.
- Pausing or recreating AudioTrack on network callbacks (Path C already ignores
  route changes except `DEAD_OBJECT`).
- Stall watchdog calling `forceReconnectCheck` (it must not; it already does not).
- Bumping librespot off `=0.8.0`.
- Changing streaming vs download quality independence, or Clear Cache vs pins.
- Using ConnectivityManager as a playback-teardown signal (Phase 1 removes that).

---

## Phase 0 — Field loop (no product change)

**Why:** H1 vs H2 vs H3 need different patches. Coding the wrong one wastes the
overlap-hardening.

**On device (LP3), during the pause/play storm:**

```bash
adb logcat -s spotify-core:W Playback:W PhonoAudioDrain PhonoAudioTrack
```

Grep `continuity:`. Rust tag is `spotify-core` (not `SpotifyCore`). Kotlin uses
`Log.w` so lines survive R8. `buffered=?` on play/pause/loading is intentional:
`is_current_fully_buffered()` must not run on the player-event path.

Dump `playback_debug_metrics()` on every `on_connection_lost` / `onPaused` /
`onPlaying`. Cheap A/B: same album on **TIDAL**. If TIDAL is smooth, stop looking
at AudioTrack/Wi‑Fi hardware.

**H1 confirmed when:** every audible blip has `build_active … reason=session_rebuild`
or `full_rebuild++` / `on_connection_lost`; `is_current_fully_buffered` is often
true; play/pause icon flips; TIDAL does not blip.

**H2 confirmed when:** blips line up with `onAvailable` / `onLost` past 3s and
`transport_reconnect++`, often with `fully_buffered = false`.

**H3 confirmed when:** silence without rebuild; `WaitTimeout` / “unable to get
next packet”; possible skip; `stall_events++`.

**Instrumentation in 0.1.6** (`continuity:` prefix; keep these counters):

| Counter | Meaning |
|---------|---------|
| `connection_lost_while_buffered` | Monitor fired while `is_current_fully_buffered` |
| `decoder_wait_timeout` | `AudioFileError::WaitTimeout` on the playing track |
| `force_reconnect_skipped_banked` | Native skipped teardown because banked |
| `session_only_rebuild` | Phase 2+ path taken (not yet) |
| `session_only_rebuild_fallback` | Session-only failed, full rebuild (not yet) |

**Completion:** one captured log (redacted) that names H1/H2/H3. No Phase 1+
without that, unless the implementer is explicitly fixing an already-confirmed
path.

**Cannot be done in the emulator.** Same constraint as the existing stability
matrix.

---

## Phase 1 — ConnectivityManager is policy, not teardown

**Why:** Android already moved the default network. New HTTP will use it. Old AP
TCP will die on its own. Kotlin must not *also* destroy the Player.

### Behaviour

`StreamingPolicy` still classifies tiers, banks, prefetches, and runs the 30s
Wi‑Fi prefer gate for **prefetch depth / metered vs unmetered**. It does not
trigger `forceReconnectCheck`.

`PlaybackController.networkCallback`:

- `onAvailable` / `onLost`: keep `networkOnline` + `setNetworkOnline` (hygiene,
  offline Active upgrade, pin clock). The 3s lost-grace stays so a 200ms roam
  does not flip the UI to “No connection.”
- **Stop** `debouncedForceReconnect()` from `onAvailable` except the existing
  “offline Active → live session” upgrade inside rust `set_network_online`
  (that path already goes through `should_teardown_on_force_reconnect`).
- **Stop** `maybeForceReconnectAfterHandoff` / `onWifiPreferGateElapsed` from
  tearing down a live Player. Confirmed wifi↔cell can still *warm* spclient
  (`warmSpclientSessionAsync`); it must not `forceReconnectCheck` while playing.

Rust `force_reconnect_check` remains for: session actually dead, offline→online
upgrade of a pin-only Active, explicit “ensure playback ready” when there is no
Player. The cache-aware skip (`playing && fully_buffered`) stays as a belt.

### Files

- `app/.../playback/PlaybackController.kt` — `networkCallback`,
  `maybeForceReconnectAfterHandoff`, `onWifiPreferGateElapsed`,
  `debouncedForceReconnect`
- `app/.../playback/ReconnectPolicy.kt` — decision helpers; add
  `shouldTearDownOnAvailable` / keep tests honest
- `app/.../playback/StreamingPolicy.kt` — no teardown calls (already none)
- Tests: `ReconnectPolicyTest.kt`, `PlaybackContinuityStressTest.kt`

### Tests

- Confirmed handoff + banked → 0 pause/play blips (already true).
- Confirmed handoff + unbanked → **0 teardowns** after this phase (behaviour
  change: we used to rebuild unbanked tracks proactively). Document that we now
  wait for real session death or decoder stall.
- `onAvailable` while healthy → 0 reconnects (already true).
- `onAvailable` while session dead + **not** playing banked → reconnect still
  allowed (cold start / paused).
- Stress: random capability flaps never increment `nativeTeardowns` unless
  `sessionHealthy == false`.

### Completion

Unit tests green. Logcat on a wifi↔cell toggle while playing a **banked** track:
no `forceReconnectCheck`, no `build_active reason=session_rebuild` from Kotlin.
Session death (airplane 30s) still rebuilds via the monitor.

### Rollback

Restore the three Kotlin call sites. Policy object can stay.

### Product note

Unbanked tracks on a transport change may now play from the 5s read-ahead until
the AP socket actually dies, then Phase 2/4 apply. That is the point: we stop
**causing** the blip. Phase 3 makes “unbanked” rare.

---

## Phase 2 — Session-only rebuild (the actual fix)

**Why:** H1. This is the java/go contract for the “song already in cache” case,
without implementing rust `Session.reconnect()`.

### Keep vs full rebuild

`player_survives_session_death` is true when all hold:

1. `Active` exists, `offline == false` (or offline-with-pins: already playable).
2. Player state is Playing or Paused (not Loading / EndOfTrack).
3. Remaining audio from the **read cursor** is local:
   `player.is_current_fully_buffered()` **or** (Phase 2.1 stretch)
   `remaining_local_ms >= SURVIVE_THRESHOLD_MS` (start with “fully buffered
   only”; threshold is a later tightening).
4. No user transport command in flight (`command_epoch` stable for the rebuild).
5. Not already inside `rebuild_inflight`.

If false → today’s `orchestrate_rebuild` (drop Player, `load` at position).

### Session-only sequence

Must run under `rebuild_inflight` (same single-flight as today).

1. **Do not** `active.lock().take()`. **Do not** `player.stop()`. **Do not**
   abort `event_task`. `forward_events` stays on the existing player channel.
2. **Do not** notify `on_connection_lost` as a transport pause. Either skip the
   listener or add `on_session_reconnecting(keep_playing = true)` that Kotlin
   maps to *no* `isPlaying = false`. Play icon stays pause. MediaSession stays
   `STATE_READY` + `playWhenReady = true`.
3. Snapshot `Session` handle; `old_session.shutdown()`; bump `rebuild_generation`
   so the **old** `spawn_monitor` exits (`monitor_gen` mismatch) **before** it
   can `*active = None`.
4. `Session::new` + `connect(stored_creds)` + `clear_client_token()` — same as
   `build_active_impl`, including Keymaster identity. Fail → full rebuild
   fallback (stop player first, then existing path).
5. `player.set_session(new_session)`.
6. Replace `Active.session` only. Spawn `spawn_monitor` for the new session
   with the new generation.
7. **Do not** `player.load` / `load_discontinuous` the current URI. That would
   flush the sink and pause.
8. Notify `on_connection_restored` only if we previously advertised a drop;
   prefer silence if we never paused the UI.

`PlayerEvent::Stopped` recovery (`forward_events`) must not treat a session-only
rebuild as a stop. If `Stopped` still fires because we shut down the old
session, **suppress** the recovery thread while `player_survives` (same
`recovery_inflight` guard, plus a “session-only in progress” flag).

### `set_session` limits (honest)

`PlayerCommand::SetSession` assigns `self.session = session` on `PlayerInternal`.
The live `AudioFileFetch` still holds the **old** Session for
`spclient().stream_from_cdn` / `session.spawn`. That is fine when the remaining
file is already in the tempfile (`range_to_end_available`). It is **not** fine
for H3 (still downloading). Phase 2 therefore **requires** fully-buffered
(or Cached). Phase 3 makes that the common case; Phase 4/5 cover the rest.

### Files

- `rust/spotify-core/src/lib.rs` — `orchestrate_rebuild`, `build_active_impl`,
  `spawn_monitor`, `force_reconnect_check`, `forward_events` (`Stopped`),
  `Active` mutation
- `rust/spotify-core/src/playback_continuity.rs` — `should_keep_player_on_session_death`,
  `monitor_emits_pause_on_connection_lost` becomes false when keeping player;
  tests for 0 Pause/Play when banked
- UniFFI listener: optional `on_session_reconnecting`; Kotlin
  `PlaybackController.onConnectionLost` must not clear `isPlaying` on that path
- `PlaybackDebugMetrics` — `session_only_rebuild`, fallback, coalesced

### Tests (host)

Extend `playback_continuity.rs` and `PlaybackContinuityStressTest`:

- Banked + session invalid → 0 `ContinuityEvent::Paused`, Player identity
  preserved (generation / pointer), `set_session` called, no `load`.
- Unbanked + session invalid → still full rebuild, Pause then Play (honest).
- Session-only failure → one full rebuild, never two Players.
- User skip during session-only → `command_epoch` wins; `load_discontinuous`;
  `stale_load_suppressed` as today.
- 500 random banked deaths → 0 teardowns of Player.
- `Active::Drop` still stops the player on sign-out / engine teardown.

Rust unit tests cannot open an AudioTrack; they **can** lock the decision
functions and a fake “would_load / would_stop_player” harness. Keep the Kotlin
sim in `PlaybackContinuityStressTest` as the UI-blip model.

### Field tests (add to the stability matrix)

| ID | Scenario | Pass |
|----|----------|------|
| C1 | Play on Wi‑Fi until banked (`is_current_fully_buffered`), toggle airplane ~5s, restore | **no** play/pause icon flip; same track continues; `session_only_rebuild++`; `sink_epoch_rejected_writes == 0` |
| C2 | Same, but skip during the rebuild | Lands on skip target; one stream |
| C3 | Unbanked (play and immediately airplane) | May pause/rebuild (full path); no overlap |
| C4 | TIDAL control on same roam | Unchanged, still smooth |
| C5 | S1–S6 from the existing matrix | Still pass (overlap guards) |

### Completion

C1 passes on LP3. `full_rebuild` does not increment on C1. Overlap counters stay
~0. Existing S2/S3 (skip/play during reconnect) still pass.

### Rollback

Feature flag `session_only_rebuild` (rust `OnceLock` or engine setting) default
on after soak; off restores monitor-takes-Active. Do not leave a half-swapped
`Active.session`.

### Hard parts (budget here, not in Phase 1)

- Old monitor must not `*active = None` after generation bump vs shutdown race.
- `event_task` must survive; only the monitor is replaced.
- Sign-out / `signingOut` still drops everything.
- Offline pin Active: if AP dies but we can play the pin, prefer keeping Player
  (already local file) over session-only connect loops while `network_online == false`.

---

## Phase 3 — Make “enough buffer” the default

**Why:** Phase 2 only helps when the remaining file is local. Default
`read_ahead_during_playback` is **5 seconds**. A roam longer than that still
starves an unbanked track.

### Behaviour

- At engine init, `AudioFetchParams::set` (OnceLock, first write wins) with
  `read_ahead_during_playback` of **30–45s** (pick one; document). Keep
  `read_ahead_before_playback` modest (1–2s) so skip start stays snappy.
- Keep **bank current track first** (`buffer_current_to_end`) on every playing
  non-OFFLINE, non-battery-constrained tick — already the policy. Make
  `awaitBankIdle` / stall watchdog still prefer bank over look-ahead.
- Do not wait for GOOD_UNMETERED / 30s Wi‑Fi gate to bank. (Already true; add a
  regression test so nobody “optimizes” it back.)
- Prefetch-ahead stays tiered (1/2/3). Current-track bank is not a prefetch.

### Files

- `rust/librespot-audio-patched` — only if defaults must change in-crate;
  prefer `AudioFetchParams::set` from `spotify-core` `EngineShared::new`
- `StreamingPolicy.kt` / `NetworkTierLogicTest.kt` — assert bank is not gated
  on `WIFI_PREFER_AFTER_MS`
- `docs/playback-stability-field-tests.md` — P1 (hard-drop after bank) should
  become the common Wi‑Fi case, not a lucky one

### Tests

- `classifyLink` still needs 30s for GOOD_UNMETERED.
- `prefetchAhead` unchanged.
- Playing + FAIR/POOR/GOOD_METERED still calls `bufferCurrentToEnd`.
- Params getter returns the raised read-ahead after engine construct.

### Field

| ID | Scenario | Pass |
|----|----------|------|
| C6 | Play 10s on good Wi‑Fi, then airplane | remainder of **current** song finishes from cache (P1, now expected) |
| C7 | Play 2s then airplane (before bank) | may stall; must not skip the track (needs Phase 4) |

### Completion

C6 reliable on LP3. Battery ≤14% / power-save still skips opportunistic bank
(P4 / T-P4).

### Rollback

Stop calling `AudioFetchParams::set`; defaults return to 5s.

---

## Phase 4 — Decoder waits; it does not skip

**Why:** H3. Rust today: `Read` `WaitTimeout` (8s) → `io::ErrorKind::TimedOut`
→ Symphonia `Err` → `PlayerInternal` emits **EndOfTrack** and skips. Java waits
and retries (`MAX_CHUNK_TRIES = 128`) and emits halt/resume.

### Behaviour

On the **playing** track only:

- Treat `WaitTimeout` / short CDN reset as retry: request the range again, wait
  again, notify buffering (`on_buffering(true)`) without `on_paused`.
- Cap retries / total wait (e.g. 30–60s) then error the track (Unavailable /
  skip) — do not hang forever.
- `sink.write` errors still `handle_pause` (real output failure).
- Refresh expired CDN URLs before retry (java `CdnUrl.url()` renews 5 minutes
  early). Implement the existing TODO in `receive.rs`.

This is an **audio crate** patch. Keep 0.8.0 pin; document in
`librespot-audio-patched/PATCHES.md`.

Optional stretch: give `AudioFileFetch` an HTTP client / `Session` that can be
updated when `set_session` runs, so unbanked tracks can continue downloading on
the **new** Session. That is the bridge to Phase 5. If it stays blocked on the
dead Session, Phase 2+3 still cover banked songs; unbanked mid-roam may stall
until timeout then skip unless this retry loop holds the decoder until Phase 2
finishes… but the fetch task is on the **old** `session.spawn`. Retrying against
a shut-down Session will not help. So Phase 4 retry only works if:

- the old Session is **not** shut down until the file is complete (zombie
  Session — ugly, “already connected”), **or**
- fetch uses `EngineShared.runtime` + `HttpClient` independent of Session, with
  CDN URL refresh via the **new** spclient, **or**
- we do Phase 5 in-place reconnect and never shut the Session object down.

**Recommendation:** implement halt/retry for transient errors **while the Session
is still valid** (CDN 429, one dropped range). Do **not** pretend retry fixes
H1 unbanked until fetch is unbound from the dying Session. Unbind **or** Phase 5
is a separate slice; call it **Phase 4b**.

### Files

- `rust/librespot-audio-patched/src/fetch/mod.rs` (`Read`, `fetch_blocking`)
- `rust/librespot-audio-patched/src/fetch/receive.rs` (CDN refresh TODO)
- `rust/librespot-playback-patched/src/player.rs` (decode error → skip)
- `rust/librespot-playback-patched/src/decoder/symphonia_decoder.rs`
- `PATCHES.md` for both crates

### Tests

- TimedOut on a hole, then bytes appear → decoder continues, no EndOfTrack.
- Persistent hole past cap → Unavailable / skip once, not a tight loop.
- 429 path still drops the download permit before `Retry-After` (existing patch).

### Completion

C7 does not skip; may show buffering. No overlapping audio.

---

## Phase 5 — Optional: in-place AP reconnect (java/go, rust)

**Only if** Phase 2+3+4b still blip on unbanked audio, or Connect/spclient
quality while the Player lives is poor.

Implement the rust equivalent of java `Session.reconnect()` /
go `Accesspoint.reconnect`:

- On TCP error / keep-alive timeout: **do not** set `invalid` forever.
- New AP TCP, Shannon, `authenticate` with stored credentials.
- Restart `DispatchTask`; keep Mercury/spclient/Player pointing at the same
  `Session`.
- `login5.rs` stays unchanged (stored-credential path already uses session
  client ID).

This is a **core** patch, same class of risk as the identity work. Upstream
refused to finish it (`TODO` in `session.rs`). We would own it on 0.8.0.

**Do not** start Phase 5 to fix C1. C1 is Phase 2.

---

## Suggested order and what “done” means

| Phase | Fixes | Effort | Ships without later phases? |
|-------|--------|--------|------------------------------|
| 0 | Diagnosis | field day | n/a |
| 1 | H2, self-inflicted Kotlin blips | small | yes, safe alone |
| 2 | H1 when banked (the 90% case) | **medium-hard** | yes, if Phase 3 makes bank common |
| 3 | Bank/read-ahead so Phase 2 applies | small | yes |
| 4 | H3 skip-on-timeout (Session still up) | medium | yes |
| 4b | Unbanked download after Session swap | hard | only if C7 still fails |
| 5 | True in-place AP reconnect | hard | last resort |

Ship **1 → 3 → 2** if banking is already reliable on Wi‑Fi (3 before 2 makes C1
easier to hit). Ship **1 → 2 → 3** if Phase 0 shows H1 on already-banked tracks
(fix the monitor first). Do not ship 2 without the overlap tests.

**Product done:** C1 + C6 on LP3, S1–S6 still green, TIDAL unchanged, no new JNI,
librespot stays `=0.8.0`.

---

## Metrics and log lines

Keep existing `PlaybackDebugMetrics`. Add (names indicative):

- `session_only_rebuild`
- `session_only_rebuild_fallback`
- `connection_lost_while_buffered`
- `decoder_wait_timeout`

Logcat, session-only success:

```
session_only_rebuild: uri=... pos=... kept_player=1
```

Full rebuild must keep:

```
build_active: uri=... reason=session_rebuild
```

so the two paths are greppable. Update
[playback-stability-field-tests.md](../playback-stability-field-tests.md) when
Phase 2 lands (not before).

---

## Risks

| Risk | Mitigation |
|------|------------|
| Two Players / overlapping PCM | Never `take()` Active on the keep path; `rebuild_inflight`; sink epoch; no `load` |
| Old monitor races `*active = None` | Generation bump **before** shutdown; monitor checks generation then `ptr_eq` of session |
| `Stopped` recovery starts a second rebuild | Suppress while session-only in flight; coalesce on `recovery_inflight` |
| `set_session` does not rebind live CDN | Phase 2 gated on fully-buffered; 4b/5 for the rest |
| UI pause from Kotlin anyway | Do not call `on_connection_lost` on the keep path |
| Login5 / identity drift on the new Session | Same `SessionConfig`, stored creds, `clear_client_token`; no `login5.rs` edits |
| “Already connected” if two Sessions overlap | Shutdown old AP **before** connect, or accept a short dual window; do not leave both alive |
| Airplane / 30-day pin hygiene | Unchanged; session-only must not mark pins online |

---

## Docs / agent rules to update **when Phase 2 ships** (not before)

- `AGENTS.md` hard rule “Recover Spotify sessions by rebuilding `Active`” →
  rebuild **Session**; keep Player when remaining audio is local.
- `AGENTS.md` “When things break”: Wi‑Fi pause/play → this doc + C1 logs.
- `docs/README.md` “Session recovery” sentence.
- Jetispot reference: we copy java’s **contract**, we do not use java’s runtime.

Until then the hard rule stays: today’s code still drops `Active`.

---

## Decision log

- **Keep rust librespot 0.8.0.** Java/Go reconnect is the behaviour to copy, not
  the engine to vendor. Light’s JNI concern: Go is gomobile (still JNI); Java
  would drop the `.so` at the cost of rewriting the app. Out of scope here.
- **No `Session.reconnect()` in Phase 2.** New Session object + `set_session` +
  kept Player. In-place reconnect is Phase 5.
- **No ConnectivityManager teardown.** Policy and `networkOnline` only.
- **Fully-buffered gate for keeping the Player** until 4b/5. Do not claim
  unbanked mid-track survival in Phase 2.
- **One sink.** Session-only rebuild is invalid if it constructs a Player.
