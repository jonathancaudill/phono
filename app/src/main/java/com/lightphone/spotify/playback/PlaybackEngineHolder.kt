package com.lightphone.spotify.playback

import android.content.Context
import com.lightphone.spotify.NativeInit
import com.lightphone.spotify.data.webapi.WebApiAuth
import com.lightphone.spotify.ffi.LibrespotEngine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bridges [PlaybackService] engine ownership (P0b) with lazy [PlaybackController] access.
 * The engine is created on first playback/login need, not at app cold start.
 */
object PlaybackEngineHolder {
    @Volatile
    private var serviceReady = false

    @Volatile
    private var engineLatch = CountDownLatch(1)

    @Volatile
    private var engineAttached = false

    @Volatile
    private var sharedEngine: LibrespotEngine? = null

    private val attachLock = Any()

    /**
     * Get-or-create the shared native engine. Synchronized (double-checked
     * locking) so this is safe even if called directly instead of only through
     * [ensureEngineAttached] — without the lock, two concurrent callers can both
     * observe `sharedEngine == null` and each construct their own
     * [LibrespotEngine], silently orphaning one and leaking its native
     * resources/cache-dir file locks.
     */
    fun createEngine(context: Context): LibrespotEngine {
        sharedEngine?.let { return it }
        synchronized(attachLock) {
            sharedEngine?.let { return it }
            NativeInit.ensureLoaded(context)
            val cacheDir = File(context.filesDir, "spotify-cache").apply { mkdirs() }
            return LibrespotEngine(cacheDir.absolutePath).also { sharedEngine = it }
        }
    }

    /** Build and attach the chosen playback backend on first playback/login need. */
    fun ensureEngineAttached(context: Context, controller: PlaybackController) {
        if (engineAttached) return
        synchronized(attachLock) {
            if (engineAttached) return
            val backend = controller.createBackend()
            controller.attachBackend(backend)
            engineAttached = true
            engineLatch.countDown()
        }
    }

    /** Called from [PlaybackService] once MediaSession is wired. */
    fun markServiceReady() {
        serviceReady = true
        engineLatch.countDown()
    }

    fun clearService() {
        serviceReady = false
        engineLatch.countDown()
    }

    /**
     * Drop the shared engine/backend so a new [PlaybackController] (after
     * re-picking Spotify vs TIDAL) can attach fresh. Call only after logout
     * and with [PlaybackService] stopped.
     */
    fun resetForBackendSwitch() {
        synchronized(attachLock) {
            sharedEngine = null
            engineAttached = false
            serviceReady = false
            engineLatch = CountDownLatch(1)
        }
    }

    fun isServiceReady(): Boolean = serviceReady

    fun awaitEngineReady(timeoutMs: Long = 5000): Boolean {
        engineLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return engineAttached
    }

    fun webApiAuth(context: Context): WebApiAuth = WebApiAuth(context.applicationContext)
}
