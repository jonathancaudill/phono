//! Resolve Spotify login usernames to display names via spclient user-profile-view.

use std::sync::Arc;

use librespot::core::Session;
use serde_json::Value;

use crate::SpotifyError;

pub async fn fetch_user_display_name(session: &Session, username: &str) -> Option<String> {
    if username.is_empty() {
        return None;
    }
    let bytes = session
        .spclient()
        .get_user_profile(username, None, None)
        .await
        .ok()?;
    parse_user_display_name(&bytes, username)
}

fn parse_user_display_name(bytes: &[u8], username: &str) -> Option<String> {
    let value: Value = serde_json::from_slice(bytes).ok()?;
    extract_display_name(&value, username)
}

fn extract_display_name(value: &Value, username: &str) -> Option<String> {
    let fields = [
        value.get("name").and_then(|n| n.as_str()),
        value.get("displayName").and_then(|n| n.as_str()),
        value.get("display_name").and_then(|n| n.as_str()),
        value
            .get("profile")
            .and_then(|p| p.get("name"))
            .and_then(|n| n.as_str()),
    ];
    for name in fields.iter().flatten() {
        let trimmed = name.trim();
        if !trimmed.is_empty() && trimmed != username {
            return Some(trimmed.to_string());
        }
    }
    fields
        .iter()
        .flatten()
        .find(|name| !name.trim().is_empty())
        .map(|name| name.trim().to_string())
}

impl super::EngineShared {
    pub fn user_display_name_native(
        self: &Arc<Self>,
        username: &str,
    ) -> Result<Option<String>, SpotifyError> {
        let session = Self::session_or_err(self)?;
        let handle = self.runtime.handle().clone();
        let username = username.to_string();
        Ok(handle.block_on(async move { fetch_user_display_name(&session, &username).await }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extract_display_name_prefers_name_field() {
        let json = r#"{"name":"Jane Doe","username":"abc123xyz"}"#;
        let value: Value = serde_json::from_str(json).unwrap();
        assert_eq!(
            extract_display_name(&value, "abc123xyz").as_deref(),
            Some("Jane Doe")
        );
    }

    #[test]
    fn extract_display_name_falls_back_to_display_name() {
        let json = r#"{"display_name":"Public Name"}"#;
        let value: Value = serde_json::from_str(json).unwrap();
        assert_eq!(
            extract_display_name(&value, "user1").as_deref(),
            Some("Public Name")
        );
    }
}
