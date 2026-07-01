# Patch Spec: Opportunistic Buffering — Current Track + Up to 3 Tracks Ahead (Fix 4A + Fix 5)

Goal: eliminate mid-track skips on fluctuating cell signal by (1) forcing the CURRENTLY PLAYING
track to download fully when the network is good, and (2) opportunistically pre-downloading up to
3 UPCOMING tracks into the shared audio cache so a signal dropout across track boundaries never
stalls. The app decides how aggressive to be based on Android network quality.

Two independent mechanisms:
- PART 1-2 (Fix 4A): patch `librespot-playback` to fully buffer the CURRENT track on demand.
- PART 1.5 (Fix 5): NO playback patch needed — prefetch upcoming tracks into the cache directly
  from the Rust core using the public `AudioItem::get_file` + `AudioFile::open` (which auto-saves
  to the same cache the player reads). This is the multi-track-ahead piece.

PART 1-2 requires patching `librespot-playback` (NOT currently patched — only `librespot-core`
is). PART 1.5 requires NO new patch. Read "Build Setup" before Part 1.

## Why this works (background)

librespot keeps the active track in STREAM mode (`set_stream_mode()` in `load_track`,
player.rs:1197), which only fetches `read_ahead_during_playback` (~12s) ahead of the playhead.
The NEXT track gets fully pulled by preload. So track transitions are protected but the active
track only has ~12s of runway — a longer signal dropout drains it and you skip. The
`StreamLoaderController` already exposes `fetch(range)` and the file already supports
`set_random_access_mode()` (which downloads greedily). We expose a Player command that, for the
current track, switches to random-access and fetches the whole remaining range. The app calls it
when Android reports good signal/unmetered network.

Bonus: the next-track preload is gated on `range_to_end_available()` of the current track
(player.rs:1574). Fully buffering the current track makes that gate flip sooner, so the next
track also starts preloading earlier. One lever, two wins.

---

## PART 1 — Patch `librespot-playback`

### Step 1.1 — Add a new PlayerCommand variant

In `playback/src/player.rs`, find `enum PlayerCommand` and add a variant (place it near
`Preload`):

```rust
    /// Force the current track to download to its end (random-access mode) rather
    /// than the default ~12s streaming read-ahead. Used for opportunistic buffering
    /// on good networks to survive signal dropouts.
    BufferCurrentToEnd,
```

### Step 1.2 — Add the public Player method

Near `pub fn preload(...)` (player.rs ~552), add:

```rust
    /// Request that the currently playing/paused track be fully buffered to its end.
    /// No-op if nothing is loaded. Safe to call repeatedly (idempotent at the fetch layer).
    pub fn buffer_current_to_end(&self) {
        self.command(PlayerCommand::BufferCurrentToEnd);
    }
```

### Step 1.3 — Handle the command

In `fn handle_command` (player.rs ~2268), add an arm alongside the others:

```rust
            PlayerCommand::BufferCurrentToEnd => self.handle_buffer_current_to_end(),
```

(Match the existing arms' style. It returns `()` like `Play`/`Pause`, not `PlayerResult`, unless
you choose to propagate errors — keep it infallible to avoid touching the `?` flow.)

### Step 1.4 — Implement the handler

Mirror the EXISTING `preload_data_before_playback` (player.rs ~2419), which already reaches the
current track's `stream_loader_controller` from `&mut self`. Add this method near it:

```rust
    fn handle_buffer_current_to_end(&mut self) {
        use crate::audio::range::Range; // confirm the actual Range import path (see note below)

        // Reach the current track's stream loader in either Playing or Paused state.
        let controller = match self.state {
            PlayerState::Playing { ref mut stream_loader_controller, .. }
            | PlayerState::Paused { ref mut stream_loader_controller, .. } => {
                Some(stream_loader_controller)
            }
            _ => None,
        };

        if let Some(slc) = controller {
            if slc.range_to_end_available() {
                return; // already fully buffered; nothing to do
            }
            // Switch off the just-ahead streaming strategy so the loader downloads greedily.
            slc.set_random_access_mode();
            // Fetch the entire remaining file. fetch() is non-blocking (queues the range).
            let len = slc.len();
            if len > 0 {
                slc.fetch(Range::new(0, len));
            }
        }
    }
```

IMPORTANT NOTES for the implementer:
- `Range` lives in the audio crate's fetch module. Find the correct import by checking how
  `preload_data_before_playback` constructs its fetch range (it computes a `request_data_length`
  and calls a fetch with a range). Use the SAME `Range` type and constructor it uses. Do NOT
  guess the path — copy it from that function.
