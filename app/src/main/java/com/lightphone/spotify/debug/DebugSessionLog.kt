package com.lightphone.spotify.debug

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Debug-mode NDJSON ingest (session 0d7c80). Requires `adb reverse tcp:7275 tcp:7275` on device. */
object DebugSessionLog {
    private val client = OkHttpClient()
    private val jsonMedia = "application/json".toMediaType()
    private const val ENDPOINT =
        "http://127.0.0.1:7275/ingest/c5bc4e13-cb65-4040-906d-cf2a15c3b0ac"
    private const val SESSION = "0d7c80"

    fun log(
        location: String,
        message: String,
        data: Map<String, Any?>,
        hypothesisId: String,
        runId: String = "pre-fix",
    ) {
        Thread {
            runCatching {
                val payload = JSONObject().apply {
                    put("sessionId", SESSION)
                    put("timestamp", System.currentTimeMillis())
                    put("location", location)
                    put("message", message)
                    put("hypothesisId", hypothesisId)
                    put("runId", runId)
                    put("data", JSONObject(data))
                }
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Debug-Session-Id", SESSION)
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
                client.newCall(request).execute().close()
            }
        }.start()
    }
}
