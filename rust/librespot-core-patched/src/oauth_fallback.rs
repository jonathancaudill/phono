//! Optional OAuth bearer used when login5 rejects stored credentials (common on
//! Android clients that authenticate via the Keymaster OAuth flow).

use std::sync::{Mutex, OnceLock};

use crate::token::Token;

static OAUTH_FALLBACK: OnceLock<Mutex<Option<Token>>> = OnceLock::new();

fn slot() -> &'static Mutex<Option<Token>> {
    OAUTH_FALLBACK.get_or_init(|| Mutex::new(None))
}

/// Cache a Spotify OAuth access token for spclient HTTP calls when login5 fails.
pub fn set_oauth_fallback_token(access_token: String, token_type: &str, expires_in_secs: u64) {
    let token = Token {
        access_token,
        token_type: token_type.to_string(),
        expires_in: std::time::Duration::from_secs(expires_in_secs.max(60)),
        scopes: vec![],
        timestamp: std::time::SystemTime::now(),
    };
    *slot().lock().unwrap() = Some(token);
}

pub fn clear_oauth_fallback_token() {
    *slot().lock().unwrap() = None;
}

pub fn has_oauth_fallback_token() -> bool {
    oauth_fallback_token().is_some()
}

pub(crate) fn oauth_fallback_token() -> Option<Token> {
    let mut guard = slot().lock().unwrap();
    if let Some(token) = guard.as_ref() {
        if token.is_expired() {
            *guard = None;
            return None;
        }
        return Some(token.clone());
    }
    None
}