- `set_random_access_mode()`, `len()`, `range_to_end_available()`, and `fetch(Range)` are all
  public on `StreamLoaderController` (audio/src/fetch/mod.rs). Confirmed present in v0.8.0.
- After switching to random-access mode, consider whether to switch BACK to stream mode is
  necessary. It is not strictly required (random-access just means "fetch eagerly"), and the
  next `load_track` sets stream mode again for the next track. Leave it in random-access for the
  current track once requested.
- `fetch()` is non-blocking — it signals the loader thread. Do NOT use `fetch_blocking` here; we
  don't want to stall the player command loop.

### Step 1.5 — (Optional) progress query for smarter app logic

If you want the app to know whether the current track is already fully cached (to avoid
redundant calls or to decide whether to start caching the NEXT track), add:

```rust
    // Player public method
    pub fn current_track_fully_buffered(&self) -> bool { /* command + reply channel, OR
        track via a shared AtomicBool the handler sets. Simplest: skip this for v1. */ }
```
For v1, SKIP this — calling `buffer_current_to_end()` repeatedly is already idempotent (the
handler early-returns when `range_to_end_available()`).

---

## PART 1.5 — Prefetch up to 3 upcoming tracks into the cache (NO playback patch)

This is the multi-track-ahead mechanism. librespot's built-in `preload` only has ONE slot, so we
do NOT use it for tracks +2 and +3. Instead we replicate, in the `spotify-core` Rust layer, the
exact path the player uses to download a track — but we point it at upcoming queue URIs and let
the result land in the shared on-disk cache. When the player later reaches those tracks, they're
already local (`AudioFile::open` returns the cached file).

### Why this works (verified against v0.8.0 source)
- `AudioItem::get_file(session, uri)` (metadata/src/audio/item.rs:71) resolves a track URI to an
  `AudioItem` with a `files: AudioFiles` map (format -> FileId) and `alternatives`.
- `AudioFile::open(session, file_id, bytes_per_second)` (audio/src/fetch/mod.rs:380) checks the
  cache first and, on a miss, downloads — and on completion AUTO-SAVES to
  `session.cache()` (the same cache the player uses). So merely opening + fetching a file warms
  the cache for free.
- The player selects the file by bitrate: it iterates preferred formats for `config.bitrate` and
  takes the first present in `audio_item.files` (player.rs:84-101), then computes
  `bytes_per_second` via a fixed format->kbps table (player.rs `stream_data_rate`).

We replicate that selection so prefetched files match what the player will actually request
(same FileId), guaranteeing a cache hit rather than downloading a different format.

### Step 1.5a — Add a prefetch helper in `spotify-core` (lib.rs)

