//! Stable FFI surface for the Light Phone III Spotify client.
//!
//! This crate wraps librespot (pinned to =0.8.0) behind a thin, stable UniFFI
//! API so that librespot's frequent breaking changes only ever touch this file
//! and its sibling modules - never the Kotlin side.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use librespot::core::authentication::Credentials;
use librespot::core::cache::Cache;
use librespot::core::config::SessionConfig;
use librespot::core::spotify_id::SpotifyId;
use librespot::core::{Session, SpotifyUri};
use librespot::playback::config::AudioFormat;
use librespot::playback::mixer::softmixer::SoftMixer;
use librespot::playback::mixer::{Mixer, MixerConfig};
use librespot::playback::player::{Player, PlayerEvent};
use librespot::playback::audio_backend;
use tokio::runtime::Runtime;

mod auth;
mod library;
mod queue;
mod settings;
#[cfg(target_os = "android")]
mod android_ctx;

pub use library::EntityInfo;
pub use queue::{QueueSnapshot, RepeatMode};
pub use settings::{NetworkBufferPreset, NormalizationType, StreamingQuality};

uniffi::setup_scaffolding!();

const AUDIO_CACHE_LIMIT_BYTES: u64 = 512 * 1024 * 1024; // 512 MiB

/// Refresh the access token this many seconds before Spotify's nominal expiry.
const REFRESH_EARLY_SECS: u64 = 60;
/// After nominal expiry, keep using the last access token briefly during outages.
const STALE_GRACE_SECS: u64 = 300;
/// After a failed refresh, wait before trying again (avoids search keystroke storms).
const REFRESH_COOLDOWN_SECS: u64 = 30;

/// Errors surfaced across the FFI. Variants are deliberately distinct so the UI
/// and logs can tell a Spotify backend outage apart from a local bug.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SpotifyError {
    #[error("not logged in")]
    NotLoggedIn,
    #[error("authentication failed: {msg}")]
    Auth { msg: String },
    #[error("premium account required")]
    PremiumRequired,
    #[error("network error: {msg}")]
    Network { msg: String },
    #[error("invalid spotify uri: {uri}")]
    InvalidUri { uri: String },
    #[error("track unavailable")]
    TrackUnavailable,
    #[error("internal error: {msg}")]
    Internal { msg: String },
}

/// A track surfaced to the UI (search results, library, now-playing). Built
/// from librespot spclient `context-resolve` — never the public Web API.
#[derive(uniffi::Record, Clone)]
pub struct TrackInfo {
    pub uri: String,
    pub title: String,
    pub artists: String,
    pub album: String,
    pub duration_ms: i64,
    pub art_url: Option<String>,
}

/// Playback events forwarded from librespot's player to Kotlin. Implemented by
/// the Android `PlaybackService` (which maps them onto a Media3 MediaSession).
#[uniffi::export(callback_interface)]
pub trait PlayerEventListener: Send + Sync {
    fn on_track_changed(&self, uri: String);
    fn on_loading(&self);
    fn on_playing(&self, position_ms: i64);
    fn on_paused(&self, position_ms: i64);
    fn on_position_changed(&self, position_ms: i64);
    fn on_end_of_track(&self);
    fn on_unavailable(&self, uri: String);
    fn on_connection_lost(&self);
    fn on_connection_restored(&self);
    fn on_error(&self, message: String);
    fn on_queue_changed(&self);
}

type SharedListener = Arc<Mutex<Option<Box<dyn PlayerEventListener>>>>;

use queue::QueueState;

/// A live, connected session and everything bound to its lifetime. A new
/// `Active` is built on every (re)connect because librespot sessions cannot be
/// reused once invalidated.
struct Active {
    session: Session,
    player: Arc<Player>,
    mixer: Arc<SoftMixer>,
    queue: Arc<Mutex<QueueState>>,
    event_task: tokio::task::JoinHandle<()>,
}

impl Drop for Active {
    fn drop(&mut self) {
        // NOTE: do not abort the reconnect monitor here. It is detached and
        // self-terminates via a Weak<EngineShared>; aborting it from Drop would
        // cancel the very task that performs a reconnect (it nulls `active`,
        // dropping this Active, mid-reconnect).
        self.event_task.abort();
        self.player.stop();
    }
}

/// In-memory OAuth token state used only for librespot session bootstrap and
/// spclient oauth_fallback when Login5 stored-credential refresh fails.
struct OAuthState {
    access_token: String,
    refresh_token: Option<String>,
    /// When to proactively refresh (nominal expiry minus REFRESH_EARLY_SECS).
    expires_at: Instant,
    /// When the current access_token was minted.
    issued_at: Instant,
    /// Full Spotify `expires_in` for the current access token.
    token_ttl_secs: u64,
    last_refresh_fail: Option<Instant>,
}

impl OAuthState {
    fn stale_grace_deadline(&self) -> Instant {
        self.issued_at
            + Duration::from_secs(self.token_ttl_secs.saturating_add(STALE_GRACE_SECS))
    }

    fn within_stale_grace(&self, now: Instant) -> bool {
        !self.access_token.is_empty() && now <= self.stale_grace_deadline()
    }

    fn remaining_grace_secs(&self, now: Instant) -> u64 {
        self.stale_grace_deadline()
            .saturating_duration_since(now)
            .as_secs()
            .max(60)
    }
}

#[derive(serde::Serialize, serde::Deserialize)]
struct PersistedOAuthCache {
    access_token: String,
    expires_at_epoch_secs: u64,
    token_ttl_secs: u64,
}

struct EngineShared {
    runtime: Runtime,
    session_config: Mutex<SessionConfig>,
    cache: Cache,
    cred_dir: PathBuf,
    audio_dir: PathBuf,
    tmp_dir: PathBuf,
    settings: settings::SettingsStore,
    listener: SharedListener,
    pkce_verifier: Mutex<Option<String>>,
    active: Mutex<Option<Active>>,
    oauth: Mutex<Option<OAuthState>>,
    refresh_mutex: Mutex<()>,
}

/// The UniFFI object handed to Kotlin.
#[derive(uniffi::Object)]
pub struct LibrespotEngine {
    shared: Arc<EngineShared>,
}

