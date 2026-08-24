# Download rate limiting

Research note for phono album/playlist pins. Mature unofficial Spotify and TIDAL
clients do **not** treat every HTTP 429 as the same surface. Web API
(`api.spotify.com`) documents a rolling 30s window and `Retry-After`. Audio CDN,
spclient, audio-key, and TIDAL `playbackinfo` are separate. Phono already
downloads **one track at a time**; the remaining gap is inter-track pacing plus
Retry-After-aware cooldown. **Hard-code a 2.5–5s jittered stagger, keep
parallelism at 1, honor `Retry-After` on the pin queue, and do not expose a
user-facing delay slider.** 400–1200ms is below every mature sequential
downloader surveyed.

## Comparison

| Project | Surfaces throttled | Concurrency | Inter-request delay | 429 handling | User-configurable? |
|---------|-------------------|----------------|---------------------|--------------|--------------------|
| **spotDL** | Web API metadata only (audio is YouTube Music, not Spotify CDN) | `threads` default **4** (YouTube fetches) | None on Spotify CDN (N/A) | spotipy/urllib3 retries on 429; honors `Retry-After` | `max_retries` (default 3), `threads` |
| **zotify** | Web API (`api.spotify.com`) + librespot CDN/audio-key | **Sequential** albums | `BULK_WAIT_TIME` default **1s**; optional realtime pacing | Web API: sleep **5s**, retry (`RETRY_ATTEMPTS` default 1). No `Retry-After` parse | Yes (CLI/config). `OVERRIDE_AUTO_WAIT` exists; unused in track download path |
| **Soggfy** | Official client playback (no extra CDN client) | Playback-bound | Must play start→finish | None in dumper (official client’s pacing) | Playback speed in official client; not an API throttle |
| **DownOnSpot** | librespot audio-key + CDN | Default **`concurrent_downloads: 4`** | **1s** after success; **7s** on some errors | No HTTP 429 parser. Maintainers: audio-key fails when requests are too dense | `concurrent_downloads` in `settings.json` |
| **librespot 0.8.0** | HTTP client (spclient + CDN): governor + 429 | CDN `download_slots` = **1** (phono patched to **2**) | Implicit via Quota 300/30s/domain | Sleep `Retry-After` / Fastly / Akamai; **ignore wait > 10s** | No (constants) |
| **psst** | Web API only (no pin/downloader) | Request-at-a-time | None proactive | Infinite loop: sleep `Retry-After` (default 2s) | No |
| **Jetispot** | Playback only | N/A | N/A | **No offline download** (ToS; never implemented) | N/A |
| **tidal-dl** (yaronzz) | `playbackinfopostpaywall` API | `multiThread` default **false**; else 5 workers | **0.5–5s random after every playbackinfo** | HTTP 429 → **20s** then retry (3 attempts) | `downloadDelay` bool; 0.5–5s range hard-coded |
| **tidal-dl-ng** | After each successful media download (API+CDN cycle) | `downloads_concurrent_max` default **3** | **3–5s** random when delay on | Not header-specific in download loop (delay is proactive) | Yes: delay on/off, min/max sec, concurrent max |
| **orpheusdl-tidal** | `playbackinfopostpaywall` | Sequential in core | **None** | Comments mention rate limit; **no 429/Retry-After handler** | No throttle knobs |
| **OnTheSpot** | Spotify (librespot) + TIDAL | `maximum_download_workers` default **1** | **`download_delay`: 3s** after each item | Retry worker optional (minutes, not 429) | Yes |
| **phono (this change)** | Spotify: audio-key + CDN. TIDAL: playbackinfo (not Media3 CDN bytes) | Spotify queue **1**. TIDAL Media3 **1**. CDN slots **2** | **2500–5000ms** jitter | Spotify: 20s cooldown on 429 (8 tries); non-429 still 2s/5s/10s. TIDAL API: Retry-After + 20s if resolve still 429s | No (hard-set) |

## Surfaces (do not conflate)

### 1. `api.spotify.com` (Web API)

Official docs: a 429 means the app exceeded the Web API rate limit, computed over
a **rolling 30 second window**. The 429 **normally includes `Retry-After` in
seconds**; wait that long before calling the Web API again. Some endpoints have
stricter custom limits. Development-mode apps also have a separate **quota**
mechanism; as of July 2026 a quota 429 body may include `"reason": "QUOTA_EXCEEDED"`.

