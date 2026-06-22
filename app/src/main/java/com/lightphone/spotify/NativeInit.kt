package com.lightphone.spotify

import android.content.Context

/**
 * Bootstraps the native library.
 *
 * [initAndroidContext] is a raw-JNI entrypoint (implemented in Rust in
 * `android_ctx.rs`). It must run once, before any playback, so cpal's AAudio
 * backend can read the JavaVM/Context from `ndk_context`. JNI_OnLoad's reserved
 * arg is null, so we hand the real applicationContext across ourselves.
 */
object NativeInit {
    @Volatile
    private var initialized = false

    fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            System.loadLibrary("spotify_core")
            initAndroidContext(context.applicationContext)
            initialized = true
        }
    }

    private external fun initAndroidContext(context: Context)
}