#[uniffi::export]
impl LibrespotEngine {
    /// Create the engine. `cache_dir` should be an app-private directory
    /// (e.g. `context.filesDir/spotify-cache`).
    #[uniffi::constructor]
    pub fn new(cache_dir: String) -> Result<Arc<Self>, SpotifyError> {
        Ok(Arc::new(Self {
            shared: EngineShared::new(cache_dir)?,
        }))
    }

    /// Register the playback event listener. Replaces any previous listener.
    pub fn set_listener(&self, listener: Box<dyn PlayerEventListener>) {
        *self.shared.listener.lock().unwrap() = Some(listener);
    }

    /// Begin OAuth: returns the authorization URL the WebView should load. The
    /// PKCE verifier is stashed until `login_with_oauth_code` is called.
    pub fn begin_login(&self) -> String {
        self.shared.begin_login()
    }

    /// Complete OAuth using the `?code=` captured by the WebView.
    pub fn login_with_oauth_code(&self, code: String) -> Result<(), SpotifyError> {
        self.shared.login_with_oauth_code(code)
    }

    /// Attempt to connect using previously cached credentials. Returns `false`
    /// if there are no cached credentials (caller should start OAuth).
    pub fn login_with_cached_credentials(&self) -> Result<bool, SpotifyError> {
        self.shared.login_with_cached_credentials()
    }

    pub fn is_logged_in(&self) -> bool {
        self.shared.is_logged_in()
    }

    /// OAuth access token (PKCE bootstrap / spclient fallback when Login5 is unavailable).
    pub fn access_token(&self) -> Result<String, SpotifyError> {
        self.shared.access_token()
    }

    /// Resolve a context/playlist URI to tracks via spclient context-resolve.
    pub fn context_tracks(&self, context_uri: String, limit: u32) -> Result<Vec<TrackInfo>, SpotifyError> {
        let session = self.shared.session_or_err()?;
        self.shared.context_tracks(&session, &context_uri, limit)
    }

    /// Discover Daily Mix / Made-For-You playlists via native context-resolve.
    pub fn daily_mixes(&self) -> Result<Vec<EntityInfo>, SpotifyError> {
        self.shared.daily_mixes()
    }

    /// Replace the playback context with `uris` and start playing at `start_index`.
    /// `context_label` is shown in the queue UI (e.g. album name). Clears manual queue.
    pub fn play_uris(
        &self,
        uris: Vec<String>,
        start_index: u32,
        context_label: Option<String>,
    ) -> Result<(), SpotifyError> {
        self.shared.play_uris(uris, start_index, context_label)
    }

    /// Convenience: play a single URI.
    pub fn play_uri(&self, uri: String) -> Result<(), SpotifyError> {
        self.shared.play_uris(vec![uri], 0, None)
    }

    pub fn pause(&self) {
        self.shared.transport_pause();
    }

    pub fn resume(&self) {
        self.shared.transport_resume();
    }

    pub fn next(&self) {
        self.shared.transport_next();
    }

    pub fn previous(&self) {
        self.shared.transport_previous();
    }

    pub fn seek(&self, position_ms: u32) {
        self.shared.transport_seek(position_ms);
    }

    pub fn get_shuffle(&self) -> bool {
        self.shared.queue_shuffle()
    }

    pub fn toggle_shuffle(&self) -> bool {
        self.shared.toggle_shuffle()
    }

    pub fn get_repeat_mode(&self) -> RepeatMode {
        self.shared.queue_repeat_mode()
    }

    pub fn toggle_repeat(&self) -> RepeatMode {
        self.shared.toggle_repeat()
    }

    /// Current queue from now-playing through the end, in playback order.
    pub fn get_queue(&self) -> QueueSnapshot {
        self.shared.get_queue()
    }

    /// Append a track to the end of the queue without interrupting playback.
    pub fn add_to_queue(&self, uri: String) -> Result<(), SpotifyError> {
        self.shared.add_to_queue(uri)
    }

    /// Move a manual-queue item earlier. `index` is into [QueueSnapshot::next_in_queue].
    pub fn move_queue_item_up(&self, index: u32) -> Result<(), SpotifyError> {
        self.shared.move_manual_queue_item(index, true)
    }

    pub fn move_queue_item_down(&self, index: u32) -> Result<(), SpotifyError> {
        self.shared.move_manual_queue_item(index, false)
    }

    pub fn move_context_item_up(&self, index: u32) -> Result<(), SpotifyError> {
        self.shared.move_context_queue_item(index, true)
    }

    pub fn move_context_item_down(&self, index: u32) -> Result<(), SpotifyError> {
        self.shared.move_context_queue_item(index, false)
    }

    /// Remove all manually queued tracks (does not affect playback context).
    pub fn clear_manual_queue(&self) {
        self.shared.clear_manual_queue()
    }

    /// Volume as a percentage 0..=100.
    pub fn get_volume(&self) -> u8 {
        self.shared.get_volume()
    }

    /// Volume as a percentage 0..=100. Persisted via librespot's volume cache.
    pub fn set_volume(&self, percent: u8) {
        self.shared.set_volume(percent);
    }

    pub fn get_streaming_quality(&self) -> StreamingQuality {
        self.shared.settings.get().streaming_quality
    }

    /// Bitrate changes require player recreation; applied immediately when logged in.
    pub fn set_streaming_quality(&self, quality: StreamingQuality) {
        self.shared
            .settings
            .update(|s| s.streaming_quality = quality);
        self.rebuild_player_if_active();
    }

    pub fn get_gapless_enabled(&self) -> bool {
        self.shared.settings.get().gapless_enabled
    }

    pub fn set_gapless_enabled(&self, enabled: bool) {
        self.shared
            .settings
            .update(|s| s.gapless_enabled = enabled);
        self.rebuild_player_if_active();
    }

    pub fn get_normalization_enabled(&self) -> bool {
        self.shared.settings.get().normalization_enabled
    }

    pub fn set_normalization_enabled(&self, enabled: bool) {
        self.shared
            .settings
            .update(|s| s.normalization_enabled = enabled);
        self.rebuild_player_if_active();
    }

