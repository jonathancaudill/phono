//! OAuth Authorization Code flow with PKCE.
//!
//! We deliberately do NOT use `librespot-oauth`'s `get_access_token`, because it
//! either opens a system browser or runs its own local TCP server to capture the
//! redirect. Our design hands the auth URL to a Kotlin WebView, which intercepts
//! the `127.0.0.1` redirect itself and passes the `?code=` back. So we only need
//! the two halves librespot-oauth keeps private: build the auth URL (with a PKCE
//! challenge) and exchange the returned code (with the matching verifier).

use std::sync::OnceLock;
use std::time::Duration;

use base64::Engine as _;
use rand::Rng;
use sha2::{Digest, Sha256};

use crate::SpotifyError;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);

const REFRESH_MAX_ATTEMPTS: usize = 3;
const REFRESH_BACKOFFS: [Duration; 2] = [Duration::from_millis(500), Duration::from_secs(1)];

pub(crate) fn http_client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(REQUEST_TIMEOUT)
            .build()
            .expect("reqwest client")
    })
}

/// Desktop-style UA retained for optional diagnostics only.
pub(crate) const WEB_API_USER_AGENT: &str = "Spotify/8.8.98.1234 OSX/0 (LightPhone/0.1.0)";

/// Hit a few Web API routes and log status — used to distinguish Login5 auth vs rate limits.
pub(crate) async fn probe_web_api_bearer(label: &str, bearer: &str) {
    let client = http_client();
    let urls = [
        "https://api.spotify.com/v1/me",
        "https://api.spotify.com/v1/search?q=test&type=track&limit=1&market=from_token",
        "https://api.spotify.com/v1/me/tracks?limit=1&market=from_token&offset=0",
        "https://api.spotify.com/v1/me/albums?limit=1&market=from_token&offset=0",
    ];
    for url in urls {
        match client
            .get(url)
            .header("Authorization", format!("Bearer {bearer}"))
            .header("User-Agent", WEB_API_USER_AGENT)
            .send()
            .await
        {
            Ok(resp) => {
                let status = resp.status();
                let retry = resp
                    .headers()
                    .get("retry-after")
                    .and_then(|v| v.to_str().ok())
                    .map(|s| s.to_string())
                    .unwrap_or_else(|| "-".to_string());
                let body = resp.text().await.unwrap_or_default();
                let snippet: String = body.chars().take(160).collect();
                log::info!(
                    "Web API probe [{label}] {status} retry-after={retry} {url} body={snippet}"
                );
            }
            Err(e) => log::warn!("Web API probe [{label}] {url} failed: {e}"),
        }
    }
}

/// Spotify's well-known desktop / "keymaster" client ID. Using one client ID for
/// both the OAuth exchange and the librespot `Session` keeps `login5` happy on
/// Android (stored credentials must match the session client ID).
pub const CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";

/// Must exactly match a redirect URI registered for `CLIENT_ID`, and must be
/// `127.0.0.1` (not `localhost`). This is the loopback URI Spotify has registered
/// for the desktop client (same value librespot-oauth uses); other ports/paths
/// return "redirect_uri: Not matching configuration". The WebView intercepts
/// navigation here instead of letting it actually load.
pub const REDIRECT_URI: &str = "http://127.0.0.1:8898/login";

const AUTH_ENDPOINT: &str = "https://accounts.spotify.com/authorize";
const TOKEN_ENDPOINT: &str = "https://accounts.spotify.com/api/token";

const SCOPES: &[&str] = &[
    "streaming",
    "user-read-email",
    "user-read-private",
    "playlist-read-private",
    "playlist-read-collaborative",
    "user-library-read",
    "user-library-modify",
    "user-read-playback-state",
    "user-modify-playback-state",
    "user-top-read",
    "user-follow-read",
    "user-read-recently-played",
];

/// A PKCE pair: the `verifier` is kept secret in engine state until the code is
/// exchanged; the `challenge` is embedded in the auth URL.
pub struct Pkce {
    pub verifier: String,
    pub challenge: String,
}

impl Pkce {
    pub fn generate() -> Self {
        let verifier = random_verifier();
        let digest = Sha256::digest(verifier.as_bytes());
        let challenge = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(digest);
        Self {
            verifier,
            challenge,
        }
    }
}

