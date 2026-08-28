package com.lightphone.spotify.update

import com.lightphone.spotify.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A published release newer than the running build, with its APK asset. */
data class AvailableUpdate(
    val version: String,
    val apkUrl: String,
    val apkBytes: Long,
)

/**
 * Reads phono's GitHub releases. `/releases/latest` already excludes drafts and
 * pre-releases, so betas never reach the prompt.
 */
class GithubReleaseClient(
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val latestReleaseUrl: String = LATEST_RELEASE_URL,
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun latestNewerRelease(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(latestReleaseUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        val release = client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) {
                throw IOException("GitHub releases HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty release response")
            json.decodeFromString<GithubRelease>(body)
        }

        if (release.draft || release.prerelease) return@withContext null
        if (!isNewerVersion(release.tagName, currentVersion)) return@withContext null

        val apk = release.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) && it.browserDownloadUrl.isNotBlank()
        } ?: return@withContext null

        AvailableUpdate(
            version = release.tagName.trim().removePrefix("v"),
            apkUrl = apk.browserDownloadUrl,
            apkBytes = apk.size,
        )
    }

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/jonathancaudill/phono/releases/latest"

        private val json = Json { ignoreUnknownKeys = true }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String = "",
    val size: Long = 0L,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

/** Numeric dotted-component compare; unparseable versions are treated as "not newer". */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val candidateParts = versionParts(candidate)
    val currentParts = versionParts(current)
    if (candidateParts.isEmpty() || currentParts.isEmpty()) return false

    for (i in 0 until maxOf(candidateParts.size, currentParts.size)) {
        val a = candidateParts.getOrElse(i) { 0 }
        val b = currentParts.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

private fun versionParts(raw: String): List<Int> {
    val core = raw.trim()
        .removePrefix("v")
        .substringBefore('-')
        .substringBefore('+')
    if (core.isEmpty()) return emptyList()
    val parts = core.split('.').map { it.toIntOrNull() }
    return if (parts.any { it == null }) emptyList() else parts.filterNotNull()
}
