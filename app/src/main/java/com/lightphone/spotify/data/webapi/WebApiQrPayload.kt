package com.lightphone.spotify.data.webapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val PAYLOAD_TYPE = "phono.spotify.webapi"
private const val PAYLOAD_VERSION = 1

@Serializable
data class WebApiQrPayload(
    @SerialName("v") val version: Int,
    @SerialName("type") val type: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
    @SerialName("redirect_uri") val redirectUri: String? = null,
)

private val qrJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun parseWebApiQrPayload(raw: String): Result<WebApiQrPayload> = runCatching {
    val payload = qrJson.decodeFromString<WebApiQrPayload>(raw.trim())
    require(payload.version == PAYLOAD_VERSION) {
        "Unsupported QR version (${payload.version}). Update phono."
    }
    require(payload.type == PAYLOAD_TYPE) {
        "Not a phono Web API QR code."
    }
    val clientId = payload.clientId.trim()
    val clientSecret = payload.clientSecret.trim()
    require(clientId.isNotEmpty()) { "QR code is missing Client ID." }
    require(clientSecret.isNotEmpty()) { "QR code is missing Client Secret." }
    payload.redirectUri?.trim()?.takeIf { it.isNotEmpty() }?.let { redirectUri ->
        require(redirectUri == WebApiAuth.REDIRECT_URI) {
            "QR redirect URI does not match phono ($redirectUri)."
        }
    }
    payload.copy(clientId = clientId, clientSecret = clientSecret)
}
