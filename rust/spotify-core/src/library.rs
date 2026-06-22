//! Library, search, and browse metadata via librespot spclient and the desktop
//! Web Client API (Login5 on api.spotify.com/v1 — psst model).

#[path = "web_client.rs"]
mod web_client;

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use librespot::core::{Session, SpotifyUri};
use librespot::metadata::{Album, Artist, Metadata, Track};

use crate::SpotifyError;

const CACHE_TTL: Duration = Duration::from_secs(45);
const SEARCH_DEBOUNCE: Duration = Duration::from_millis(350);

#[derive(uniffi::Record, Clone)]
pub struct EntityInfo {
    pub entity_type: String,
    pub id: String,
    pub uri: String,
    pub name: String,
    pub subtitle: String,
    pub art_url: Option<String>,
}

#[derive(uniffi::Record, Clone)]
pub struct SavedAlbumInfo {
    pub album: EntityInfo,
    pub added_at_ms: Option<i64>,
}

#[derive(uniffi::Record, Clone)]
pub struct AlbumDetailInfo {
    pub album: EntityInfo,
    pub tracks: Vec<crate::TrackInfo>,
    pub is_saved: bool,
}

#[derive(uniffi::Record, Clone)]
pub struct ArtistDetailInfo {
    pub artist: EntityInfo,
    pub top_tracks: Vec<crate::TrackInfo>,
    pub albums: Vec<EntityInfo>,
}

pub struct LibraryCache {
    saved_albums: Mutex<Option<(Instant, Vec<SavedAlbumInfo>)>>,
    last_search: Mutex<Option<(String, Instant)>>,
    search_results: Mutex<HashMap<String, (Instant, Vec<EntityInfo>)>>,
}

impl LibraryCache {
    pub fn new() -> Self {
        Self {
            saved_albums: Mutex::new(None),
            last_search: Mutex::new(None),
            search_results: Mutex::new(HashMap::new()),
        }
    }

    pub fn invalidate_saved_albums(&self) {
        *self.saved_albums.lock().unwrap() = None;
    }
}

