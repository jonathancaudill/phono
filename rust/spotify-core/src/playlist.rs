//! Native playlist reads and writes via librespot spclient (Login5).

use std::sync::Arc;

use base64::Engine as _;
use librespot::core::{Session, SpotifyId, SpotifyUri};
use librespot::metadata::{Metadata, Playlist};
use librespot::protocol::playlist4_external::{
    self, Add, CreateListReply, Item, ListAttributesPartialState, Mov, Op, Rem,
    SelectedListContent, UpdateListAttributes, op::Kind as OpKind,
};
use protobuf::{EnumOrUnknown, Message};

use crate::library::EntityInfo;
use crate::{SpotifyError, TrackInfo};

const DEFAULT_TRACK_PAGE: i32 = 100;
const MAX_TRACKS: u32 = 500;

#[derive(uniffi::Record, Clone)]
pub struct PlaylistDetailNative {
    pub id: String,
    pub uri: String,
    pub name: String,
    pub description: String,
    pub owner_id: String,
    pub owner_name: String,
    pub revision_b64: String,
    pub track_count: u32,
    pub image_url: Option<String>,
    pub is_public: bool,
    pub collaborative: bool,
}

#[derive(uniffi::Record, Clone)]
pub struct PlaylistTrackNative {
    pub added_at_ms: i64,
    pub track: TrackInfo,
}

#[derive(uniffi::Record, Clone)]
pub struct PlaylistDetailBundle {
    pub detail: PlaylistDetailNative,
    pub tracks: Vec<PlaylistTrackNative>,
}

pub fn revision_to_b64(revision: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(revision)
}

pub fn revision_from_b64(revision_b64: &str) -> Result<Vec<u8>, SpotifyError> {
    base64::engine::general_purpose::STANDARD
        .decode(revision_b64.trim())
        .map_err(|e| SpotifyError::Internal {
            msg: format!("invalid revision: {e}"),
        })
}

#[derive(uniffi::Record, Clone)]
pub struct RootlistPageNative {
    pub playlists: Vec<EntityInfo>,
    pub total: u32,
}

impl super::EngineShared {
    pub fn native_session_username(self: &Arc<Self>) -> Result<String, SpotifyError> {
        let session = Self::session_or_err(self)?;
        Ok(session.username())
    }

    pub fn playlist_detail_native(
        self: &Arc<Self>,
        playlist_id: &str,
        track_limit: u32,
    ) -> Result<PlaylistDetailBundle, SpotifyError> {
        let session = Self::session_or_err(self)?;
        let handle = self.runtime.handle().clone();
        let playlist_id = playlist_id.to_string();
        let limit = track_limit.clamp(1, MAX_TRACKS);
        handle
            .block_on(async move {
                fetch_playlist_detail(&session, &playlist_id, limit).await
            })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }

    pub fn playlist_rootlist_native(
        self: &Arc<Self>,
        from: u32,
        length: u32,
    ) -> Result<RootlistPageNative, SpotifyError> {
        let session = Self::session_or_err(self)?;
        let handle = self.runtime.handle().clone();
        handle
            .block_on(async move {
                fetch_rootlist_page(&session, from as usize, length as usize).await
            })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }

    pub fn create_playlist_native(
        self: &Arc<Self>,
        name: &str,
        is_public: bool,
    ) -> Result<PlaylistDetailNative, SpotifyError> {
        let session = Self::session_or_err(self)?;
        let handle = self.runtime.handle().clone();
        let name = name.to_string();
        handle
            .block_on(async move {
                create_playlist(&session, &name, is_public).await
            })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }

    pub fn update_playlist_metadata_native(
        self: &Arc<Self>,
        playlist_id: &str,
        revision_b64: &str,
        name: Option<String>,
        is_public: Option<bool>,
    ) -> Result<String, SpotifyError> {
        let session = Self::session_or_err(self)?;
        let revision = revision_from_b64(revision_b64)?;
        let playlist_id = playlist_id.to_string();
        let handle = self.runtime.handle().clone();
        handle
            .block_on(async move {
                update_playlist_metadata(&session, &playlist_id, &revision, name, is_public).await
            })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }

    pub fn playlist_add_tracks_native(
        self: &Arc<Self>,
        playlist_id: &str,
        revision_b64: &str,
        uris: Vec<String>,
        position: Option<u32>,
    ) -> Result<String, SpotifyError> {
        let session = Self::session_or_err(self)?;
        let revision = revision_from_b64(revision_b64)?;
        let playlist_id = playlist_id.to_string();
        let handle = self.runtime.handle().clone();
        handle
            .block_on(async move {
                playlist_add_tracks(&session, &playlist_id, &revision, uris, position).await
            })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }

