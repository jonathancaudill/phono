package com.lightphone.spotify

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lightphone.spotify.data.backend.BackendPreferences
import com.lightphone.spotify.ui.light.LightPhonoTheme
import com.lightphone.spotify.ui.navigation.SpotifyApp
import com.lightphone.spotify.ui.screens.BackendPickerScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted) {
                    android.util.Log.w(
                        "MainActivity",
                        "POST_NOTIFICATIONS denied; playback notification may be limited",
                    )
                }
            }.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val backendPrefs = BackendPreferences(this)

        setContent {
            // The controller is backend-specific, so the choice must be made before
            // AppViewModel (and thus the controller) is created. Gate the picker here.
            if (!backendPrefs.isChosen()) {
                LightPhonoTheme {
                    BackendPickerScreen(
                        onPicked = { choice ->
                            backendPrefs.setChoice(choice)
                            // Rebuild from scratch so App.onCreate builds the right controller.
                            recreate()
                        },
                    )
                }
            } else {
                (application as App).ensureController()
                SpotifyApp()
            }
        }
    }
}