impl super::EngineShared {
    pub fn saved_albums(&self, limit: u32) -> Result<Vec<SavedAlbumInfo>, SpotifyError> {
        if let Some((at, items)) = self.library_cache.saved_albums.lock().unwrap().as_ref() {
            if at.elapsed() < CACHE_TTL {
                let n = limit.min(items.len() as u32) as usize;
                return Ok(items.iter().take(n).cloned().collect());
            }
        }
        let session = self.session_or_err()?;
        let limit = limit.clamp(1, 50);
        let handle = self.runtime.handle().clone();
        let session_for_fetch = session.clone();
        let items = handle
            .block_on(async move {
                match web_client::fetch_saved_albums(&session_for_fetch, limit).await {
                    Ok(items) => Ok(items),
                    Err(e) => {
                        log::warn!("web client saved albums failed ({e}); trying spclient");
                        let items = fetch_saved_albums_spclient(&session_for_fetch, limit).await?;
                        enrich_saved_albums(&session_for_fetch, items).await
                    }
                }
            })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })?;
        if items.is_empty() {
            return Err(SpotifyError::Network {
                msg: "no saved albums found".into(),
            });
        }
        *self.library_cache.saved_albums.lock().unwrap() = Some((Instant::now(), items.clone()));
        Ok(items)
    }

    pub fn album_detail(&self, album_id: &str) -> Result<AlbumDetailInfo, SpotifyError> {
        let session = self.session_or_err()?;
        let album_uri = format!("spotify:album:{album_id}");
        let handle = self.runtime.handle().clone();
        handle
            .block_on(async {
                let ctx_tracks =
                    load_context_track_entries(&session, &album_uri, 200).await?;
                let album = fetch_album_entity(&session, &album_uri).await?;
                let mut tracks = Vec::new();
                for entry in ctx_tracks {
                    if let Ok(info) = context_entry_to_track_info(&session, &entry).await {
                        tracks.push(info);
                    }
                }
                if tracks.is_empty() {
                    tracks = album_tracks_from_metadata(&session, &album_uri).await?;
                }
                let is_saved = check_contains(&session, &album_uri).await.unwrap_or(false);
                Ok(AlbumDetailInfo {
                    album,
                    tracks,
                    is_saved,
                })
            })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }

    pub fn is_album_saved(&self, album_id: &str) -> Result<bool, SpotifyError> {
        let session = self.session_or_err()?;
        let uri = format!("spotify:album:{album_id}");
        self.runtime
            .handle()
            .block_on(check_contains(&session, &uri))
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }

    pub fn save_album(&self, album_id: &str) -> Result<(), SpotifyError> {
        let uri = format!("spotify:album:{album_id}");
        self.collection_add(&uri)?;
        self.library_cache.invalidate_saved_albums();
        Ok(())
    }

    pub fn remove_album(&self, album_id: &str) -> Result<(), SpotifyError> {
        let uri = format!("spotify:album:{album_id}");
        self.collection_remove(&uri)?;
        self.library_cache.invalidate_saved_albums();
        Ok(())
    }

    pub fn artist_detail(&self, artist_id: &str) -> Result<ArtistDetailInfo, SpotifyError> {
        let session = self.session_or_err()?;
        let artist_uri = format!("spotify:artist:{artist_id}");
        let handle = self.runtime.handle().clone();
        handle
            .block_on(async {
                let artist_entity = fetch_artist_entity(&session, &artist_uri).await?;
                let ctx = session.spclient().get_context(&artist_uri).await?;
                let mut top_tracks = Vec::new();
                let mut albums = Vec::new();
                for page in &ctx.pages {
                    for track in &page.tracks {
                        let Some(uri) = track.uri.as_deref() else { continue };
                        if uri.starts_with("spotify:track:") {
                            if let Ok(info) = context_entry_to_track_info(&session, track).await {
                                top_tracks.push(info);
                            }
                        } else if uri.starts_with("spotify:album:") {
                            if let Ok(entity) = entity_from_context_track(track, &page.metadata) {
                                albums.push(entity);
                            }
                        }
                    }
                    if let Some(ref next) = page.next_page_url {
                        if let Ok(page_ctx) = fetch_context_page(&session, next).await {
                            for track in &page_ctx.tracks {
                                let Some(uri) = track.uri.as_deref() else { continue };
                                if uri.starts_with("spotify:album:") {
                                    if let Ok(entity) =
                                        entity_from_context_track(track, &page_ctx.metadata)
                                    {
                                        albums.push(entity);
                                    }
                                }
                            }
                        }
                    }
                }
                if top_tracks.is_empty() {
                    if let Ok(artist) = Artist::get(&session, &SpotifyUri::from_uri(&artist_uri)?).await
                    {
                        let country = session.country();
                        for uri in artist.top_tracks.for_country(&country).iter() {
                            if let Ok(track) = Track::get(&session, uri).await {
                                top_tracks.push(track_to_info(&track));
                            }
                        }
                    }
                }
                if albums.is_empty() {
                    if let Ok(artist) = Artist::get(&session, &SpotifyUri::from_uri(&artist_uri)?).await
                    {
                        for uri in artist.albums_current() {
                            if let Ok(album) = Album::get(&session, uri).await {
                                albums.push(album_to_entity(&album));
                            }
                        }
                    }
                }
                Ok(ArtistDetailInfo {
                    artist: artist_entity,
                    top_tracks,
                    albums,
                })
            })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }

    pub fn search_catalog(&self, query: &str, limit: u32) -> Result<Vec<EntityInfo>, SpotifyError> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(vec![]);
        }
        {
            let mut last = self.library_cache.last_search.lock().unwrap();
            if let Some((prev_q, at)) = last.as_ref() {
                if prev_q == q && at.elapsed() < SEARCH_DEBOUNCE {
                    if let Some((cached_at, items)) =
                        self.library_cache.search_results.lock().unwrap().get(q)
                    {
                        if cached_at.elapsed() < CACHE_TTL {
                            let n = limit.min(items.len() as u32) as usize;
                            return Ok(items.iter().take(n).cloned().collect());
                        }
                    }
                }
            }
            *last = Some((q.to_string(), Instant::now()));
        }
        if let Some((at, items)) = self.library_cache.search_results.lock().unwrap().get(q) {
            if at.elapsed() < CACHE_TTL {
                let n = limit.min(items.len() as u32) as usize;
                return Ok(items.iter().take(n).cloned().collect());
            }
        }
        let session = self.session_or_err()?;
        let search_uri = format!("spotify:search:{}", q.replace(' ', "+"));
        let limit = limit.clamp(1, 50);
        let limit_per_type = (limit / 4).clamp(1, 10);
        let handle = self.runtime.handle().clone();
        let session_for_search = session.clone();
        let items = handle
            .block_on(async move {
                match web_client::search_catalog_pathfinder(&session_for_search, q, limit_per_type)
                    .await
                {
                    Ok(items) => Ok(items),
                    Err(e) => {
                        log::warn!("pathfinder search failed ({e}); trying web v1/search");
                        match web_client::search_catalog_web(
                            &session_for_search,
                            q,
                            limit_per_type,
                        )
                        .await
                        {
                            Ok(items) => Ok(items),
                            Err(e2) => {
                                log::warn!("web search failed ({e2}); trying context-resolve");
                                parse_search_context(&session_for_search, &search_uri, limit).await
                            }
                        }
                    }
                }
            })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })?;
        self.library_cache
            .search_results
            .lock()
            .unwrap()
            .insert(q.to_string(), (Instant::now(), items.clone()));
        Ok(items)
    }

    pub fn is_track_saved(&self, uri: &str) -> Result<bool, SpotifyError> {
        let session = self.session_or_err()?;
        let normalized = normalize_uri(uri);
        self.runtime
            .handle()
            .block_on(check_contains(&session, &normalized))
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }

    pub fn save_track(&self, uri: &str) -> Result<(), SpotifyError> {
        let normalized = normalize_uri(uri);
        self.collection_add(&normalized)?;
        Ok(())
    }

    pub fn remove_track(&self, uri: &str) -> Result<(), SpotifyError> {
        let normalized = normalize_uri(uri);
        self.collection_remove(&normalized)?;
        Ok(())
    }

    fn collection_add(&self, uri: &str) -> Result<(), SpotifyError> {
        let session = self.session_or_err()?;
        self.runtime
            .handle()
            .block_on(collection_mutate(&session, uri, true))
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }

    fn collection_remove(&self, uri: &str) -> Result<(), SpotifyError> {
        let session = self.session_or_err()?;
        self.runtime
            .handle()
            .block_on(collection_mutate(&session, uri, false))
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }
}

