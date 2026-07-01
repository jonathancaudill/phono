# Bug Diagnosis: Current Build

16 reported bugs collapse into about 7 root causes. Grouped by cause, with the fix for each.
Line numbers are approximate; locate by symbol.

---

## ROOT CAUSE A — Reconnect tears down and rebuilds the whole player/session

This single cause explains a LARGE cluster:
- "will just… start playing out of nowhere a few minutes after playback has been stopped"
- "queue disappears when session reconnects to new network"
- "new network causes total session dis/reconnect"
- "playback interrupted and unresumable by timer"
- "unable to reconnect from very stale connection"

### What's happening

`spawn_monitor` (lib.rs ~1163) polls `session.is_invalid()` every 5s. When the session goes
invalid (network change, stale connection), it does:
```
let resume = shared.snapshot_resume();   // capture queue + position
*shared.active.lock() = None;            // DROP the whole Active (session+player+queue)
... build_active(creds, resume) ...      // build a BRAND NEW session + player + queue
```
And critically, `build_active` ends with:
```rust
if let Some(uri) = q.current_uri() {
    let pos = queue.lock().position_ms();
    player.load(uri, true, pos);         // <-- load(..., start_playing = TRUE, ...)
}
```

So every reconnect REBUILDS the player and **auto-starts playback** (`true` = start playing),
restoring from the resume snapshot. This is the "starts playing out of nowhere" bug: a network
blip minutes after you stopped triggers a reconnect, which rebuilds the player and presses play.

It also explains:
- "new network causes total session dis/reconnect" — yes, by design: `is_invalid()` trips on
  network change, and the monitor nukes `active` and rebuilds from scratch instead of letting
  librespot's connection recover.
- "queue disappears when session reconnects" — the queue is rebuilt from `snapshot_resume()`; if
  the snapshot is linear-only (`restore_linear`) it loses queued-but-not-in-context tracks, and
  any UI observing the old queue sees it reset.
- "playback interrupted and unresumable by timer" — a sleep/timer pause followed by a reconnect
  rebuilds the player; if resume state was cleared on pause, there's nothing to resume, OR the
  rebuild loads but the timer-pause state is lost.
- "unable to reconnect from very stale connection" — the monitor only acts on
  `session.is_invalid()`. A *stale* connection (TCP half-open, no clean invalidation) may never
  flip `is_invalid()` to true, so the monitor never fires and you're stuck until a hard restart.

### Fixes

1. **Do NOT auto-play on reconnect rebuild.** In `build_active`, the post-rebuild
   `player.load(uri, true, pos)` should preserve the PRE-DISCONNECT play/pause state, not force
   `true`. Capture `was_playing` in `snapshot_resume()` and pass it:
   ```rust
   player.load(uri, was_playing, pos);   // only resume playing if we were playing
   ```
   This alone kills "starts playing out of nowhere."

2. **Distinguish "reconnect transport" from "rebuild everything."** A network change should try
   to let librespot re-establish the connection on the EXISTING session before nuking `active`.
   Full teardown+rebuild should be the last resort, not the first response. If librespot 0.8.0's
   session can't transparently reconnect, at minimum gate the rebuild so it does not discard the
   queue: persist the full queue (not just linear) across rebuilds.

3. **Detect stale connections proactively.** `is_invalid()` alone misses half-open sockets. Add a
   keepalive/health check: if no successful network activity in N seconds AND playback is
   expected, treat as stale and trigger the reconnect path. This fixes "unable to reconnect from
   very stale connection."

4. **Preserve timer-pause state across reconnect.** Ensure the sleep-timer pause sets a paused
   state that survives `snapshot_resume()`, so a reconnect during a timer pause stays paused and
   is resumable, rather than being interrupted-and-lost.

---

## ROOT CAUSE B — Lookahead/prefetch is single-track and not signal-adaptive

Reported:
- "skipping issues … increase lookahead … cache multiple songs in advance … related to
  fluctuating cell signal … intelligent opportunistic lookahead needed"

### What's happening

Two layers control buffering:
- `AudioFetchParams` (settings.rs `to_audio_fetch_params`) sets `read_ahead_during_playback`
  (5s/12s/15s for Low/Normal/High). This is the IN-TRACK read-ahead buffer.
- `refresh_next_preload` (lib.rs ~963) preloads exactly ONE next track (`player.preload(uri)`).

So when cell signal fluctuates, the in-track buffer (even 15s on High) drains during a dropout
and you get a skip/stall, and only one track ahead is prefetched. There is no multi-track
caching and no adaptation to signal quality.

Also note: `AudioFetchParams::set` is a **process-global one-shot** (`apply_audio_fetch_params`
warns "already set; ignoring" on second call). So the buffer preset only takes effect at app
launch and cannot be raised at runtime when signal degrades.

### Fixes

1. **Raise the default read-ahead substantially for mobile.** 12s (Normal) is thin for cellular.
   Consider 30s+ for the during-playback read-ahead on the mobile-default preset. The buffer is
   the first line of defense against signal dropouts.

