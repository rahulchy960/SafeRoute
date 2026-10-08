// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.location.LocationRepository
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.map.CameraState
import com.saferoute.app.core.map.FollowView
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapController
import com.saferoute.app.core.map.MapEngine
import com.saferoute.app.core.map.MapOverlay
import com.saferoute.app.core.map.MapSelection
import com.saferoute.app.core.map.MarkerStyle
import com.saferoute.app.core.map.RouteDisplay
import com.saferoute.app.core.map.SelectedPlace
import com.saferoute.app.core.map.boundsOf
import com.saferoute.app.core.map.routeOverlays
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
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
 * A place chosen in search arrives through [MapSelection]: the camera flies to it once, a pin
 * is drawn, and the sheet shows a card until the user closes it. It is kept across a rotation
 * and across process death (in [savedState], like the camera), and nowhere else.
 *
 * Where the map opens (ADR 0015, "Initial camera"): on the whole region; and, once per launch,
 * on the user's position when the permission was already granted and a position arrives in
 * time. See [InitialCamera].
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
    private val selection: MapSelection,
    private val routes: RouteDisplay,
) : ViewModel() {

    /**
     * Whether the map may still move to the user's position by itself. Decided once, here: a
     * saved camera means the user has been on this screen before (rotation keeps this object;
     * after process death the camera comes back from [savedState]), and then the map belongs
     * where they left it.
     */
    private var initialCamera =
        if (savedCamera() == null) InitialCamera.Pending else InitialCamera.Settled
    private var initialWait: Job? = null

    /** The map's state. It survives rotation with this ViewModel; the map view does not. */
    val map: MapController = mapEngine.createController(viewModelScope, savedCamera())

    // Only the ViewModel may write; the screen gets the read-only StateFlow below.
    private val _emergencyDialog = MutableStateFlow(EmergencyDialogState.Hidden)

    /** A StateFlow always has a current value and tells its collectors when it changes. */
    val emergencyDialog: StateFlow<EmergencyDialogState> = _emergencyDialog.asStateFlow()

    /** True while Home is visible and the location permission is granted. */
    private val locationActive = MutableStateFlow(false)
    private val following = MutableStateFlow(false)

    /** While a route is followed: the user moved the map away, and the camera leaves it there. */
    private val followCameraFree = MutableStateFlow(false)

    /** True while a route is followed and the map no longer shows where the user is. */
    val recentreOffered: StateFlow<Boolean> = followCameraFree.asStateFlow()
    private var wasFollowingRoute = false

    /** Set by a tap on "my location": the next position centres the map, once. */
    private var recentreOnNextFix = false

    val locationState: StateFlow<LocationState> = location.state

    /** The place shown on the map and in the sheet's card, or null. */
    val selectedPlace: StateFlow<SelectedPlace?> = selection.selected

    /** The location dot and its circle, kept so that the pin can be drawn together with them. */
    private var locationShapes: List<MapOverlay> = emptyList()

    val myLocation: StateFlow<MyLocationControl> =
        combine(locationActive, location.state, following, ::myLocationControl)
            .stateIn(viewModelScope, SharingStarted.Eagerly, MyLocationControl.Off)

    init {
        // After process death the selection object is new and empty: put the saved place back.
        if (selection.selected.value == null) savedPlace()?.let(selection::select)
        viewModelScope.launch {
            var first = true
            selection.selected.collect { place ->
                savePlace(place)
                drawOverlays()
                // Fly to a place that was just chosen. Not on the first value: that one is a
                // place already on screen (rotation, restore), and the camera is where the user
                // left it.
                if (place != null && !first) {
                    settleInitialCamera()
                    following.value = false
                    recentreOnNextFix = false
                    val camera = map.camera.value
                    map.moveCamera(
                        camera.copy(target = place.position, zoom = maxOf(camera.zoom, PLACE_ZOOM)),
                    )
                }
                first = false
            }
        }
        viewModelScope.launch {
            map.camera.collect { camera ->
                // The coarse area a search prefers: where the map looks. Looking at the whole
                // region is not looking at an area, so then there is none.
                selection.viewCentre = camera.target.takeIf { camera.zoom >= SEARCH_AREA_MIN_ZOOM }
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
        // Never fight the user: once they have moved the map themselves, it stays theirs.
        viewModelScope.launch {
            map.userGestures.drop(1).collect {
                settleInitialCamera()
                if (routes.following.value != null) followCameraFree.value = true
            }
        }
        viewModelScope.launch { routes.following.collect(::onFollowView) }
        viewModelScope.launch {
            var fitted: Int? = null
            routes.routes.collect { shown ->
                drawOverlays()
                // New routes: show the selected one whole, once. Choosing an alternative or
                // turning the phone leaves the map where the user put it.
                if (shown != null && shown.fitToken != fitted) {
                    fitted = shown.fitToken
                    val line = shown.lines.firstOrNull { it.id == shown.selectedId }
                    // A route recalculated while it is followed: the camera stays on the user.
                    boundsOf(line?.points.orEmpty())?.takeIf { routes.following.value == null }?.let {
                        settleInitialCamera()
                        following.value = false
                        recentreOnNextFix = false
                        map.fitBounds(it)
                    }
                }
            }
        }
    }

    /** Home is visible and location is permitted: start updates (the repository checks again). */
    fun onLocationAvailable(userAsked: Boolean) {
        if (userAsked) {
            // A tap on "my location" centres the map by itself; no second, automatic move.
            settleInitialCamera()
            recentreOnNextFix = true
        } else if (initialCamera == InitialCamera.Pending) {
            // Home came up with the permission already in place: go to the user's position
            // when one arrives in time, otherwise stay on the overview.
            initialCamera = InitialCamera.Waiting
            initialWait = viewModelScope.launch {
                delay(INITIAL_FIX_WAIT_MILLIS)
                settleInitialCamera()
            }
        }
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
            // No permission when Home came up: the overview is where the map stays. A grant
            // later comes from a tap on "my location", which moves the map itself.
            settleInitialCamera()
            locationActive.value = false
            following.value = false
            recentreOnNextFix = false
            locationShapes = emptyList()
            drawOverlays()
        }
    }

    /** The place card was closed, or back was pressed while it showed. */
    fun onPlaceDismiss() {
        selection.clear()
    }

    /**
     * One list for the map, bottom to top: the routes (if any), the user's dot (if any) and the
     * chosen place's pin (if any), which is also a route's destination.
     */
    private fun drawOverlays() {
        val pin = selection.selected.value?.let {
            MapOverlay.Marker(id = PLACE_PIN_ID, position = it.position, style = MarkerStyle.Place)
        }
        val lines = routes.routes.value?.let { routeOverlays(it, routes.following.value) }.orEmpty()
        map.setOverlays(lines + locationShapes + listOfNotNull(pin))
    }

    private fun savePlace(place: SelectedPlace?) {
        if (place == null) {
            // Nothing chosen: nothing is kept, not even an empty entry.
            savedState.remove<Array<String>>(KEY_PLACE_TEXT)
            savedState.remove<DoubleArray>(KEY_PLACE_POSITION)
        } else {
            savedState[KEY_PLACE_TEXT] = arrayOf(place.name, place.label)
            savedState[KEY_PLACE_POSITION] = doubleArrayOf(place.position.latitude, place.position.longitude)
        }
    }

    private fun savedPlace(): SelectedPlace? {
        val text = savedState.get<Array<String>>(KEY_PLACE_TEXT)?.takeIf { it.size == 2 } ?: return null
        val position =
            savedState.get<DoubleArray>(KEY_PLACE_POSITION)?.takeIf { it.size == 2 } ?: return null
        return SelectedPlace(name = text[0], label = text[1], position = LatLng(position[0], position[1]))
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

    /** "Re-centre": the camera follows the user again. */
    fun onRecentre() {
        followCameraFree.value = false
        routes.following.value?.let { moveFollowCamera(it, closeUp = true) }
    }

    /**
     * A route is followed (ADR 0022): the camera stays on the user and turns with their
     * direction of travel, until they move the map themselves. When following ends, north is
     * up again and the map stays where it is.
     */
    private fun onFollowView(view: FollowView?) {
        drawOverlays()
        if (view == null) {
            if (wasFollowingRoute) map.moveCamera(map.camera.value.copy(bearing = 0.0))
            wasFollowingRoute = false
            followCameraFree.value = false
            return
        }
        val first = !wasFollowingRoute
        if (first) {
            wasFollowingRoute = true
            settleInitialCamera()
            following.value = false
            recentreOnNextFix = false
        }
        if (!followCameraFree.value) moveFollowCamera(view, closeUp = first)
    }

    private fun moveFollowCamera(view: FollowView, closeUp: Boolean) {
        val zoom = map.camera.value.zoom
        map.moveCamera(
            CameraState(
                target = view.position,
                zoom = if (closeUp) maxOf(zoom, FOLLOW_ZOOM) else zoom,
                bearing = view.bearing,
            ),
        )
    }

    private fun settleInitialCamera() {
        initialCamera = InitialCamera.Settled
        initialWait?.cancel()
        initialWait = null
    }

    /**
     * The one automatic move per launch. A new position always qualifies; one from before
     * (the phone's last known position, or one this app had earlier) only while it is recent.
     * Wherever it is, inside the launch region or not: the map shows where the user is.
     */
    private fun openOnPosition(state: LocationState) {
        if (initialCamera != InitialCamera.Waiting) return
        val fix = when (state) {
            is LocationState.Fix -> state.fix
            is LocationState.Stale -> state.lastFix.takeIf { state.ageSeconds <= INITIAL_FIX_MAX_AGE_SECONDS }
            // No position can be had: nothing to wait for.
            LocationState.Unavailable, LocationState.NoPermission -> return settleInitialCamera()
            LocationState.Searching -> null
        } ?: return
        settleInitialCamera()
        map.moveCamera(CameraState(target = fix.position, zoom = INITIAL_LOCATE_ZOOM))
    }

    private fun onLocationState(state: LocationState) {
        if (!locationActive.value) return
        locationShapes = locationOverlays(state)
        drawOverlays()
        openOnPosition(state)
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

    /** Where the automatic move to the user's position stands; it happens at most once. */
    private enum class InitialCamera {
        /** A fresh launch; Home has not said yet whether location is permitted. */
        Pending,

        /** Permitted: waiting, for a short while, for a position that is recent enough. */
        Waiting,

        /** Done, given up, or not wanted: the camera is the user's from here on. */
        Settled,
    }

    private companion object {
        const val KEY_CAMERA = "map_camera"
        const val KEY_PLACE_TEXT = "selected_place_text"
        const val KEY_PLACE_POSITION = "selected_place_position"
        const val PLACE_PIN_ID = "selected-place"

        /** Close enough to see the streets around a chosen place. */
        const val PLACE_ZOOM = 15.0

        /** Close enough to see the next turns of a route that is followed. */
        const val FOLLOW_ZOOM = 17.0
    }
}
