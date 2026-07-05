package com.lightphone.spotify.data.webapi

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * OAuth Authorization Code flow (with client secret) for the user's own Spotify
 * dev-app. Credentials and tokens live in EncryptedSharedPreferences — never
 * build-time constants.
 */
class WebApiAuth private constructor(
    private val prefs: SharedPreferences,
    private val tokenClient: OkHttpClient,
    private val tokenEndpoint: String,
) {

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

        /** Test-only factory with injectable prefs and HTTP client. */
        internal fun createForTest(
            prefs: SharedPreferences,
            tokenClient: OkHttpClient,
            tokenEndpoint: String = TOKEN_ENDPOINT,
        ): WebApiAuth = WebApiAuth(prefs, tokenClient, tokenEndpoint)

        private fun defaultTokenClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

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
    }

    constructor(context: Context) : this(
        createEncryptedPrefs(context),
        defaultTokenClient(),
        TOKEN_ENDPOINT,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Any()
    private val refreshFlightLock = Any()

    @Volatile
    private var refreshInFlight: CompletableFuture<String?>? = null

    private val _sessionState = MutableStateFlow(computeSessionState())
    val sessionState: StateFlow<WebApiSessionState> = _sessionState.asStateFlow()

    fun hasCredentials(): Boolean = synchronized(lock) {
        prefs.getString(KEY_CLIENT_ID, null)?.isNotBlank() == true &&
            prefs.getString(KEY_CLIENT_SECRET, null)?.isNotBlank() == true
    }

    fun isAuthorized(): Boolean = synchronized(lock) {
        hasCredentials() && prefs.getString(KEY_ACCESS_TOKEN, null)?.isNotBlank() == true &&
            prefs.getString(KEY_REFRESH_TOKEN, null)?.isNotBlank() == true
    }

    fun saveCredentials(clientId: String, clientSecret: String) = synchronized(lock) {
        prefs.edit()
            .putString(KEY_CLIENT_ID, clientId.trim())
            .putString(KEY_CLIENT_SECRET, clientSecret.trim())
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT_MS)
            .apply()
        emitSessionState()
    }

    fun getClientId(): String? = synchronized(lock) {
        prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
    }

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
    fun exchangeCode(code: String): Result<Unit> {
        val creds = synchronized(lock) {
            val clientId = getClientId() ?: return Result.failure(IllegalStateException("No client ID"))
            val secret = prefs.getString(KEY_CLIENT_SECRET, null)
                ?: return Result.failure(IllegalStateException("No client secret"))
            clientId to secret
        }
        val (clientId, secret) = creds
        return runCatching {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .build()
            val request = Request.Builder()
                .url(tokenEndpoint)
                .header("Authorization", basicAuth(clientId, secret))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(body)
                .build()
            val response = tokenClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                if (responseBody.contains("invalid_grant")) {
                    synchronized(lock) { clearTokensInternal() }
                }
                throw IOException("Token exchange failed: HTTP ${response.code} $responseBody")
            }
            val tokens = json.decodeFromString<TokenResponse>(responseBody)
            synchronized(lock) { storeTokensInternal(tokens) }
        }
    }

    /**
     * Returns a valid bearer token, refreshing proactively when near expiry.
     * On invalid_grant, clears tokens so the UI can re-run Step 2.
     */
    fun currentBearer(): String {
        readFreshAccessToken()?.let { return it }
        refreshTokensSingleFlight() ?: throw WebApiAuthException(
            "Session expired — re-authorize your dev app in Step 2",
        )
        return synchronized(lock) {
            prefs.getString(KEY_ACCESS_TOKEN, null)
                ?: throw WebApiAuthException("Web API not authorized")
        }
    }

    fun refreshTokens(): Result<Unit> = runCatching {
        refreshTokensSingleFlight() ?: throw WebApiAuthException("Token refresh failed — re-authorize Step 2")
    }

    fun clearTokens() = synchronized(lock) {
        clearTokensInternal()
    }

    fun clearAll() = synchronized(lock) {
        prefs.edit().clear().apply()
        emitSessionState()
    }

    private fun clearTokensInternal() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT_MS)
            .apply()
        emitSessionState()
    }

    /**
     * Ensures exactly one refresh HTTP call is in flight; concurrent callers wait
     * and reuse the result. HTTP runs outside [lock] so library/scrub paths stay responsive.
     */
    private fun refreshTokensSingleFlight(): String? {
        val (future, isLeader) = synchronized(refreshFlightLock) {
            refreshInFlight?.let { return@synchronized it to false }
            CompletableFuture<String?>().also { refreshInFlight = it } to true
        }

        if (!isLeader) {
            return try {
                future.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (_: Exception) {
                null
            }
        }

        try {
            val result = performRefreshIfNeeded()
            future.complete(result)
            return result
        } catch (e: Exception) {
            future.complete(null)
            return null
        } finally {
            synchronized(refreshFlightLock) {
                if (refreshInFlight === future) {
                    refreshInFlight = null
                }
            }
        }
    }

    /** Returns a still-valid access token, or null if refresh is required. */
    private fun readFreshAccessToken(): String? = synchronized(lock) {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
            throw WebApiAuthException("Web API not authorized — complete Step 2 setup")
        }
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)
        if (System.currentTimeMillis() + REFRESH_EARLY_MS < expiresAt) {
            access
        } else {
            null
        }
    }

    private fun performRefreshIfNeeded(): String? {
        readFreshAccessToken()?.let { return it }

        val creds = synchronized(lock) {
            val clientId = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
            val secret = prefs.getString(KEY_CLIENT_SECRET, null) ?: return null
            val refresh = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
            Triple(clientId, secret, refresh)
        }
        val (clientId, secret, refreshUsed) = creds

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshUsed)
            .build()
        val request = Request.Builder()
            .url(tokenEndpoint)
            .header("Authorization", basicAuth(clientId, secret))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(body)
            .build()
        val response = tokenClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            if (responseBody.contains("invalid_grant")) {
                handleInvalidGrant(refreshUsed)
            }
            return null
        }
        val tokens = json.decodeFromString<TokenResponse>(responseBody)
        return synchronized(lock) {
            storeTokensInternal(tokens)
            prefs.getString(KEY_ACCESS_TOKEN, null)
        }
    }

    /**
     * Clears tokens on genuine revocation only. Skips clear when another thread
     * already rotated the refresh token or stored a fresh access token.
     */
    private fun handleInvalidGrant(refreshTokenUsed: String) {
        synchronized(lock) {
            val access = prefs.getString(KEY_ACCESS_TOKEN, null)
            val expiresAt = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)
            if (!access.isNullOrBlank() &&
                System.currentTimeMillis() + REFRESH_EARLY_MS < expiresAt
            ) {
                return
            }
            val currentRefresh = prefs.getString(KEY_REFRESH_TOKEN, null)
            if (currentRefresh != null && currentRefresh != refreshTokenUsed) {
                return
            }
            clearTokensInternal()
        }
    }

    private fun storeTokensInternal(tokens: TokenResponse) {
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000L
        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putLong(KEY_EXPIRES_AT_MS, expiresAt)
        tokens.refreshToken?.let { editor.putString(KEY_REFRESH_TOKEN, it) }
        editor.apply()
        emitSessionState()
    }

    private fun computeSessionState(): WebApiSessionState {
        val hasCreds = prefs.getString(KEY_CLIENT_ID, null)?.isNotBlank() == true &&
            prefs.getString(KEY_CLIENT_SECRET, null)?.isNotBlank() == true
        if (!hasCreds) return WebApiSessionState.NotConfigured
        val hasTokens = prefs.getString(KEY_ACCESS_TOKEN, null)?.isNotBlank() == true &&
            prefs.getString(KEY_REFRESH_TOKEN, null)?.isNotBlank() == true
        return if (hasTokens) WebApiSessionState.Authorized else WebApiSessionState.Expired
    }

    private fun emitSessionState() {
        _sessionState.value = computeSessionState()
    }

    private fun basicAuth(clientId: String, secret: String): String {
        val credentials = "$clientId:$secret"
        val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray())
        return "Basic $encoded"
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

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