    pub fn get_normalization_type(&self) -> NormalizationType {
        self.shared.settings.get().normalization_type
    }

    pub fn set_normalization_type(&self, kind: NormalizationType) {
        self.shared
            .settings
            .update(|s| s.normalization_type = kind);
        self.rebuild_player_if_active();
    }

    /// Optional HTTP proxy URL (e.g. `http://host:port`). Requires reconnect.
    pub fn get_proxy(&self) -> Option<String> {
        self.shared.settings.get().proxy.clone()
    }

    pub fn set_proxy(&self, proxy: Option<String>) {
        self.shared.set_proxy(proxy);
        self.rebuild_player_if_active();
    }

    /// Network buffer preset (read-ahead tuning). Persists only; takes effect on next app launch.
    pub fn get_network_buffer_preset(&self) -> NetworkBufferPreset {
        self.shared.settings.get().network_buffer_preset
    }

    pub fn set_network_buffer_preset(&self, preset: NetworkBufferPreset) {
        self.shared
            .settings
            .update(|s| s.network_buffer_preset = preset);
    }

    /// Delete downloaded audio cache files (credentials are untouched).
    pub fn clear_audio_cache(&self) {
        self.shared.clear_audio_cache();
    }

    /// Disconnect and forget cached credentials.
    pub fn logout(&self) {
        self.shared.logout();
    }

    /// Rebuild session + player so persisted settings take effect mid-session.
    fn rebuild_player_if_active(&self) {
        if self.shared.active.lock().unwrap().is_none() {
            return;
        }
        let resume = self.shared.snapshot_resume();
        let this = self.shared.clone();
        let handle = this.runtime.handle().clone();
        handle.spawn(async move {
            if let Some(active) = this.active.lock().unwrap().take() {
                active.session.shutdown();
            }
            if let Some(creds) = this.cache.credentials() {
                let _ = this.build_active(creds, resume).await;
            }
        });
    }
}

impl EngineShared {
    fn new(cache_dir: String) -> Result<Arc<Self>, SpotifyError> {
        #[cfg(target_os = "android")]
        android_ctx::init_logging();

        let base = PathBuf::from(&cache_dir);
        let cred_dir = base.join("creds");
        let audio_dir = base.join("audio");
        let tmp_dir = base.join("streaming-tmp");
        let _ = std::fs::create_dir_all(&cred_dir);
        let _ = std::fs::create_dir_all(&audio_dir);
        let _ = std::fs::create_dir_all(&tmp_dir);

        let settings = settings::SettingsStore::new(&base);
        let buffer_preset = settings.get().network_buffer_preset;
        settings::apply_audio_fetch_params(buffer_preset);

        let cache = Cache::new(
            Some(&cred_dir),
            Some(&cred_dir),
            Some(&audio_dir),
            Some(AUDIO_CACHE_LIMIT_BYTES),
        )
        .map_err(|e| SpotifyError::Internal { msg: e.to_string() })?;

        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("spotify-rt")
            .on_thread_start(|| {
                #[cfg(target_os = "android")]
                android_ctx::attach_current_thread_permanently();
            })
            .build()
            .map_err(|e| SpotifyError::Internal { msg: e.to_string() })?;

        // Keymaster OAuth on Android: align HTTP UA with client-token/login5 desktop path.
        #[cfg(target_os = "android")]
        librespot::core::config::set_http_platform_override("linux");

        // OAuth uses the Keymaster client id (auth.rs). The session must use the
        // same id so login5/spclient accept the reusable credentials Spotify
        // returns after AP connect (see librespot login5.rs + CHANGELOG fix for
        // "Invalid Credentials" with Keymaster token on Android).
        let mut session_config = SessionConfig::default();
        session_config.client_id = auth::CLIENT_ID.to_string();
        session_config.device_id = load_or_create_device_id(&base);
        session_config.tmp_dir = tmp_dir.clone();

        if let Some(ref proxy) = settings.get().proxy {
            if let Ok(url) = proxy.parse() {
                session_config.proxy = Some(url);
            }
        }

        Ok(Arc::new(Self {
            runtime,
            session_config: Mutex::new(session_config),
            cache,
            cred_dir,
            audio_dir,
            tmp_dir,
            settings,
            listener: Arc::new(Mutex::new(None)),
            pkce_verifier: Mutex::new(None),
            active: Mutex::new(None),
            oauth: Mutex::new(None),
            refresh_mutex: Mutex::new(()),
        }))
    }

    fn get_volume(&self) -> u8 {
        self.cache
            .volume()
            .map(|v| ((v as u32 * 100) / u16::MAX as u32) as u8)
            .unwrap_or(100)
    }

    fn set_volume(&self, percent: u8) {
        let pct = percent.min(100) as u32;
        let scaled = (pct * u16::MAX as u32 / 100) as u16;
        self.with_active(|a| a.mixer.set_volume(scaled));
        self.cache.save_volume(scaled);
    }

    fn set_proxy(&self, proxy: Option<String>) {
        let trimmed = proxy
            .map(|p| p.trim().to_string())
            .filter(|p| !p.is_empty());
        self.settings.update(|s| s.proxy = trimmed.clone());
        if let Ok(mut cfg) = self.session_config.lock() {
            cfg.proxy = trimmed.and_then(|p| p.parse().ok());
        }
    }

    fn clear_audio_cache(&self) {
        for dir in [&self.audio_dir, &self.tmp_dir] {
            if dir.is_dir() {
                let _ = std::fs::remove_dir_all(dir);
            }
            let _ = std::fs::create_dir_all(dir);
        }
    }

    fn begin_login(&self) -> String {
        let pkce = auth::Pkce::generate();
        let state = random_hex(16);
        let url = auth::build_auth_url(&pkce.challenge, &state);
        *self.pkce_verifier.lock().unwrap() = Some(pkce.verifier);
        url
    }