```rust
use librespot::metadata::audio::{AudioFileFormat, AudioFiles, AudioItem};
use librespot::audio::{AudioFile, range::Range}; // confirm exact Range path as in Part 1
use librespot::core::SpotifyUri;

/// Preferred formats for a given bitrate, mirroring the player's selection order.
/// Keep this in sync with librespot's PlayerConfig bitrate handling.
fn preferred_formats(bitrate: librespot::playback::config::Bitrate) -> &'static [AudioFileFormat] {
    use librespot::playback::config::Bitrate;
    use AudioFileFormat::*;
    match bitrate {
        Bitrate::Bitrate96  => &[OGG_VORBIS_96,  OGG_VORBIS_160, OGG_VORBIS_320],
        Bitrate::Bitrate160 => &[OGG_VORBIS_160, OGG_VORBIS_96,  OGG_VORBIS_320],
        Bitrate::Bitrate320 => &[OGG_VORBIS_320, OGG_VORBIS_160, OGG_VORBIS_96],
    }
}

/// Same fixed table librespot uses (player.rs stream_data_rate). Bytes/sec.
fn bytes_per_second(format: AudioFileFormat) -> usize {
    use AudioFileFormat::*;
    let kbps: f32 = match format {
        OGG_VORBIS_96 => 12., OGG_VORBIS_160 => 20., OGG_VORBIS_320 => 40.,
        MP3_256 => 32., MP3_320 => 40., MP3_160 => 20., MP3_96 => 12., MP3_160_ENC => 20.,
        AAC_24 => 3., AAC_48 => 6., AAC_160 => 20., AAC_320 => 40., MP4_128 => 16.,
        OTHER5 => 40., FLAC_FLAC => 112., XHE_AAC_12 => 1.5, XHE_AAC_16 => 2.,
        XHE_AAC_24 => 3., FLAC_FLAC_24BIT => 3.,
    };
    (kbps * 1024.).ceil() as usize
}

/// Resolve a track URI to (file_id, bytes_per_second) honoring the configured bitrate,
/// following alternatives if the track itself has no files.
async fn resolve_playable_file(
    session: &Session,
    uri: SpotifyUri,
    bitrate: librespot::playback::config::Bitrate,
) -> Option<(librespot::core::FileId, usize)> {
    let mut item = AudioItem::get_file(session, uri).await.ok()?;
    if item.files.is_empty() {
        // Follow an alternative (region/relink), like the player's find_available_alternative.
        let alts = item.alternatives.clone()?;
        for alt in alts {
            if let Ok(alt_item) = AudioItem::get_file(session, alt).await {
                if !alt_item.files.is_empty() { item = alt_item; break; }
            }
        }
    }
    for &fmt in preferred_formats(bitrate) {
        if let Some(&file_id) = item.files.get(&fmt) {
            return Some((file_id, bytes_per_second(fmt)));
        }
    }
    None
}

/// Warm the cache for one upcoming track. Cheap if already cached. Non-blocking fetch.
async fn prefetch_track(session: &Session, uri: SpotifyUri, bitrate: librespot::playback::config::Bitrate) {
    let Some((file_id, bps)) = resolve_playable_file(session, uri, bitrate).await else { return };
    // Already cached? AudioFile::open returns the cached file without downloading.
    match AudioFile::open(session, file_id, bps).await {
        Ok(file) => {
            if let Ok(slc) = file.get_stream_loader_controller() {
                if slc.range_to_end_available() { return; } // fully cached already
                slc.set_random_access_mode();
                let len = slc.len();
                if len > 0 { slc.fetch(Range::new(0, len)); } // non-blocking; auto-saves on complete
            }
        }
        Err(e) => log::debug!("prefetch open failed for {file_id}: {e}"),
    }
}
```

### Step 1.5b — Engine method: prefetch the next N queue tracks

```rust
/// Opportunistically prefetch up to `ahead` upcoming tracks (default 3) into the cache.
/// Called by the app only on good networks. Safe/idempotent; cached tracks are skipped.
pub fn prefetch_upcoming(&self, ahead: u32) {
    let ahead = ahead.min(3); // cap at 3 to bound bandwidth/disk
    let Some(active) = self.shared.active.lock().unwrap().as_ref().cloned_handles() else { return };
    // `cloned_handles`: grab session + a snapshot of the next `ahead` URIs from the queue,
    // WITHOUT holding the active lock across .await. Implement to return (Session, Vec<SpotifyUri>).
    let (session, upcoming) = active;
    let bitrate = self.shared.settings.get().player_config().bitrate;
    self.shared.runtime.spawn(async move {
        for uri in upcoming.into_iter().take(ahead as usize) {
            prefetch_track(&session, uri, bitrate).await;
        }
    });
}
```

IMPLEMENTER NOTES:
- Do NOT hold the `active`/queue mutex across `.await`. Snapshot the next `ahead` URIs and the
  `Session` (which is cheap to clone) FIRST, release the lock, THEN spawn the async prefetch.
- Pull the upcoming URIs from your existing `QueueState` (the same structure `snapshot_resume`
  reads). Take the next `ahead` items after the current index.
- These prefetches do NOT touch the player's single `preload` slot, so they don't interfere with
  gapless preload of the immediate next track. librespot's own preload still handles track +1 for
  gapless; this adds +2/+3 (and reinforces +1) purely at the cache layer.
- Order matters: prefetch nearest-first (+1, then +2, then +3) so the most-soon-needed track is
  cached first if the network dies partway.

### Step 1.5c — Expose via UniFFI

Add `prefetch_upcoming(ahead: u32)` to the UniFFI surface (same as Part 2 for
`buffer_current_to_end`).

---

## PART 2 — Expose it through `spotify-core` (Rust UniFFI layer)

### Step 2.1 — Add an engine method

In `lib.rs`, where other player passthroughs live (near `pause`, `set_volume`), add a method on
the engine that forwards to the active player:

```rust
    /// Opportunistically buffer the current track to its end (good-network hint from the app).
    pub fn buffer_current_to_end(&self) {
        if let Some(active) = self.shared.active.lock().unwrap().as_ref() {
            active.player.buffer_current_to_end();
        }
    }
```

(Match how existing methods reach `active.player` — e.g. how `pause()` does it. If `pause` goes
through a helper, mirror that.)