    pub fn playlist_remove_tracks_native(
        self: &Arc<Self>,
        playlist_id: &str,
        revision_b64: &str,
        uris: Vec<String>,
    ) -> Result<String, SpotifyError> {
        let session = Self::session_or_err(self)?;
        let revision = revision_from_b64(revision_b64)?;
        let playlist_id = playlist_id.to_string();
        let handle = self.runtime.handle().clone();
        handle
            .block_on(async move {
                playlist_remove_tracks(&session, &playlist_id, &revision, uris).await
            })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }

    pub fn playlist_reorder_native(
        self: &Arc<Self>,
        playlist_id: &str,
        revision_b64: &str,
        range_start: u32,
        insert_before: u32,
        range_length: u32,
    ) -> Result<String, SpotifyError> {
        let session = Self::session_or_err(self)?;
        let revision = revision_from_b64(revision_b64)?;
        let playlist_id = playlist_id.to_string();
        let handle = self.runtime.handle().clone();
        handle
            .block_on(async move {
                playlist_reorder(
                    &session,
                    &playlist_id,
                    &revision,
                    range_start,
                    insert_before,
                    range_length,
                )
                .await
            })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }

    pub fn rootlist_add_native(self: &Arc<Self>, playlist_uri: &str) -> Result<(), SpotifyError> {
        let session = Self::session_or_err(self)?;
        let uri = playlist_uri.to_string();
        let handle = self.runtime.handle().clone();
        handle
            .block_on(async move { rootlist_add_with_retry(&session, &uri, 3).await })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }

    pub fn rootlist_remove_native(self: &Arc<Self>, playlist_uri: &str) -> Result<(), SpotifyError> {
        let session = Self::session_or_err(self)?;
        let uri = playlist_uri.to_string();
        let handle = self.runtime.handle().clone();
        handle
            .block_on(async move { rootlist_remove_with_retry(&session, &uri, 3).await })
            .map_err(|e: librespot::core::Error| SpotifyError::Network { msg: e.to_string() })
    }
}

async fn fetch_playlist_detail(
    session: &Session,
    playlist_id: &str,
    track_limit: u32,
) -> Result<PlaylistDetailBundle, librespot::core::Error> {
    let id = SpotifyId::from_base62(playlist_id)?;
    let uri = SpotifyUri::Playlist {
        id,
        user: None,
    };
    let playlist = Playlist::get(session, &uri).await?;
    let mut tracks = collect_playlist_tracks(session, &id, &playlist, track_limit).await?;
    if tracks.iter().any(|t| t.track.title.is_empty()) {
        let mut infos: Vec<TrackInfo> = tracks.iter().map(|t| t.track.clone()).collect();
        super::enrich_track_metadata(session, &mut infos).await?;
        for (row, info) in tracks.iter_mut().zip(infos) {
            row.track = info;
        }
    }
    Ok(PlaylistDetailBundle {
        detail: playlist_to_native(&playlist, playlist_id, &session.username()),
        tracks,
    })
}

async fn collect_playlist_tracks(
    session: &Session,
    playlist_id: &SpotifyId,
    playlist: &Playlist,
    limit: u32,
) -> Result<Vec<PlaylistTrackNative>, librespot::core::Error> {
    let mut out = playlist_items_to_tracks(&playlist.contents.items);

    // Paginate with from/length when the first page is truncated.
    if playlist.contents.is_truncated {
        let mut from = out.len() as i32;
        while (out.len() as u32) < limit {
            let page_len = DEFAULT_TRACK_PAGE.min((limit - out.len() as u32) as i32);
            let body = session
                .spclient()
                .get_playlist_page(playlist_id, from, page_len)
                .await?;
            let content = SelectedListContent::parse_from_bytes(&body)?;
            let items = content
                .contents
                .get_or_default()
                .items
                .as_slice();
            if items.is_empty() {
                break;
            }
            out.extend(playlist_items_to_tracks_from_proto(items));
            from += items.len() as i32;
            if !content.contents.get_or_default().truncated() {
                break;
            }
        }
    }

    out.truncate(limit as usize);
    Ok(out)
}