    fn login_with_oauth_code(self: &Arc<Self>, code: String) -> Result<(), SpotifyError> {
        let verifier = self
            .pkce_verifier
            .lock()
            .unwrap()
            .take()
            .ok_or(SpotifyError::Auth {
                msg: "no pending login (call begin_login first)".into(),
            })?;

        let handle = self.runtime.handle().clone();
        let tokens = handle.block_on(auth::exchange_code(&code, &verifier))?;
        let credentials = Credentials::with_access_token(&tokens.access_token);
        self.store_oauth(tokens);
        handle.block_on(self.clone().build_active(credentials, None))?;
        self.probe_login5_token();
        Ok(())
    }

    fn login_with_cached_credentials(self: &Arc<Self>) -> Result<bool, SpotifyError> {
        let creds = match self.cache.credentials() {
            Some(c) => c,
            None => return Ok(false),
        };
        // Restore OAuth tokens from disk so metadata works before the first refresh.
        if let Some(state) = self.load_persisted_oauth() {
            librespot::core::oauth_fallback::set_oauth_fallback_token(
                state.access_token.clone(),
                "Bearer",
                state
                    .expires_at
                    .saturating_duration_since(Instant::now())
                    .as_secs()
                    .max(60),
            );
            *self.oauth.lock().unwrap() = Some(state);
        } else if let Some(refresh) = self.load_refresh_token() {
            *self.oauth.lock().unwrap() = Some(OAuthState {
                access_token: String::new(),
                refresh_token: Some(refresh),
                expires_at: Instant::now(),
                issued_at: Instant::now(),
                token_ttl_secs: 0,
                last_refresh_fail: None,
            });
        }
        let handle = self.runtime.handle().clone();
        handle.block_on(self.clone().build_active(creds, None))?;
        // Prime the OAuth fallback token for spclient/playback.
        let _ = self.access_token();
        self.probe_login5_token();
        Ok(true)
    }

    /// Path where the OAuth refresh token is persisted.
    fn refresh_token_path(&self) -> PathBuf {
        self.cred_dir.join("oauth_refresh_token")
    }

    fn oauth_cache_path(&self) -> PathBuf {
        self.cred_dir.join("oauth_access_cache.json")
    }

    fn load_refresh_token(&self) -> Option<String> {
        std::fs::read_to_string(self.refresh_token_path())
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
    }

    fn load_persisted_oauth(&self) -> Option<OAuthState> {
        let raw = std::fs::read_to_string(self.oauth_cache_path()).ok()?;
        let cached: PersistedOAuthCache = serde_json::from_str(&raw).ok()?;
        if cached.access_token.is_empty() {
            return None;
        }
        let now_secs = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .ok()?
            .as_secs();
        if now_secs >= cached.expires_at_epoch_secs {
            return None;
        }
        let remaining = cached.expires_at_epoch_secs.saturating_sub(now_secs);
        let issued_at = Instant::now() - Duration::from_secs(
            cached
                .token_ttl_secs
                .saturating_sub(remaining)
                .min(cached.token_ttl_secs),
        );
        Some(OAuthState {
            access_token: cached.access_token,
            refresh_token: self.load_refresh_token(),
            expires_at: Instant::now() + Duration::from_secs(remaining),
            issued_at,
            token_ttl_secs: cached.token_ttl_secs,
            last_refresh_fail: None,
        })
    }

