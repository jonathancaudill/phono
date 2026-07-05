//! Batched extended-metadata fetches via spclient (Login5).

use std::collections::HashMap;

use librespot::core::{Error, Session, SpotifyUri};
use librespot::metadata::{Album, Metadata, Track};
use librespot::protocol::extended_metadata::{BatchedEntityRequest, EntityRequest, ExtensionQuery};
use librespot::protocol::extension_kind::ExtensionKind;
use librespot::protocol::metadata::{Album as AlbumMessage, Track as TrackMessage};
use protobuf::{EnumOrUnknown, Message};

/// Practical chunk size for `/extended-metadata/v0/extended-metadata` (413 above ~50–100).
pub const METADATA_BATCH_SIZE: usize = 50;

pub(crate) fn normalize_entity_uri(uri: &str) -> String {
    uri.split('?').next().unwrap_or(uri).to_string()
}

async fn fetch_extension_metadata_chunk(
    session: &Session,
    chunk: &[SpotifyUri],
    kind: ExtensionKind,
) -> Result<HashMap<String, bytes::Bytes>, Error> {
    let entity_request: Vec<EntityRequest> = chunk
        .iter()
        .filter_map(|uri| {
            Some(EntityRequest {
                entity_uri: uri.to_uri().ok()?,
                query: vec![ExtensionQuery {
                    extension_kind: EnumOrUnknown::new(kind),
                    ..Default::default()
                }],
                ..Default::default()
            })
        })
        .collect();
    if entity_request.is_empty() {
        return Ok(HashMap::new());
    }

    let req = BatchedEntityRequest {
        entity_request,
        ..Default::default()
    };
    let res = session.spclient().get_extended_metadata(req).await?;
    let mut out = HashMap::new();
    for arr in res.extended_metadata {
        for mut entry in arr.extension_data {
            let entity_uri = normalize_entity_uri(&entry.entity_uri);
            match entry.extension_data.take() {
                None => continue,
                Some(any) => {
                    if any.value.is_empty() {
                        continue;
                    }
                    out.insert(entity_uri, bytes::Bytes::from(any.value));
                }
            }
        }
    }
    Ok(out)
}

async fn fetch_extension_metadata_batch(
    session: &Session,
    uris: &[SpotifyUri],
    kind: ExtensionKind,
) -> Result<HashMap<String, bytes::Bytes>, Error> {
    let chunks: Vec<&[SpotifyUri]> = uris.chunks(METADATA_BATCH_SIZE).collect();
    if chunks.is_empty() {
        return Ok(HashMap::new());
    }

    let session = session.clone();
    let mut join_set = tokio::task::JoinSet::new();
    for chunk in chunks {
        let session = session.clone();
        let chunk = chunk.to_vec();
        join_set.spawn(async move { fetch_extension_metadata_chunk(&session, &chunk, kind).await });
    }

    let mut out = HashMap::new();
    while let Some(result) = join_set.join_next().await {
        match result {
            Ok(Ok(partial)) => out.extend(partial),
            Ok(Err(e)) => log::warn!("batch metadata chunk failed: {e}"),
            Err(e) => log::warn!("batch metadata task failed: {e}"),
        }
    }
    Ok(out)
}

pub(crate) async fn fetch_tracks_metadata_batch(
    session: &Session,
    uris: &[SpotifyUri],
) -> Result<HashMap<String, Track>, Error> {
    let raw = fetch_extension_metadata_batch(session, uris, ExtensionKind::TRACK_V4).await?;
    let mut out = HashMap::new();
    for (uri_str, bytes) in raw {
        let Ok(uri) = SpotifyUri::from_uri(&uri_str) else {
            continue;
        };
        let Ok(msg) = TrackMessage::parse_from_bytes(&bytes) else {
            log::warn!("batch track metadata parse failed for {uri_str}");
            continue;
        };
        match Track::parse(&msg, &uri) {
            Ok(track) => {
                out.insert(uri_str, track);
            }
            Err(e) => log::warn!("batch track metadata decode failed for {uri_str}: {e}"),
        }
    }
    Ok(out)
}

pub(crate) async fn fetch_albums_metadata_batch(
    session: &Session,
    uris: &[SpotifyUri],
) -> Result<HashMap<String, Album>, Error> {
    let raw = fetch_extension_metadata_batch(session, uris, ExtensionKind::ALBUM_V4).await?;
    let mut out = HashMap::new();
    for (uri_str, bytes) in raw {
        let Ok(uri) = SpotifyUri::from_uri(&uri_str) else {
            continue;
        };
        let Ok(msg) = AlbumMessage::parse_from_bytes(&bytes) else {
            log::warn!("batch album metadata parse failed for {uri_str}");
            continue;
        };
        match Album::parse(&msg, &uri) {
            Ok(album) => {
                out.insert(uri_str, album);
            }
            Err(e) => log::warn!("batch album metadata decode failed for {uri_str}: {e}"),
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_strips_query_params() {
        assert_eq!(
            normalize_entity_uri("spotify:track:abc?context=foo"),
            "spotify:track:abc"
        );
    }

    #[test]
    fn batch_size_is_reasonable() {
        assert!(METADATA_BATCH_SIZE >= 10);
        assert!(METADATA_BATCH_SIZE <= 100);
    }
}
