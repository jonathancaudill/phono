package com.lightphone.spotify.playback.tidal

import android.util.Log
import com.lightphone.spotify.data.tidal.TidalAuth
import com.lightphone.spotify.data.tidal.TidalUri
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reports completed listens to TIDAL's Event Platform (`ec.tidal.com/api/event-batch`)
 * using the same SQS SendMessageBatch wire format as the official Android client /
 * SONE reference implementation.
 *
 * Events may be accepted (HTTP 200) yet ignored by `play_log` if the client id is
 * not treated as a first-party player — we use ClearHiRes Android credentials.
 */
class TidalPlaybackReporter(
    private val auth: TidalAuth,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tidal-play-report").apply { isDaemon = true }
    }
    private val lock = Any()
    private var current: ActiveSession? = null

    fun setEnabled(enabled: Boolean) {
        auth.setReportPlaysEnabled(enabled)
        if (!enabled) {
            synchronized(lock) { current = null }
        }
    }

    fun isEnabled(): Boolean = auth.reportPlaysEnabled()

    fun onTrackStarted(uri: String, qualityApiValue: String, durationMs: Long) {
        if (!isEnabled()) {
            synchronized(lock) { current = null }
            return
        }
        val productId = TidalUri.rawId(uri)
        if (productId.isBlank()) return
        val toSend = synchronized(lock) {
            val prev = current?.finalizeIfEligible()
            current = ActiveSession(
                sessionId = UUID.randomUUID().toString(),
                productId = productId,
                quality = mapQuality(qualityApiValue),
                durationSecs = (durationMs / 1000L).coerceAtLeast(0L),
                startMs = System.currentTimeMillis(),
            )
            prev
        }
        toSend?.let { postAsync(it) }
    }

    fun onPlaying() {
        synchronized(lock) { current?.resume() }
    }

    fun onPaused() {
        synchronized(lock) { current?.pause() }
    }

    fun onTrackStopped() {
        if (!isEnabled()) {
            synchronized(lock) { current = null }
            return
        }
        val toSend = synchronized(lock) {
            current?.finalizeIfEligible().also { current = null }
        }
        toSend?.let { postAsync(it) }
    }

    private fun postAsync(payloadJson: String) {
        executor.execute {
            runCatching { postEvent(payloadJson) }
                .onFailure { Log.w(TAG, "play report failed: ${it.message}") }
        }
    }

    private fun postEvent(payloadJson: String) {
        val access = auth.currentBearer()
        val ts = System.currentTimeMillis()
        val evtId = UUID.randomUUID().toString()
        val messageBody = buildMessageBody(payloadJson, ts, evtId)
        val headersJson = buildHeadersJson(auth.clientId, access, ts)
        val form = FormBody.Builder()
            .add("SendMessageBatchRequestEntry.1.Id", evtId)
            .add("SendMessageBatchRequestEntry.1.MessageBody", messageBody)
            .add("SendMessageBatchRequestEntry.1.MessageAttribute.1.Name", "Name")
            .add("SendMessageBatchRequestEntry.1.MessageAttribute.1.Value.StringValue", "playback_session")
            .add("SendMessageBatchRequestEntry.1.MessageAttribute.1.Value.DataType", "String")
            .add("SendMessageBatchRequestEntry.1.MessageAttribute.2.Name", "Headers")
            .add("SendMessageBatchRequestEntry.1.MessageAttribute.2.Value.StringValue", headersJson)
            .add("SendMessageBatchRequestEntry.1.MessageAttribute.2.Value.DataType", "String")
            .build()
        val request = Request.Builder()
            .url(EVENT_BATCH_URL)
            .header("Authorization", "Bearer $access")
            .post(form)
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            Log.i(TAG, "playback_session HTTP ${resp.code}: ${body.take(200)}")
            if (resp.code == 401) {
                auth.refreshAfterUnauthorized()?.let { retryToken ->
                    val headers2 = buildHeadersJson(auth.clientId, retryToken, ts)
                    val form2 = FormBody.Builder()
                        .add("SendMessageBatchRequestEntry.1.Id", evtId)
                        .add("SendMessageBatchRequestEntry.1.MessageBody", messageBody)
                        .add("SendMessageBatchRequestEntry.1.MessageAttribute.1.Name", "Name")
                        .add(
                            "SendMessageBatchRequestEntry.1.MessageAttribute.1.Value.StringValue",
                            "playback_session",
                        )
                        .add("SendMessageBatchRequestEntry.1.MessageAttribute.1.Value.DataType", "String")
                        .add("SendMessageBatchRequestEntry.1.MessageAttribute.2.Name", "Headers")
                        .add(
                            "SendMessageBatchRequestEntry.1.MessageAttribute.2.Value.StringValue",
                            headers2,
                        )
                        .add("SendMessageBatchRequestEntry.1.MessageAttribute.2.Value.DataType", "String")
                        .build()
                    val retry = Request.Builder()
                        .url(EVENT_BATCH_URL)
                        .header("Authorization", "Bearer $retryToken")
                        .post(form2)
                        .build()
                    httpClient.newCall(retry).execute().use { r2 ->
                        Log.i(
                            TAG,
                            "playback_session retry HTTP ${r2.code}: ${r2.body?.string().orEmpty().take(200)}",
                        )
                    }
                }
            }
        }
    }

    private class ActiveSession(
        val sessionId: String,
        val productId: String,
        val quality: String,
        val durationSecs: Long,
        val startMs: Long,
    ) {
        private var accumulatedMs = 0L
        private var segmentStartMs: Long? = System.currentTimeMillis()
        private val finalized = AtomicBoolean(false)

        fun pause() {
            val seg = segmentStartMs ?: return
            accumulatedMs += (System.currentTimeMillis() - seg).coerceAtLeast(0L)
            segmentStartMs = null
        }

        fun resume() {
            if (segmentStartMs == null) segmentStartMs = System.currentTimeMillis()
        }

        fun finalizeIfEligible(): String? {
            if (!finalized.compareAndSet(false, true)) return null
            pause()
            val playedSecs = accumulatedMs / 1000.0
            val threshold = when {
                durationSecs <= 0L -> MIN_LISTEN_SECS
                else -> minOf(MIN_LISTEN_SECS.toDouble(), durationSecs * 0.5)
            }
            if (playedSecs < threshold) {
                Log.d(TAG, "skip report $productId — played ${playedSecs}s < ${threshold}s")
                return null
            }
            val endMs = System.currentTimeMillis()
            val endPos = if (durationSecs > 0) minOf(playedSecs, durationSecs.toDouble()) else playedSecs
            return buildPayload(
                sessionId = sessionId,
                productId = productId,
                quality = quality,
                startMs = startMs,
                endMs = endMs,
                endPos = endPos,
            )
        }
    }

    companion object {
        private const val TAG = "TidalPlayReport"
        private const val EVENT_BATCH_URL = "https://ec.tidal.com/api/event-batch"
        private const val MIN_LISTEN_SECS = 30.0
        private val json = Json { ignoreUnknownKeys = true }

        private fun mapQuality(q: String): String = when (q.uppercase()) {
            "LOW" -> "LOW"
            "HIGH" -> "HIGH"
            "LOSSLESS" -> "LOSSLESS"
            "HI_RES", "HI_RES_LOSSLESS" -> "HI_RES_LOSSLESS"
            else -> "LOSSLESS"
        }

        private fun buildPayload(
            sessionId: String,
            productId: String,
            quality: String,
            startMs: Long,
            endMs: Long,
            endPos: Double,
        ): String = buildJsonObject {
            put("playbackSessionId", sessionId)
            put("startTimestamp", startMs)
            put("startAssetPosition", 0.0)
            put("isPostPaywall", true)
            put("productType", "TRACK")
            put("requestedProductId", productId)
            put("actualProductId", productId)
            put("actualAssetPresentation", "FULL")
            put("actualAudioMode", "STEREO")
            put("actualQuality", quality)
            put(
                "actions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("actionType", "PLAYBACK_START")
                            put("timestamp", startMs)
                            put("assetPosition", 0.0)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("actionType", "PLAYBACK_STOP")
                            put("timestamp", endMs)
                            put("assetPosition", endPos)
                        },
                    )
                },
            )
            put("endTimestamp", endMs)
            put("endAssetPosition", endPos)
        }.toString()

        private fun buildMessageBody(payloadJson: String, tsMs: Long, uuid: String): String {
            val payloadElement = json.parseToJsonElement(payloadJson)
            return buildJsonObject {
                put("group", "play_log")
                put("name", "playback_session")
                put("payload", payloadElement)
                put("version", 2)
                put("ts", tsMs)
                put("uuid", uuid)
            }.toString()
        }

        private fun buildHeadersJson(clientId: String, bareToken: String, tsMs: Long): String =
            buildJsonObject {
                put("app-name", "TIDAL_ANDROID")
                put("app-version", "2.92.0")
                put("client-id", clientId)
                put("consent-category", "NECESSARY")
                put("os-name", "ANDROID")
                put("requested-sent-timestamp", tsMs)
                put("authorization", bareToken)
            }.toString()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