    fn persist_oauth_cache(&self, state: &OAuthState) {
        let expires_at_epoch_secs = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0)
            .saturating_add(
                state
                    .expires_at
                    .saturating_duration_since(Instant::now())
                    .as_secs(),
            );
        let cached = PersistedOAuthCache {
            access_token: state.access_token.clone(),
            expires_at_epoch_secs,
            token_ttl_secs: state.token_ttl_secs,
        };
        if let Ok(json) = serde_json::to_string(&cached) {
            let _ = std::fs::write(self.oauth_cache_path(), json);
        }
    }

    /// Cache the tokens in memory and persist the refresh token to disk.
    fn store_oauth(&self, tokens: auth::OAuthTokens) {
        if let Some(refresh) = tokens.refresh_token.as_deref() {
            let _ = std::fs::write(self.refresh_token_path(), refresh);
        }
        let now = Instant::now();
        // Refresh early to avoid racing expiry.
        let ttl = tokens
            .expires_in_secs
            .saturating_sub(REFRESH_EARLY_SECS)
            .max(1);
        let mut guard = self.oauth.lock().unwrap();
        let prev_refresh = guard.as_ref().and_then(|s| s.refresh_token.clone());
        *guard = Some(OAuthState {
            access_token: tokens.access_token.clone(),
            refresh_token: tokens.refresh_token.or(prev_refresh),
            expires_at: now + Duration::from_secs(ttl),
            issued_at: now,
            token_ttl_secs: tokens.expires_in_secs,
            last_refresh_fail: None,
        });
        // Let librespot spclient use this when login5 rejects stored credentials.
        librespot::core::oauth_fallback::set_oauth_fallback_token(
            tokens.access_token.clone(),
            "Bearer",
            tokens.expires_in_secs,
        );
        if let Some(state) = guard.as_ref() {
            self.persist_oauth_cache(state);
        }
    }

    fn sync_oauth_fallback_stale(&self, state: &OAuthState) {
        let now = Instant::now();
        if !state.within_stale_grace(now) {
            return;
        }
        librespot::core::oauth_fallback::set_oauth_fallback_token(
            state.access_token.clone(),
            "Bearer",
            state.remaining_grace_secs(now),
        );
    }

    fn is_logged_in(&self) -> bool {
        self.active
            .lock()
            .unwrap()
            .as_ref()
            .map(|a| !a.session.is_invalid())
            .unwrap_or(false)
    }

    /// A Spotify Web API access token. Returns the cached OAuth token while it is
    /// still valid, otherwise refreshes it via the stored refresh token.
    fn access_token(&self) -> Result<String, SpotifyError> {
        let now = Instant::now();

        // Fast path + cooldown path: read lock, no network.
        {
            let guard = self.oauth.lock().unwrap();
            if let Some(state) = guard.as_ref() {
                if !state.access_token.is_empty() && now < state.expires_at {
                    return Ok(state.access_token.clone());
                }
                if let Some(failed_at) = state.last_refresh_fail {
                    if now.duration_since(failed_at)
                        < Duration::from_secs(REFRESH_COOLDOWN_SECS)
                        && state.within_stale_grace(now)
                    {
                        let token = state.access_token.clone();
                        self.sync_oauth_fallback_stale(state);
                        return Ok(token);
                    }
                }
            }
        }

        let refresh_token = {
            let guard = self.oauth.lock().unwrap();
            match guard.as_ref() {
                Some(state) => state.refresh_token.clone(),
                None => None,
            }
        };
        let refresh_token = refresh_token
            .or_else(|| self.load_refresh_token())
            .ok_or(SpotifyError::NotLoggedIn)?;

        let _refresh_guard = self.refresh_mutex.lock().unwrap();

        // Re-check fast path after acquiring refresh mutex (another caller may have refreshed).
        {
            let guard = self.oauth.lock().unwrap();
            if let Some(state) = guard.as_ref() {
                if !state.access_token.is_empty() && now < state.expires_at {
                    return Ok(state.access_token.clone());
                }
            }
        }

        let handle = self.runtime.handle().clone();
        match handle.block_on(auth::refresh_access_token_with_retry(&refresh_token)) {
            Ok(tokens) => {
                let access = tokens.access_token.clone();
                self.store_oauth(tokens);
                Ok(access)
            }
            Err(SpotifyError::Network { msg }) => {
                let mut guard = self.oauth.lock().unwrap();
                if let Some(state) = guard.as_mut() {
                    state.last_refresh_fail = Some(Instant::now());
                    if state.within_stale_grace(Instant::now()) {
                        log::warn!("OAuth refresh failed ({msg}); using stale token within grace window");
                        let token = state.access_token.clone();
                        self.sync_oauth_fallback_stale(state);
                        return Ok(token);
                    }
                }
                Err(SpotifyError::Network { msg })
            }
            Err(e) => Err(e),
        }
    }

    pub(crate) fn session_or_err(&self) -> Result<Session, SpotifyError> {
        self.active
            .lock()
            .unwrap()
            .as_ref()
            .map(|a| a.session.clone())
            .ok_or(SpotifyError::NotLoggedIn)
    }

    /// Diagnostic: log whether Login5 and client-token mint succeed after connect.
    fn probe_login5_token(&self) {
        let Ok(session) = self.session_or_err() else {
            return;
        };
        let client_id = session.client_id();
        let auth_len = session.auth_data().len();
        log::info!("Login5 probe: session client_id={client_id} auth_data_len={auth_len}");

        let handle = self.runtime.handle().clone();
        let session_for_ct = session.clone();
        let ct_result = handle.block_on(async move {
            session_for_ct.spclient().clear_client_token();
            session_for_ct.spclient().client_token().await
        });
        match &ct_result {
            Ok(_) => log::info!("Login5 probe: client-token mint OK"),
            Err(e) => log::warn!("Login5 probe: client-token mint failed ({e})"),
        }

        let login5_result = handle.block_on(async move { session.login5().auth_token().await });
        match login5_result {
            Ok(token) => {
                log::info!(
                    "Login5 probe OK ({}s TTL, {} char bearer)",
                    token.expires_in.as_secs(),
                    token.access_token.len()
                );
            }
            Err(e) => {
                log::warn!("Login5 probe failed ({e}) — spclient may use OAuth fallback");
            }
        }
    }

    /// Resolve a context URI via librespot spclient `context-resolve` (Login5).
    fn context_tracks(
        &self,
        session: &Session,
        context_uri: &str,
        limit: u32,
    ) -> Result<Vec<TrackInfo>, SpotifyError> {
        let limit = limit.clamp(1, 50);
        let uri = context_uri.to_string();
        let session = session.clone();
        let handle = self.runtime.handle().clone();

        handle
            .block_on(async move {
                let ctx = session.spclient().get_context(&uri).await?;
                let mut tracks = parse_context_tracks(&ctx, limit);
                if tracks.iter().any(|t| t.title.is_empty()) {
                    enrich_track_metadata(&session, "", &mut tracks).await?;
                }
                Ok::<Vec<TrackInfo>, librespot::core::Error>(tracks)
            })
            .map_err(|e| SpotifyError::Network { msg: e.to_string() })
    }

    fn play_uris(
        &self,
        uris: Vec<String>,
        start_index: u32,
        context_label: Option<String>,
    ) -> Result<(), SpotifyError> {
        if uris.is_empty() {
            return Err(SpotifyError::InvalidUri {
                uri: "empty queue".into(),
            });
        }
        if start_index as usize >= uris.len() {
            return Err(SpotifyError::InvalidUri {
                uri: format!("index {start_index} out of range"),
            });
        }

        let parsed: Vec<SpotifyUri> = uris
            .iter()
            .map(|u| parse_uri(u))
            .collect::<Result<_, _>>()?;

        let (player, uri) = {
            let guard = self.active.lock().unwrap();
            let active = guard.as_ref().ok_or(SpotifyError::NotLoggedIn)?;
            {
                let mut q = active.queue.lock().unwrap();
                q.set_queue(parsed, start_index as usize, context_label);
            }
            let uri = active
                .queue
                .lock()
                .unwrap()
                .current_uri()
                .ok_or(SpotifyError::InvalidUri {
                    uri: format!("index {start_index} out of range"),
                })?;
            (active.player.clone(), uri)
        };

        player.load(uri, true, 0);
        self.notify_queue_changed();
        Ok(())
    }

    fn get_queue(&self) -> QueueSnapshot {
        self.with_active_queue(|q| q.queue_snapshot())
    }

    fn add_to_queue(&self, uri: String) -> Result<(), SpotifyError> {
        if !self.is_logged_in() {
            return Err(SpotifyError::NotLoggedIn);
        }
        let parsed = parse_uri(&uri)?;
        self.with_active_queue_mut(|q| {
            q.add_to_queue(parsed);
        });
        self.refresh_next_preload();
        self.notify_queue_changed();
        Ok(())
    }

    fn move_manual_queue_item(&self, index: u32, up: bool) -> Result<(), SpotifyError> {
        if !self.is_logged_in() {
            return Err(SpotifyError::NotLoggedIn);
        }
        let mut success = false;
        self.with_active_queue_mut(|q| {
            success = if up {
                q.move_manual_up(index as usize).is_ok()
            } else {
                q.move_manual_down(index as usize).is_ok()
            };
        });
        if !success {
            return Err(SpotifyError::InvalidUri {
                uri: format!("queue index {index} out of range"),
            });
        }
        self.notify_queue_changed();
        self.refresh_next_preload();
        Ok(())
    }

    fn move_context_queue_item(&self, index: u32, up: bool) -> Result<(), SpotifyError> {
        if !self.is_logged_in() {
            return Err(SpotifyError::NotLoggedIn);
        }
        let mut success = false;
        self.with_active_queue_mut(|q| {
            success = if up {
                q.move_context_up(index as usize).is_ok()
            } else {
                q.move_context_down(index as usize).is_ok()
            };
        });
        if !success {
            return Err(SpotifyError::InvalidUri {
                uri: format!("context index {index} out of range"),
            });
        }
        self.notify_queue_changed();
        self.refresh_next_preload();
        Ok(())
    }

    fn clear_manual_queue(&self) {
        self.with_active_queue_mut(|q| q.clear_manual_queue());
        self.notify_queue_changed();
        self.refresh_next_preload();
    }

    /// Re-preload the current up-next track after queue order changes mid-song.
    /// librespot discards a stale preload when the URI differs, so this is safe
    /// to call on every mutation.
    fn refresh_next_preload(&self) {
        self.with_active(|a| {
            if let Some(uri) = a.queue.lock().unwrap().next_preload_uri() {
                a.player.preload(uri);
            }
        });
    }

    fn notify_queue_changed(&self) {
        notify(&self.listener, |l| l.on_queue_changed());
    }

    fn with_active<F: FnOnce(&Active)>(&self, f: F) {
        if let Some(active) = self.active.lock().unwrap().as_ref() {
            f(active);
        }
    }

    fn transport_pause(&self) {
        self.with_active(|a| a.player.pause());
    }

    fn transport_resume(&self) {
        self.with_active(|a| a.player.play());
    }

    fn transport_next(&self) {
        self.skip(1, true);
    }

    fn transport_previous(&self) {
        self.skip(-1, true);
    }

    fn transport_seek(&self, position_ms: u32) {
        self.with_active(|a| {
            a.player.seek(position_ms);
            a.queue.lock().unwrap().set_position_ms(position_ms);
        });
    }

    fn skip(&self, delta: i64, user_initiated: bool) {
        let resolved = {
            let guard = self.active.lock().unwrap();
            let active = match guard.as_ref() {
                Some(a) => a,
                None => return,
            };
            let next = {
                let mut q = active.queue.lock().unwrap();
                if delta > 0 {
                    q.skip_next(user_initiated)
                } else {
                    q.skip_prev(user_initiated)
                }
            };
            next.map(|uri| (active.player.clone(), uri))
        };
        if let Some((player, uri)) = resolved {
            player.load(uri, true, 0);
            self.notify_queue_changed();
            self.refresh_next_preload();
        }
    }

    fn queue_shuffle(&self) -> bool {
        self.with_active_queue(|q| q.shuffle())
    }

    fn toggle_shuffle(&self) -> bool {
        let mut out = false;
        self.with_active_queue_mut(|q| out = q.toggle_shuffle());
        self.notify_queue_changed();
        self.refresh_next_preload();
        out
    }

    fn queue_repeat_mode(&self) -> RepeatMode {
        self.with_active_queue(|q| q.repeat_mode())
    }

    fn toggle_repeat(&self) -> RepeatMode {
        let mut out = RepeatMode::Off;
        self.with_active_queue_mut(|q| out = q.toggle_repeat());
        out
    }

    fn with_active_queue<F: FnOnce(&QueueState) -> R, R>(&self, f: F) -> R {
        let guard = self.active.lock().unwrap();
        if let Some(active) = guard.as_ref() {
            f(&active.queue.lock().unwrap())
        } else {
            f(&QueueState::default())
        }
    }

    fn with_active_queue_mut<F: FnOnce(&mut QueueState)>(&self, f: F) {
        if let Some(active) = self.active.lock().unwrap().as_ref() {
            f(&mut active.queue.lock().unwrap());
        }
    }

    fn logout(&self) {
        // Remove credentials first so any in-flight reconnect monitor gives up,
        // then invalidate the session so the monitor wakes and exits.
        let _ = std::fs::remove_file(self.cred_dir.join("credentials.json"));
        let _ = std::fs::remove_file(self.refresh_token_path());
        *self.oauth.lock().unwrap() = None;
        librespot::core::oauth_fallback::clear_oauth_fallback_token();
        if let Some(active) = self.active.lock().unwrap().take() {
            active.session.shutdown();
        }
    }

    /// Build a fresh `Active` (session + player + event/monitor tasks).
    async fn build_active(
        self: Arc<Self>,
        credentials: Credentials,
        resume: Option<(Vec<SpotifyUri>, usize, u32)>,
    ) -> Result<(), SpotifyError> {
        let session_config = self.session_config.lock().unwrap().clone();
        let session = Session::new(session_config, Some(self.cache.clone()));

        session
            .connect(credentials, true)
            .await
            .map_err(map_connect_err)?;

        // Fresh client-token for this session identity (Keymaster + Linux on Android).
        session.spclient().clear_client_token();

        let backend = audio_backend::find(None).ok_or(SpotifyError::Internal {
            msg: "no audio backend compiled in (expected rodio)".into(),
        })?;
        let mixer = Arc::new(
            SoftMixer::open(MixerConfig::default())
                .map_err(|e| SpotifyError::Internal { msg: e.to_string() })?,
        );
        let volume_getter = mixer.get_soft_volume();

        let player_config = self.settings.get().player_config();
        let audio_format = AudioFormat::S16;

        let player = Player::new(player_config, session.clone(), volume_getter, move || {
            backend(None, audio_format)
        });

        if let Some(vol) = self.cache.volume() {
            mixer.set_volume(vol);
        }

        let queue = Arc::new(Mutex::new(QueueState::default()));
        if let Some((uris, uri_index, position_ms)) = resume {
            queue.lock().unwrap().restore_linear(uris, uri_index, position_ms);
        }

        let rx = player.get_player_event_channel();
        let event_task = self.runtime.spawn(forward_events(
            rx,
            player.clone(),
            queue.clone(),
            self.listener.clone(),
        ));

        spawn_monitor(
            Arc::downgrade(&self),
            session.clone(),
            self.runtime.handle().clone(),
        );

        if let Some(uri) = {
            let q = queue.lock().unwrap();
            q.current_uri()
        } {
            let pos = queue.lock().unwrap().position_ms();
            player.load(uri, true, pos);
        }

        *self.active.lock().unwrap() = Some(Active {
            session,
            player,
            mixer,
            queue,
            event_task,
        });
        Ok(())
    }

    fn snapshot_resume(&self) -> Option<(Vec<SpotifyUri>, usize, u32)> {
        self.active
            .lock()
            .unwrap()
            .as_ref()
            .and_then(|a| a.queue.lock().unwrap().snapshot_resume())
    }
}

