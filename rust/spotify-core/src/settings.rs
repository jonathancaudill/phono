//! Persisted playback/session preferences (settings.json under the cache dir).

use std::path::{Path, PathBuf};
use std::time::Duration;

use librespot::audio::AudioFetchParams;
use librespot::playback::config::{Bitrate, NormalisationType as LibrespotNormType, PlayerConfig};
use serde::{Deserialize, Serialize};

const SETTINGS_FILE: &str = "settings.json";

/// Streaming bitrate exposed to the UI (librespot supports 96 / 160 / 320 kbps only).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum StreamingQuality {
    /// 96 kbps
    Low,
    /// 160 kbps (librespot default)
    Normal,
    /// 320 kbps
    High,
}

impl Default for StreamingQuality {
    fn default() -> Self {
        Self::High
    }
}

impl StreamingQuality {
    fn to_bitrate(self) -> Bitrate {
        match self {
            Self::Low => Bitrate::Bitrate96,
            Self::Normal => Bitrate::Bitrate160,
            Self::High => Bitrate::Bitrate320,
        }
    }
}

/// Volume-normalization strategy (maps to librespot `NormalisationType`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum NormalizationType {
    Album,
    Track,
    Auto,
}

impl Default for NormalizationType {
    fn default() -> Self {
        Self::Auto
    }
}

impl NormalizationType {
    fn to_librespot(self) -> LibrespotNormType {
        match self {
            Self::Album => LibrespotNormType::Album,
            Self::Track => LibrespotNormType::Track,
            Self::Auto => LibrespotNormType::Auto,
        }
    }
}

/// Network read-ahead preset for librespot `AudioFetchParams` (applied once at engine init).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum, Default)]
pub enum NetworkBufferPreset {
    /// Near librespot defaults (1s / 5s read-ahead).
    Low,
    /// Mobile-friendly buffering (2s / 30s read-ahead).
    #[default]
    Normal,
    /// Aggressive buffering for poor networks (3s / 30s read-ahead).
    High,
}

impl NetworkBufferPreset {
    pub fn to_audio_fetch_params(self) -> AudioFetchParams {
        match self {
            Self::Low => {
                let minimum_download_size = 64 * 1024;
                let minimum_throughput = 8 * 1024;
                AudioFetchParams {
                    minimum_download_size,
                    minimum_throughput,
                    initial_ping_time_estimate: Duration::from_millis(500),
                    maximum_assumed_ping_time: Duration::from_millis(1500),
                    read_ahead_before_playback: Duration::from_secs(1),
                    read_ahead_during_playback: Duration::from_secs(5),
                    prefetch_threshold_factor: 4.0,
                    download_timeout: Duration::from_secs(
                        (minimum_download_size / minimum_throughput) as u64,
                    ),
                }
            }
            Self::Normal => {
                let minimum_download_size = 64 * 1024;
                let minimum_throughput = 6 * 1024;
                AudioFetchParams {
                    minimum_download_size,
                    minimum_throughput,
                    initial_ping_time_estimate: Duration::from_millis(800),
                    maximum_assumed_ping_time: Duration::from_millis(2000),
                    read_ahead_before_playback: Duration::from_secs(2),
                    read_ahead_during_playback: Duration::from_secs(30),
                    prefetch_threshold_factor: 5.0,
                    download_timeout: Duration::from_secs(15),
                }
            }
            Self::High => {
                let minimum_download_size = 64 * 1024;
                let minimum_throughput = 4 * 1024;
                AudioFetchParams {
                    minimum_download_size,
                    minimum_throughput,
                    initial_ping_time_estimate: Duration::from_millis(1000),
                    maximum_assumed_ping_time: Duration::from_millis(2500),
                    read_ahead_before_playback: Duration::from_secs(3),
                    read_ahead_during_playback: Duration::from_secs(30),
                    prefetch_threshold_factor: 6.0,
                    download_timeout: Duration::from_secs(20),
                }
            }
        }
    }
}

/// Apply librespot network buffering. Must run once per process before any playback.
pub fn apply_audio_fetch_params(preset: NetworkBufferPreset) {
    let params = preset.to_audio_fetch_params();
    if let Err(_already) = AudioFetchParams::set(params) {
        log::warn!("AudioFetchParams already set; ignoring preset {preset:?}");
    } else {
        log::info!("AudioFetchParams applied: {preset:?}");
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppSettings {
    #[serde(default)]
    pub streaming_quality: StreamingQuality,
    #[serde(default = "default_true")]
    pub gapless_enabled: bool,
    #[serde(default)]
    pub normalization_enabled: bool,
    #[serde(default)]
    pub normalization_type: NormalizationType,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub proxy: Option<String>,
    #[serde(default)]
    pub network_buffer_preset: NetworkBufferPreset,
}

fn default_true() -> bool {
    true
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            streaming_quality: StreamingQuality::default(),
            gapless_enabled: true,
            normalization_enabled: false,
            normalization_type: NormalizationType::default(),
            proxy: None,
            network_buffer_preset: NetworkBufferPreset::default(),
        }
    }
}

impl AppSettings {
    pub fn load(path: &Path) -> Self {
        match std::fs::read_to_string(path) {
            Ok(raw) => serde_json::from_str(&raw).unwrap_or_default(),
            Err(_) => Self::default(),
        }
    }

    pub fn save(&self, path: &Path) {
        if let Ok(json) = serde_json::to_string_pretty(self) {
            let _ = std::fs::write(path, json);
        }
    }

    /// Build a `PlayerConfig` from persisted preferences.
    pub fn player_config(&self) -> PlayerConfig {
        let mut cfg = PlayerConfig::default();
        cfg.bitrate = self.streaming_quality.to_bitrate();
        cfg.gapless = self.gapless_enabled;
        cfg.normalisation = self.normalization_enabled;
        cfg.normalisation_type = self.normalization_type.to_librespot();
        cfg.position_update_interval = Some(std::time::Duration::from_secs(1));
        cfg
    }
}

pub struct SettingsStore {
    path: PathBuf,
    inner: std::sync::Mutex<AppSettings>,
}

impl SettingsStore {
    pub fn new(base_dir: &Path) -> Self {
        let path = base_dir.join(SETTINGS_FILE);
        let inner = AppSettings::load(&path);
        Self {
            path,
            inner: std::sync::Mutex::new(inner),
        }
    }

    pub fn get(&self) -> AppSettings {
        self.inner.lock().unwrap().clone()
    }

    pub fn update<F: FnOnce(&mut AppSettings)>(&self, f: F) {
        let mut guard = self.inner.lock().unwrap();
        f(&mut guard);
        guard.save(&self.path);
    }
}
