//! TTL-bounded playback queue checkpoint for process-death recovery.
//!
//! Restores queue + position **paused only** when the app reopens within
//! [CHECKPOINT_TTL]. Stale checkpoints are deleted on load.

use std::path::Path;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::queue::{QueueCheckpointWire, QueueState};

const CHECKPOINT_FILE: &str = "playback_checkpoint.json";
const CHECKPOINT_TTL: Duration = Duration::from_secs(20 * 60);
const CHECKPOINT_DEBOUNCE: Duration = Duration::from_secs(30);

#[derive(Serialize, Deserialize)]
struct PlaybackCheckpoint {
    saved_at_unix_ms: u64,
    queue: QueueCheckpointWire,
}

pub fn checkpoint_path(base_dir: &Path) -> std::path::PathBuf {
    base_dir.join(CHECKPOINT_FILE)
}

pub fn debounce_interval() -> Duration {
    CHECKPOINT_DEBOUNCE
}

/// Write queue snapshot to disk (best-effort).
pub fn save(base_dir: &Path, queue: &QueueState) {
    let Some(wire) = queue.to_checkpoint_wire() else {
        let _ = std::fs::remove_file(checkpoint_path(base_dir));
        return;
    };
    let saved_at_unix_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    let checkpoint = PlaybackCheckpoint {
        saved_at_unix_ms,
        queue: wire,
    };
    if let Ok(json) = serde_json::to_string(&checkpoint) {
        let _ = std::fs::write(checkpoint_path(base_dir), json);
    }
}

/// Load queue if checkpoint exists and is younger than [CHECKPOINT_TTL].
pub fn load_if_fresh(base_dir: &Path) -> Option<QueueState> {
    let path = checkpoint_path(base_dir);
    let raw = std::fs::read_to_string(&path).ok()?;
    let checkpoint: PlaybackCheckpoint = serde_json::from_str(&raw).ok()?;
    let now_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    if now_ms.saturating_sub(checkpoint.saved_at_unix_ms) > CHECKPOINT_TTL.as_millis() as u64 {
        let _ = std::fs::remove_file(&path);
        return None;
    }
    QueueState::from_checkpoint_wire(checkpoint.queue).ok()
}

pub fn delete(base_dir: &Path) {
    let _ = std::fs::remove_file(checkpoint_path(base_dir));
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot::core::SpotifyId;
    use librespot::core::SpotifyUri;

    fn track_uri(id: &str) -> SpotifyUri {
        SpotifyUri::Track {
            id: SpotifyId::from_base62(id).unwrap(),
        }
    }

    #[test]
    fn checkpoint_round_trip() {
        let dir = std::env::temp_dir().join(format!("phono_ckpt_{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let mut q = QueueState::default();
        q.set_queue(
            vec![track_uri("6rqhFgbbKwnb9MLmUQDhG6"), track_uri("3n3Ppam7vgaVa1iaRUc9Lp")],
            0,
            Some("Album".into()),
        );
        q.set_position_ms(12_000);
        save(&dir, &q);
        let restored = load_if_fresh(&dir).expect("fresh checkpoint");
        assert_eq!(restored.position_ms(), 12_000);
        assert_eq!(restored.queue_snapshot().context_label.as_deref(), Some("Album"));
        delete(&dir);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn expired_checkpoint_is_discarded() {
        let dir = std::env::temp_dir().join(format!("phono_ckpt_exp_{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let mut q = QueueState::default();
        q.set_queue(vec![track_uri("6rqhFgbbKwnb9MLmUQDhG6")], 0, None);
        let stale = PlaybackCheckpoint {
            saved_at_unix_ms: 0,
            queue: q.to_checkpoint_wire().unwrap(),
        };
        let json = serde_json::to_string(&stale).unwrap();
        std::fs::write(checkpoint_path(&dir), json).unwrap();
        assert!(load_if_fresh(&dir).is_none());
        assert!(!checkpoint_path(&dir).exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
