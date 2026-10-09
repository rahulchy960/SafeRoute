// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.feature.home.EmergencyDialog
import com.saferoute.app.feature.home.EmergencyDialogState
import com.saferoute.app.feature.home.openEmergencyDialer
import dagger.hilt.android.AndroidEntryPoint

/**
 * The emergency screen: where every emergency shortcut outside the app leads, and where a
 * running SOS is shown (the countdown, the active emergency, the questions after a restart).
 *
 * A separate, small activity on purpose:
 * - **It may be shown over the lock screen.** What it shows is safe there: fixed text, a
 *   countdown, statuses in words and buttons. No account, no map, no contact, no position.
 *   The main activity is never shown over the lock screen.
 * - **It does not unlock anything by itself.** "Call 112" opens the dialer; what the dialer
 *   does on a locked phone is the phone's own rule. "I'm safe" ASKS for the unlock
 *   (`requestDismissKeyguard`): ending an emergency is the one thing here that a stranger
 *   holding the phone must not be able to do.
 * - **It owns nothing.** The emergency belongs to `SosRunner`; this screen shows its state
 *   and passes taps on. Closing it, rotating it or killing it changes nothing about the SOS.
 *
 * When nothing is running it shows the same dialog as the in-app SOS control, as before.
 * It is not exported: only this app's own tile, notifications and screens start it.
 */
@AndroidEntryPoint
class EmergencyActivity : ComponentActivity() {

    private val viewModel: SosViewModel by viewModels()

    /** True while the lock screen is up. Read again every time the screen comes back. */
    private var locked by mutableStateOf(false)
    private var fresh = false

    private val mode: SosOpenMode
        get() = intent.getStringExtra(EmergencyShortcut.EXTRA_MODE)
            ?.let { name -> SosOpenMode.entries.firstOrNull { it.name == name } }
            ?: SosOpenMode.OPEN

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A screen that Android re-creates (rotation, coming back after the process was
        // ended) must never start a new emergency: only the first creation may.
        fresh = savedInstanceState == null
        showOverLockScreen()

        setContent {
            SafeRouteTheme {
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                // Survives a rotation: the dialog does not fall back to its first state.
                var dialerMissing by rememberSaveable { mutableStateOf(false) }
                val call112 = { if (!openEmergencyDialer(this)) dialerMissing = true }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxSize())
                }
                when (val state = ui) {
                    SosUi.Options -> EmergencyDialog(
                        state = if (dialerMissing) EmergencyDialogState.DialerUnavailable else EmergencyDialogState.OfferDialer,
                        // The dialer is open: this screen has done its job.
                        onCallEmergency = { if (openEmergencyDialer(this)) finish() else dialerMissing = true },
                        // Cancel, Close, back or a tap outside: nothing else is on this screen.
                        onDismiss = ::finish,
                    )

                    is SosUi.Countdown -> {
                        KeepAwake()
                        // Back must not look like Cancel: only the Cancel button cancels.
                        BackHandler(enabled = true) {}
                        SosCountdownScreen(
                            secondsLeft = state.secondsLeft,
                            practice = state.practice,
                            dialerMissing = dialerMissing,
                            onCancel = viewModel::onCancel,
                            onCall112 = call112,
                        )
                    }

                    is SosUi.Active -> {
                        KeepAwake()
                        SosActiveScreen(
                            state = state,
                            locked = locked,
                            dialerMissing = dialerMissing,
                            onSafe = ::onSafeTapped,
                            onSafeConfirm = viewModel::onSafeConfirm,
                            onSafeDismiss = viewModel::onSafeDismiss,
                            onContinue = viewModel::onContinue,
                            onCall112 = call112,
                            onOpenComposer = viewModel::onOpenComposer,
                            onTellContactsChange = viewModel::onTellContactsChange,
                        )
                    }

                    SosUi.AskSendOrCancel -> {
                        // The question stays until it is answered.
                        BackHandler(enabled = true) {}
                        SosAskScreen(
                            dialerMissing = dialerMissing,
                            onSendNow = viewModel::onSendNow,
                            onCancel = viewModel::onCancel,
                            onCall112 = call112,
                        )
                    }

                    SosUi.PracticeFinished -> SosPracticeFinishedScreen(onClose = viewModel::onClose)

                    SosUi.Closed -> LaunchedEffect(Unit) { finish() }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        viewModel.onShown(mode, fresh)
    }

    /**
     * "I'm safe". On a locked phone Android first asks for the PIN, pattern or fingerprint;
     * only after a successful unlock does the confirmation appear. A cancelled or failed
     * unlock changes nothing.
     */
    private fun onSafeTapped() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked != true) {
            viewModel.onSafeClick()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    locked = false
                    viewModel.onSafeClick()
                }
            },
        )
    }

    /** Keeps the screen on while an emergency is shown, and gives the flag back afterwards. */
    @Composable
    private fun KeepAwake() {
        DisposableEffect(Unit) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    /**
     * Over the lock screen, and with the screen switched on when it opens (a countdown that
     * starts on a dark screen would not be seen). The methods came with Android 8.1; before
     * that they were window flags.
     */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }
}
