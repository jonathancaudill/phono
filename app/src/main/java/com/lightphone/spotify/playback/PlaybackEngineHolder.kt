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

    private val engineLatch = CountDownLatch(1)

    @Volatile
    private var engineAttached = false

    @Volatile
    private var sharedEngine: LibrespotEngine? = null

    private val attachLock = Any()

    fun createEngine(context: Context): LibrespotEngine {
        sharedEngine?.let { return it }
        NativeInit.ensureLoaded(context)
        val cacheDir = File(context.filesDir, "spotify-cache").apply { mkdirs() }
        return LibrespotEngine(cacheDir.absolutePath).also { sharedEngine = it }
    }

    /** Create and attach the native engine on first playback/login need. */
    fun ensureEngineAttached(context: Context, controller: PlaybackController) {
        if (engineAttached) return
        synchronized(attachLock) {
            if (engineAttached) return
            val engine = createEngine(context.applicationContext)
            controller.attachEngine(engine)
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

    fun isServiceReady(): Boolean = serviceReady

    fun awaitEngineReady(timeoutMs: Long = 5000): Boolean {
        engineLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return engineAttached
    }

    fun webApiAuth(context: Context): WebApiAuth = WebApiAuth(context.applicationContext)
}