### Step 2.2 — Expose via UniFFI

Add BOTH `buffer_current_to_end` AND `prefetch_upcoming(ahead)` (from Part 1.5) to the UniFFI
interface the same way `pause`/`set_volume` are exposed. Confirm both show up in the generated
Kotlin bindings after a build.

---

## PART 3 — Drive it from Android based on signal quality

### Step 3.1 — Observe network quality

In the Kotlin playback layer (`PlaybackController.kt`), register a
`ConnectivityManager.NetworkCallback` and read `NetworkCapabilities` to classify the connection:

```kotlin
val cm = context.getSystemService(ConnectivityManager::class.java)
cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        // Downstream bandwidth estimate in kbps (0 if unknown)
        val downKbps = caps.linkDownstreamBandwidthKbps
        val goodForAggressiveBuffer =
            validated && (unmetered || downKbps >= GOOD_DOWN_KBPS)  // pick a threshold, e.g. 3000
        currentNetworkGood = goodForAggressiveBuffer
        maybeBufferCurrent()
    }
})
```

### Step 3.2 — Trigger opportunistic buffering (current + ahead)

On good networks, do BOTH: fully buffer the current track, then prefetch up to 3 ahead. Trigger on
(1) a new track starting, (2) the network transitioning to good, while (3) playback is active:

```kotlin
private fun maybeBufferOpportunistically() {
    if (!isPlaying) return
    when (networkTier) {
        NetworkTier.GOOD_UNMETERED -> {
            runCatching { engine.bufferCurrentToEnd() }
            runCatching { engine.prefetchUpcoming(3u) }   // up to 3 ahead on WiFi
        }
        NetworkTier.GOOD_METERED -> {
            runCatching { engine.bufferCurrentToEnd() }    // protect the current track
            runCatching { engine.prefetchUpcoming(1u) }    // only +1 ahead on good cellular
        }
        NetworkTier.POOR -> {
            // best-effort: still try to finish the current track; skip multi-ahead
            runCatching { engine.bufferCurrentToEnd() }
        }
    }
}
```