/// Watch a session for disconnection and rebuild it with cached credentials.
///
/// Runs on its own OS thread and drives the (`!Send`) reconnect via
/// `Handle::block_on`. Detached; self-terminates via the `Weak`.
fn spawn_monitor(
    weak: std::sync::Weak<EngineShared>,
    session: Session,
    handle: tokio::runtime::Handle,
) {
    let _ = std::thread::Builder::new()
        .name("spotify-monitor".into())
        .spawn(move || loop {
            std::thread::sleep(Duration::from_secs(5));
            if !session.is_invalid() {
                continue;
            }

            let shared = match weak.upgrade() {
                Some(s) => s,
                None => return,
            };
            notify(&shared.listener, |l| l.on_connection_lost());

            let resume = shared.snapshot_resume();
            *shared.active.lock().unwrap() = None;

            let mut delay = 2u64;
            loop {
                if weak.upgrade().is_none() {
                    return;
                }
                if let Some(creds) = shared.cache.credentials() {
                    match handle.block_on(shared.clone().build_active(creds, resume.clone())) {
                        Ok(()) => {
                            notify(&shared.listener, |l| l.on_connection_restored());
                            let _ = shared.access_token();
                            return; // the new Active started its own monitor
                        }
                        Err(e) => log::warn!("reconnect attempt failed: {e}"),
                    }
                } else {
                    log::warn!("reconnect: no cached credentials, giving up");
                    return;
                }
                std::thread::sleep(Duration::from_secs(delay));
                delay = (delay * 2).min(60);
            }
        });
}