fn random_verifier() -> String {
    // RFC 7636: 43-128 chars from the unreserved set. 64 random bytes -> base64url.
    let mut bytes = [0u8; 64];
    rand::thread_rng().fill(&mut bytes[..]);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

/// Build the authorization URL the WebView should load.
pub fn build_auth_url(challenge: &str, state: &str) -> String {
    let scope = SCOPES.join(" ");
    let query = [
        ("client_id", CLIENT_ID),
        ("response_type", "code"),
        ("redirect_uri", REDIRECT_URI),
        ("code_challenge_method", "S256"),
        ("code_challenge", challenge),
        ("scope", &scope),
        ("state", state),
    ];
    let qs = serde_urlencoded_lite(&query);
    format!("{AUTH_ENDPOINT}?{qs}")
}

/// A successful response from the OAuth token endpoint.
#[derive(serde::Deserialize)]
struct TokenResponse {
    access_token: String,
    /// Present on the initial code exchange; on refresh Spotify may omit it
    /// (in which case the previous refresh token stays valid).
    refresh_token: Option<String>,
    #[serde(default = "default_expires")]
    expires_in: u64,
}

fn default_expires() -> u64 {
    3600
}

/// The tokens we keep after OAuth. The Web API is called with `access_token`
/// until it nears expiry, then refreshed via `refresh_token`.
pub struct OAuthTokens {
    pub access_token: String,
    pub refresh_token: Option<String>,
    pub expires_in_secs: u64,
}

/// Exchange an authorization `code` (captured by the WebView) plus the matching
/// PKCE `verifier` for an access token (+ refresh token).
pub async fn exchange_code(code: &str, verifier: &str) -> Result<OAuthTokens, SpotifyError> {
    let form = [
        ("grant_type", "authorization_code"),
        ("code", code),
        ("redirect_uri", REDIRECT_URI),
        ("client_id", CLIENT_ID),
        ("code_verifier", verifier),
    ];
    post_token(&form).await
}

/// Use a stored `refresh_token` to mint a fresh access token.
pub async fn refresh_access_token(refresh_token: &str) -> Result<OAuthTokens, SpotifyError> {
    let form = [
        ("grant_type", "refresh_token"),
        ("refresh_token", refresh_token),
        ("client_id", CLIENT_ID),
    ];
    post_token(&form).await
}

/// Refresh with bounded retries on transient network failures only.
pub async fn refresh_access_token_with_retry(
    refresh_token: &str,
) -> Result<OAuthTokens, SpotifyError> {
    let mut last_err = SpotifyError::Network {
        msg: "refresh failed".into(),
    };
    for attempt in 0..REFRESH_MAX_ATTEMPTS {
        match refresh_access_token(refresh_token).await {
            Ok(tokens) => return Ok(tokens),
            Err(e @ SpotifyError::Auth { .. }) => return Err(e),
            Err(e @ SpotifyError::Network { .. }) => {
                last_err = e;
                if attempt + 1 < REFRESH_MAX_ATTEMPTS {
                    let delay = REFRESH_BACKOFFS
                        .get(attempt)
                        .copied()
                        .unwrap_or(Duration::from_secs(2));
                    tokio::time::sleep(delay).await;
                }
            }
            Err(e) => return Err(e),
        }
    }
    Err(last_err)
}

async fn post_token(form: &[(&str, &str)]) -> Result<OAuthTokens, SpotifyError> {
    let resp = http_client()
        .post(TOKEN_ENDPOINT)
        .form(form)
        .send()
        .await
        .map_err(|e| SpotifyError::Network { msg: e.to_string() })?;

    if !resp.status().is_success() {
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        return Err(SpotifyError::Auth {
            msg: format!("token request failed ({status}): {body}"),
        });
    }

    let token: TokenResponse = resp
        .json()
        .await
        .map_err(|e| SpotifyError::Auth { msg: e.to_string() })?;
    Ok(OAuthTokens {
        access_token: token.access_token,
        refresh_token: token.refresh_token,
        expires_in_secs: token.expires_in,
    })
}

/// Minimal application/x-www-form-urlencoded for query strings, avoiding an extra
/// dependency for a handful of fixed keys.
fn serde_urlencoded_lite(pairs: &[(&str, &str)]) -> String {
    pairs
        .iter()
        .map(|(k, v)| format!("{}={}", encode(k), encode(v)))
        .collect::<Vec<_>>()
        .join("&")
}

fn encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for byte in s.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}