fn normalize_uri(uri: &str) -> String {
    let base = uri.split('?').next().unwrap_or(uri).trim();
    if base.starts_with("spotify:") {
        base.to_string()
    } else {
        format!("spotify:track:{base}")
    }
}

fn uri_id(uri: &str) -> String {
    uri.rsplit(':').next().unwrap_or(uri).to_string()
}

fn spotify_uri_id(uri: &SpotifyUri) -> String {
    uri.to_uri()
        .ok()
        .map(|s| uri_id(&s))
        .unwrap_or_default()
}

fn album_cover_url(covers: &librespot::metadata::image::Images) -> Option<String> {
    covers
        .iter()
        .max_by_key(|c| c.width)
        .map(|c| format!("https://i.scdn.co/image/{}", c.id))
}

fn track_to_info(track: &Track) -> crate::TrackInfo {
    crate::TrackInfo {
        uri: track.id.to_uri().unwrap_or_default(),
        title: track.name.clone(),
        artists: track.artists.iter().map(|a| a.name.clone()).collect::<Vec<_>>().join(", "),
        album: track.album.name.clone(),
        duration_ms: track.duration as i64,
        art_url: album_cover_url(&track.album.covers),
    }
}

fn album_to_entity(album: &Album) -> EntityInfo {
    EntityInfo {
        entity_type: "album".into(),
        id: spotify_uri_id(&album.id),
        uri: album.id.to_uri().unwrap_or_default(),
        name: album.name.clone(),
        subtitle: album.artists.iter().map(|a| a.name.clone()).collect::<Vec<_>>().join(", "),
        art_url: album_cover_url(&album.covers),
    }
}

