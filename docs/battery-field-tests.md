# Battery field tests — Light Phone III (1300 mAh)

Manual verification checklist after battery optimizations. Use Android Battery Historian or:

```bash
adb shell dumpsys batterystats --reset
# … exercise scenario …
adb shell dumpsys batterystats | grep -A5 "Estimated power use"
```

## Scenarios

| ID | Scenario | Pass criteria |
|----|----------|---------------|
| T-browse | Open app, browse library 10 min, never play | No playback FGS at cold start; no native engine until login/play |
| T-play-pause | Play 1 track, pause, screen off 30 min | 100 ms stall poll stopped; FGS demoted when paused |
| T-relaunch | Normal daily relaunch (cached library) | ~3 head-check API calls only; no parallel library fill storm |
| T-first-login-wifi | First login on Wi-Fi, large library | Library fill completes; acceptable one-time burst |
| T-first-login-cell | First login on cellular | Library fill deferred until Wi-Fi or library tab on unmetered |
| T-quality | Fresh install → Settings | Default "Normal (160 kbps)"; change to High persists after kill |
| T-picker-index | Open add-to-playlist picker | URI index sync runs (lazy), not at login |

## Notes

- Active playback will always consume battery (decode + network) — expected.
- AP session keepalive while playing is inherent to Spotify protocol.
