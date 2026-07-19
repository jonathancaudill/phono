package com.lightphone.spotify.playback.tidal

import android.util.Base64
import android.util.Log
import com.lightphone.spotify.data.tidal.TidalApiClient
import com.lightphone.spotify.data.tidal.TidalAudioQuality
import com.lightphone.spotify.data.tidal.TidalBtsManifest
import java.io.IOException
import kotlinx.serialization.json.Json

/**
 * JIT playbackinfo resolution for TIDAL.
 *
 * ClearHiRes often returns `application/dash+xml` (clear segmented FLAC). Linux
 * clients (high-tide, mopidy-tidal, low-tide) write that MPD to disk and let the
 * player demux it — they do **not** parse `group="main"` with strict DASH libs
 * (TIDAL ships non-numeric AdaptationSet@group; Media3's parser crashes on it).
 */
internal sealed class TidalResolvedStream {
    abstract val cacheKey: String
    abstract val audioQuality: String

    data class Progressive(
        val url: String,
        override val cacheKey: String,
        override val audioQuality: String,
    ) : TidalResolvedStream()

    data class ClearDash(
        /** Sanitized MPD XML safe for Media3 [DashManifestParser]. */
        val mpdXml: String,
        override val cacheKey: String,
        override val audioQuality: String,
    ) : TidalResolvedStream()
}

internal object TidalStreamResolve {
    private const val TAG = "TidalMedia"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun resolve(api: TidalApiClient, trackId: String, quality: String): TidalResolvedStream {
        var lastError: Exception? = null
        for (q in qualityFallbacks(quality)) {
            try {
                val info = api.playbackInfo(trackId, q)
                val mime = info.manifestMimeType
                val cacheKey = "tidal:$trackId:${info.audioQuality.ifBlank { q }}"
                val decoded = String(Base64.decode(info.manifest, Base64.DEFAULT))
                when {
                    mime.contains("bts", ignoreCase = true) -> {
                        val manifest = json.decodeFromString<TidalBtsManifest>(decoded)
                        val enc = manifest.encryptionType.ifBlank { "NONE" }
                        if (!enc.equals("NONE", ignoreCase = true)) {
                            lastError = IOException(
                                "TIDAL stream encrypted ($enc) for $trackId @ ${info.audioQuality}",
                            )
                            Log.w(TAG, lastError!!.message!!)
                            continue
                        }
                        val url = manifest.urls.firstOrNull()
                            ?: throw IOException("TIDAL bts manifest for $trackId had no urls")
                        Log.i(TAG, "BTS $trackId@${info.audioQuality} -> ${url.take(96)}")
                        return TidalResolvedStream.Progressive(url, cacheKey, info.audioQuality)
                    }
                    mime.contains("dash", ignoreCase = true) -> {
                        if (isWidevineDash(decoded)) {
                            lastError = IOException(
                                "TIDAL Widevine DASH for $trackId @ ${info.audioQuality}",
                            )
                            Log.w(TAG, lastError!!.message!!)
                            continue
                        }
                        val sanitized = sanitizeTidalMpd(decoded)
                        Log.i(TAG, "clear DASH $trackId@${info.audioQuality} (${sanitized.length} chars)")
                        return TidalResolvedStream.ClearDash(sanitized, cacheKey, info.audioQuality)
                    }
                    else -> {
                        lastError = IOException(
                            "TIDAL returned $mime for $trackId @ ${info.audioQuality}",
                        )
                        Log.w(TAG, lastError!!.message!!)
                        continue
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "playbackinfo $trackId@$q failed: ${e.message?.take(200)}")
            }
        }
        throw lastError ?: IOException("playbackinfo failed for $trackId")
    }

    fun qualityFallbacks(requested: String): List<String> {
        val order = listOf("HI_RES_LOSSLESS", "LOSSLESS", "HIGH", "LOW")
        val start = order.indexOf(requested).takeIf { it >= 0 }
            ?: order.indexOf(TidalAudioQuality.DEFAULT.apiValue)
        return order.drop(start.coerceAtLeast(0)).distinct()
    }

    /**
     * TIDAL MPDs include `group="main"` (non-numeric). Spec requires an unsigned
     * int; Media3 [DashManifestParser] NumberFormatExceptions. mopidy-tidal
     * stopped parsing for the same reason — we strip the attribute instead.
     */
    fun sanitizeTidalMpd(mpdXml: String): String {
        var s = mpdXml
        s = s.replace(Regex("""\s+group="[^"]*""""), "")
        // Stub ContentProtection with empty KID confuses some paths; clear streams
        // don't need it (high-tide / mopidy also ignore DRM stubs).
        s = s.replace(Regex("""(?is)<ContentProtection\b[^>]*/\s*>"""), "")
        s = s.replace(Regex("""(?is)<ContentProtection\b[^>]*>.*?</ContentProtection>"""), "")
        return s
    }

    fun isWidevineDash(mpdXml: String): Boolean {
        val lower = mpdXml.lowercase()
        if (!lower.contains("contentprotection")) return false
        if (lower.contains("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")) return true
        if (lower.contains("urn:uuid:edef8ba9")) return true
        if (Regex("""default_kid\s*=\s*"[^"]+"""", RegexOption.IGNORE_CASE).containsMatchIn(mpdXml) &&
            lower.contains("cenc:pssh")
        ) {
            return true
        }
        return false
    }
}