Sequencing rationale: current track FIRST (it's what stalls now), then nearest upcoming. The
prefetch helper already orders +1,+2,+3 and skips already-cached tracks, so calling on both
track-change and network-change is safe and idempotent.

Tune the `ahead` count by tier: 3 on unmetered WiFi, 1 on good cellular (bound data), 0 extra on
poor. Make the cellular aggressiveness a user setting if desired (see 3.3).

### Step 3.3 — (Optional) don't fight metered data caps

On metered/cellular, you may NOT want to download entire tracks (data usage). Decision matrix:
- Unmetered (WiFi): always buffer current to end. Clear win, no cost.
- Metered (cellular) + good signal: this is the exact case the bug is about — buffering ahead
  prevents skips, but uses more data up front (data you'd spend anyway if you finish the track).
  Recommend: buffer to end on metered too IF signal is good, because the alternative is skips.
  Make it a setting if you want to respect data-conscious users ("Aggressive buffering on
  cellular: on/off").
- Metered + poor signal: buffering to end will be slow and may not finish before the dropout,
  but partial progress still helps. Still call it — `fetch()` makes progress opportunistically.

DISK + DATA BUDGET for multi-track prefetch (new with Part 1.5):
- 3 tracks ahead at 320kbps ≈ 3 × ~10-12 MB ≈ ~30-36 MB of cache fill ahead of playback. The
  librespot `Cache` is size-bounded; confirm its max size is large enough that prefetched tracks
  aren't evicted before they play (a too-small cache makes prefetch pointless — it evicts +3 to
  fetch +3). If needed, raise the audio cache cap.
- On metered networks, default to `ahead = 1` (or 0 with a setting) so you don't burn cellular
  data pre-downloading tracks the user may skip. Unmetered WiFi is where `ahead = 3` belongs.
- Skipped tracks waste prefetch: if the user skips a lot, +2/+3 prefetch is wasted bandwidth.
  Acceptable on WiFi; another reason to keep cellular conservative.

---

## Build Setup (required before Part 1)

Adding a patched `librespot-playback`:
1. Copy the v0.8.0 `playback` crate source into `rust/librespot-playback-patched/` (match the
   exact version; the repo pins `=0.8.0`).
2. In `rust/spotify-core/Cargo.toml`, under `[patch.crates-io]`, add:
   ```toml
   librespot-playback = { path = "../librespot-playback-patched" }
   ```
   alongside the existing `librespot-core` patch.
3. The patched playback crate will itself depend on `librespot-core`; make sure it resolves to
   YOUR patched core (the workspace patch should handle this — verify with `cargo tree` that
   there's only ONE librespot-core).
4. Keep the patched playback crate pinned to 0.8.0. Document in its README that the ONLY change
   from upstream is the `BufferCurrentToEnd` command + handler (so future-you can re-apply on a
   version bump).

NOTE: you do NOT need to patch `librespot-audio` for EITHER fix — `StreamLoaderController`'s
`fetch`/`set_random_access_mode`/`len`/`range_to_end_available`, plus `AudioFile::open` and
`AudioItem::get_file`, are already public in the upstream audio/metadata crates. Only `playback`
needs patching, and ONLY for the current-track command (Part 1). The multi-track prefetch
(Part 1.5) uses entirely public APIs from `spotify-core` — no patch.

---

## Verification

### Compile / wiring
- `cargo tree | grep librespot-core` shows exactly ONE core (no duplicate from the new playback
  patch).
- `buffer_current_to_end` appears in the generated Kotlin bindings.

### Functional (the actual skip fix)
- On WiFi: start a track, watch logs/StreamLoader. Confirm the current track downloads to its END
  shortly after starting (not just 12s ahead). `range_to_end_available()` becomes true well
  before the playhead reaches the end.
- Simulate a mid-track dropout (airplane mode for ~20s mid-song) AFTER the track has fully
  buffered. Playback should NOT skip/stall, because the whole track is local.
- Compare against current behavior (dropout mid-track before this fix = stall/skip).

### Next-track cascade
- Confirm that fully buffering the current track makes `TimeToPreloadNextTrack` fire earlier
  (next track preload starts sooner), since its gate is `range_to_end_available()` on current.

### Multi-track prefetch (Part 1.5)
- On WiFi, start a track and call `prefetchUpcoming(3)`. Confirm via logs that the next 3 queue
  URIs resolve and download, and that their files land in the audio cache (`AudioFile::open` later
  returns `Cached`).
- Confirm format match: the prefetched FileId equals what the player requests when it reaches the
  track (same bitrate/format) — i.e. reaching track +2 is a cache HIT, not a re-download. If it
  re-downloads, `preferred_formats`/bitrate selection is out of sync with the player.
- Airplane-mode test across a track boundary: with +1/+2 prefetched, skipping to the next track
  and losing signal should still play from cache.
- Cache-size test: confirm prefetched tracks are not evicted before playback (raise audio cache
  cap if they are).
- Idempotency: calling `prefetchUpcoming` repeatedly does not re-download cached tracks
  (`range_to_end_available()` / `Cached` short-circuit).
- Lock safety: confirm `prefetch_upcoming` does NOT hold the queue/active mutex across `.await`
  (snapshot URIs + session first, then spawn).

### Metered behavior
- On cellular, confirm buffering still triggers when signal is good (this is the target case),
  and respects the optional "aggressive buffering on cellular" setting if you add one.

### Idempotency / no harm
- Calling `buffer_current_to_end()` repeatedly (track-change + network-change both firing) does
  not cause errors or redundant downloads (handler early-returns on `range_to_end_available()`).

---

## Rules — Do Not Violate
- Use `fetch()` (non-blocking), never `fetch_blocking()`, in the command handler — blocking would
  stall the player command loop.
- Reuse the EXACT `Range` type/constructor that `preload_data_before_playback` uses; do not invent
  an import path.
- Do not remove `set_stream_mode()` from `load_track`; the NEXT track should still start in stream
  mode. We only override the CURRENT track on demand.
- Keep the patched playback crate pinned to `=0.8.0` and document the single change.
- The app must gate aggressive buffering on network quality; do not unconditionally download full
  tracks on every network (defeats the point and burns metered data needlessly).
- Do not add the optional progress-query for v1 unless needed; idempotent repeat-calls cover it.
- Multi-track prefetch (Part 1.5) must NOT hold the queue/active mutex across `.await` — snapshot
  the next URIs + Session, release the lock, then spawn.
- Cap `prefetch_upcoming` at 3 ahead. Do not prefetch the whole queue.
- Keep `preferred_formats`/`bytes_per_second` in sync with librespot's player selection so
  prefetched files are cache HITS, not wasted alternate-format downloads.
- Gate `ahead` by network tier: 3 on unmetered, ≤1 on metered, 0 extra on poor. Never prefetch
  multiple tracks ahead on metered by default.
- Verify the audio `Cache` max size is large enough to hold current + 3 ahead without evicting
  not-yet-played prefetches.
