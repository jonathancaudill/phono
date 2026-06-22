//! Desktop Web API (psst model): OAuth bearer on api.spotify.com via reqwest.

use librespot::core::Session;

use super::{EntityInfo, SavedAlbumInfo};

fn encode_query(query: &str) -> String {
    url::form_urlencoded::byte_serialize(query.as_bytes()).collect()
}

fn http() -> &'static reqwest::Client {
    crate::auth::http_client()
}

async fn client_token(session: &Session) -> Option<String> {
    session.spclient().client_token().await.ok()
}

async fn web_api_get(
    session: &Session,
    bearer: &str,
    path_query: &str,
) -> Result<serde_json::Value, librespot::core::Error> {
    let url = format!("https://api.spotify.com/{path_query}");
    let mut req = http()
        .get(&url)
        .header("Accept", "application/json")
        .header("Authorization", format!("Bearer {bearer}"));

    if let Some(ct) = client_token(session).await {
        req = req.header("client-token", ct);
    }

    let resp = req.send().await.map_err(|e| {
        librespot::core::Error::unavailable(format!("web api GET failed: {e}"))
    })?;
    let status = resp.status();
    let body = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        let snippet: String = body.chars().take(200).collect();
        log::warn!("web api GET {status} {url}: {snippet}");
        return Err(librespot::core::Error::resource_exhausted(format!(
            "web api GET {status}"
        )));
    }
    serde_json::from_str(&body).map_err(|e| {
        librespot::core::Error::failed_precondition(format!("web api json: {e}"))
    })
}

fn image_url(value: &serde_json::Value) -> Option<String> {
    value
        .get("images")
        .and_then(|v| v.as_array())
        .and_then(|imgs| imgs.first())
        .and_then(|img| img.get("url"))
        .and_then(|u| u.as_str())
        .or_else(|| {
            value
                .get("coverArt")
                .and_then(|c| c.get("sources"))
                .and_then(|s| s.as_array())
                .and_then(|imgs| imgs.first())
                .and_then(|img| img.get("url"))
                .and_then(|u| u.as_str())
        })
        .map(str::to_string)
}

fn artists_subtitle(value: &serde_json::Value) -> String {
    if let Some(items) = value
        .get("artists")
        .and_then(|a| a.get("items"))
        .and_then(|v| v.as_array())
    {
        return items
            .iter()
            .filter_map(|a| {
                a.get("profile")
                    .and_then(|p| p.get("name"))
                    .and_then(|n| n.as_str())
                    .or_else(|| a.get("name").and_then(|n| n.as_str()))
            })
            .collect::<Vec<_>>()
            .join(", ");
    }
    value
        .get("artists")
        .and_then(|v| v.as_array())
        .map(|artists| {
            artists
                .iter()
                .filter_map(|a| a.get("name").and_then(|n| n.as_str()))
                .collect::<Vec<_>>()
                .join(", ")
        })
        .unwrap_or_default()
}

fn uri_id(uri: &str) -> String {
    uri.rsplit(':').next().unwrap_or(uri).to_string()
}

fn album_entity(album: &serde_json::Value) -> Option<EntityInfo> {
    let id = album
        .get("id")
        .and_then(|v| v.as_str())
        .map(str::to_string)
        .or_else(|| {
            album
                .get("uri")
                .and_then(|u| u.as_str())
                .map(uri_id)
        })?;
    Some(EntityInfo {
        entity_type: "album".into(),
        id: id.to_string(),
        uri: album
            .get("uri")
            .and_then(|v| v.as_str())
            .unwrap_or(&format!("spotify:album:{id}"))
            .to_string(),
        name: album
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
        subtitle: artists_subtitle(album),
        art_url: image_url(album),
    })
}

fn artist_entity(artist: &serde_json::Value) -> Option<EntityInfo> {
    let id = artist
        .get("id")
        .and_then(|v| v.as_str())
        .map(str::to_string)
        .or_else(|| {
            artist
                .get("uri")
                .and_then(|u| u.as_str())
                .map(uri_id)
        })?;
    let name = artist
        .get("name")
        .and_then(|v| v.as_str())
        .or_else(|| {
            artist
                .get("profile")
                .and_then(|p| p.get("name"))
                .and_then(|n| n.as_str())
        })
        .unwrap_or_default();
    Some(EntityInfo {
        entity_type: "artist".into(),
        id: id.to_string(),
        uri: artist
            .get("uri")
            .and_then(|v| v.as_str())
            .unwrap_or(&format!("spotify:artist:{id}"))
            .to_string(),
        name: name.to_string(),
        subtitle: "Artist".into(),
        art_url: image_url(artist).or_else(|| {
            artist
                .get("visuals")
                .and_then(|v| v.get("avatarImage"))
                .and_then(image_url)
        }),
    })
}

