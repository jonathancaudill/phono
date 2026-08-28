package com.lightphone.spotify.update

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    /** Only shown for a check the user asked for; the launch check stays silent. */
    data object Checking : UpdateUiState
    data class Available(val version: String) : UpdateUiState
    data class Downloading(val percent: Int?) : UpdateUiState
    data object Installing : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

/**
 * Drives the update prompt. Activity-scoped, so `SpotifyApp` (automatic check) and
 * `SettingsScreen` (manual check) share one instance and one overlay.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = UpdatePreferences(application)
    private val releases = GithubReleaseClient()
    private val installer = ApkSelfInstaller(application)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var pending: AvailableUpdate? = null
    private var launchCheckDone = false
    private var job: Job? = null

    init {
        viewModelScope.launch {
            UpdateInstallStatus.failures.collect { message ->
                _state.value = UpdateUiState.Failed(message)
            }
        }
    }

    /** Runs at most once per process, and at most once per [UpdatePreferences.CHECK_INTERVAL_MS]. */
    fun checkOnLaunch() {
        if (launchCheckDone) return
        launchCheckDone = true
        if (prefs.isIgnored()) return
        if (!prefs.isDue(System.currentTimeMillis())) return
        check(userInitiated = false)
    }

    /** Asking for updates undoes an earlier IGNORE. */
    fun checkNow() {
        prefs.setIgnored(false)
        check(userInitiated = true)
    }

    fun ignore() {
        prefs.setIgnored(true)
        pending = null
        _state.value = UpdateUiState.Idle
    }

    fun dismiss() {
        _state.value = UpdateUiState.Idle
    }

    fun applyUpdate() {
        val update = pending ?: return
        if (job?.isActive == true) return
        _state.value = UpdateUiState.Downloading(null)
        job = viewModelScope.launch {
            try {
                installer.downloadAndInstall(update) { fraction ->
                    _state.value = UpdateUiState.Downloading(fraction?.let { (it * 100).toInt() })
                }
                _state.value = UpdateUiState.Installing
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Update download failed", t)
                _state.value = UpdateUiState.Failed("Download failed")
            }
        }
    }

    private fun check(userInitiated: Boolean) {
        if (job?.isActive == true) return
        if (userInitiated) _state.value = UpdateUiState.Checking
        job = viewModelScope.launch {
            val update = try {
                releases.latestNewerRelease()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Update check failed", t)
                _state.value = if (userInitiated) {
                    UpdateUiState.Failed("Could not reach GitHub")
                } else {
                    UpdateUiState.Idle
                }
                return@launch
            }
            prefs.markChecked(System.currentTimeMillis())
            pending = update
            _state.value = when {
                update != null -> UpdateUiState.Available(update.version)
                userInitiated -> UpdateUiState.UpToDate
                else -> UpdateUiState.Idle
            }
        }
    }

    private companion object {
        const val TAG = "UpdateViewModel"
    }
}
