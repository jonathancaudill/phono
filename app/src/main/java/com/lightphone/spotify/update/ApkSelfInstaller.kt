package com.lightphone.spotify.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Streams a release APK straight into a [PackageInstaller] session — the bytes never
 * touch our own storage, so there is no partial download to clean up.
 *
 * Because the target package is our own, Android skips the system confirmation dialog
 * as long as we hold `UPDATE_PACKAGES_WITHOUT_USER_ACTION` and the new APK is signed
 * with the same key. When it refuses anyway, the session reports
 * `STATUS_PENDING_USER_ACTION` and [UpdateInstallReceiver] shows the system installer.
 */
class ApkSelfInstaller(
    context: Context,
    private val client: OkHttpClient = defaultClient(),
) {
    private val context = context.applicationContext

    /**
     * Downloads and commits the update. Returns once the session is committed; the
     * process is killed shortly afterwards as the new APK is applied.
     *
     * [onProgress] receives 0f..1f, or null while the size is unknown.
     */
    suspend fun downloadAndInstall(
        update: AvailableUpdate,
        onProgress: (Float?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                writeApk(session, update, onProgress)
                session.commit(statusIntentSender(sessionId))
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }
    }

    private suspend fun writeApk(
        session: PackageInstaller.Session,
        update: AvailableUpdate,
        onProgress: (Float?) -> Unit,
    ) {
        val request = Request.Builder()
            .url(update.apkUrl)
            .header("Accept", "application/octet-stream")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("APK download HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty APK response")
            val total = body.contentLength().takeIf { it > 0 } ?: update.apkBytes

            session.openWrite(APK_ENTRY_NAME, 0, total.takeIf { it > 0 } ?: -1L).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var written = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        } else {
                            onProgress(null)
                        }
                    }
                }
                session.fsync(out)
            }
        }
    }

    private fun statusIntentSender(sessionId: Int): IntentSender =
        PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(context, UpdateInstallReceiver::class.java),
            // Mutable: the platform fills in the status extras before dispatching.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender

    companion object {
        private const val APK_ENTRY_NAME = "phono-update.apk"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
