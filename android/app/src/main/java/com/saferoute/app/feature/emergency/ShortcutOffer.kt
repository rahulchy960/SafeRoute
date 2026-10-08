// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The one-time offer of the notification shortcut.
 *
 * It appears once in the app's life on this phone: after the user has used the in-app SOS
 * control for the first time and closed its dialog. Never at launch, never in onboarding, and
 * not when the shortcut is on already. "Set up" only leads to Settings, where the switch
 * explains itself and asks for the permission; the offer itself requests nothing.
 */
@HiltViewModel
class ShortcutOfferViewModel @Inject constructor(
    private val preferences: EmergencyShortcutPreferences,
) : ViewModel() {

    private val _offer = MutableStateFlow(false)
    val offer: StateFlow<Boolean> = _offer.asStateFlow()

    /** The emergency dialog was closed, whichever way. */
    fun onSosDialogClosed() {
        viewModelScope.launch {
            if (preferences.offerShown.first() || preferences.notificationEnabled.first()) return@launch
            // Remembered before it is shown: whatever the answer, it is not offered again.
            preferences.setOfferShown()
            _offer.value = true
        }
    }

    fun onOfferDismiss() {
        _offer.value = false
    }
}

@Composable
fun ShortcutOfferDialog(onSetUp: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = {
            Text(
                text = stringResource(R.string.shortcut_offer_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            // Scrolls, so that large text can never push the buttons off the screen.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xs),
            ) {
                Text(text = stringResource(R.string.shortcut_offer_body))
                Text(text = stringResource(R.string.settings_notification_limits))
            }
        },
        confirmButton = {
            TextButton(onClick = onSetUp) { Text(text = stringResource(R.string.shortcut_offer_set_up)) }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) { Text(text = stringResource(R.string.shortcut_offer_not_now)) }
        },
    )
}
