package com.lightphone.spotify.data.webapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebApiQrPayloadTest {

    private fun payloadJson(
        version: Int = 1,
        type: String = "phono.spotify.webapi",
        clientId: String = "abc123",
        clientSecret: String = "secret456",
        redirectUri: String? = null,
    ): String {
        val redirectField = redirectUri?.let { ""","redirect_uri":"$it"""" } ?: ""
        return """{"v":$version,"type":"$type","client_id":"$clientId","client_secret":"$clientSecret"$redirectField}"""
    }

    @Test
    fun validPayload_parsesSuccessfully() {
        val result = parseWebApiQrPayload(payloadJson())

        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertEquals(1, payload.version)
        assertEquals("phono.spotify.webapi", payload.type)
        assertEquals("abc123", payload.clientId)
        assertEquals("secret456", payload.clientSecret)
    }

    @Test
    fun validPayload_withoutRedirectUri_parsesSuccessfully() {
        val result = parseWebApiQrPayload(payloadJson(redirectUri = null))

        assertTrue(result.isSuccess)
    }

    @Test
    fun validPayload_withMatchingRedirectUri_parsesSuccessfully() {
        val result = parseWebApiQrPayload(payloadJson(redirectUri = WebApiAuth.REDIRECT_URI))

        assertTrue(result.isSuccess)
    }

    @Test
    fun payload_withBlankRedirectUri_isTreatedAsNotProvided() {
        val result = parseWebApiQrPayload(payloadJson(redirectUri = "   "))

        assertTrue(result.isSuccess)
    }

    @Test
    fun payload_withMismatchedRedirectUri_fails() {
        val result = parseWebApiQrPayload(payloadJson(redirectUri = "http://evil.example/callback"))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("redirect"))
    }

    @Test
    fun payload_withUnsupportedVersion_fails() {
        val result = parseWebApiQrPayload(payloadJson(version = 2))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("Unsupported QR version"))
    }

    @Test
    fun payload_withWrongType_fails() {
        val result = parseWebApiQrPayload(payloadJson(type = "some.other.payload"))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("Not a phono Web API QR code"))
    }

    @Test
    fun payload_withBlankClientId_fails() {
        val result = parseWebApiQrPayload(payloadJson(clientId = "   "))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("Client ID"))
    }

    @Test
    fun payload_withBlankClientSecret_fails() {
        val result = parseWebApiQrPayload(payloadJson(clientSecret = ""))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("Client Secret"))
    }

    @Test
    fun payload_trimsWhitespaceFromCredentials() {
        val result = parseWebApiQrPayload(payloadJson(clientId = "  abc123  ", clientSecret = "  secret456  "))

        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertEquals("abc123", payload.clientId)
        assertEquals("secret456", payload.clientSecret)
    }

    @Test
    fun rawString_withSurroundingWhitespace_isTrimmedBeforeParsing() {
        val raw = "\n  " + payloadJson() + "  \n"

        val result = parseWebApiQrPayload(raw)

        assertTrue(result.isSuccess)
    }

    @Test
    fun rawString_withUnknownExtraKeys_isIgnored() {
        val raw = """{"v":1,"type":"phono.spotify.webapi","client_id":"abc","client_secret":"def","extra":"ignored"}"""

        val result = parseWebApiQrPayload(raw)

        assertTrue(result.isSuccess)
    }

    @Test
    fun malformedJson_failsGracefully() {
        val result = parseWebApiQrPayload("not-json-at-all")

        assertTrue(result.isFailure)
    }

    @Test
    fun emptyString_failsGracefully() {
        val result = parseWebApiQrPayload("")

        assertTrue(result.isFailure)
    }

    @Test
    fun jsonMissingRequiredField_failsGracefully() {
        val raw = """{"v":1,"type":"phono.spotify.webapi","client_id":"abc"}"""

        val result = parseWebApiQrPayload(raw)

        assertTrue(result.isFailure)
    }
}