fn playlist_to_native(
    playlist: &Playlist,
    playlist_id: &str,
    fallback_owner: &str,
) -> PlaylistDetailNative {
    let uri = format!("spotify:playlist:{playlist_id}");
    let (mut owner_id, mut owner_name) = match &playlist.id {
        SpotifyUri::Playlist { user, .. } => {
            let u = user.clone().unwrap_or_default();
            (u.clone(), u)
        }
        _ => (String::new(), String::new()),
    };
    if owner_id.is_empty()
        && !fallback_owner.is_empty()
        && playlist.capabilities.can_administrate_permissions
    {
        owner_id = fallback_owner.to_string();
        owner_name = fallback_owner.to_string();
    }
    let image_url = playlist
        .attributes
        .picture_sizes
        .first()
        .map(|p| p.url.clone())
        .or_else(|| {
            if playlist.attributes.picture.is_empty() {
                None
            } else {
                Some(format!(
                    "https://i.scdn.co/image/{}",
                    base64::engine::general_purpose::STANDARD.encode(&playlist.attributes.picture)
                ))
            }
        });
    let is_public = playlist
        .attributes
        .format_attributes
        .get("isPublished")
        .map(|v| v == "true")
        .unwrap_or(false);
    PlaylistDetailNative {
        id: playlist_id.to_string(),
        uri,
        name: playlist.attributes.name.clone(),
        description: playlist.attributes.description.clone(),
        owner_id,
        owner_name,
        revision_b64: revision_to_b64(&playlist.revision),
        track_count: playlist.length.max(0) as u32,
        image_url,
        is_public,
        collaborative: playlist.attributes.is_collaborative,
    }
}

fn playlist_items_to_tracks(items: &librespot::metadata::playlist::item::PlaylistItems) -> Vec<PlaylistTrackNative> {
    items
        .iter()
        .filter_map(|item| playlist_item_to_track(item))
        .collect()
}

fn playlist_items_to_tracks_from_proto(
    items: &[playlist4_external::Item],
) -> Vec<PlaylistTrackNative> {
    items
        .iter()
        .filter_map(|item| {
            let uri = item.uri.as_deref()?;
            if !uri.starts_with("spotify:track:") {
                return None;
            }
            let added_at_ms = item
                .attributes
                .as_ref()
                .map(|a| a.timestamp())
                .unwrap_or(0);
            Some(PlaylistTrackNative {
                added_at_ms,
                track: TrackInfo {
                    uri: uri.split('?').next().unwrap_or(uri).to_string(),
                    title: String::new(),
                    artists: String::new(),
                    album: String::new(),
                    duration_ms: 0,
                    art_url: None,
                },
            })
        })
        .collect()
}

fn playlist_item_to_track(
    item: &librespot::metadata::playlist::item::PlaylistItem,
) -> Option<PlaylistTrackNative> {
    let uri = item.id.to_uri().ok()?;
    if !uri.starts_with("spotify:track:") {
        return None;
    }
    Some(PlaylistTrackNative {
        added_at_ms: item.attributes.timestamp.unix_timestamp() * 1000,
        track: TrackInfo {
            uri,
            title: String::new(),
            artists: String::new(),
            album: String::new(),
            duration_ms: 0,
            art_url: None,
        },
    })
}

async fn fetch_rootlist_page(
    session: &Session,
    from: usize,
    length: usize,
) -> Result<RootlistPageNative, librespot::core::Error> {
    let body = session.spclient().get_rootlist(from, Some(length)).await?;
    let content = SelectedListContent::parse_from_bytes(&body)?;
    let total = content.length().max(0) as u32;
    let list = content.contents.get_or_default();
    let mut entities = Vec::new();

    for (idx, item) in list.items.iter().enumerate() {
        let uri = match item.uri.as_deref() {
            Some(u) => u,
            None => continue,
        };
        if !uri.starts_with("spotify:playlist:") {
            continue;
        }
        let id = uri.rsplit(':').next().unwrap_or("").to_string();
        let meta = list.meta_items.get(idx);
        let (name, subtitle, art_url, track_count) = if let Some(m) = meta {
            let attrs = m.attributes.get_or_default();
            (
                attrs.name().to_string(),
                m.owner_username().to_string(),
                attrs.picture_size.first().map(|p| p.url().to_string()),
                m.length().max(0) as u32,
            )
        } else {
            (String::new(), String::new(), None, 0)
        };
        entities.push(EntityInfo {
            entity_type: "playlist".into(),
            id,
            uri: uri.to_string(),
            name,
            subtitle,
            art_url,
            track_count,
        });
    }

    Ok(RootlistPageNative { playlists: entities, total })
}