2. **Prefetch more than one track ahead.** `refresh_next_preload` preloads one URI. librespot's
   `preload` is single-target, but you can keep a small rolling prefetch of the next 2-3 queue
   items by preloading the next, and on track-advance immediately preloading the new next — and
   optionally fetching further tracks' audio keys/initial chunks. Caching the next 2-3 songs'
   opening chunks means a dropout between tracks doesn't stall.

3. **Make buffering signal-adaptive ("opportunistic").** Because `AudioFetchParams` is a global
   one-shot, true runtime adaptation needs either (a) a librespot patch to make read-ahead
   adjustable at runtime, or (b) driving prefetch aggressiveness from the app: when on good
   signal/WiFi, aggressively preload further ahead into the audio cache; when signal is poor,
   you've already banked buffer. Use Android connectivity APIs (NetworkCapabilities:
   downstream bandwidth, metered/unmetered, signal) to decide how many tracks to pre-cache.
   This is the "intelligent opportunistic lookahead" the report asks for — it lives in the app
   layer driving the Rust prefetch, since the Rust buffer itself can't be re-tuned mid-session.

4. **Persist the audio cache across the reconnect rebuild.** Confirm the `Cache` (lib.rs ~162) is
   not cleared on reconnect; rebuilt players should reuse the on-disk audio cache so prefetched
   chunks survive a network blip.

---

## ROOT CAUSE C — Overlays don't block touches to the layer beneath

Reported:
- "overlays receive taps thru to background"
- "track bar not properly scrubbable (likely due to tap passthru)"

### What's happening

The overlay/scrim hosts (`ContextMenuHost`, `MonoContextOverlays`) render the overlay content but
the scrim/background does not consume pointer events. In Compose, a `Box` drawn on top does NOT
intercept touches unless it has a pointer modifier (`.pointerInput`/`.clickable`). So taps pass
through to whatever is behind, and a background list/button beneath the overlay still reacts.

The track-bar scrubber issue is likely the same family but inverted: `ProgressBar`
(PlayingScreen ~200) uses `detectTapGestures` only — it handles a TAP-to-seek but NOT a
drag-to-scrub. So "not properly scrubbable" = there is no drag handler, only tap. If an overlay
or parent is also intercepting, that compounds it.

### Fixes

1. **Make every overlay scrim consume input.** The full-screen scrim behind any overlay/menu must
   have a `.pointerInput { detectTapGestures { onDismiss() } }` (or at minimum an empty consumer)
   so taps are absorbed (and ideally dismiss the overlay) instead of passing through. Apply to
   `ContextMenuHost`, the playlist picker, copied-overlay, and any modal.

2. **Add a drag handler to the track scrubber.** Replace the tap-only `detectTapGestures` in
   `ProgressBar` with a combined tap + horizontal-drag handler so the user can scrub:
   ```kotlin
   .pointerInput(duration) {
       detectHorizontalDragGestures(
           onHorizontalDrag = { change, _ ->
               val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
               onSeek((duration * fraction).toLong())
           }
       )
   }
   .pointerInput(duration) {   // keep tap-to-seek too
       detectTapGestures { offset ->
           val fraction = (offset.x / size.width).coerceIn(0f, 1f)
           onSeek((duration * fraction).toLong())
       }
   }
   ```
   (Consider debouncing `onSeek` during drag and only committing the seek on drag-end to avoid
   spamming seek calls.)

---

## ROOT CAUSE D — Detail screens show stale state before the new data loads

Reported:
- "tapping on album flashes last album viewed for a second, then loads in current album"
- "album added status takes a sec to flash in"
- "album art flashes in on now playing screen"

### What's happening

`AlbumDetailScreen` reads a SHARED `vm.albumDetail` StateFlow and triggers
`LaunchedEffect(albumId) { vm.loadAlbumDetail(albumId) }`. On navigating to a new album, the
StateFlow STILL HOLDS THE PREVIOUS ALBUM until `loadAlbumDetail` completes. So you render the old
album's data for a frame (the "flash of last album"), then it updates.

Same mechanism for "added status flashes in" (`state.isSaved` still reflects the old album until
the new fetch resolves) and "album art flashes" (old art shown, then replaced).

### Fixes

1. **Clear/scope the detail state on album change.** When `loadAlbumDetail(albumId)` starts, FIRST
   reset `albumDetail` to a loading/empty state for the new id, so the screen shows a placeholder
   instead of the previous album. Guard renders on id match:
   ```kotlin
   // in loadAlbumDetail: _albumDetail.value = AlbumDetailUiState(loadingId = albumId)
   // in screen: only render album if state.album?.id == albumId
   val album = state.album?.takeIf { it.id == albumId }
   ```
2. **Seed from cache when available.** If the album/its saved-status is already in the local cache
   (see Root Cause E), seed the detail state synchronously on navigation so there's no flash and
   no "added status takes a sec." Cache-first render, network-refresh after.

---

