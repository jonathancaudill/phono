package com.lightphone.spotify.data.webapi

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OAuth Authorization Code flow (with client secret) for the user's own Spotify
 * dev-app. Credentials and tokens live in EncryptedSharedPreferences — never
 * build-time constants.
 */
class WebApiAuth(private val context: Context) {

    companion object {
        /** Loopback URI for WebView interception (must match Spotify dashboard exactly). */
        const val REDIRECT_URI = "http://127.0.0.1:43821/callback"
        const val AUTH_ENDPOINT = "https://accounts.spotify.com/authorize"
        const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"

        val SCOPES = listOf(
            "user-library-read",
            "user-library-modify",
            "playlist-read-private",
            "playlist-read-collaborative",
            "playlist-modify-public",
            "playlist-modify-private",
            "user-read-private",
        )

        private const val PREFS_NAME = "phono_web_api_auth"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
        private const val REFRESH_EARLY_MS = 60_000L
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val tokenClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    private val lock = Any()

    fun hasCredentials(): Boolean =
        prefs.getString(KEY_CLIENT_ID, null)?.isNotBlank() == true &&
            prefs.getString(KEY_CLIENT_SECRET, null)?.isNotBlank() == true

    fun isAuthorized(): Boolean = synchronized(lock) {
        hasCredentials() && prefs.getString(KEY_ACCESS_TOKEN, null)?.isNotBlank() == true &&
            prefs.getString(KEY_REFRESH_TOKEN, null)?.isNotBlank() == true
    }

    fun saveCredentials(clientId: String, clientSecret: String) {
        prefs.edit()
            .putString(KEY_CLIENT_ID, clientId.trim())
            .putString(KEY_CLIENT_SECRET, clientSecret.trim())
            .apply()
    }

    fun getClientId(): String? = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }

    fun buildAuthorizeUrl(): String {
        val clientId = getClientId()
            ?: throw IllegalStateException("Client ID not configured")
        val scope = SCOPES.joinToString(" ")
        return buildString {
            append(AUTH_ENDPOINT)
            append("?client_id=").append(urlEncode(clientId))
            append("&response_type=code")
            append("&redirect_uri=").append(urlEncode(REDIRECT_URI))
            append("&scope=").append(urlEncode(scope))
            append("&show_dialog=true")
        }
    }

  /** Exchange authorization code for tokens (Authorization Code + secret). */
    fun exchangeCode(code: String): Result<Unit> = synchronized(lock) {
        val clientId = getClientId() ?: return Result.failure(IllegalStateException("No client ID"))
        val secret = prefs.getString(KEY_CLIENT_SECRET, null)
            ?: return Result.failure(IllegalStateException("No client secret"))
        runCatching {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .build()
            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .header("Authorization", basicAuth(clientId, secret))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(body)
                .build()
            val response = tokenClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                if (responseBody.contains("invalid_grant")) {
                    clearTokens()
                }
                throw IOException("Token exchange failed: HTTP ${response.code} $responseBody")
            }
            val tokens = json.decodeFromString<TokenResponse>(responseBody)
            storeTokens(tokens)
        }
    }

    /**
     * Returns a valid bearer token, refreshing proactively when near expiry.
     * On invalid_grant, clears tokens so the UI can re-run Step 2.
     */
    fun currentBearer(): String = synchronized(lock) {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
            throw WebApiAuthException("Web API not authorized — complete Step 2 setup")
        }
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)
        if (System.currentTimeMillis() + REFRESH_EARLY_MS >= expiresAt) {
            refreshTokensInternal() ?: throw WebApiAuthException(
                "Session expired — re-authorize your dev app in Step 2",
            )
        }
        prefs.getString(KEY_ACCESS_TOKEN, null)
            ?: throw WebApiAuthException("Web API not authorized")
    }

    fun refreshTokens(): Result<Unit> = synchronized(lock) {
        runCatching {
            if (refreshTokensInternal() == null) {
                throw WebApiAuthException("Token refresh failed — re-authorize Step 2")
            }
        }
    }

    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT_MS)
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun refreshTokensInternal(): String? {
        val clientId = getClientId() ?: return null
        val secret = prefs.getString(KEY_CLIENT_SECRET, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .build()
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .header("Authorization", basicAuth(clientId, secret))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(body)
            .build()
        val response = tokenClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            if (responseBody.contains("invalid_grant")) {
                clearTokens()
            }
            return null
        }
        val tokens = json.decodeFromString<TokenResponse>(responseBody)
        storeTokens(tokens)
        return prefs.getString(KEY_ACCESS_TOKEN, null)
    }

    private fun storeTokens(tokens: TokenResponse) {
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000L
        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putLong(KEY_EXPIRES_AT_MS, expiresAt)
        tokens.refreshToken?.let { editor.putString(KEY_REFRESH_TOKEN, it) }
        editor.apply()
    }

    private fun basicAuth(clientId: String, secret: String): String {
        val credentials = "$clientId:$secret"
        val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("expires_in") val expiresIn: Long,
        @SerialName("refresh_token") val refreshToken: String? = null,
        val scope: String? = null,
    )
}

class WebApiAuthException(message: String) : Exception(message)
