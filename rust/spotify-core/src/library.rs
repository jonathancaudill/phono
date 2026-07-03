//! Daily Mix discovery via librespot spclient context-resolve (hybrid path).

use std::collections::{HashMap, HashSet};

use librespot::core::{Session, SpotifyUri};

use crate::SpotifyError;

#[derive(uniffi::Record, Clone)]
pub struct EntityInfo {
    pub entity_type: String,
    pub id: String,
    pub uri: String,
    pub name: String,
    pub subtitle: String,
    pub art_url: Option<String>,
}

impl super::EngineShared {
    pub fn daily_mixes(&self) -> Result<Vec<EntityInfo>, SpotifyError> {
        let session = self.session_or_err()?;
        let handle = self.runtime.handle().clone();
        let session = session.clone();
        handle
            .block_on(async move { fetch_daily_mix_playlists(&session).await })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }
}

async fn fetch_daily_mix_playlists(session: &Session) -> Result<Vec<EntityInfo>, librespot::core::Error> {
    let mut playlists = Vec::new();
    let mut seen = HashSet::new();

    for query in ["Daily+Mix", "Discover+Weekly", "Release+Radar", "Made+For+You"] {
        let uri = format!("spotify:search:{query}");
        let entities = search_context_playlists(session, &uri, 20).await?;
        for entity in entities {
            if entity.entity_type != "playlist" {
                continue;
            }
            let name_lower = entity.name.to_lowercase();
            let is_daily = name_lower.contains("daily mix")
                || name_lower.contains("discover weekly")
                || name_lower.contains("release radar")
                || name_lower.contains("made for you");
            if !is_daily {
                continue;
            }
            if seen.insert(entity.uri.clone()) {
                playlists.push(entity);
            }
        }
    }

  Ok(playlists)
}

async fn search_context_playlists(
    session: &Session,
    context_uri: &str,
    limit: u32,
) -> Result<Vec<EntityInfo>, librespot::core::Error> {
    let ctx = session.spclient().get_context(context_uri).await?;
    let mut entities = Vec::new();
    let mut pages_to_fetch: Vec<String> = Vec::new();

    for page in &ctx.pages {
        collect_page_entities(session, page, &mut entities).await?;
        if let Some(ref next) = page.next_page_url {
            pages_to_fetch.push(next.clone());
        }
    }

    while let Some(url) = pages_to_fetch.pop() {
        let page = fetch_context_page(session, &url).await?;
        collect_page_entities(session, &page, &mut entities).await?;
        if let Some(ref next) = page.next_page_url {
            pages_to_fetch.push(next.clone());
        }
    }

    entities.truncate(limit as usize);
    Ok(entities)
}

async fn collect_page_entities(
    session: &Session,
    page: &librespot::protocol::context_page::ContextPage,
    entities: &mut Vec<EntityInfo>,
) -> Result<(), librespot::core::Error> {
    for track in &page.tracks {
        let Some(uri) = track.uri.as_deref() else { continue };
        let base = uri.split('?').next().unwrap_or(uri);
        if let Ok(mut entity) = entity_from_context_track(track, &page.metadata) {
            if entity.entity_type == "playlist" || base.starts_with("spotify:playlist:") {
                entity.entity_type = "playlist".into();
                enrich_playlist_entity(session, base, &mut entity).await?;
                entities.push(entity);
            }
        }
    }
    Ok(())
}

async fn enrich_playlist_entity(
    session: &Session,
    base: &str,
    entity: &mut EntityInfo,
) -> Result<(), librespot::core::Error> {
    if !entity.name.is_empty() {
        return Ok(());
    }
    if let Ok(uri) = SpotifyUri::from_uri(base) {
        if let Ok(ctx) = session.spclient().get_context(base).await {
            if let Some(page) = ctx.pages.first() {
                if let Some(name) = page.metadata.get("title").or(page.metadata.get("name")) {
                    entity.name = name.clone();
                }
            }
        }
        let _ = uri;
    }
    Ok(())
}

async fn fetch_context_page(
    session: &Session,
    page_url: &str,
) -> Result<librespot::protocol::context_page::ContextPage, librespot::core::Error> {
    let body = session.spclient().get_next_page(page_url).await?;
    let ctx_json = String::from_utf8(body.to_vec())?;
    if let Ok(page) =
        protobuf_json_mapping::parse_from_str::<librespot::protocol::context_page::ContextPage>(
            &ctx_json,
        )
    {
        return Ok(page);
    }
    let ctx = protobuf_json_mapping::parse_from_str::<librespot::protocol::context::Context>(
        &ctx_json,
    )?;
    Ok(ctx
        .pages
        .into_iter()
        .next()
        .unwrap_or_default())
}

fn entity_from_context_track(
    track: &librespot::protocol::context_track::ContextTrack,
    page_md: &HashMap<String, String>,
) -> Result<EntityInfo, librespot::core::Error> {
    let uri = track.uri.as_deref().unwrap_or_default();
    let base = uri.split('?').next().unwrap_or(uri);
    let entity_type = base
        .split(':')
        .nth(1)
        .unwrap_or("unknown")
        .to_string();
    let mut md = page_md.clone();
    for (k, v) in &track.metadata {
        md.insert(k.clone(), v.clone());
    }
    let name = meta_get(
        &md,
        &["title", "album_title", "artist_name", "name", "track_name"],
    )
    .unwrap_or_default();
    let subtitle = meta_get(&md, &["artist_name", "artists", "album_title"]).unwrap_or_default();
    let art_url = meta_get(
        &md,
        &[
            "image_url",
            "image_xlarge_url",
            "image_large_url",
            "cover_uri",
        ],
    );
    Ok(EntityInfo {
        entity_type,
        id: uri_id(base),
        uri: base.to_string(),
        name,
        subtitle,
        art_url,
    })
}

fn meta_get(md: &HashMap<String, String>, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|k| md.get(*k).cloned())
        .filter(|s| !s.is_empty())
}

fn uri_id(uri: &str) -> String {
    uri.rsplit(':').next().unwrap_or(uri).to_string()
}