async fn fetch_album_entity(
    session: &Session,
    album_uri: &str,
) -> Result<EntityInfo, librespot::core::Error> {
    let uri = SpotifyUri::from_uri(album_uri)?;
    let album = Album::get(session, &uri).await?;
    Ok(album_to_entity(&album))
}

async fn fetch_artist_entity(
    session: &Session,
    artist_uri: &str,
) -> Result<EntityInfo, librespot::core::Error> {
    let uri = SpotifyUri::from_uri(artist_uri)?;
    let artist = Artist::get(session, &uri).await?;
    Ok(EntityInfo {
        entity_type: "artist".into(),
        id: spotify_uri_id(&artist.id),
        uri: artist.id.to_uri().unwrap_or_default(),
        name: artist.name.clone(),
        subtitle: "Artist".into(),
        art_url: album_cover_url(&artist.portraits),
    })
}

async fn album_tracks_from_metadata(
    session: &Session,
    album_uri: &str,
) -> Result<Vec<crate::TrackInfo>, librespot::core::Error> {
    let uri = SpotifyUri::from_uri(album_uri)?;
    let album = Album::get(session, &uri).await?;
    let mut out = Vec::new();
    for track_uri in album.tracks() {
        if let Ok(track) = Track::get(session, track_uri).await {
            out.push(track_to_info(&track));
        }
    }
    Ok(out)
}

