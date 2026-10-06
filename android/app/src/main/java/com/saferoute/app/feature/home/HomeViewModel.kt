// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.map.CameraState
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapController
import com.saferoute.app.core.map.MapEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 * The map and the emergency dialog are two separate pieces of state on purpose: nothing the map
 * does (loading, failing, having no key) can reach the dialog.
 *
 * There is no SOS logic here. P014 replaces the dialog with the real device-first SOS flow.
 *
 * @param savedState a small key-value store that Android keeps even when it kills the app's
 * process in the background to free memory. The map camera is saved there, so the map reopens
 * where the user left it. It lives in memory managed by the system, not in a file or a log.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    val mapEngine: MapEngine,
) : ViewModel() {

    /** The map's state. It survives rotation with this ViewModel; the map view does not. */
    val map: MapController = mapEngine.createController(viewModelScope, savedCamera())

    // Only the ViewModel may write; the screen gets the read-only StateFlow below.
    private val _emergencyDialog = MutableStateFlow(EmergencyDialogState.Hidden)

    /** A StateFlow always has a current value and tells its collectors when it changes. */
    val emergencyDialog: StateFlow<EmergencyDialogState> = _emergencyDialog.asStateFlow()

    init {
        viewModelScope.launch {
            map.camera.collect { camera ->
                savedState[KEY_CAMERA] = doubleArrayOf(
                    camera.target.latitude,
                    camera.target.longitude,
                    camera.zoom,
                    camera.bearing,
                )
            }
        }
    }

    private fun savedCamera(): CameraState? =
        savedState.get<DoubleArray>(KEY_CAMERA)?.takeIf { it.size == 4 }?.let {
            CameraState(target = LatLng(it[0], it[1]), zoom = it[2], bearing = it[3])
        }

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

    private companion object {
        const val KEY_CAMERA = "map_camera"
    }
}