async fn create_playlist(
    session: &Session,
    name: &str,
    is_public: bool,
) -> Result<PlaylistDetailNative, librespot::core::Error> {
    let body = session.spclient().create_playlist(name, is_public).await?;
    let reply = CreateListReply::parse_from_bytes(&body)?;
    let uri = reply.uri();
    let id = uri.rsplit(':').next().unwrap_or("").to_string();

    if let Err(e) = rootlist_add_with_retry(session, &uri, 3).await {
        log::warn!("rootlist add after create failed for {uri}: {e}");
    }

    let mut revision_b64 = reply
        .revision
        .as_ref()
        .filter(|r| !r.is_empty())
        .map(|r| revision_to_b64(r))
        .unwrap_or_default();

    if revision_b64.is_empty() {
        if let Ok(bundle) = fetch_playlist_detail(session, &id, 1).await {
            revision_b64 = bundle.detail.revision_b64;
        }
    }

    Ok(PlaylistDetailNative {
        id: id.clone(),
        uri: uri.to_string(),
        name: name.to_string(),
        description: String::new(),
        owner_id: session.username(),
        owner_name: session.username(),
        revision_b64,
        track_count: 0,
        image_url: None,
        is_public,
        collaborative: false,
    })
}

async fn apply_ops(
    session: &Session,
    playlist_id: &str,
    revision: &[u8],
    ops: Vec<Op>,
) -> Result<String, librespot::core::Error> {
    let id = SpotifyId::from_base62(playlist_id)?;
    let body = session
        .spclient()
        .apply_playlist_changes(&id, revision, ops)
        .await?;
    extract_new_revision(&body)
}

async fn update_playlist_metadata(
    session: &Session,
    playlist_id: &str,
    revision: &[u8],
    name: Option<String>,
    is_public: Option<bool>,
) -> Result<String, librespot::core::Error> {
    let mut values = playlist4_external::ListAttributes::new();
    if let Some(n) = name {
        values.name = Some(n);
    }
    if let Some(public) = is_public {
        let mut fmt = playlist4_external::FormatListAttribute::new();
        fmt.key = Some("isPublished".to_string());
        fmt.value = Some(if public { "true" } else { "false" }.to_string());
        values.format_attributes = vec![fmt].into();
    }
    let mut partial = ListAttributesPartialState::new();
    partial.values = Some(values).into();
    let mut update = UpdateListAttributes::new();
    update.new_attributes = Some(partial).into();
    let mut op = Op::new();
    op.kind = Some(EnumOrUnknown::new(OpKind::UPDATE_LIST_ATTRIBUTES));
    op.update_list_attributes = Some(update).into();
    apply_ops(session, playlist_id, revision, vec![op]).await
}

async fn playlist_add_tracks(
    session: &Session,
    playlist_id: &str,
    revision: &[u8],
    uris: Vec<String>,
    position: Option<u32>,
) -> Result<String, librespot::core::Error> {
    let items: Vec<Item> = uris
        .into_iter()
        .map(|uri| {
            let mut item = Item::new();
            item.uri = Some(uri);
            item
        })
        .collect();
    let mut add = Add::new();
    add.items = items;
    if let Some(pos) = position {
        add.from_index = Some(pos as i32);
    } else {
        add.add_last = Some(true);
    }
    let mut op = Op::new();
    op.kind = Some(EnumOrUnknown::new(OpKind::ADD));
    op.add = Some(add).into();
    apply_ops(session, playlist_id, revision, vec![op]).await
}

async fn playlist_remove_tracks(
    session: &Session,
    playlist_id: &str,
    revision: &[u8],
    uris: Vec<String>,
) -> Result<String, librespot::core::Error> {
    let items: Vec<Item> = uris
        .into_iter()
        .map(|uri| {
            let mut item = Item::new();
            item.uri = Some(uri);
            item
        })
        .collect();
    let mut rem = Rem::new();
    rem.items = items;
    rem.items_as_key = Some(true);
    let mut op = Op::new();
    op.kind = Some(EnumOrUnknown::new(OpKind::REM));
    op.rem = Some(rem).into();
    apply_ops(session, playlist_id, revision, vec![op]).await
}

