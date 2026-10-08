// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.feature.home.EmergencyDialog
import com.saferoute.app.feature.home.EmergencyDialogState
import com.saferoute.app.feature.home.openEmergencyDialer

/**
 * Where every emergency shortcut outside the app leads: the same dialog as the in-app SOS
 * control, and nothing else.
 *
 * A separate, tiny activity on purpose:
 * - **It may be shown over the lock screen.** Android lets a tile open an activity on top of
 *   the lock screen when what it does is safe while locked. This one is: it shows fixed text
 *   and offers to open the phone's dialer with 112. No account, no map, no position, nothing
 *   of the user's. The main activity is never shown over the lock screen.
 * - **It does not unlock anything.** "Call 112" opens the dialer; what the dialer does on a
 *   locked phone is the phone's own rule. Leaving this screen leads back to the lock screen.
 * - **It needs no sign-in and touches no data,** so it also works before onboarding.
 *
 * Not `@AndroidEntryPoint`: it needs nothing injected, and the less it depends on, the less
 * can stop it from opening. It is not exported: only this app's own tile and notification
 * start it. From P014 this is where the countdown starts.
 */
class EmergencyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        setContent {
            SafeRouteTheme {
                // Survives a rotation: the dialog does not fall back to its first state.
                var dialog by rememberSaveable { mutableStateOf(EmergencyDialogState.OfferDialer) }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxSize())
                }
                EmergencyDialog(
                    state = dialog,
                    onCallEmergency = {
                        // The dialer is open: this screen has done its job.
                        if (openEmergencyDialer(this)) finish() else dialog = EmergencyDialogState.DialerUnavailable
                    },
                    // Cancel, Close, back or a tap outside: nothing else is on this screen.
                    onDismiss = ::finish,
                )
            }
        }
    }

    /** The method came with Android 8.1; before that it was a window flag. */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }
}
