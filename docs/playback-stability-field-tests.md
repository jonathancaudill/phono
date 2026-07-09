# Playback stability — bad-network field tests

Validates the reconnect/rebuild hardening that eliminates overlapping ("two songs
at once" / "two sections of the same song") audio under flaky cellular, plus the
network-aware precaching. Run on the Light Phone III (arm64) with a real, flaky
connection (subway, elevator, edge-of-coverage) — the emulator cannot reproduce
the racey session drops that triggered the bug.

## Logcat tags

```bash
adb logcat -s SpotifyCore Playback PhonoAudioDrain PhonoAudioTrack
```

Key lines:
- `build_active: uri=... reason=session_rebuild` — a rebuild installed + resumed.
- `build_active: skipping auto-resume, superseded by user command epoch` — the
  command-epoch guard suppressed a stale restore (expected when you tap during a
  reconnect).

## UniFFI `PlaybackDebugMetrics` (new stability counters)

| Field | Meaning | Healthy |
|-------|---------|---------|
| `rebuild_coalesced` | Rebuild requests that merged onto an in-flight single-flight rebuild | May climb under bad cell — this is races being *avoided* |
| `sink_epoch_rejected_writes` | PCM writes blocked because a newer sink owns the AudioTrack | At/near **0**; any nonzero proves the ownership guard caught an overlap that would otherwise be audible |
| `stale_load_suppressed` | Auto-resume loads dropped because a newer user command arrived | Rises when tapping during reconnect (expected) |
| `prefetch_cancelled` | Prefetch loops abandoned after the queue moved on | Rises with rapid skipping (expected) |

## Correctness matrix (must pass)

| ID | Scenario | Pass criteria |
|----|----------|---------------|
| S1 | Airplane-mode ON for ~10s mid-track, then OFF | Resumes the same track; **never** two overlapping streams |
| S2 | Rapid skip (5× in 2s) while reconnecting | Lands on the final target only; no garble; one clean load |
| S3 | Tap play on a new playlist during a reconnect | New selection wins; old auto-resume suppressed (`stale_load_suppressed` +1) |
| S4 | Background the app during a reconnect, return | No duplicate audio; single player alive |
| S5 | Subway/elevator repeated dropouts over 5 min | Zero overlapping-audio events; `sink_epoch_rejected_writes` stays ~0 |
| S6 | Pause during reconnect, wait, resume | Restores paused; resume plays exactly one stream |

## Precaching matrix

| ID | Scenario | Pass criteria |
|----|----------|---------------|
| P1 | Play on Wi‑Fi, then hard-drop network mid-track | Current track finishes from banked cache (no stall) |
| P2 | Skip forward on a weak connection | Next track starts fast (predictive prefetch banked it) |
| P3 | Connection upgrades POOR → GOOD | Session warms proactively; next skip has no cold-rebuild delay |
| P4 | Battery ≤14% or power-save on | Opportunistic banking backs off (no aggressive prefetch) |

## Failure triage

- **Overlapping audio still heard** → check `sink_epoch_rejected_writes` (should
  have caught it) and confirm only one `build_active` line per reconnect; look for
  two `PhonoAudioDrain` threads alive simultaneously.
- **Skips slow during reconnect** → confirm the transport coalesce window and that
  `warmSpclientSessionAsync` fired on the last tier upgrade.
- **Stalls on disconnect mid-track** → current-track banking (`bufferCurrentToEnd`)
  did not complete before the drop; check `StreamingPolicy` battery/tier gating.
