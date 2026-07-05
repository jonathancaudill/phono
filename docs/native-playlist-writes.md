# Native playlist writes (spclient / playlist4)

phono playlist and artist metadata uses Login5 `spclient` (Keymaster session), not the dev-app
Web API. Playlist **writes** use the `playlist4_external` protobuf ops that the official desktop
client sends over HTTPS.

## Endpoints (spclient host, Login5 bearer + client-token)

| Operation | Method | Path | Request body | Response |
|-----------|--------|------|--------------|----------|
| Read playlist | GET | `/playlist/v2/playlist/{base62}` | — | `SelectedListContent` |
| Read page | GET | `/playlist/v2/playlist/{base62}?from={n}&length={m}` | — | `SelectedListContent` |
| Continuation | POST | `/playlist/v2/playlist/{base62}/continuation` | JSON `{"continuation_token":"..."}` | `ListItems` |
| Apply ops | POST | `/playlist/v2/playlist/{base62}/changes` | `ListChanges` | `SelectedListContent` |
| Create | POST | `/playlist/v2/playlist` | `ListUpdateRequest` | `CreateListReply` |
| Rootlist read | GET | `/playlist/v2/user/{user}/rootlist?decorate=...&from=&length=` | — | `SelectedListContent` |
| Follow / unfollow | POST | `/playlist/v2/user/{user}/rootlist/changes` | `ListChanges` | `SelectedListContent` |

All mutation requests use `Content-Type: application/x-protobuf`. Reads return protobuf unless
`Accept: application/json` is set (we use protobuf throughout).

## Revision vs Web API snapshot_id

Native playlists use opaque **revision** bytes (`SelectedListContent.revision`). phono maps these
to Kotlin/Room `snapshot_id` as **standard Base64** strings at the FFI boundary. Mutations must
pass the current revision; conflicts return a fresh revision in `sync_result` / error.

## Op kinds (`playlist4_external.Op`)

| Op | Use |
|----|-----|
| `ADD` | Append or insert track URIs (`Add.items`, optional `from_index`) |
| `REM` | Remove by index range or by URI keys (`Rem.items`, `items_as_key`) |
| `MOV` | Reorder (`Mov.from_index`, `length`, `to_index`) |
| `UPDATE_LIST_ATTRIBUTES` | Rename, description, public/collaborative via partial attributes |

Public visibility is carried in `ListAttributes.format_attributes` (`key=isPublished`,
`value=true|false`) on create; updates use `UPDATE_LIST_ATTRIBUTES`.

## ChangeInfo

Every delta includes `ChangeInfo` with `user = session.username()` and
`source.client = CLIENT` so Spotify attributes edits to the desktop client identity.

## Follow / unfollow

Rootlist mutations use `ListChanges` on `/rootlist/changes` with `ADD` / `REM` ops whose
`Item.uri` is `spotify:playlist:{id}` (not track URIs).

## Identity constraints

These calls run on the **Keymaster / Login5** session only. Do not send dev-app OAuth tokens to
spclient hosts. The patched librespot client-token must remain aligned with Keymaster (see
`AGENTS.md`).
