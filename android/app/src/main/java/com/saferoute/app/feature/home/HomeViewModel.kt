// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the emergency dialog on the Home screen is showing. */
enum class EmergencyDialogState {
    /** No dialog. */
    Hidden,

    /** The dialog offers to open the phone dialer with 112. */
    OfferDialer,

    /** The device has no phone app; the dialog shows the number to dial from another phone. */
    DialerUnavailable,
}

/**
 * Holds the Home screen's state.
 *
 * A ViewModel outlives the screen's recompositions and rotations: if the phone is turned while
 * the emergency dialog is open, the dialog is still open afterwards. The screen never changes
 * the state itself. It reports events (`on…` functions) and redraws from [emergencyDialog]:
 * data flows down, events flow up (unidirectional data flow).
 *
 * `@HiltViewModel` lets Hilt create it; the screen obtains it with `hiltViewModel()`.
 *
 * There is no SOS logic here. P014 replaces the dialog with the real device-first SOS flow.
 */
@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {

    // Only the ViewModel may write; the screen gets the read-only StateFlow below.
    private val _emergencyDialog = MutableStateFlow(EmergencyDialogState.Hidden)

    /** A StateFlow always has a current value and tells its collectors when it changes. */
    val emergencyDialog: StateFlow<EmergencyDialogState> = _emergencyDialog.asStateFlow()

    /** The emergency button was tapped. */
    fun onEmergencyClick() {
        _emergencyDialog.value = EmergencyDialogState.OfferDialer
    }

    /** The dialer was opened; the dialog has done its job. */
    fun onDialerOpened() {
        _emergencyDialog.value = EmergencyDialogState.Hidden
    }

    /** Opening the dialer failed because the device has none. Keep the dialog and say so. */
    fun onDialerUnavailable() {
        _emergencyDialog.value = EmergencyDialogState.DialerUnavailable
    }

    /** Cancel, Close, back gesture or a tap outside the dialog. */
    fun onEmergencyDialogDismiss() {
        _emergencyDialog.value = EmergencyDialogState.Hidden
    }
}