/// Caps rapid auto-skip when many consecutive tracks fail to load.
struct UnavailableGuard {
    count: u32,
    window_start: Instant,
}

impl UnavailableGuard {
    fn new() -> Self {
        Self {
            count: 0,
            window_start: Instant::now(),
        }
    }

    fn reset(&mut self) {
        self.count = 0;
        self.window_start = Instant::now();
    }

    /// Returns true when playback should stop (too many failures in a short window).
    fn record(&mut self) -> bool {
        let now = Instant::now();
        if now.duration_since(self.window_start) > Duration::from_secs(10) {
            self.reset();
        }
        self.count += 1;
        self.count >= 5
    }
}

/// Forward librespot player events to the Kotlin listener. Queue advancement and
/// gapless preload are handled here for local playback.
async fn forward_events(
    mut rx: tokio::sync::mpsc::UnboundedReceiver<PlayerEvent>,
    player: Arc<Player>,
    queue: Arc<Mutex<QueueState>>,
    listener: SharedListener,
) {
    let mut unavailable_guard = UnavailableGuard::new();
    while let Some(event) = rx.recv().await {
        match event {
            PlayerEvent::Loading { track_id, .. } => {
                unavailable_guard.reset();
                notify(&listener, |l| l.on_loading());
                notify(&listener, |l| l.on_track_changed(uri_to_string(&track_id)));
            }
            PlayerEvent::Playing { track_id, position_ms, .. } => {
                unavailable_guard.reset();
                notify(&listener, |l| l.on_playing(position_ms as i64));
                notify(&listener, |l| l.on_track_changed(uri_to_string(&track_id)));
            }
            PlayerEvent::Paused { position_ms, .. } => {
                notify(&listener, |l| l.on_paused(position_ms as i64));
            }
            PlayerEvent::PositionChanged { position_ms, .. }
            | PlayerEvent::PositionCorrection { position_ms, .. }
            | PlayerEvent::Seeked { position_ms, .. } => {
                notify(&listener, |l| l.on_position_changed(position_ms as i64));
            }
            PlayerEvent::Unavailable { track_id, .. } => {
                notify(
                    &listener,
                    |l| l.on_unavailable(uri_to_string(&track_id)),
                );
                // Advance past dead tracks (skip_next, not end_of_track — repeat-one must not retry unavailable URI).
                if unavailable_guard.record() {
                    log::warn!("too many consecutive unavailable tracks; stopping playback");
                    unavailable_guard.reset();
                    notify(&listener, |l| l.on_end_of_track());
                } else {
                    let next = queue.lock().unwrap().skip_next(false);
                    if let Some(uri) = next {
                        player.load(uri, true, 0);
                    } else {
                        notify(&listener, |l| l.on_end_of_track());
                    }
                }
            }
            PlayerEvent::Stopped { track_id, .. } => {
                log::warn!("playback stopped for {}", uri_to_string(&track_id));
            }
            PlayerEvent::TimeToPreloadNextTrack { .. } => {
                if let Some(uri) = queue.lock().unwrap().next_preload_uri() {
                    player.preload(uri);
                }
            }
            PlayerEvent::EndOfTrack { .. } => {
                let next = queue.lock().unwrap().end_of_track();
                if let Some(uri) = next {
                    player.load(uri, true, 0);
                } else {
                    notify(&listener, |l| l.on_end_of_track());
                }
            }
            _ => {}
        }
    }
}

