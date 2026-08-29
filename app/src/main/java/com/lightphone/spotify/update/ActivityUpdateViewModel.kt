package com.lightphone.spotify.update

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * [UpdateViewModel] must live on the Activity, not the Settings [androidx.navigation.NavBackStackEntry].
 * Settings sits inside a NavHost, so a default [viewModel] call would create a second instance
 * and the overlay in [com.lightphone.spotify.ui.navigation.SpotifyApp] would never see the result.
 */
@Composable
fun activityUpdateViewModel(): UpdateViewModel {
    val activity = LocalContext.current as ComponentActivity
    return viewModel(viewModelStoreOwner = activity)
}
