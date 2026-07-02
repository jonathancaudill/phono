# Phase B — Audio sink baseline metrics

Capture these **before** comparing Phase C builds on emulator and Light Phone III.
Use the same track (3–4 min, steady playback) and note device / BT headset model.

## Logcat tags

```bash
adb logcat -s PhonoAudioTrack SpotifyCore
```

## Kotlin counters (via log or debug settings)

| Metric | Source | Baseline (fill in) |
|--------|--------|-------------------|
| `pendingFrames` / pending ms | `PhonoAudioTrack` pcm stats | |
| `recreates` | pcm stats | |
| `routingIgnored` | pcm stats | |
| `bpWaits` / `bpWaitMs` | v1 only; Phase C uses ring | |
| `deadObjectCount` | `getDeadObjectCount()` | |
| `writeErrorCount` | `getWriteErrorCount()` | |

## UniFFI `PlaybackDebugMetrics` (Phase C+)

| Field | Meaning |
|-------|---------|
| `ring_occupancy_ms` | Software ring buffer latency |
| `pending_output_ms` | AudioTrack HAL buffer latency |
| `producer_block_ms` | Time producer slept at HIGH_WATER |
| `drain_partial_writes` | NON_BLOCKING partial writes |

## BT handoff checklist (manual)

| ID | Scenario | Pass criteria | Baseline notes |
|----|----------|---------------|----------------|
| T1 | Speaker → BT during playback | Audible on BT <2s | |
| T2 | BT → speaker | Resumes without re-login | |
| T6 | Route flapping 5×/30s | ≤3 effective recreates | |

## System dump (optional)

```bash
adb shell dumpsys media.audio_flinger
adb shell dumpsys audio
```

Record during: idle playback, BT connect mid-track, BT disconnect.

## Phase C success (R4)

Compare against baseline:

- `producer_block_ms` stable under BT handoff (T9: player thread not blocked on write)
- `recreates` not storming on emulator routing spam
- Audible position (`getOutputDelayMs`) within ~1s of scrubber during steady play

## Full test matrix (C12 — Light Phone III sign-off)

### Routing / BT

| ID | Scenario | Pass |
|----|----------|------|
| T1 | Speaker → BT connect during playback | Audible on BT <2s; position ≠ 0 |
| T2 | BT disconnect → speaker | Resumes without re-login |
| T3 | BT connect during pause | Clean resume on play |
| T4 | Seek on BT | No stale pre-seek audio |
| T5 | Gapless album across BT connect | No flush between tracks |
| T6 | Route flapping 5×/30s | ≤3 effective recreates; no ANR |
| T7 | 30min BT session | No drift >1s/min |
| T8 | Wired headset ↔ speaker | Same as T1/T2 |
| T9 | BT connect during active drain write | No player thread block >50ms p99 |
| T10 | Overnight BT idle → reconnect | Recoverable |

### Focus / lifecycle

| ID | Scenario | Pass |
|----|----------|------|
| F1 | Incoming call | Pause <200ms; no write after pause |
| F2 | AUDIO_BECOMING_NOISY | Pause; no crash |
| F3 | Focus regain | Resume only if was playing |
| S1 | Stop mid-track | Clean release; no JNI after release |
| S2 | Engine teardown | Ordered shutdown; no native crash |
| S4 | Gapless album | Inter-track gap <500ms |

### Phase C specific

| ID | Scenario | Pass |
|----|----------|------|
| R1 | Ring at HIGH_WATER | Producer blocks; no OOM |
| R2 | Recreate during drain | No deadlock <500ms |
| R3 | Flush during seek | Ring empty before new PCM |
| R4 | Compare Phase B baseline | `player_thread_block_ms` ↓; BT T9 passes |
