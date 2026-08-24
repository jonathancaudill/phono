//! Playback continuity across network / session events.
//!
//! `force_reconnect_check` used to tear down a healthy Active on every
//! confirmed transport change. That is an audible Pause→Play: the player is
//! dropped and a new one is `load`ed. Optimistic `buffer_current_to_end`
//! cannot save a track we just destroyed.
//!
//! If the current track is fully present in the audio cache, keep the live
//! player. The monitor still rebuilds if the AP session actually dies.

use std::time::{Duration, Instant};

pub(crate) const FORCE_RECONNECT_COOLDOWN: Duration = Duration::from_secs(5);

/// Tear down Active on a force-reconnect request?
///
/// Playing + fully banked → keep the player (no pause/play blip).
/// Anything else matches the historical "proactive rebuild" behaviour.
pub(crate) fn should_teardown_on_force_reconnect(playing: bool, fully_buffered: bool) -> bool {
    !(playing && fully_buffered)
}

pub(crate) fn force_reconnect_cooldown_elapsed(last: Option<Instant>, now: Instant) -> bool {
    match last {
        None => true,
        Some(prev) => now.duration_since(prev) >= FORCE_RECONNECT_COOLDOWN,
    }
}

/// Sequence of player-facing events produced by tearing down Active and
/// auto-resuming. This is the user-visible "actual pause then play".
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ContinuityEvent {
    Paused,
    Playing,
    // Buffering is not a pause/play blip; omitted from teardown events.
}

pub(crate) fn events_for_force_reconnect_teardown(was_playing: bool) -> &'static [ContinuityEvent] {
    if was_playing {
        &[ContinuityEvent::Paused, ContinuityEvent::Playing]
    } else {
        &[]
    }
}

/// Monitor path: `on_connection_lost` always clears isPlaying, even when a
/// rebuild will auto-resume. That is a UI pause/play independent of cache.
pub(crate) fn monitor_emits_pause_on_connection_lost(was_playing: bool) -> bool {
    was_playing
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn banked_playing_track_survives_force_reconnect() {
        assert!(!should_teardown_on_force_reconnect(
            /* playing */ true,
            /* fully_buffered */ true,
        ));
    }

    #[test]
    fn unbanked_playing_track_still_rebuilds() {
        assert!(should_teardown_on_force_reconnect(true, false));
    }

    #[test]
    fn paused_session_may_rebuild() {
        // No audible blip when already paused.
        assert!(should_teardown_on_force_reconnect(false, true));
        assert!(should_teardown_on_force_reconnect(false, false));
    }

    #[test]
    fn teardown_of_playing_track_is_pause_then_play() {
        assert_eq!(
            events_for_force_reconnect_teardown(true),
            &[ContinuityEvent::Paused, ContinuityEvent::Playing]
        );
        assert!(events_for_force_reconnect_teardown(false).is_empty());
    }

    #[test]
    fn monitor_pauses_ui_when_session_dies_while_playing() {
        assert!(monitor_emits_pause_on_connection_lost(true));
        assert!(!monitor_emits_pause_on_connection_lost(false));
    }

    #[test]
    fn cooldown_rejects_second_teardown_within_five_seconds() {
        let t0 = Instant::now();
        assert!(force_reconnect_cooldown_elapsed(None, t0));
        assert!(!force_reconnect_cooldown_elapsed(
            Some(t0),
            t0 + Duration::from_secs(4)
        ));
        assert!(force_reconnect_cooldown_elapsed(
            Some(t0),
            t0 + Duration::from_secs(5)
        ));
    }

    /// Historical bug: every confirmed handoff tore down even when banked.
    /// That policy produces one pause/play blip per handoff.
    #[test]
    fn legacy_always_teardown_blips_even_when_banked() {
        fn legacy_should_teardown(playing: bool, _fully_buffered: bool) -> bool {
            playing
        }
        assert!(legacy_should_teardown(true, true));
        assert_eq!(
            events_for_force_reconnect_teardown(true).len(),
            2,
            "legacy teardown is the audible blip"
        );
        assert!(
            !should_teardown_on_force_reconnect(true, true),
            "cache-aware policy must not blip a banked track"
        );
    }

    #[test]
    fn stress_banked_handoffs_never_teardown() {
        for i in 0..500 {
            let playing = i % 7 != 0;
            let banked = true;
            if playing && banked {
                assert!(
                    !should_teardown_on_force_reconnect(playing, banked),
                    "handoff {i} tore down a banked playing track"
                );
            }
        }
    }

    #[test]
    fn stress_unbanked_playing_always_tears_down() {
        for _ in 0..100 {
            assert!(should_teardown_on_force_reconnect(true, false));
        }
    }
}
