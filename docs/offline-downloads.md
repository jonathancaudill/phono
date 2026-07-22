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

## Future

App-level encryption and subscription checks for pins are intentionally deferred.
