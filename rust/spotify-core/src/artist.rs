//! Native artist detail via librespot extended-metadata (Login5).

use librespot::core::{Session, SpotifyUri};
use librespot::metadata::artist::CountryTopTracks;
use librespot::metadata::{Album, Artist, Metadata, Track};
use librespot::metadata::album::AlbumType;

use crate::context_track_to_info;
use crate::metadata_batch::{fetch_albums_metadata_batch, fetch_tracks_metadata_batch, normalize_entity_uri};
use crate::{SpotifyError, TrackInfo};

#[derive(uniffi::Record, Clone)]
pub struct AlbumSummaryNative {
    pub id: String,
    pub name: String,
    pub uri: String,
    pub image_url: Option<String>,
    pub album_type: String,
}

#[derive(uniffi::Record, Clone)]
pub struct ArtistDetailBundle {
    pub id: String,
    pub name: String,
    pub image_url: Option<String>,
    pub genres: Vec<String>,
    pub top_tracks: Vec<TrackInfo>,
    pub albums: Vec<AlbumSummaryNative>,
}

impl super::EngineShared {
    pub fn artist_detail_native(
        &self,
        artist_id: &str,
        album_limit: u32,
        top_track_limit: u32,
    ) -> Result<ArtistDetailBundle, SpotifyError> {
        let session = self.session_or_err()?;
        let handle = self.runtime.handle().clone();
        let artist_id = artist_id.to_string();
        handle
            .block_on(async move {
                fetch_artist_detail(&session, &artist_id, album_limit, top_track_limit).await
            })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }
}

async fn fetch_artist_detail(
    session: &Session,
    artist_id: &str,
    album_limit: u32,
    top_track_limit: u32,
) -> Result<ArtistDetailBundle, librespot::core::Error> {
    let uri = SpotifyUri::from_uri(&format!("spotify:artist:{artist_id}"))?;
    let artist = Artist::get(session, &uri).await?;

    let image_url = artist
        .portraits
        .iter()
        .max_by_key(|img| img.width)
        .map(|img| format!("https://i.scdn.co/image/{}", img.id));

    let top_tracks =
        fetch_artist_top_tracks(session, &artist, artist_id, top_track_limit).await?;

    let album_uris: Vec<SpotifyUri> = artist
        .albums
        .current_releases()
        .chain(artist.singles.current_releases())
        .take(album_limit as usize)
        .cloned()
        .collect();

    let fetched_albums = fetch_albums_metadata_batch(session, &album_uris).await?;
    let mut albums = Vec::new();
    for album_uri in album_uris {
        let key = normalize_entity_uri(&album_uri.to_uri().unwrap_or_default());
        if let Some(album) = fetched_albums.get(&key) {
            albums.push(album_to_summary(album));
        }
    }

    Ok(ArtistDetailBundle {
        id: artist_id.to_string(),
        name: artist.name,
        image_url,
        genres: Vec::new(),
        top_tracks,
        albums,
    })
}

/// Popular tracks: context-resolve page 1 (Spotify artist view), then extended-metadata buckets.
async fn fetch_artist_top_tracks(
    session: &Session,
    artist: &Artist,
    artist_id: &str,
    limit: u32,
) -> Result<Vec<TrackInfo>, librespot::core::Error> {
    let mut tracks = fetch_top_tracks_via_context(session, artist_id, limit).await?;
    if tracks.is_empty() {
        tracks = fetch_top_tracks_via_metadata(session, &artist.top_tracks, limit).await?;
    } else if tracks.iter().any(|t| t.title.is_empty()) {
        enrich_track_infos(session, &mut tracks).await?;
    }
    Ok(tracks)
}

/// spclient context-resolve returns page 1 as the artist's most popular tracks.
async fn fetch_top_tracks_via_context(
    session: &Session,
    artist_id: &str,
    limit: u32,
) -> Result<Vec<TrackInfo>, librespot::core::Error> {
    let uri = format!("spotify:artist:{artist_id}");
    let ctx = session.spclient().get_context(&uri).await?;
    let mut out = Vec::new();
    let Some(page) = ctx.pages.first() else {
        return Ok(out);
    };
    for track in &page.tracks {
        if let Some(info) = context_track_to_info(track, &page.metadata) {
            out.push(info);
            if out.len() >= limit as usize {
                break;
            }
        }
    }
    log::info!(
        "artist top tracks via context-resolve: {} tracks for {artist_id}",
        out.len()
    );
    Ok(out)
}

