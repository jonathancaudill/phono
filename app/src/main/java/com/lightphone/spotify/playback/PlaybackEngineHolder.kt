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
 * The service creates the native engine; the controller is wired on attach.
 */
object PlaybackEngineHolder {
    @Volatile
    private var serviceReady = false

    private val engineLatch = CountDownLatch(1)

    @Volatile
    private var engineAttached = false

    fun createEngine(context: Context): LibrespotEngine {
        NativeInit.ensureLoaded(context)
        val cacheDir = File(context.filesDir, "spotify-cache").apply { mkdirs() }
        return LibrespotEngine(cacheDir.absolutePath)
    }

    /** Called from [PlaybackService.onCreate] once the native engine is constructed. */
    fun attachEngine(controller: PlaybackController, engine: LibrespotEngine) {
        if (!engineAttached) {
            controller.attachEngine(engine)
            engineAttached = true
        }
        engineLatch.countDown()
    }

    fun markServiceReady() {
        serviceReady = true
        engineLatch.countDown()
    }

    fun clearService() {
        serviceReady = false
    }

    fun isServiceReady(): Boolean = serviceReady

    fun awaitEngineReady(timeoutMs: Long = 2000): Boolean {
        engineLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return engineAttached
    }

    fun webApiAuth(context: Context): WebApiAuth = WebApiAuth(context.applicationContext)
}
