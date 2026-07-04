package com.lightphone.spotify.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred

private class CameraPermissionRequestState {
    var pending: CompletableDeferred<Boolean>? = null
}

@Composable
fun rememberCameraPermissionHandlers(): Pair<suspend () -> Result<Boolean>, suspend () -> Unit> {
    val context = LocalContext.current
    val requestState = remember { CameraPermissionRequestState() }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        requestState.pending?.complete(granted)
    }

    val checkPermission: suspend () -> Result<Boolean> = remember(context) {
        {
            Result.success(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
    }

    val launchRequest: suspend () -> Unit = remember(launcher, requestState) {
        {
            val deferred = CompletableDeferred<Boolean>()
            requestState.pending = deferred
            launcher.launch(Manifest.permission.CAMERA)
            deferred.await()
        }
    }

    return checkPermission to launchRequest
}