async fn fetch_top_tracks_via_metadata(
    session: &Session,
    top_tracks: &CountryTopTracks,
    limit: u32,
) -> Result<Vec<TrackInfo>, librespot::core::Error> {
    let top_uris = resolve_top_track_uris(session, top_tracks, limit);
    if top_uris.is_empty() {
        return Ok(Vec::new());
    }
    let fetched_tracks = fetch_tracks_metadata_batch(session, &top_uris).await?;
    let mut out = Vec::new();
    for track_uri in top_uris {
        let key = normalize_entity_uri(&track_uri.to_uri().unwrap_or_default());
        if let Some(track) = fetched_tracks.get(&key) {
            out.push(track_to_info(track));
        }
    }
    log::info!(
        "artist top tracks via extended-metadata: {} tracks",
        out.len()
    );
    Ok(out)
}

fn resolve_top_track_uris(
    session: &Session,
    top_tracks: &CountryTopTracks,
    limit: u32,
) -> Vec<SpotifyUri> {
    let country = session.country();
    for code in [country.as_str(), ""] {
        let tracks = top_tracks.for_country(code);
        if !tracks.is_empty() {
            return tracks.iter().take(limit as usize).cloned().collect();
        }
    }
    top_tracks
        .iter()
        .find(|bucket| !bucket.tracks.is_empty())
        .map(|bucket| bucket.tracks.iter().take(limit as usize).cloned().collect())
        .unwrap_or_default()
}

async fn enrich_track_infos(
    session: &Session,
    tracks: &mut [TrackInfo],
) -> Result<(), librespot::core::Error> {
    let mut pending: Vec<(usize, SpotifyUri)> = Vec::new();
    for (i, track) in tracks.iter().enumerate() {
        if !track.title.is_empty() {
            continue;
        }
        let normalized = normalize_entity_uri(&track.uri);
        let Ok(uri) = SpotifyUri::from_uri(&normalized) else {
            continue;
        };
        pending.push((i, uri));
    }
    if pending.is_empty() {
        return Ok(());
    }

    let uris: Vec<SpotifyUri> = pending.iter().map(|(_, uri)| uri.clone()).collect();
    let fetched = fetch_tracks_metadata_batch(session, &uris).await?;

    for (idx, uri) in pending {
        let key = normalize_entity_uri(&uri.to_uri().unwrap_or_default());
        let Some(meta) = fetched.get(&key) else {
            continue;
        };
        tracks[idx] = track_to_info(meta);
    }
    Ok(())
}

fn track_to_info(track: &Track) -> TrackInfo {
    TrackInfo {
        uri: track.id.to_uri().unwrap_or_default(),
        title: track.name.clone(),
        artists: track
            .artists
            .iter()
            .map(|a| a.name.clone())
            .collect::<Vec<_>>()
            .join(", "),
        album: track.album.name.clone(),
        duration_ms: track.duration as i64,
        art_url: track
            .album
            .covers
            .iter()
            .max_by_key(|c| c.width)
            .map(|c| format!("https://i.scdn.co/image/{}", c.id)),
    }
}

fn album_to_summary(album: &Album) -> AlbumSummaryNative {
    let uri = album.id.to_uri().unwrap_or_default();
    let id = uri.rsplit(':').next().unwrap_or("").to_string();
    AlbumSummaryNative {
        id,
        name: album.name.clone(),
        uri,
        image_url: album
            .covers
            .iter()
            .max_by_key(|c| c.width)
            .map(|c| format!("https://i.scdn.co/image/{}", c.id)),
        album_type: match album.album_type {
            AlbumType::ALBUM => "album",
            AlbumType::SINGLE => "single",
            AlbumType::COMPILATION => "compilation",
            _ => "album",
        }
        .to_string(),
    }
}
