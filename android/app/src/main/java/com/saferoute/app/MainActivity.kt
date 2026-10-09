// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import androidx.lifecycle.lifecycleScope
import com.saferoute.app.core.emergency.SosEngine
import com.saferoute.app.core.emergency.SosRunState
import com.saferoute.app.core.emergency.SosRunner
import com.saferoute.app.feature.emergency.EmergencyNotificationController
import com.saferoute.app.feature.emergency.EmergencyShortcut
import com.saferoute.app.feature.emergency.SosOpenMode
import kotlinx.coroutines.launch
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

    @Inject lateinit var sosRunner: SosRunner

    @Inject lateinit var sosEngine: SosEngine

    /**
     * The app came to the front. If the user's notification shortcut is on and it was swiped
     * away or removed in the meantime, it is put back here. Nothing is requested.
     */
    override fun onStart() {
        super.onStart()
        emergencyNotification.syncInBackground()
        showUnattendedEmergency()
    }

    /**
     * Recovery (ADR 0027): if the phone remembers an emergency that nothing in this process
     * is running (the app was closed or killed, or the phone was restarted), the emergency
     * screen opens and says so: it continues a countdown, asks "start now or cancel" about
     * one whose time has passed, or shows that the SOS is still active. Once the runner has
     * picked it up this does nothing, so the user can come back to the map during an SOS.
     */
    private fun showUnattendedEmergency() {
        lifecycleScope.launch {
            if (sosRunner.state.value == SosRunState.Idle && sosEngine.current() != null) {
                startActivity(EmergencyShortcut.modeIntent(this@MainActivity, SosOpenMode.OPEN))
            }
        }
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