fn track_entity(track: &serde_json::Value) -> Option<EntityInfo> {
    let inner = track.get("data").unwrap_or(track);
    let id = inner
        .get("id")
        .and_then(|v| v.as_str())
        .map(str::to_string)
        .or_else(|| inner.get("uri").and_then(|u| u.as_str()).map(uri_id))?;
    Some(EntityInfo {
        entity_type: "track".into(),
        id: id.to_string(),
        uri: inner
            .get("uri")
            .and_then(|v| v.as_str())
            .unwrap_or(&format!("spotify:track:{id}"))
            .to_string(),
        name: inner
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
        subtitle: artists_subtitle(inner),
        art_url: inner
            .get("album")
            .and_then(|a| a.get("coverArt"))
            .and_then(image_url)
            .or_else(|| inner.get("album").and_then(image_url))
            .or_else(|| image_url(inner)),
    })
}

fn playlist_entity(playlist: &serde_json::Value) -> Option<EntityInfo> {
    let id = playlist
        .get("id")
        .and_then(|v| v.as_str())
        .map(str::to_string)
        .or_else(|| {
            playlist
                .get("uri")
                .and_then(|u| u.as_str())
                .map(uri_id)
        })?;
    Some(EntityInfo {
        entity_type: "playlist".into(),
        id: id.to_string(),
        uri: playlist
            .get("uri")
            .and_then(|v| v.as_str())
            .unwrap_or(&format!("spotify:playlist:{id}"))
            .to_string(),
        name: playlist
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
        subtitle: playlist
            .get("owner")
            .and_then(|o| o.get("display_name"))
            .or_else(|| playlist.get("ownerV2").and_then(|o| o.get("displayName")))
            .and_then(|n| n.as_str())
            .unwrap_or("Playlist")
            .to_string(),
        art_url: image_url(playlist),
    })
}

fn page_items<'a>(value: &'a serde_json::Value, key: &str) -> Option<&'a Vec<serde_json::Value>> {
    value.get(key)?.get("items")?.as_array()
}

/// psst: GET v1/me/albums?market=from_token (OAuth bearer via reqwest).
pub async fn fetch_saved_albums(
    session: &Session,
    bearer: &str,
    limit: u32,
) -> Result<Vec<SavedAlbumInfo>, librespot::core::Error> {
    let limit = limit.clamp(1, 500);
    let mut out = Vec::new();
    let mut offset = 0u32;
    let page_size = 50u32;

    while out.len() < limit as usize {
        let take = page_size.min(limit - out.len() as u32);
        let path = format!("v1/me/albums?limit={take}&offset={offset}&market=from_token");
        let json = web_api_get(session, bearer, &path).await?;
        let Some(items) = json.get("items").and_then(|v| v.as_array()) else {
            break;
        };
        if items.is_empty() {
            break;
        }

        for item in items {
            let Some(album) = item.get("album") else { continue };
            let Some(entity) = album_entity(album) else { continue };
            out.push(SavedAlbumInfo {
                album: entity,
                added_at_ms: None,
            });
            if out.len() >= limit as usize {
                break;
            }
        }

        if items.len() < take as usize {
            break;
        }
        offset += take;
    }

    log::info!("web client saved albums: {} item(s)", out.len());
    Ok(out)
}

/// psst: GET v1/search?type=artist,album,track,playlist&market=from_token
pub async fn search_catalog_web(
    session: &Session,
    bearer: &str,
    query: &str,
    limit_per_type: u32,
) -> Result<Vec<EntityInfo>, librespot::core::Error> {
    let q = encode_query(query.trim());
    if q.is_empty() {
        return Ok(vec![]);
    }
    let limit = limit_per_type.clamp(1, 10);
    let path = format!(
        "v1/search?q={q}&type=artist,album,track,playlist&limit={limit}&market=from_token"
    );
    let json = web_api_get(session, bearer, &path).await?;

    let mut out = Vec::new();
    if let Some(items) = page_items(&json, "artists") {
        for item in items {
            if let Some(entity) = artist_entity(item) {
                if !entity.name.is_empty() {
                    out.push(entity);
                }
            }
        }
    }
    if let Some(items) = page_items(&json, "albums") {
        for item in items {
            if let Some(entity) = album_entity(item) {
                if !entity.name.is_empty() {
                    out.push(entity);
                }
            }
        }
    }
    if let Some(items) = page_items(&json, "tracks") {
        for item in items {
            if let Some(entity) = track_entity(item) {
                if !entity.name.is_empty() {
                    out.push(entity);
                }
            }
        }
    }
    if let Some(items) = page_items(&json, "playlists") {
        for item in items {
            if let Some(entity) = playlist_entity(item) {
                if !entity.name.is_empty() {
                    out.push(entity);
                }
            }
        }
    }

    log::info!("web client search {:?}: {} result(s)", query, out.len());
    Ok(out)
}