async fn load_context_track_entries(
    session: &Session,
    context_uri: &str,
    limit: u32,
) -> Result<Vec<librespot::protocol::context_track::ContextTrack>, librespot::core::Error> {
    let ctx = session.spclient().get_context(context_uri).await?;
    let mut out = Vec::new();
    let mut pages_to_fetch: Vec<String> = Vec::new();
    for page in &ctx.pages {
        out.extend(page.tracks.clone());
        if let Some(ref next) = page.next_page_url {
            pages_to_fetch.push(next.clone());
        }
    }
    while let Some(url) = pages_to_fetch.pop() {
        if out.len() >= limit as usize {
            break;
        }
        let page = fetch_context_page(session, &url).await?;
        out.extend(page.tracks.clone());
        if let Some(ref next) = page.next_page_url {
            pages_to_fetch.push(next.clone());
        }
    }
    out.truncate(limit as usize);
    Ok(out)
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

async fn context_entry_to_track_info(
    session: &Session,
    track: &librespot::protocol::context_track::ContextTrack,
) -> Result<crate::TrackInfo, librespot::core::Error> {
    let uri = track.uri.as_deref().unwrap_or_default();
    let base = uri.split('?').next().unwrap_or(uri);
    if !base.starts_with("spotify:track:") {
        return Err(librespot::core::Error::invalid_argument("not a track uri"));
    }
    let mut info = super::context_track_to_info(track, &HashMap::new())
        .unwrap_or(crate::TrackInfo {
            uri: base.to_string(),
            title: String::new(),
            artists: String::new(),
            album: String::new(),
            duration_ms: 0,
            art_url: None,
        });
    if info.title.is_empty() {
        let spotify_uri = SpotifyUri::from_uri(base)?;
        if let Ok(meta) = super::get_track_metadata_bearer(session, "", &spotify_uri).await {
            info.title = meta.name;
            info.artists = meta.artists.iter().map(|a| a.name.clone()).collect::<Vec<_>>().join(", ");
            info.album = meta.album.name;
            info.duration_ms = meta.duration as i64;
            info.art_url = album_cover_url(&meta.album.covers);
        }
    }
    Ok(info)
}

fn meta_get(md: &HashMap<String, String>, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|k| md.get(*k).cloned())
        .filter(|s| !s.is_empty())
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

async fn parse_search_context(
    session: &Session,
    search_uri: &str,
    limit: u32,
) -> Result<Vec<EntityInfo>, librespot::core::Error> {
    let ctx = session.spclient().get_context(search_uri).await?;
    let mut by_type: HashMap<String, Vec<EntityInfo>> = HashMap::new();
    let mut seen = HashMap::new();
    let mut pages_to_fetch: Vec<String> = Vec::new();

    for page in &ctx.pages {
        collect_search_page_entities(session, page, &mut by_type, &mut seen).await?;
        if let Some(ref next) = page.next_page_url {
            pages_to_fetch.push(next.clone());
        }
    }

    while let Some(url) = pages_to_fetch.pop() {
        let page = fetch_context_page(session, &url).await?;
        collect_search_page_entities(session, &page, &mut by_type, &mut seen).await?;
        if let Some(ref next) = page.next_page_url {
            pages_to_fetch.push(next.clone());
        }
    }

    Ok(balance_search_results(by_type, limit))
}

async fn collect_search_page_entities(
    session: &Session,
    page: &librespot::protocol::context_page::ContextPage,
    by_type: &mut HashMap<String, Vec<EntityInfo>>,
    seen: &mut HashMap<String, ()>,
) -> Result<(), librespot::core::Error> {
    for track in &page.tracks {
        let Some(uri) = track.uri.as_deref() else { continue };
        let base = uri.split('?').next().unwrap_or(uri);
        if seen.contains_key(base) {
            continue;
        }
        if let Ok(mut entity) = entity_from_context_track(track, &page.metadata) {
            enrich_search_entity(session, track, base, &mut entity).await?;
            seen.insert(base.to_string(), ());
            by_type.entry(entity.entity_type.clone()).or_default().push(entity);
        }
    }
    Ok(())
}

async fn enrich_search_entity(
    session: &Session,
    track: &librespot::protocol::context_track::ContextTrack,
    base: &str,
    entity: &mut EntityInfo,
) -> Result<(), librespot::core::Error> {
    if base.starts_with("spotify:album:") {
        if let Ok(album) = Album::get(session, &SpotifyUri::from_uri(base)?).await {
            entity.name = album.name.clone();
            entity.subtitle = album
                .artists
                .iter()
                .map(|a| a.name.clone())
                .collect::<Vec<_>>()
                .join(", ");
            entity.art_url = album_cover_url(&album.covers);
        }
        return Ok(());
    }
    if !entity.name.is_empty() {
        return Ok(());
    }
    if base.starts_with("spotify:track:") {
        if let Ok(info) = context_entry_to_track_info(session, track).await {
            entity.name = info.title;
            entity.subtitle = info.artists;
            entity.art_url = info.art_url;
        }
        return Ok(());
    }
    if base.starts_with("spotify:artist:") {
        if let Ok(artist) = Artist::get(session, &SpotifyUri::from_uri(base)?).await {
            entity.name = artist.name.clone();
            entity.subtitle = "Artist".into();
            entity.art_url = album_cover_url(&artist.portraits);
        }
    }
    Ok(())
}

fn balance_search_results(by_type: HashMap<String, Vec<EntityInfo>>, limit: u32) -> Vec<EntityInfo> {
    let limit = limit.max(1) as usize;
    let type_order = ["track", "album", "artist", "playlist"];
    let mut per_type = (limit / type_order.len()).max(3);
    let mut items = Vec::new();

    loop {
        let mut added = 0usize;
        for entity_type in type_order {
            if items.len() >= limit {
                return items;
            }
            if let Some(list) = by_type.get(entity_type) {
                let taken = list
                    .iter()
                    .filter(|e| !e.name.is_empty())
                    .filter(|e| !items.iter().any(|i| i.uri == e.uri))
                    .take(per_type)
                    .cloned()
                    .collect::<Vec<_>>();
                added += taken.len();
                items.extend(taken);
            }
        }
        if items.len() >= limit || added == 0 {
            break;
        }
        per_type = per_type.saturating_add(2);
    }

    for list in by_type.values() {
        for entity in list {
            if items.len() >= limit {
                return items;
            }
            if !items.iter().any(|i| i.uri == entity.uri) {
                items.push(entity.clone());
            }
        }
    }

    items.truncate(limit);
    items
}

async fn enrich_saved_albums(
    session: &Session,
    mut items: Vec<SavedAlbumInfo>,
) -> Result<Vec<SavedAlbumInfo>, librespot::core::Error> {
    for saved in &mut items {
        if !saved.album.name.is_empty() {
            continue;
        }
        if let Ok(album) = Album::get(session, &SpotifyUri::from_uri(&saved.album.uri)?).await {
            saved.album.name = album.name.clone();
            saved.album.subtitle = album
                .artists
                .iter()
                .map(|a| a.name.clone())
                .collect::<Vec<_>>()
                .join(", ");
            saved.album.art_url = album_cover_url(&album.covers);
        }
    }
    Ok(items)
}

async fn fetch_saved_albums_spclient(
    session: &Session,
    limit: u32,
) -> Result<Vec<SavedAlbumInfo>, librespot::core::Error> {
    if let Ok(items) = fetch_saved_albums_your_library(session, limit).await {
        return Ok(items);
    }

    if let Ok(items) = fetch_saved_albums_cosmos(session, limit).await {
        return Ok(items);
    }

    // Fallback: some clients expose saved albums as a browse context URI.
    let username = session.username();
    for uri in [
        format!("spotify:user:{username}:saved-albums"),
        format!("spotify:user:{username}:collection:album"),
        format!("spotify:user:{username}:collection:albums"),
    ] {
        if let Ok(ctx) = session.spclient().get_context(&uri).await {
            let items = context_to_saved_albums(&ctx);
            if !items.is_empty() {
                return Ok(items);
            }
        }
    }

    Err(librespot::core::Error::unavailable(
        "spclient saved albums unavailable",
    ))
}

async fn fetch_saved_albums_your_library(
    session: &Session,
    limit: u32,
) -> Result<Vec<SavedAlbumInfo>, librespot::core::Error> {
    let json_body = serde_json::json!({
        "header": {
            "length": limit,
            "filters": { "filter": ["ALBUM"] },
            "sortOrder": { "sortOrder": "RECENTLY_ADDED" }
        }
    })
    .to_string();
    let proto_body = encode_your_library_album_request(limit);

    let endpoints = [
        "/your-library/v1/get",
        "/your-library/v1/yourlibrary",
        "/your-library/v1/root",
        "/your-library-esperanto/v1/yourlibrary",
    ];

    for endpoint in endpoints {
        if let Ok(items) =
            spclient_post_your_library(session, endpoint, &proto_body, &json_body).await
        {
            return Ok(items);
        }
    }

    Err(librespot::core::Error::unavailable(
        "your-library saved albums unavailable",
    ))
}

async fn fetch_saved_albums_cosmos(
    session: &Session,
    limit: u32,
) -> Result<Vec<SavedAlbumInfo>, librespot::core::Error> {
    let endpoints = [
        "/collection-cosmos/v1/album/list",
        "/collection-cosmos/v1/albums",
        "/collection/v1/albums",
        "/cosmos/v1/collection/album/list",
    ];
    for endpoint in endpoints {
        if let Ok(bytes) = spclient_post_protobuf(session, endpoint, &[]).await {
            let items = parse_cosmos_album_list_bytes(&bytes, limit);
            if !items.is_empty() {
                return Ok(items);
            }
        }
    }
    Err(librespot::core::Error::unavailable(
        "collection cosmos saved albums unavailable",
    ))
}

fn encode_your_library_album_request(limit: u32) -> Vec<u8> {
    // YourLibraryRequest { header { length=12, filters { filter=ALBUM(0) } } }
    let filters = vec![0x08u8, 0x00];
    let mut header = Vec::new();
    header.push(0x60);
    encode_varint(limit as u64, &mut header);
    header.push(0x72);
    encode_varint(filters.len() as u64, &mut header);
    header.extend_from_slice(&filters);

    let mut out = Vec::new();
    out.push(0x0a);
    encode_varint(header.len() as u64, &mut out);
    out.extend_from_slice(&header);
    out
}

async fn spclient_post_your_library(
    session: &Session,
    endpoint: &str,
    proto_body: &[u8],
    json_body: &str,
) -> Result<Vec<SavedAlbumInfo>, librespot::core::Error> {
    if let Ok(bytes) = spclient_post_protobuf(session, endpoint, proto_body).await {
        if let Some(items) = parse_your_library_albums_bytes(&bytes) {
            return Ok(items);
        }
    }
    if let Ok(json) = spclient_post_json(session, endpoint, json_body).await {
        return Ok(parse_your_library_albums_json(&json));
    }
    Err(librespot::core::Error::unavailable(
        "your-library request failed",
    ))
}

fn parse_your_library_albums_bytes(body: &[u8]) -> Option<Vec<SavedAlbumInfo>> {
    let text = String::from_utf8(body.to_vec()).ok()?;
    if text.trim_start().starts_with('{') {
        return Some(parse_your_library_albums_json(
            &serde_json::from_str(&text).ok()?,
        ));
    }
    None
}

fn parse_cosmos_album_list_bytes(body: &[u8], limit: u32) -> Vec<SavedAlbumInfo> {
    let text = match std::str::from_utf8(body) {
        Ok(value) if value.trim_start().starts_with('{') => value.to_string(),
        _ => String::from_utf8_lossy(body).into_owned(),
    };
    let mut out = Vec::new();
    let mut seen = HashMap::new();
    for uri in extract_spotify_album_uris(&text) {
        if seen.insert(uri.clone(), ()).is_some() {
            continue;
        }
        out.push(SavedAlbumInfo {
            album: EntityInfo {
                entity_type: "album".into(),
                id: uri_id(&uri),
                uri: uri.clone(),
                name: String::new(),
                subtitle: String::new(),
                art_url: None,
            },
            added_at_ms: None,
        });
        if out.len() >= limit as usize {
            break;
        }
    }
    out
}

fn extract_spotify_album_uris(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut start = 0usize;
    while let Some(idx) = text[start..].find("spotify:album:") {
        let at = start + idx;
        let rest = &text[at..];
        let end = rest
            .find(|c: char| !(c.is_ascii_alphanumeric() || c == ':'))
            .unwrap_or(rest.len());
        let uri = rest[..end].to_string();
        if uri.len() > "spotify:album:".len() {
            out.push(uri);
        }
        start = at + end;
    }
    out
}

fn context_to_saved_albums(
    ctx: &librespot::protocol::context::Context,
) -> Vec<SavedAlbumInfo> {
    let mut out = Vec::new();
    for page in &ctx.pages {
        for track in &page.tracks {
            let Some(uri) = track.uri.as_ref() else { continue };
            if !uri.starts_with("spotify:album:") {
                continue;
            }
            if let Ok(entity) = entity_from_context_track(track, &page.metadata) {
                out.push(SavedAlbumInfo {
                    album: entity,
                    added_at_ms: None,
                });
            }
        }
    }
    out
}

fn parse_your_library_albums_json(value: &serde_json::Value) -> Vec<SavedAlbumInfo> {
    let mut out = Vec::new();
    let entities = value
        .get("entity")
        .or_else(|| value.get("entities"))
        .and_then(|v| v.as_array());
    let Some(entities) = entities else {
        return out;
    };
    for entity in entities {
        let info = entity.get("entityInfo").or_else(|| entity.get("entity_info"));
        let Some(info) = info else { continue };
        let uri = info
            .get("uri")
            .and_then(|v| v.as_str())
            .unwrap_or_default();
        if !uri.starts_with("spotify:album:") {
            continue;
        }
        let name = info
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string();
        let subtitle = entity
            .get("album")
            .and_then(|a| a.get("artistName").or_else(|| a.get("artist_name")))
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string();
        let image_uri = info
            .get("imageUri")
            .or_else(|| info.get("image_uri"))
            .and_then(|v| v.as_str());
        let art_url = image_uri.filter(|s| !s.is_empty()).map(|id| {
            if id.starts_with("http") {
                id.to_string()
            } else {
                format!("https://i.scdn.co/image/{id}")
            }
        });
        let add_time = info
            .get("addTime")
            .or_else(|| info.get("add_time"))
            .and_then(|v| v.as_i64());
        out.push(SavedAlbumInfo {
            album: EntityInfo {
                entity_type: "album".into(),
                id: uri_id(uri),
                uri: uri.to_string(),
                name,
                subtitle,
                art_url,
            },
            added_at_ms: add_time.map(|s| s * 1000),
        });
    }
    out
}

async fn check_contains(
    session: &Session,
    uri: &str,
) -> Result<bool, librespot::core::Error> {
    let body = serde_json::json!({
        "requestedUri": [uri],
        "requested_uri": [uri],
    })
    .to_string();
    let endpoints = [
        "/your-library/v1/contains",
        "/your-library/v1/yourlibrarycontains",
    ];
    for endpoint in endpoints {
        if let Ok(json) = spclient_post_json(session, endpoint, &body).await {
            if let Some(entity) = json
                .get("entity")
                .and_then(|v| v.as_array())
                .and_then(|a| a.first())
            {
                let saved = entity
                    .get("isInLibrary")
                    .or_else(|| entity.get("is_in_library"))
                    .and_then(|v| v.as_bool());
                if let Some(saved) = saved {
                    return Ok(saved);
                }
            }
        }
    }
    Ok(false)
}

async fn collection_mutate(
    session: &Session,
    uri: &str,
    add: bool,
) -> Result<(), librespot::core::Error> {
    let proto_body = encode_string_list_proto(1, &[uri.to_string()]);
    let json_body = serde_json::json!({ "uri": [uri] }).to_string();

    let mercury_paths = if add {
        [
            "hm://collection/add-remove/v1/add",
            "hm://collection/v1/add",
            "hm://cosmos/collection/add",
        ]
    } else {
        [
            "hm://collection/add-remove/v1/remove",
            "hm://collection/v1/remove",
            "hm://cosmos/collection/remove",
        ]
    };
    for path in mercury_paths {
        if mercury_send(session, path, &proto_body).await.is_ok() {
            return Ok(());
        }
    }

    let sp_endpoints = if add {
        [
            "/collection/v1/items/add",
            "/collection-cosmos/v1/add",
            "/cosmos/v1/collection/add",
        ]
    } else {
        [
            "/collection/v1/items/remove",
            "/collection-cosmos/v1/remove",
            "/cosmos/v1/collection/remove",
        ]
    };
    for endpoint in sp_endpoints {
        if spclient_post_protobuf(session, endpoint, &proto_body)
            .await
            .is_ok()
        {
            return Ok(());
        }
        if spclient_post_json(session, endpoint, &json_body)
            .await
            .is_ok()
        {
            return Ok(());
        }
    }
    Err(librespot::core::Error::unavailable(
        "collection add/remove failed",
    ))
}

/// Minimal protobuf encoder for `repeated string` on one field (collection add/remove).
fn encode_string_list_proto(field_number: u32, values: &[String]) -> Vec<u8> {
    let tag = (field_number << 3) | 2;
    let mut out = Vec::new();
    for value in values {
        out.push(tag as u8);
        let bytes = value.as_bytes();
        encode_varint(bytes.len() as u64, &mut out);
        out.extend_from_slice(bytes);
    }
    out
}

fn encode_varint(mut value: u64, out: &mut Vec<u8>) {
    while value > 0x7f {
        out.push((value as u8) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

async fn mercury_send(
    session: &Session,
    uri: &str,
    body: &[u8],
) -> Result<(), librespot::core::Error> {
    let mut sender = session.mercury().sender(uri);
    sender.send(body.to_vec())?;
    sender.flush().await?;
    Ok(())
}

async fn spclient_post_json(
    session: &Session,
    endpoint: &str,
    body: &str,
) -> Result<serde_json::Value, librespot::core::Error> {
    use http::header::{ACCEPT, CONTENT_TYPE};
    use http::{Method, HeaderMap};

    let mut headers = HeaderMap::new();
    headers.insert(ACCEPT, "application/json".parse().unwrap());
    headers.insert(CONTENT_TYPE, "application/json".parse().unwrap());
    let res = session
        .spclient()
        .request(&Method::POST, endpoint, Some(headers), Some(body.as_bytes()))
        .await?;
    let text = String::from_utf8(res.to_vec())?;
    serde_json::from_str(&text).map_err(|e| librespot::core::Error::failed_precondition(e))
}

async fn spclient_post_protobuf(
    session: &Session,
    endpoint: &str,
    body: &[u8],
) -> Result<bytes::Bytes, librespot::core::Error> {
    use http::header::{ACCEPT, CONTENT_TYPE};
    use http::{Method, HeaderMap};

    let mut headers = HeaderMap::new();
    headers.insert(ACCEPT, "application/json".parse().unwrap());
    headers.insert(CONTENT_TYPE, "application/x-protobuf".parse().unwrap());
    session
        .spclient()
        .request(&Method::POST, endpoint, Some(headers), Some(body))
        .await
}
