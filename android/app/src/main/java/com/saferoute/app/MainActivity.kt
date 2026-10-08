// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.feature.emergency.EmergencyNotificationController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The single activity of the app. It owns the window; everything visible is Compose.
 *
 * `@AndroidEntryPoint` lets Hilt inject into this activity and into the ViewModels of the
 * screens it hosts.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var emergencyNotification: EmergencyNotificationController

    /**
     * The app came to the front. If the user's notification shortcut is on and it was swiped
     * away or removed in the meantime, it is put back here. Nothing is requested.
     */
    override fun onStart() {
        super.onStart()
        emergencyNotification.syncInBackground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Both calls must come before super.onCreate(): the first swaps the starting (splash)
        // theme for the real one, the second lets the app draw behind the status and
        // navigation bars. Screens then keep their content clear of the bars with insets.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            SafeRouteTheme {
                SafeRouteApp()
            }
        }
    }
}