fn notify<F: FnOnce(&dyn PlayerEventListener)>(listener: &SharedListener, f: F) {
    if let Ok(guard) = listener.lock() {
        if let Some(l) = guard.as_ref() {
            f(l.as_ref());
        }
    }
}

fn map_connect_err(e: librespot::core::Error) -> SpotifyError {
    let msg = e.to_string();
    let lower = msg.to_lowercase();
    if lower.contains("premium") {
        SpotifyError::PremiumRequired
    } else if lower.contains("credential") || lower.contains("unauthenticated") {
        SpotifyError::Auth { msg }
    } else {
        SpotifyError::Network { msg }
    }
}

fn parse_uri(input: &str) -> Result<SpotifyUri, SpotifyError> {
    let s = input.trim().split('?').next().unwrap_or(input.trim());
    // Handles `spotify:track:...`, `spotify:episode:...`, and the https forms.
    if let Ok(uri) = SpotifyUri::from_uri(s) {
        return Ok(uri);
    }
    // Fallback: a bare base62 id is assumed to be a track.
    let id = SpotifyId::from_base62(s).map_err(|_| SpotifyError::InvalidUri {
        uri: input.to_string(),
    })?;
    Ok(SpotifyUri::Track { id })
}

fn uri_to_string(uri: &SpotifyUri) -> String {
    uri.to_uri().unwrap_or_default()
}

fn load_or_create_device_id(base: &Path) -> String {
    let path = base.join("device_id");
    if let Ok(existing) = std::fs::read_to_string(&path) {
        let trimmed = existing.trim();
        if !trimmed.is_empty() {
            return trimmed.to_string();
        }
    }
    let id = random_hex(20);
    let _ = std::fs::write(&path, &id);
    id
}

fn random_hex(bytes: usize) -> String {
    use rand::Rng;
    let mut rng = rand::thread_rng();
    (0..bytes)
        .map(|_| format!("{:02x}", rng.gen::<u8>()))
        .collect()
}

fn parse_context_tracks(
    ctx: &librespot::protocol::context::Context,
    limit: u32,
) -> Vec<TrackInfo> {
    let mut out = Vec::new();
    let page_count = ctx.pages.len();
    for page in &ctx.pages {
        for track in &page.tracks {
            if let Some(info) = context_track_to_info(track, &page.metadata) {
                out.push(info);
                if out.len() >= limit as usize {
                    log::info!("context-resolve: {page_count} pages -> {} tracks", out.len());
                    return out;
                }
            }
        }
    }
    log::info!(
        "context-resolve: {page_count} pages -> {} tracks (uri={:?})",
        out.len(),
        ctx.uri
    );
    out
}

pub(crate) fn context_track_to_info(
    track: &librespot::protocol::context_track::ContextTrack,
    page_metadata: &std::collections::HashMap<String, String>,
) -> Option<TrackInfo> {
    let uri = track.uri.as_ref()?.clone();
    let base_uri = uri.split('?').next().unwrap_or(&uri);
    if !base_uri.starts_with("spotify:track:") {
        return None;
    }

    let mut md = page_metadata.clone();
    for (k, v) in &track.metadata {
        md.insert(k.clone(), v.clone());
    }

    let title = meta(&md, &["title", "track_name"]).unwrap_or_default();
    let artists = meta(&md, &["artist_name", "artists"]).unwrap_or_default();
    let album = meta(&md, &["album_title", "album_name", "album"]).unwrap_or_default();
    let duration_ms = meta(&md, &["duration", "duration_ms"])
        .and_then(|s| s.parse().ok())
        .unwrap_or(0);
    let art_url = meta(
        &md,
        &[
            "image_url",
            "image_xlarge_url",
            "image_large_url",
            "cover_uri",
        ],
    );

    Some(TrackInfo {
        uri: base_uri.to_string(),
        title,
        artists,
        album,
        duration_ms,
        art_url,
    })
}

fn meta(md: &std::collections::HashMap<String, String>, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|k| md.get(*k).cloned())
        .filter(|s| !s.is_empty())
}

/// Fill in title/artist/album/art via librespot's `extended-metadata` endpoint.
async fn enrich_track_metadata(
    session: &Session,
    bearer: &str,
    tracks: &mut [TrackInfo],
) -> Result<(), librespot::core::Error> {
    use librespot::metadata::{Metadata, Track};

    for track in tracks.iter_mut() {
        if !track.title.is_empty() {
            continue;
        }
        let Ok(uri) = SpotifyUri::from_uri(track.uri.as_str()) else {
            continue;
        };
        match get_track_metadata_bearer(session, bearer, &uri).await {
            Ok(meta) => {
                track.title = meta.name;
                track.artists = meta
                    .artists
                    .iter()
                    .map(|a| a.name.clone())
                    .collect::<Vec<_>>()
                    .join(", ");
                track.album = meta.album.name;
                track.duration_ms = meta.duration as i64;
                track.art_url = album_cover_url(&meta.album.covers);
            }
            Err(e) => {
                log::warn!("metadata enrich failed for {}: {e}", track.uri);
                // Best-effort: librespot Track::get via login5 as last resort.
                if let Ok(meta) = Track::get(session, &uri).await {
                    track.title = meta.name;
                    track.artists = meta
                        .artists
                        .iter()
                        .map(|a| a.name.clone())
                        .collect::<Vec<_>>()
                        .join(", ");
                    track.album = meta.album.name;
                    track.duration_ms = meta.duration as i64;
                    track.art_url = album_cover_url(&meta.album.covers);
                }
            }
        }
    }
    Ok(())
}

pub(crate) async fn get_track_metadata_bearer(
    session: &Session,
    _bearer: &str,
    track_uri: &SpotifyUri,
) -> Result<librespot::metadata::Track, librespot::core::Error> {
    use librespot::metadata::{Metadata, Track};

    Track::get(session, track_uri).await
}

fn album_cover_url(covers: &librespot::metadata::image::Images) -> Option<String> {
    covers
        .iter()
        .max_by_key(|c| c.width)
        .map(|c| format!("https://i.scdn.co/image/{}", c.id))
}
