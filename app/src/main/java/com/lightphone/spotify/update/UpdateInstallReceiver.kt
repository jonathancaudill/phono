package com.lightphone.spotify.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Install failures surfaced back to the UI. A success never arrives here in a useful
 * state — the old process is already gone — so only failures are published.
 */
object UpdateInstallStatus {
    private val _failures = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val failures: SharedFlow<String> = _failures

    fun publishFailure(message: String) {
        _failures.tryEmit(message)
    }
}

/** Receives [PackageInstaller] session status for the self-update commit. */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirmWithSystemInstaller(context, intent)
            PackageInstaller.STATUS_SUCCESS -> relaunch(context)
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateInstallStatus.publishFailure("Update cancelled")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Install failed: status=$status message=$message")
                UpdateInstallStatus.publishFailure("Update failed")
            }
        }
    }

    private fun confirmWithSystemInstaller(context: Context, intent: Intent) {
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        if (confirm == null) {
            UpdateInstallStatus.publishFailure("Update failed")
            return
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(confirm) }.onFailure {
            Log.w(TAG, "Could not show system installer", it)
            UpdateInstallStatus.publishFailure("Allow phono to install apps, then retry")
        }
    }

    /**
     * Best effort: background activity launch restrictions may drop this, in which case
     * the user reopens phono from the launcher.
     */
    private fun relaunch(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { context.startActivity(launch) }.onFailure {
            Log.w(TAG, "Could not relaunch after update", it)
        }
    }

    private companion object {
        const val TAG = "UpdateInstall"
    }
}
