// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.location.LocationRepository
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.map.CameraState
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapController
import com.saferoute.app.core.map.MapEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
 * The map, the location and the emergency dialog are separate pieces of state on purpose:
 * nothing the map or the location does (loading, failing, having no key, having no position)
 * can reach the dialog.
 *
 * Location here is display only: positions go from [LocationRepository] to the map as overlays
 * and to the camera. They are not saved, logged or sent anywhere.
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
    private val location: LocationRepository,
) : ViewModel() {

    /** The map's state. It survives rotation with this ViewModel; the map view does not. */
    val map: MapController = mapEngine.createController(viewModelScope, savedCamera())

    // Only the ViewModel may write; the screen gets the read-only StateFlow below.
    private val _emergencyDialog = MutableStateFlow(EmergencyDialogState.Hidden)

    /** A StateFlow always has a current value and tells its collectors when it changes. */
    val emergencyDialog: StateFlow<EmergencyDialogState> = _emergencyDialog.asStateFlow()

    /** True while Home is visible and the location permission is granted. */
    private val locationActive = MutableStateFlow(false)
    private val following = MutableStateFlow(false)

    /** Set by a tap on "my location": the next position centres the map, once. */
    private var recentreOnNextFix = false

    val locationState: StateFlow<LocationState> = location.state

    val myLocation: StateFlow<MyLocationControl> =
        combine(locationActive, location.state, following, ::myLocationControl)
            .stateIn(viewModelScope, SharingStarted.Eagerly, MyLocationControl.Off)

    init {
        viewModelScope.launch {
            map.camera.collect { camera ->
                savedState[KEY_CAMERA] = doubleArrayOf(
                    camera.target.latitude,
                    camera.target.longitude,
                    camera.zoom,
                    camera.bearing,
                )
                // The user dragged the map away: stop following instead of pulling it back.
                val fix = location.state.value.fixOrNull()
                if (following.value && fix != null && !camera.isCentredOn(fix)) following.value = false
            }
        }
        viewModelScope.launch { location.state.collect(::onLocationState) }
    }

    /** Home is visible and location is permitted: start updates (the repository checks again). */
    fun onLocationAvailable(userAsked: Boolean) {
        if (userAsked) recentreOnNextFix = true
        locationActive.value = true
        location.start()
        onLocationState(location.state.value)
    }

    /**
     * Home is no longer visible, or the permission is gone: updates stop at once.
     *
     * @param permissionLost also takes the dot off the map; a position the app may no longer
     * read must not stay on screen.
     */
    fun onLocationUnavailable(permissionLost: Boolean) {
        location.stop()
        if (permissionLost) {
            locationActive.value = false
            following.value = false
            recentreOnNextFix = false
            map.setOverlays(emptyList())
        }
    }

    /**
     * "My location" was tapped with the permission in place. Returns false when there is no
     * position to act on, so that the screen can say why.
     */
    fun onMyLocationClick(): Boolean {
        val fix = location.state.value.fixOrNull()
        when (myLocation.value) {
            MyLocationControl.Following -> following.value = false
            MyLocationControl.Located -> if (fix != null) {
                // First tap centres the map; a tap while centred starts following.
                if (map.camera.value.isCentredOn(fix)) following.value = true else centreOn(fix.position)
            }
            MyLocationControl.Searching, MyLocationControl.Off -> recentreOnNextFix = true
            MyLocationControl.Unavailable -> {
                recentreOnNextFix = true
                // Try once more: the user may have gone outdoors or switched Location on.
                location.stop()
                location.start()
                return location.state.value != LocationState.Unavailable
            }
        }
        return true
    }

    private fun onLocationState(state: LocationState) {
        if (!locationActive.value) return
        map.setOverlays(locationOverlays(state))
        val fix = (state as? LocationState.Fix)?.fix ?: return
        when {
            recentreOnNextFix -> {
                recentreOnNextFix = false
                centreOn(fix.position)
            }
            // Later positions move the dot only, unless the user chose to follow.
            following.value -> map.moveCamera(map.camera.value.copy(target = fix.position))
        }
    }

    private fun centreOn(position: LatLng) {
        val camera = map.camera.value
        map.moveCamera(camera.copy(target = position, zoom = maxOf(camera.zoom, LOCATE_ZOOM)))
    }

    override fun onCleared() {
        location.stop()
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