- [Rate Limits](https://developer.spotify.com/documentation/web-api/concepts/rate-limits)
- [API calls — 429](https://developer.spotify.com/documentation/web-api/concepts/api-calls)
- [July 2026 changelog (quota `reason`)](https://developer.spotify.com/documentation/web-api/references/changes/july-2026)

This surface is **not** the Spotify pin/CDN path. Phono’s metadata client already
honors `Retry-After` in `SpotifyWebApi`. Pinning does not need Web API pacing
unless a pin job also hammers `/v1/*`.

### 2. spclient / mercury / audio-key

Audio keys travel on the **access-point TCP** session (`RequestKey` / `AesKey`),
not HTTP. librespot times out after **1500ms** and has **no Retry-After**.
Maintainers say mercury has no such header; callers must pace themselves.

- [librespot `audio_key.rs` (v0.8.0)](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/core/src/audio_key.rs)
- [librespot#1319](https://github.com/librespot-org/librespot/issues/1319) (HTTP Retry-After vs mercury)

spclient **HTTP** (CDN URL resolve, etc.) *does* go through `HttpClient` (governor
+ 429). That is still not `api.spotify.com`.

### 3. Audio CDN (`*.scdn.co`, Akamai, etc.)

librespot’s fetcher expects **206 Partial Content**. On **429** it reads
`Fastly-RateLimit-Reset` (`*.scdn.co`), `X-RateLimit-Next` (Akamai), or
`Retry-After`. Upstream **holds** the download slot while sleeping. Phono’s
audio patch **drops** the permit, sleeps, re-acquires.

### 4. TIDAL playbackinfo vs CDN

`GET …/playbackinfopostpaywall` (or equivalent) mints a short-lived stream
manifest. CDN bytes are a second hop. yaronzz delays **only after playbackinfo
URLs**, then waits **20s** on HTTP 429. tidal-dl-ng delays **after each finished
track** (covers both hops). Phono staggers collection **resolve** (playbackinfo)
400–1200ms and sets Media3 `maxParallelDownloads = 1`.

No public TIDAL developer page documenting a numeric playbackinfo quota was
retrieved (fetch of a `/guidelines-rate-limits` URL timed out / was not a usable
spec). Third-party clients treat 429 empirically.

---

## Per-project findings

### spotDL (`spotDL/spotify-downloader`)

Audio is **not** Spotify CDN. Default `audio_providers: ["youtube-music"]`.
Spotify traffic is metadata via spotipy (or SpotipyFree).

- `max_retries`: **3** (metadata)
- `threads`: **4** (download workers for the **audio provider**, not spclient)

Sources:

- [`spotdl/utils/config.py`](https://github.com/spotDL/spotify-downloader/blob/cd4a4203f5b12bd6dbbdf22d7674807858d35e05/spotdl/utils/config.py) (`SPOTIFY_OPTIONS.max_retries`, `DOWNLOADER_OPTIONS.threads`)
- [`spotdl/download/downloader.py`](https://github.com/spotDL/spotify-downloader/blob/cd4a4203f5b12bd6dbbdf22d7674807858d35e05/spotdl/download/downloader.py) (`asyncio.Semaphore(self.settings["threads"])`)
- Official client uses `status_forcelist=(429, 500, 502, 503, 504, 404)` when wrapping spotipy

spotipy (what official-API mode uses) builds urllib3 `Retry` with
`status_forcelist` including **429**, `retries=3`, `backoff_factor=0.3`. urllib3
`Retry` honors `Retry-After` by default (`respect_retry_after_header=True`).

- [`spotipy/client.py`](https://github.com/spotipy-dev/spotipy/blob/351d4223d0aa2f8417fb2d51c529f3564ecdc4dc/spotipy/client.py) (`default_retry_codes = (429, 500, 502, 503, 504)`)
- [urllib3 `Retry`](https://urllib3.readthedocs.io/en/stable/reference/urllib3.util.html#urllib3.util.Retry)

**Relevance to phono:** do not copy `threads=4`. That is YouTube parallelism, not
Spotify CDN. Copy **Retry-After on 429** for HTTP metadata only.

### zotify (`zotify-dev/zotify`)

Direct Spotify audio via librespot-style session. Albums are a **sequential**
`for` loop over `download_track`.

Config (defaults):

| Key | Default | Role |
|-----|---------|------|
| `DOWNLOAD_QUALITY` | `auto` | Vorbis bitrate tier |
| `DOWNLOAD_REAL_TIME` | `False` | Pace chunk reads to track duration |
| `BULK_WAIT_TIME` | `1` (seconds) | Sleep after each successful track |
| `RETRY_ATTEMPTS` | `1` | Web API retries |
| `OVERRIDE_AUTO_WAIT` | `False` | Documented as “disable wait”; **getter exists, not used in `track.py`** |

Realtime pacing (when enabled):

```python
if Zotify.CONFIG.get_download_real_time():
    delta_real = time.time() - time_start
    delta_want = (downloaded / total_size) * (duration_ms/1000)
    if delta_want > delta_real:
        time.sleep(delta_want - delta_real)
```

After a successful download: `time.sleep(Zotify.CONFIG.get_bulk_wait_time())` if
non-zero.

Web API errors: sleep **5 seconds**, retry; no `Retry-After` parse.

Sources (commit [`08d844fe`](https://github.com/zotify-dev/zotify/commit/08d844fe3e644ae6cd9bea34a3b4982e61129f33)):

- [`zotify/config.py`](https://github.com/zotify-dev/zotify/blob/08d844fe3e644ae6cd9bea34a3b4982e61129f33/zotify/config.py)
- [`zotify/track.py`](https://github.com/zotify-dev/zotify/blob/08d844fe3e644ae6cd9bea34a3b4982e61129f33/zotify/track.py)
- [`zotify/zotify.py`](https://github.com/zotify-dev/zotify/blob/08d844fe3e644ae6cd9bea34a3b4982e61129f33/zotify/zotify.py) (`invoke_url`)
- [`zotify/album.py`](https://github.com/zotify-dev/zotify/blob/08d844fe3e644ae6cd9bea34a3b4982e61129f33/zotify/album.py) (sequential `download_track`)
- [README config table](https://github.com/zotify-dev/zotify/blob/08d844fe3e644ae6cd9bea34a3b4982e61129f33/README.md)

**Relevance:** sequential + ≥1s between tracks is the *minimum* mature default.
Realtime is a ban-avoidance option, not required for HTTP 429. Phono’s 0.4–1.2s
is at or below zotify’s default **and** we still pipeline two CDN ranges.

### Soggfy (`Rafiuth/Soggfy`)

Windows **official-client** OGG interceptor. No librespot, no extra CDN client.

README: songs download only if played **start to finish without seeking**. That
caps request rate at **playback rate** (or the user’s playback-speed setting).

- [README](https://github.com/Rafiuth/Soggfy/blob/596bc4e3942a42f401e857c875ec456f9457b768/README.md)

**Relevance:** the only “throttle” is not outrunning the official streamer. Phono
pins dump the full file as fast as `AudioFile` can fill 256KiB chunks — the
opposite of this model.

### DownOnSpot (`grufkork/DownOnSpot`)

Rust librespot downloader. Default **4 concurrent** track jobs. After **success**,
sleep **1s**. Some error paths sleep **7s**. No 429 header handling in the
downloader loop.

Issue discussion (primary source = maintainer/commenter on the tracker): audio-key
errors when too many key requests fire; timeouts with long tracks / many concurrent
jobs / many prior successes. Dropping concurrency to 1 did **not** always fix
playlist failures — implying **inter-track spacing**, not only parallelism.

- [`src/downloader.rs`](https://github.com/grufkork/DownOnSpot/blob/549b9da9669f86a9f5f72e3f194eb207308bc4cc/src/downloader.rs) (`concurrent_downloads: 4`, `sleep(1s)` / `sleep(7s)`)
- [Issue #6](https://github.com/grufkork/DownOnSpot/issues/6)

**Relevance:** rust+librespot pins hit **audio-key** and **CDN**, not Web API.
Concurrency > 1 is a known footgun. Sequential is necessary but not sufficient
without delay.

No separate public project named `downloader-cli` with Spotify/TIDAL throttle
source was found.

### librespot (`librespot-org/librespot` v0.8.0)

**HTTP `HttpClient`** (used for spclient and CDN HTTP):

```text
RATE_LIMIT_INTERVAL            = 30s   // comment: documented by Spotify
RATE_LIMIT_CALLS_PER_INTERVAL  = 300   // comment: guesstimate
RATE_LIMIT_MAX_WAIT            = 10s   // waits longer than this are skipped
```

Keyed governor: strip host to `domain.tld`, `check_key` **before** send. On 429,
sleep parsed wait and retry. Parsers: `X-RateLimit-Next`, `Fastly-RateLimit-Reset`,
`Retry-After`. If wait **> 10s**, **do not sleep** (debug log, return none).

**Audio fetch:** `download_slots: Semaphore::new(1)`. On CDN 429, sleep Retry-After
**while holding the slot** (upstream comment: keep the number of open requests
from growing).

- [`core/src/http_client.rs`](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/core/src/http_client.rs)
- [`audio/src/fetch/mod.rs`](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/audio/src/fetch/mod.rs) (`Semaphore::new(1)`)
- [`audio/src/fetch/receive.rs`](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/audio/src/fetch/receive.rs)

Phono patches (`rust/librespot-audio-patched/PATCHES.md`): slots **2**; drop permit
before 429 sleep; retry non-206 up to 3 times.

**Relevance:** 300/30s is a **client-side ceiling**, not Spotify’s CDN quota.
`RATE_LIMIT_MAX_WAIT = 10s` means a CDN `Retry-After: 30` is **not** honored in
the HTTP helper — Kotlin’s 2/5/10s retry can then immediately re-hit the same
limit. Pin jobs that finish a track in a few seconds then wait only 0.4–1.2s
still look like a burst of range GETs + a new key/resolve.

### psst (`jpochyla/psst`)

Desktop client, **not** an album pin downloader. Web API client loops on 429:

```rust
StatusCode::TOO_MANY_REQUESTS => {
    let secs = /* Retry-After or "2" */;
    thread::sleep(Duration::from_secs(secs));
}
```

No proactive inter-request delay. Cache is for Web API responses, not audio dumps.

- [`psst-gui/src/webapi/client.rs`](https://github.com/jpochyla/psst/blob/3c3621aa79f820c737dd899e7e359b1359292466/psst-gui/src/webapi/client.rs)

**Relevance:** Retry-After-or-2s is the right **reactive** policy for HTTP APIs.
It does not replace proactive stagger on pin queues.

### Jetispot (`iTaysonLab/jetispot`)

Archived Android librespot-java client. README: offline caching / raw file
download **will NEVER be implemented**. No download throttle to copy.

- [README](https://github.com/iTaysonLab/jetispot/blob/6173bfa7872ae89664fdac9e42cf36538fe71166/README.md)

### tidal-dl (`yaronzz/Tidal-Media-Downloader`)

`__get__` after every `playbackinfopostpaywall` response (when `downloadDelay`
is not false): random sleep **500–5000ms**. On **HTTP 429**, print and wait
**20 seconds**, `continue` (up to 3 attempts). `multiThread` default **false**;
when true, `ThreadPoolExecutor(max_workers=5)` for **CDN** files.

- [`TIDALDL-PY/tidal_dl/tidal.py`](https://github.com/yaronzz/Tidal-Media-Downloader/blob/f4854f4b74b4ce6f08a3cedc9ba4e9afbcd32363/TIDALDL-PY/tidal_dl/tidal.py)
- [`TIDALDL-PY/tidal_dl/settings.py`](https://github.com/yaronzz/Tidal-Media-Downloader/blob/f4854f4b74b4ce6f08a3cedc9ba4e9afbcd32363/TIDALDL-PY/tidal_dl/settings.py) (`downloadDelay = True`, `multiThread = False`)
- [`TIDALDL-PY/tidal_dl/download.py`](https://github.com/yaronzz/Tidal-Media-Downloader/blob/f4854f4b74b4ce6f08a3cedc9ba4e9afbcd32363/TIDALDL-PY/tidal_dl/download.py)

**Relevance:** they throttle **playbackinfo**, not CDN byte rate. 0.5–5s is the
documented anti-429 delay. 20s is the 429 floor when the header is ignored.

### tidal-dl-ng

`exislow/tidal-dl-ng` returned **404** at research time. Findings from
[`qhejiafang/tidal-dl-ng`](https://github.com/qhejiafang/tidal-dl-ng) (same layout
and defaults as exislow issue config dumps).

Defaults in `model/cfg.py`:

- `download_delay = True`
- `download_delay_sec_min = 3.0`, `download_delay_sec_max = 5.0`
- `downloads_concurrent_max = 3`
- `downloads_simultaneous_per_track_max = 20` (chunks **within** one track)

Delay runs **after** a finished item, before the next, when delay is on and the
file was not skipped.

- [`tidal_dl_ng/model/cfg.py`](https://github.com/qhejiafang/tidal-dl-ng/blob/460312dbd7b4da6c0c1ef8410e23e8beac3525fd/tidal_dl_ng/model/cfg.py)
- [`tidal_dl_ng/download.py`](https://github.com/qhejiafang/tidal-dl-ng/blob/460312dbd7b4da6c0c1ef8410e23e8beac3525fd/tidal_dl_ng/download.py)

**Relevance:** 3–5s between items is the modern TIDAL default. Concurrent **3** is
for desktop bulk dumps; phono should stay at 1 on a phone radio.

### OrpheusDL + `orpheusdl-tidal`

Core has **no** global download delay. Tidal `_get` comments “Are we rate
limited?” when JSON parse fails; **no** 429 branch, **no** sleep.

- [`orpheus/core.py`](https://github.com/OrfiTeam/OrpheusDL/blob/a45ff47913508d4c09971bdb847d5845984f1e64/orpheus/core.py)
- [`tidal_api.py`](https://github.com/Dniel97/orpheusdl-tidal/blob/0d805ff5bf88441690a59c06c8c0dc1ae4fcbf3c/tidal_api.py)

**Relevance:** negative example. Do not ship playbackinfo with zero pacing.

### OnTheSpot (`ots-downloader/onthespot`)

Defaults: `download_delay: 3`, `maximum_download_workers: 1`. Sleeps
`download_delay` after each finished/failed item in the worker.

- [`otsconfig.py`](https://github.com/ots-downloader/onthespot/blob/7f7e04d883dc2d12499e6a0f9a80a09116cf69f7/src/onthespot/otsconfig.py)
- [`downloader.py`](https://github.com/ots-downloader/onthespot/blob/7f7e04d883dc2d12499e6a0f9a80a09116cf69f7/src/onthespot/downloader.py)

**Relevance:** closest “librespot + TIDAL” UI downloader. Sequential + **3s** is
their conservative default.

---

## Phono before this change

Spotify (`SpotifyDownloadCenter`): one queued track at a time; stagger
**400–1200ms** after an attempt; Kotlin retry **3×** at **2s / 5s / 10s** (not
Retry-After). Rust `downloads.rs` pulls CDN in **256KiB** chunks via librespot
`AudioFile`, then `audio_key()`. Audio patch: `Semaphore::new(2)`; 429 drops
permit, sleeps parsed wait, re-acquires. Core `HttpClient`: 300/30s/domain
governor; Retry-After family; **max wait 10s**.

TIDAL (`TidalDownloads`): playbackinfo stagger **400–1200ms**; Media3
`maxParallelDownloads = 1`; CDN retry 3× 2s/5s/10s. `TidalApiClient` already
honors `Retry-After` (clamp 1–30s, 4 attempts).

Users still saw Spotify 429 with tracks going “bangbangbang”. That matches
**download_time + 0.4–1.2s** per track (a 3–4 minute Ogg often finishes in a few
seconds on Wi‑Fi), not playback-rate spacing.

## What we shipped

Hard-set in [`DownloadPacing`](../app/src/main/java/com/lightphone/spotify/playback/download/DownloadPacing.kt)
(not a Settings row):

| Knob | Value |
|------|-------|
| Parallel tracks | 1 (unchanged) |
| Inter-track / playbackinfo jitter | **2500–5000ms** |
| Detected 429 cooldown | **20s**, up to 8 Spotify pin retries |
| CDN `download_slots` | left at 2 |
| Real-time byte pacing | no |

Spotify uses it after each pin attempt and on rate-limit failures. TIDAL uses
it after each playbackinfo resolve (not on Media3 CDN bytes). See
[offline-downloads.md](offline-downloads.md#pacing).

## Which surface is most likely causing phono’s Spotify 429s

**Most likely: audio CDN (`*.scdn.co` / Fastly) and/or spclient HTTP used to
resolve that CDN URL** — not `api.spotify.com`, not mercury audio-key *as HTTP
429*.

Reasons:

1. Pin path never uses the Web API for bytes. Web API 429 would show on search /
   library, and that client already waits on `Retry-After`.
2. Audio-key failures in librespot are `AesKey` / **timeout**, not status 429
   ([`audio_key.rs`](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/core/src/audio_key.rs),
   [librespot#1319](https://github.com/librespot-org/librespot/issues/1319)).
   Dense key traffic is still worth pacing (DownOnSpot), but it will not log as
   HTTP 429.
3. librespot’s 429 parsers are written for **Fastly `*.scdn.co`**, Akamai, and
   generic `Retry-After` on `*.spotify.com` — i.e. CDN/spclient HTTP.
4. 400–1200ms between **full-file** fetches is faster than zotify (1s),
   OnTheSpot (3s), and tidal-dl-ng (3–5s). Two CDN slots per file add overlapping
   range GETs. That is “bangbangbang” on the CDN, even with a serial Kotlin queue.
5. If `Retry-After` > 10s, librespot **does not wait**; Kotlin then retries at
   2/5/10s and can look like a tight loop.

Confirm in logcat: `429` + `Fastly-RateLimit-Reset` / `audio-fa` / `scdn.co` →
CDN. Host `spclient.wg.spotify.com` → resolve/spclient. `api.spotify.com` →
wrong diagnosis (metadata path). `audio key` / `timeout` → pace keys, don’t
blame CDN byte throttle.

## Recommendation

### Hard-set, not user-configurable

CLI downloaders expose delay because operators dump thousands of tracks and
debug 429s. Phono is a phone pin queue: one user, background, LightOS UI budget.
A delay slider invites unsafe `0` and support noise. Pick a conservative
constant; keep it in code next to the existing stagger.

Optional later: a **debug** override in `settings.json`, not a Settings row.

### Numbers (Spotify pin queue)

| Knob | Suggested | Why |
|------|-----------|-----|
| Max parallel **tracks** | **1** (keep) | Universal among careful clients; DownOnSpot default 4 is a cautionary tale |
| Inter-track delay | **2500–5000ms** uniform jitter | OnTheSpot 3s; tidal-dl-ng 3–5s; zotify 1s is the *floor* and still needs realtime as an extra |
| CDN range slots | **Leave at 2** for now | Within-track pipelining ≠ inter-track burst. Revisit only if logs show overlapping 429s *inside* one file |
| Kotlin 429/CDN failure | Honor `Retry-After` if present; else **20s** (tidal-dl); cap ~60s; pause **the whole queue** | 2/5/10s is shorter than typical CDN cooldowns and shorter than librespot’s 10s ignore threshold |
| After any 429 | Do not start the next track until cooldown ends | Reactive + proactive together |

Do **not** implement zotify `DOWNLOAD_REAL_TIME` for pins (album of 12 would take
the album’s duration). Do **not** rate-limit 256KiB CDN **payload** size unless
CDN 429s persist after 2.5–5s stagger.

### TIDAL (also shipped)

Keep **parallel = 1**. `TidalApiClient` Retry-After is already correct.
**Do not** put the 2.5–5s gap on Media3 CDN bytes. Raise **only** the
playbackinfo resolve stagger — that is what we did, as API hygiene even
without a TIDAL 429 report. A still-429 resolve then waits 20s before the
next track.

### What not to do

- Don’t treat Spotify Web API docs as CDN/audio-key law.
- Don’t add `threads=4` / `concurrent_downloads=4` on a phone pin queue.
- Don’t throttle CDN **bytes** because audio-key timed out (wrong surface).
- Don’t sleep 10s in librespot HTTP and then ignore a 30s `Retry-After` without
  the Kotlin queue also backing off.
- Don’t revert `download_slots` to 1 globally without measuring playback
  buffering (that semaphore is shared with streaming).
- Don’t ship a user-facing “download delay” control on LightOS.

## Open questions

- Log samples: exact `Host`, status, `Retry-After` / `Fastly-RateLimit-Reset`
  values on failing Spotify pins.
- Does `RATE_LIMIT_MAX_WAIT = 10s` drop real Fastly waits in the field?
- Are 429s correlated with **short** tracks (stagger-dominated) vs **long**
  tracks (CDN-dominated)?
- Should pin `AudioFile` use a dedicated slot count of 1 while playback keeps 2?
- TIDAL: any 429 on playbackinfo vs akamai/fastly CDN in our logs?
- Upstream librespot `dev` still holds the CDN slot during 429 sleep; phono
  drops it. Confirm we still want that for **pins** (dropping lets playback
  steal the slot during a pin cooldown — maybe correct on a phone).
