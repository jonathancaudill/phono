//! Desktop / Web Player API (psst model): Login5 bearer on api.spotify.com and
//! api-partner pathfinder — via reqwest, not librespot's rate-limited http_client.

use librespot::core::Session;

use super::{EntityInfo, SavedAlbumInfo};

const SPOTIFY_SEMVER: &str = "1.2.52.442";
const USER_AGENT: &str = "Spotify/1.2.52.442 Linux/0 (LightPhone/0.1.0)";

const SEARCH_DESKTOP_HASHES: &[&str] = &[
    "75bbf6bfcfdf85b8fc828417bfad92b7cd66bf7f556d85670f4da8292373ebec",
    "0dff51c99e552b992377a2a6f40d213dc42b62db86ca0bcf16cf3934aec1aae6",
];

fn encode_query(query: &str) -> String {
    url::form_urlencoded::byte_serialize(query.as_bytes()).collect()
}

fn http() -> &'static reqwest::Client {
    crate::auth::http_client()
}

async fn login5_bearer(session: &Session) -> Result<String, librespot::core::Error> {
    let token = session.login5().auth_token().await?;
    Ok(token.access_token)
}

async fn client_token(session: &Session) -> Option<String> {
    session.spclient().client_token().await.ok()
}

async fn web_api_get(
    session: &Session,
    path_query: &str,
) -> Result<serde_json::Value, librespot::core::Error> {
    let bearer = login5_bearer(session).await?;
    let url = format!("https://api.spotify.com/{path_query}");
    let mut req = http()
        .get(&url)
        .header("Accept", "application/json")
        .header("Authorization", format!("Bearer {bearer}"))
        .header("User-Agent", USER_AGENT)
        .header("app-platform", "WebPlayer")
        .header("spotify-app-version", SPOTIFY_SEMVER);

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

async fn pathfinder_post(
    session: &Session,
    operation: &str,
    variables: serde_json::Value,
    hash: &str,
) -> Result<serde_json::Value, librespot::core::Error> {
    let bearer = login5_bearer(session).await?;
    let body = serde_json::json!({
        "operationName": operation,
        "variables": variables,
        "extensions": {
            "persistedQuery": {
                "version": 1,
                "sha256Hash": hash,
            }
        }
    });

    let mut req = http()
        .post("https://api-partner.spotify.com/pathfinder/v2/query")
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("Authorization", format!("Bearer {bearer}"))
        .header("User-Agent", USER_AGENT)
        .header("app-platform", "WebPlayer")
        .header("spotify-app-version", SPOTIFY_SEMVER);

    if let Some(ct) = client_token(session).await {
        req = req.header("client-token", ct);
    }

    let resp = req.json(&body).send().await.map_err(|e| {
        librespot::core::Error::unavailable(format!("pathfinder POST failed: {e}"))
    })?;
    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        let snippet: String = text.chars().take(200).collect();
        log::warn!("pathfinder {operation} {status}: {snippet}");
        return Err(librespot::core::Error::unavailable(format!(
            "pathfinder {status}"
        )));
    }
    let json: serde_json::Value = serde_json::from_str(&text).map_err(|e| {
        librespot::core::Error::failed_precondition(format!("pathfinder json: {e}"))
    })?;
    if json.get("errors").is_some() {
        log::warn!("pathfinder {operation} errors: {json}");
        return Err(librespot::core::Error::unavailable(
            "pathfinder returned errors",
        ));
    }
    Ok(json)
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

fn parse_pathfinder_search(json: &serde_json::Value, limit_per_type: u32) -> Vec<EntityInfo> {
    let search = json
        .pointer("/data/search")
        .or_else(|| json.pointer("/data/searchV2"))
        .unwrap_or(json);
    let limit = limit_per_type as usize;
    let mut out = Vec::new();

    let mut push_items = |key: &str, map: fn(&serde_json::Value) -> Option<EntityInfo>| {
        if let Some(items) = search.get(key).and_then(|v| v.get("items")).and_then(|i| i.as_array())
        {
            for item in items.iter().take(limit) {
                if let Some(entity) = map(item) {
                    if !entity.name.is_empty() {
                        out.push(entity);
                    }
                }
            }
        }
    };

    push_items("artists", artist_entity);
    push_items("albums", album_entity);
    push_items("tracks", track_entity);
    push_items("playlists", playlist_entity);
    out
}

/// psst: GET v1/me/albums?market=from_token (Login5 bearer via reqwest).
pub async fn fetch_saved_albums(
    session: &Session,
    limit: u32,
) -> Result<Vec<SavedAlbumInfo>, librespot::core::Error> {
    let limit = limit.clamp(1, 500);
    let mut out = Vec::new();
    let mut offset = 0u32;
    let page_size = 50u32;

    while out.len() < limit as usize {
        let take = page_size.min(limit - out.len() as u32);
        let path = format!("v1/me/albums?limit={take}&offset={offset}&market=from_token");
        let json = web_api_get(session, &path).await?;
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

    if out.is_empty() {
        Err(librespot::core::Error::unavailable(
            "web client saved albums empty",
        ))
    } else {
        log::info!("web client saved albums: {} item(s)", out.len());
        Ok(out)
    }
}

pub async fn search_catalog_pathfinder(
    session: &Session,
    query: &str,
    limit_per_type: u32,
) -> Result<Vec<EntityInfo>, librespot::core::Error> {
    let trimmed = query.trim();
    if trimmed.is_empty() {
        return Ok(vec![]);
    }
    let limit = limit_per_type.clamp(1, 10);
    let variables = serde_json::json!({
        "searchTerm": trimmed,
        "offset": 0,
        "limit": limit,
        "numberOfTopResults": limit,
        "includeAudiobooks": false,
    });

    for hash in SEARCH_DESKTOP_HASHES {
        match pathfinder_post(session, "searchDesktop", variables.clone(), hash).await {
            Ok(json) => {
                let items = parse_pathfinder_search(&json, limit);
                if !items.is_empty() {
                    log::info!(
                        "pathfinder search {:?}: {} result(s) (hash {})",
                        trimmed,
                        items.len(),
                        &hash[..8]
                    );
                    return Ok(items);
                }
            }
            Err(e) => log::warn!("pathfinder searchDesktop hash {} failed: {e}", &hash[..8]),
        }
    }

    Err(librespot::core::Error::unavailable(
        "pathfinder search unavailable",
    ))
}

/// psst: GET v1/search?type=artist,album,track,playlist&marker=from_token
pub async fn search_catalog_web(
    session: &Session,
    query: &str,
    limit_per_type: u32,
) -> Result<Vec<EntityInfo>, librespot::core::Error> {
    let q = encode_query(query.trim());
    if q.is_empty() {
        return Ok(vec![]);
    }
    let limit = limit_per_type.clamp(1, 10);
    let path = format!(
        "v1/search?q={q}&type=artist,album,track,playlist&limit={limit}&marker=from_token"
    );
    let json = web_api_get(session, &path).await?;

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

    if out.is_empty() {
        Err(librespot::core::Error::unavailable(
            "web client search empty",
        ))
    } else {
        log::info!("web client search {:?}: {} result(s)", query, out.len());
        Ok(out)
    }
}
