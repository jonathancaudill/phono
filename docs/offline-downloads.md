# Offline downloads

Phono pins albums and playlists for offline playback on both Spotify and TIDAL.
The UI (Downloads tab, album/playlist header controls, hold-menu Download/Remove)
is backend-agnostic and talks only to [`OfflineDownloadCenter`](../app/src/main/java/com/lightphone/spotify/playback/download/OfflineDownloadCenter.kt).

## Architecture

| Concern | TIDAL | Spotify |
|---------|-------|---------|
| Engine | Media3 `DownloadManager` / `TidalDownloadService` | UniFFI `download_track` + `SpotifyDownloadService` |
| Bytes on disk | `filesDir/tidal-downloads` (SimpleCache, NoOp) | `filesDir/spotify-downloads/{id}_{quality}.ogg` |
| Index | Room `downloaded_tracks` / `downloaded_collections` (URI-keyed) | Same tables |
| Quality setting | Independent `TidalAudioQuality` (`download_quality` prefs) | Independent `StreamingQuality` in `settings.json` |
| Offline play | `TidalPlayableItems.offlineMediaItem` | Player prefers pin dir before CDN |

```text
UI / AppViewModel
    → OfflineDownloadCenter
        → TidalOfflineDownloadCenter (Media3)
        → SpotifyDownloadCenter (Rust decrypt-to-Ogg)
    → Room downloaded_* (shared)
```

## Quality

- **Streaming quality** and **download quality** are independent.
- Changing download quality never rewrites completed pins (future-only).
- Spotify: Low / Normal / High → Ogg Vorbis 96 / 160 / 320 (no FLAC).
- TIDAL: Extra low → Max (clear BTS/DASH only; Widevine skipped).

## Clear Cache

Settings → Clear Cache wipes **streaming** LRUs only (`tidal-stream`, `spotify-cache/audio`). Offline pins are kept.

## TOS guard (30-day offline wipe)

If Phono has not seen a usable network for 30+ days, [`OfflinePinHygiene`](../app/src/main/java/com/lightphone/spotify/playback/download/OfflinePinHygiene.kt) wipes Room pin rows and the `tidal-downloads` / `tidal-mpd` / `spotify-downloads` directories. Streaming cache and credentials are left alone. `markOnline` runs on network available; `enforce` runs once when the controller is created.

## Cold start

`MainActivity` calls `offlineDownloads.resumeDownloads()` for whichever backend is active.

## Airplane mode

True offline (no AP / no CDN) is supported for **completed pins**:

- **Spotify:** If AP `session.connect` fails or the device is offline, the engine builds an
  **offline Active** (`Session::new` without connect) so [`load_pinned_track`](../rust/librespot-playback-patched/src/player.rs)
  can play clear Ogg pins. Kotlin calls `setNetworkOnline` from the connectivity callback so
  reconnect spam is suppressed offline and a live session is rebuilt when network returns.
- **TIDAL:** `playUris` resolves the start window from completed Media3 downloads first and
  skips `ensureSessionMeta` when every track in that window is pinned.
- **Library UI:** Cached Liked/Albums/Playlists stay visible; non-downloaded rows are grayed out;
  sync error banners are suppressed (navbar already shows “Device offline”). Downloads tab stays
  fully interactive, including swipe-to-queue on completed tracks.

Unpinned tracks remain unavailable offline (“Not available offline.”).

## Future

App-level encryption and subscription checks for pins are intentionally deferred.