## ROOT CAUSE E — Albums and playlists are not cached locally

Reported:
- "albums don't cache"
- "playlists don't cache"
- (contributes to D's flashes and to the playlist-picker bug F)

### What's happening

Liked tracks have the full Room sync layer (`LibrarySync`, DAOs, `LibraryRepository`). Saved
albums appear to have an entity (`SavedAlbumEntity`) but album/playlist DETAIL and playlist
LISTS are fetched from the Web API each time without the same local-first caching, so they
refetch on every visit (slow, flashy, and they vanish offline).

### Fixes

1. **Extend the Room cache to albums and playlists** the same way liked tracks work: entities +
   DAOs + a `LibraryResource`-style sync, observed as a Flow, network-refreshed in background.
   `SavedAlbumEntity` and `PlaylistEntity` exist — wire them into the same sync/observe pattern
   `LibrarySync` uses for liked tracks (head-check delta, paginated fill, transactional writes).
2. **Cache album/playlist detail + track lists** so opening a previously-viewed album is instant
   from cache, then refreshed. This directly removes the flashes in Root Cause D.

---

## ROOT CAUSE F — Playlist picker doesn't fetch membership

Reported:
- "add to playlists screen doesn't fetch current status of song (added/not added to any given
  playlist)"

### What's happening

`PlaylistPickerScreen` lists playlists but does not query, for the track being added, which
playlists ALREADY contain it. So every playlist shows as "not added" regardless of truth.

### Fix

When opening the picker for a track, fetch (or compute from cached playlist track-lists) the set
of playlists that already contain that track URI, and render each row's added/not-added state
from that set. If playlist track-lists are cached (Root Cause E), this is a local lookup; if not,
it requires per-playlist membership checks (expensive — another reason to cache playlists).

---

## ROOT CAUSE G — Swipe-to-queue has no slop / direction lock

Reported:
- "slide-to-queue triggered by ANY movement"

### What's happening

`MonoSwipeRow` uses `detectHorizontalDragGestures` with `onDragStart = { isDragging = true }` and
accumulates `dragOffset` from the very first horizontal delta. There is no touch-slop threshold
and no direction lock, so any small horizontal movement (including the start of a vertical scroll
that has a tiny horizontal component) begins revealing the queue action. The action fires if
`dragOffset >= threshold` (28% of action width), which a stray drag can reach.

### Fixes

1. **Require touch slop before engaging.** Don't set `isDragging`/accumulate until horizontal
   movement exceeds `viewConfiguration.touchSlop`. `detectHorizontalDragGestures` already waits
   for slop internally, but the issue is likely that vertical scrolls leak horizontal deltas.
2. **Direction-lock against the vertical scroller.** Use `awaitPointerEventScope` with a manual
   drag that compares |dx| vs |dy| on the first significant move and only claims the gesture if
   horizontal dominates (e.g. `abs(dx) > abs(dy) * 1.5`). Otherwise let the `LazyColumn` keep the
   gesture for vertical scrolling. This stops vertical scrolls from triggering the swipe.
3. **Raise the commit threshold** from 28% to ~50% of the action width, and/or require the drag to
   END past threshold (already does) AND have exceeded a minimum absolute distance, so a tiny
   twitch can't commit.

---

## ROOT CAUSE H — UI nits (low risk, quick)

- **"reconnecting" banner eats screen space, shrinks icons** — the banner is taking layout space
  (push, not overlay). Render it as an overlay/snackbar that does NOT participate in the layout
  flow, or give it a fixed small height that doesn't reflow the nav icons. Likely the
  `on_connection_lost`/`on_connection_restored` (Root Cause A) drives this; once reconnect is less
  aggressive (A), the banner also appears far less often.
- **"playlist logo and queue logo the same"** — two nav/section icons use the same vector. Pick a
  distinct icon for one (queue already uses `Icons.AutoMirrored.Filled.QueueMusic` in
  MonoSwipeRow; ensure the playlist tab uses a different one).

---

## Suggested fix order (by impact and dependency)

1. **Root Cause A fix #1 (don't auto-play on reconnect)** — one-line-ish, kills the most alarming
   bug ("starts playing out of nowhere"). Do this first.
2. **Root Cause C (overlay scrim consumes taps + track-bar drag)** — small, high user-visible
   impact, fixes two bugs.
3. **Root Cause G (swipe slop/direction lock)** — small, fixes the annoying accidental queue.
4. **Root Cause E (album/playlist caching)** — larger, but unlocks D (flashes), F (picker
   membership), and the offline behavior. Highest structural payoff.
5. **Root Cause D (clear detail state on id change)** — partly fixed by E; the id-guard is quick.
6. **Root Cause A fixes #2–4 (reconnect transport vs rebuild, stale detection, queue persistence)**
   — larger networking work; the meatiest item.
7. **Root Cause B (multi-track + adaptive prefetch)** — larger; buffer bump is quick, adaptive is
   the long pole.
8. **Root Cause H (banner layout, icon)** — polish.
