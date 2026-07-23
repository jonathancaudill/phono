package com.lightphone.spotify.data.tidal

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * TIDAL OAuth for full-length hi-res playback via first-party impersonation —
 * the same category as the librespot/Keymaster trick used for Spotify.
 *
 * Two flows are supported:
 *  - **PKCE authorization-code** (primary; drives `TidalLoginScreen`'s WebView).
 *  - **Device-authorization grant** (RFC 8628; [beginDeviceLogin] / [pollDeviceLogin]).
 *
 * Tokens live in [EncryptedSharedPreferences] (`phono_tidal_auth`). Refresh is
 * single-flight (mirrors `WebApiAuth`). The embedded [clientId] is device-class
 * (hi-res tier); TIDAL rotates these periodically, so a remote-config override is
 * accepted via [applyClientIdOverride] and persisted.
 */
class TidalAuth private constructor(
    private val prefs: SharedPreferences,
    private val httpClient: OkHttpClient,
    private val authBase: String,
    private val loginBase: String,
) {

    constructor(context: Context) : this(
        createEncryptedPrefs(context.applicationContext),
        defaultHttpClient(),
        AUTH_BASE,
        LOGIN_BASE,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Any()
    private val refreshFlightLock = Any()

    @Volatile
    private var pendingVerifier: String? = null

    @Volatile
    private var pendingState: String? = null

    @Volatile
    private var pendingUniqueKey: String? = null

    @Volatile
    private var refreshInFlight: CompletableFuture<String?>? = null

    private val _sessionState = MutableStateFlow(computeSessionState())
    val sessionState: StateFlow<TidalSessionState> = _sessionState.asStateFlow()

    /**
     * Client id sent as `X-Tidal-Token` and used for PKCE authorize/refresh.
     * Device-grant sessions use [DEVICE_CLIENT_ID] instead of the Android PKCE id.
     */
    val clientId: String
        get() = synchronized(lock) {
            if (prefs.getString(KEY_AUTH_MODE, AUTH_MODE_PKCE) == AUTH_MODE_DEVICE) {
                return@synchronized DEVICE_CLIENT_ID
            }
            prefs.getString(KEY_CLIENT_ID_OVERRIDE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLIENT_ID
        }

    /** Android PKCE client id (ignores device-grant mode). Used by [buildAuthorizeUrl]. */
    private val pkceClientId: String
        get() = synchronized(lock) {
            prefs.getString(KEY_CLIENT_ID_OVERRIDE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLIENT_ID
        }

    fun applyClientIdOverride(clientId: String?) = synchronized(lock) {
        val editor = prefs.edit()
        if (clientId.isNullOrBlank()) editor.remove(KEY_CLIENT_ID_OVERRIDE)
        else editor.putString(KEY_CLIENT_ID_OVERRIDE, clientId.trim())
        editor.apply()
    }

    fun isAuthorized(): Boolean = synchronized(lock) {
        prefs.getString(KEY_ACCESS_TOKEN, null)?.isNotBlank() == true &&
            prefs.getString(KEY_REFRESH_TOKEN, null)?.isNotBlank() == true
    }

    /** TIDAL numeric user id, required for `/users/{id}/…` endpoints. */
    fun userId(): String? = synchronized(lock) { prefs.getString(KEY_USER_ID, null) }

    /** Two-letter market for `countryCode=` query params. */
    fun countryCode(): String = synchronized(lock) {
        prefs.getString(KEY_COUNTRY, null)?.takeIf { it.isNotBlank() } ?: "US"
    }

    /** Persisted TIDAL stream tier (independent of Spotify's StreamingQuality). */
    fun audioQuality(): TidalAudioQuality = synchronized(lock) {
        TidalAudioQuality.fromApiValue(prefs.getString(KEY_AUDIO_QUALITY, null))
    }

    fun setAudioQuality(quality: TidalAudioQuality) = synchronized(lock) {
        prefs.edit().putString(KEY_AUDIO_QUALITY, quality.apiValue).apply()
    }

    /**
     * Quality used when enqueueing **new** offline downloads. Independent of
     * [audioQuality]; changing this never rewrites existing pins.
     */
    fun downloadQuality(): TidalAudioQuality = synchronized(lock) {
        TidalAudioQuality.fromApiValue(prefs.getString(KEY_DOWNLOAD_QUALITY, null))
    }

    fun setDownloadQuality(quality: TidalAudioQuality) = synchronized(lock) {
        prefs.edit().putString(KEY_DOWNLOAD_QUALITY, quality.apiValue).apply()
    }

    /** Opt-in Event Platform play reporting (default on). */
    fun reportPlaysEnabled(): Boolean = synchronized(lock) {
        prefs.getBoolean(KEY_REPORT_PLAYS, true)
    }

    fun setReportPlaysEnabled(enabled: Boolean) = synchronized(lock) {
        prefs.edit().putBoolean(KEY_REPORT_PLAYS, enabled).apply()
    }

    /**
     * Refresh userId/country from `/v1/sessions` when missing. Playbackinfo 4032
     * ("No content matching subscription location") is often a stale/wrong
     * countryCode defaulting to US.
     */
    fun ensureSessionMeta() {
        val needsUser: Boolean
        val needsCountry: Boolean
        synchronized(lock) {
            needsUser = prefs.getString(KEY_USER_ID, null).isNullOrBlank()
            needsCountry = prefs.getString(KEY_COUNTRY, null).isNullOrBlank()
        }
        if (!needsUser && !needsCountry) return
        val access = synchronized(lock) { prefs.getString(KEY_ACCESS_TOKEN, null) } ?: return
        runCatching { fetchSession(access) }.getOrNull()?.let { session ->
            synchronized(lock) {
                val editor = prefs.edit()
                if (needsUser) session.userId?.toString()?.let { editor.putString(KEY_USER_ID, it) }
                if (needsCountry) session.countryCode?.let { editor.putString(KEY_COUNTRY, it) }
                editor.apply()
            }
        }
    }

    // --- PKCE authorization-code flow --------------------------------------

    fun buildAuthorizeUrl(): String {
        val verifier = randomUrlSafe(64)
        pendingVerifier = verifier
        val challenge = codeChallenge(verifier)
        val state = randomUrlSafe(16)
        pendingState = state
        // Matches orpheusdl-tidal / python-tidal Android PKCE authorize params.
        val uniqueKey = SecureRandom().nextLong().toULong().toString(16)
        pendingUniqueKey = uniqueKey
        return buildString {
            append(loginBase).append("/authorize")
            append("?response_type=code")
            append("&client_id=").append(urlEncode(pkceClientId))
            append("&redirect_uri=").append(urlEncode(REDIRECT_URI))
            append("&scope=").append(urlEncode(SCOPES))
            append("&code_challenge_method=S256")
            append("&code_challenge=").append(urlEncode(challenge))
            append("&client_unique_key=").append(urlEncode(uniqueKey))
            append("&appMode=android")
            append("&lang=en_US")
            append("&state=").append(urlEncode(state))
        }
    }

    /**
     * Exchange the authorization code for tokens. [state] must match the value
     * handed out by [buildAuthorizeUrl] (CSRF guard) — mirrors `WebApiAuth`.
     */
    fun exchangeCode(code: String, state: String?): Result<Unit> {
        val expectedState = pendingState
        val verifier = pendingVerifier
        val uniqueKey = pendingUniqueKey
        pendingState = null
        pendingVerifier = null
        pendingUniqueKey = null
        if (expectedState == null || state == null || expectedState != state) {
            return Result.failure(TidalAuthException("OAuth state mismatch — aborting login"))
        }
        if (verifier == null) {
            return Result.failure(TidalAuthException("Missing PKCE verifier — restart login"))
        }
        return runCatching {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", pkceClientId)
                .add("code_verifier", verifier)
                .add("scope", SCOPES)
            if (!uniqueKey.isNullOrBlank()) body.add("client_unique_key", uniqueKey)
            postToken(body.build())
        }
    }

    // --- Device-authorization grant (RFC 8628) -----------------------------

    /**
     * Device grant (RFC 8628) uses the TV-class credentials from streamrip /
     * python-tidal (`fX2Jxdmnt…`), not the Android PKCE client id.
     */
    fun beginDeviceLogin(): Result<TidalDeviceCode> = runCatching {
        val body = FormBody.Builder()
            .add("client_id", DEVICE_CLIENT_ID)
            .add("scope", SCOPES)
            .build()
        val request = Request.Builder()
            .url("$authBase/v1/oauth2/device_authorization")
            .post(body)
            .build()
        val response = httpClient.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IOException("device_authorization failed: HTTP ${response.code} $text")
        }
        val dto = json.decodeFromString<DeviceAuthResponse>(text)
        TidalDeviceCode(
            deviceCode = dto.deviceCode,
            userCode = dto.userCode,
            verificationUri = dto.verificationUri,
            verificationUriComplete = dto.verificationUriComplete,
            intervalSeconds = dto.interval ?: 2,
            expiresInSeconds = dto.expiresIn ?: 300,
        )
    }

    /** One poll iteration; caller loops on [interval]. Result.success on completion. */
    fun pollDeviceLogin(deviceCode: String): Result<Boolean> = runCatching {
        val body = FormBody.Builder()
            .add("client_id", DEVICE_CLIENT_ID)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("scope", SCOPES)
            .build()
        // streamrip / tidal-dl send HTTP Basic (client_id:client_secret) on device token poll.
        val request = Request.Builder()
            .url("$authBase/v1/oauth2/token")
            .header("Authorization", okhttp3.Credentials.basic(DEVICE_CLIENT_ID, DEVICE_CLIENT_SECRET))
            .post(body)
            .build()
        val response = httpClient.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        when {
            response.isSuccessful -> {
                storeTokens(json.decodeFromString(text), authMode = AUTH_MODE_DEVICE)
                true
            }
            // authorization_pending / slow_down: keep polling
            text.contains("authorization_pending") || text.contains("slow_down") -> false
            else -> throw TidalAuthException("Device login failed: HTTP ${response.code}")
        }
    }

    // --- Bearer / refresh ---------------------------------------------------

    /** Valid bearer, refreshing proactively near expiry. */
    fun currentBearer(): String {
        readFreshAccessToken()?.let { return it }
        refreshSingleFlight() ?: throw TidalAuthException("TIDAL session expired — sign in again")
        return synchronized(lock) {
            prefs.getString(KEY_ACCESS_TOKEN, null)
                ?: throw TidalAuthException("TIDAL not authorized")
        }
    }

    /** OkHttp Authenticator hook: force a refresh after a 401. */
    fun refreshAfterUnauthorized(): String? {
        synchronized(lock) { prefs.edit().putLong(KEY_EXPIRES_AT_MS, 0L).apply() }
        return refreshSingleFlight()
    }

    fun clearAll() = synchronized(lock) {
        prefs.edit().clear().apply()
        emitSessionState()
    }

    private fun readFreshAccessToken(): String? = synchronized(lock) {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
            throw TidalAuthException("TIDAL not authorized — sign in")
        }
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)
        if (System.currentTimeMillis() + REFRESH_EARLY_MS < expiresAt) access else null
    }

    private fun refreshSingleFlight(): String? {
        val (future, isLeader) = synchronized(refreshFlightLock) {
            refreshInFlight?.let { return@synchronized it to false }
            CompletableFuture<String?>().also { refreshInFlight = it } to true
        }
        if (!isLeader) {
            return try {
                future.get()
            } catch (_: Exception) {
                null
            }
        }
        try {
            val result = performRefresh()
            future.complete(result)
            return result
        } catch (e: Exception) {
            Log.w(TAG, "TIDAL refresh failed", e)
            future.complete(null)
            return null
        } finally {
            synchronized(refreshFlightLock) {
                if (refreshInFlight === future) refreshInFlight = null
            }
        }
    }

    private fun performRefresh(): String? {
        readFreshAccessToken()?.let { return it }
        val (refresh, authMode) = synchronized(lock) {
            val token = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
            token to (prefs.getString(KEY_AUTH_MODE, AUTH_MODE_PKCE) ?: AUTH_MODE_PKCE)
        }
        val response = if (authMode == AUTH_MODE_DEVICE) {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", DEVICE_CLIENT_ID)
                .add("scope", SCOPES)
                .build()
            Request.Builder()
                .url("$authBase/v1/oauth2/token")
                .header("Authorization", okhttp3.Credentials.basic(DEVICE_CLIENT_ID, DEVICE_CLIENT_SECRET))
                .post(body)
                .build()
                .let { httpClient.newCall(it).execute() }
        } else {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", pkceClientId)
                .add("scope", SCOPES)
                .build()
            Request.Builder().url("$authBase/v1/oauth2/token").post(body).build()
                .let { httpClient.newCall(it).execute() }
        }
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            if (text.contains("invalid_grant")) synchronized(lock) { clearTokensInternal() }
            return null
        }
        // Refresh responses omit refresh_token; preserve the existing one.
        storeTokens(json.decodeFromString(text), preserveRefresh = refresh, authMode = authMode)
        return synchronized(lock) { prefs.getString(KEY_ACCESS_TOKEN, null) }
    }

    private fun postToken(body: FormBody) {
        val request = Request.Builder().url("$authBase/v1/oauth2/token").post(body).build()
        val response = httpClient.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw TidalAuthException("Token exchange failed: HTTP ${response.code} $text")
        }
        storeTokens(json.decodeFromString(text), authMode = AUTH_MODE_PKCE)
    }

    private fun storeTokens(
        tokens: TokenResponse,
        preserveRefresh: String? = null,
        authMode: String = AUTH_MODE_PKCE,
    ) {
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000L
        var resolvedUserId = tokens.userId?.toString() ?: tokens.user?.userId?.toString()
        var resolvedCountry = tokens.user?.countryCode ?: tokens.countryCode
        // Token payloads sometimes omit user — community clients then hit /v1/sessions
        // (streamrip `_login_by_access_token`, tidal-dl `verifyAccessToken`).
        if (resolvedUserId.isNullOrBlank() || resolvedCountry.isNullOrBlank()) {
            runCatching { fetchSession(tokens.accessToken) }.getOrNull()?.let { session ->
                if (resolvedUserId.isNullOrBlank()) resolvedUserId = session.userId?.toString()
                if (resolvedCountry.isNullOrBlank()) resolvedCountry = session.countryCode
            }
        }
        synchronized(lock) {
            val editor = prefs.edit()
                .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
                .putLong(KEY_EXPIRES_AT_MS, expiresAt)
                .putString(KEY_AUTH_MODE, authMode)
            (tokens.refreshToken ?: preserveRefresh)?.let { editor.putString(KEY_REFRESH_TOKEN, it) }
            resolvedUserId?.let { editor.putString(KEY_USER_ID, it) }
            resolvedCountry?.let { editor.putString(KEY_COUNTRY, it) }
            editor.apply()
            emitSessionState()
        }
    }

    private fun fetchSession(accessToken: String): SessionResponse {
        val request = Request.Builder()
            .url("$API_BASE/sessions")
            .header("Authorization", "Bearer $accessToken")
            .header("X-Tidal-Token", clientId)
            .header("Accept", "application/json")
            .get()
            .build()
        val response = httpClient.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IOException("sessions failed: HTTP ${response.code} $text")
        }
        return json.decodeFromString(text)
    }

    private fun clearTokensInternal() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT_MS)
            .apply()
        emitSessionState()
    }

    private fun computeSessionState(): TidalSessionState =
        if (prefs.getString(KEY_ACCESS_TOKEN, null)?.isNotBlank() == true &&
            prefs.getString(KEY_REFRESH_TOKEN, null)?.isNotBlank() == true
        ) {
            TidalSessionState.Authenticated
        } else {
            TidalSessionState.NotAuthenticated
        }

    private fun emitSessionState() {
        _sessionState.value = computeSessionState()
    }

    // --- PKCE helpers -------------------------------------------------------

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        @SerialName("user_id") val userId: Long? = null,
        @SerialName("countryCode") val countryCode: String? = null,
        val user: TokenUser? = null,
    )

    @Serializable
    private data class TokenUser(
        val userId: Long? = null,
        val countryCode: String? = null,
    )

    @Serializable
    private data class DeviceAuthResponse(
        @SerialName("deviceCode") val deviceCode: String,
        @SerialName("userCode") val userCode: String,
        @SerialName("verificationUri") val verificationUri: String = "link.tidal.com",
        @SerialName("verificationUriComplete") val verificationUriComplete: String? = null,
        @SerialName("expiresIn") val expiresIn: Int? = null,
        @SerialName("interval") val interval: Int? = null,
    )

    @Serializable
    private data class SessionResponse(
        val userId: Long? = null,
        val countryCode: String? = null,
    )

    companion object {
        /**
         * Android app redirect intercepted by the login WebView. Matches
         * orpheusdl-tidal `TidalMobileSession.redirect_uri` and python-tidal PKCE.
         * Never `localhost`.
         */
        const val REDIRECT_URI = "https://tidal.com/android/login/auth"

        /**
         * Android PKCE **clear** hi-res client — `DefaultClearHiResV2ClientId` from
         * official `com.aspiro.tidal` 2.201.0 `assets/secrets.properties`.
         *
         * We need the clear (non-Widevine) tier so ExoPlayer can play BTS FLAC
         * without a CDM. The classic `DefaultHiResClientId` (`6BDS…`) is what
         * high-tide/python-tidal use, but it often yields DASH/DRM manifests;
         * ClearHiResV2 matches our Media3 path. Override via
         * [applyClientIdOverride] when TIDAL rotates it.
         */
        const val DEFAULT_CLIENT_ID = "YzxDFZ7SEJFgqNIz"

        /** Paired secret for [DEFAULT_CLIENT_ID] (`DefaultClearHiResV2ClientSecret`). */
        const val DEFAULT_CLIENT_SECRET =
            "l1KyypkhxHrEN9RCSwUWRgOlVJTpUJJY6vIqW8IfOmc="

        /**
         * Automotive / device-grant client — `DefaultAutomotiveClientId` from the
         * same APK secrets file. Used only by [beginDeviceLogin] / [pollDeviceLogin].
         */
        const val DEVICE_CLIENT_ID = "fX2JxdmntZWK0ixT"
        private const val DEVICE_CLIENT_SECRET =
            "1Nn9AfDAjxrgJFJbKNWLeAyKGVGmINuXPPLHVXAvxAg="

        const val SCOPES = "r_usr w_usr w_sub"
        private const val AUTH_BASE = "https://auth.tidal.com"
        private const val LOGIN_BASE = "https://login.tidal.com"
        /** Same host as [TidalApiClient]; streamrip also accepts api.tidalhifi.com. */
        private const val API_BASE = "https://api.tidal.com/v1"
        private const val REFRESH_EARLY_MS = 60_000L
        private const val PREFS_NAME = "phono_tidal_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_COUNTRY = "country_code"
        private const val KEY_CLIENT_ID_OVERRIDE = "client_id_override"
        private const val KEY_AUTH_MODE = "auth_mode"
        private const val KEY_AUDIO_QUALITY = "audio_quality"
        private const val KEY_DOWNLOAD_QUALITY = "download_quality"
        private const val KEY_REPORT_PLAYS = "report_plays"
        private const val AUTH_MODE_PKCE = "pkce"
        private const val AUTH_MODE_DEVICE = "device"
        private const val TAG = "TidalAuth"

        internal fun createForTest(
            prefs: SharedPreferences,
            httpClient: OkHttpClient,
            authBase: String,
            loginBase: String,
        ): TidalAuth = TidalAuth(prefs, httpClient, authBase, loginBase)

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
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
}

class TidalAuthException(message: String) : Exception(message)