async fn playlist_reorder(
    session: &Session,
    playlist_id: &str,
    revision: &[u8],
    range_start: u32,
    insert_before: u32,
    range_length: u32,
) -> Result<String, librespot::core::Error> {
    let mut mov = Mov::new();
    mov.from_index = Some(range_start as i32);
    mov.length = Some(range_length.max(1) as i32);
    mov.to_index = Some(insert_before as i32);
    let mut op = Op::new();
    op.kind = Some(EnumOrUnknown::new(OpKind::MOV));
    op.mov = Some(mov).into();
    apply_ops(session, playlist_id, revision, vec![op]).await
}

async fn rootlist_add(session: &Session, playlist_uri: &str) -> Result<(), librespot::core::Error> {
    let revision = fetch_rootlist_revision(session).await?;
    let mut item = Item::new();
    item.uri = Some(playlist_uri.to_string());
    let mut add = Add::new();
    add.items = vec![item];
    add.add_last = Some(true);
    let mut op = Op::new();
    op.kind = Some(EnumOrUnknown::new(OpKind::ADD));
    op.add = Some(add).into();
    session
        .spclient()
        .apply_rootlist_changes(&revision, vec![op])
        .await?;
    Ok(())
}

async fn rootlist_add_with_retry(
    session: &Session,
    playlist_uri: &str,
    max_attempts: u32,
) -> Result<(), librespot::core::Error> {
    let mut last_err = None;
    for attempt in 0..max_attempts {
        match rootlist_add(session, playlist_uri).await {
            Ok(()) => return Ok(()),
            Err(e) => {
                last_err = Some(e);
                if attempt + 1 < max_attempts {
                    log::warn!(
                        "rootlist_add attempt {} failed for {playlist_uri}: {}",
                        attempt + 1,
                        last_err.as_ref().unwrap()
                    );
                    tokio::time::sleep(std::time::Duration::from_millis(200)).await;
                }
            }
        }
    }
    Err(last_err.unwrap_or_else(|| {
        librespot::core::Error::unavailable("rootlist_add failed with no error detail")
    }))
}

async fn rootlist_remove_with_retry(
    session: &Session,
    playlist_uri: &str,
    max_attempts: u32,
) -> Result<(), librespot::core::Error> {
    let mut last_err = None;
    for attempt in 0..max_attempts {
        match rootlist_remove(session, playlist_uri).await {
            Ok(()) => return Ok(()),
            Err(e) => {
                last_err = Some(e);
                if attempt + 1 < max_attempts {
                    log::warn!(
                        "rootlist_remove attempt {} failed for {playlist_uri}: {}",
                        attempt + 1,
                        last_err.as_ref().unwrap()
                    );
                    tokio::time::sleep(std::time::Duration::from_millis(200)).await;
                }
            }
        }
    }
    Err(last_err.unwrap_or_else(|| {
        librespot::core::Error::unavailable("rootlist_remove failed with no error detail")
    }))
}

async fn rootlist_remove(
    session: &Session,
    playlist_uri: &str,
) -> Result<(), librespot::core::Error> {
    let revision = fetch_rootlist_revision(session).await?;
    let mut item = Item::new();
    item.uri = Some(playlist_uri.to_string());
    let mut rem = Rem::new();
    rem.items = vec![item];
    rem.items_as_key = Some(true);
    let mut op = Op::new();
    op.kind = Some(EnumOrUnknown::new(OpKind::REM));
    op.rem = Some(rem).into();
    session
        .spclient()
        .apply_rootlist_changes(&revision, vec![op])
        .await?;
    Ok(())
}

async fn fetch_rootlist_revision(session: &Session) -> Result<Vec<u8>, librespot::core::Error> {
    let body = session.spclient().get_rootlist(0, Some(1)).await?;
    let content = SelectedListContent::parse_from_bytes(&body)?;
    content
        .revision
        .clone()
        .ok_or_else(|| librespot::core::Error::invalid_argument("rootlist missing revision"))
}

fn extract_new_revision(body: &[u8]) -> Result<String, librespot::core::Error> {
    let content = SelectedListContent::parse_from_bytes(body)?;
    if let Some(diff) = content.sync_result.as_ref() {
        return Ok(revision_to_b64(diff.to_revision()));
    }
    if let Some(rev) = content.revision.as_ref() {
        return Ok(revision_to_b64(rev));
    }
    if let Some(rev) = content.resulting_revisions.first() {
        return Ok(revision_to_b64(rev));
    }
    Err(librespot::core::Error::invalid_argument(
        "playlist mutation returned no revision",
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn revision_b64_roundtrip() {
        let bytes = b"rev123\x00\xff";
        let b64 = revision_to_b64(bytes);
        assert_eq!(revision_from_b64(&b64).unwrap(), bytes);
    }
}
