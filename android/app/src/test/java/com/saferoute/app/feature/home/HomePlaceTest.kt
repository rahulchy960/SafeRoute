// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import com.saferoute.app.core.map.CameraState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.location.fakeFix
import com.saferoute.app.core.map.FakeMapEngine
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapOverlay
import com.saferoute.app.core.map.MapSelection
import com.saferoute.app.core.map.RouteDisplay
import com.saferoute.app.core.map.MarkerStyle
import com.saferoute.app.core.map.OverlayKind
import com.saferoute.app.core.map.OverlayPalette
import com.saferoute.app.core.map.SelectedPlace
import com.saferoute.app.core.map.overlaysToGeoJson
import com.saferoute.app.feature.directions.DirectionsActions
import com.saferoute.app.testing.assertMinTouchTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A place chosen in search, on Home: the camera, the pin (as an overlay description, never a
 * map-library type), the place card, closing it, and what survives a restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class HomePlaceTest {

    @get:Rule
    val compose = createComposeRule()

    private val savedState = SavedStateHandle()
    private val mapEngine = FakeMapEngine()
    private val location = FakeLocationRepository()
    private val selection = MapSelection()
    private lateinit var viewModel: HomeViewModel
    private val map get() = mapEngine.controller

    /** Fake, round coordinates. */
    private val place = SelectedPlace("Main Station", "Station Road, Example District", LatLng(10.5, 20.5))
    private val pin = MapOverlay.Marker(id = "selected-place", position = place.position, style = MarkerStyle.Place)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = HomeViewModel(savedState, mapEngine, location, selection, RouteDisplay())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun string(id: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    @Test
    fun `a chosen place moves the camera to it once and draws a pin`() {
        assertNull(viewModel.selectedPlace.value)
        selection.select(place)

        assertEquals(place, viewModel.selectedPlace.value)
        assertEquals(listOf<MapOverlay>(pin), map.overlays)
        assertEquals(1, map.cameraMoves.size)
        assertEquals(place.position, map.cameraMoves.single().target)
        assertTrue(map.cameraMoves.single().zoom >= 15.0)
    }

    @Test
    fun `a closer zoom is kept when flying to a place`() {
        map.camera.value = map.camera.value.copy(zoom = 17.5)
        selection.select(place)
        assertEquals(17.5, map.cameraMoves.single().zoom, 0.0)
    }

    @Test
    fun `closing the card removes the pin and leaves the camera where it is`() {
        selection.select(place)
        val movesBefore = map.cameraMoves.size

        viewModel.onPlaceDismiss()
        assertNull(viewModel.selectedPlace.value)
        assertEquals(emptyList<MapOverlay>(), map.overlays)
        assertEquals(movesBefore, map.cameraMoves.size)
    }

    @Test
    fun `the pin and the location dot are drawn together and neither removes the other`() {
        viewModel.onLocationAvailable(userAsked = false)
        location.state.value = LocationState.Fix(fakeFix())
        val dot = map.overlays
        assertEquals(2, dot.size)

        selection.select(place)
        assertEquals(dot + pin, map.overlays)

        // A new position redraws the dot; the pin stays.
        location.state.value = LocationState.Fix(fakeFix(LatLng(11.0, 21.0)))
        assertTrue(pin in map.overlays)
        assertEquals(3, map.overlays.size)

        // The permission is taken away: the dot goes, the chosen place stays.
        viewModel.onLocationUnavailable(permissionLost = true)
        assertEquals(listOf<MapOverlay>(pin), map.overlays)

        viewModel.onPlaceDismiss()
        assertEquals(emptyList<MapOverlay>(), map.overlays)
    }

    @Test
    fun `choosing a place stops following the user's position`() {
        viewModel.onLocationAvailable(userAsked = true)
        location.state.value = LocationState.Fix(fakeFix())
        viewModel.onMyLocationClick()
        assertEquals(MyLocationControl.Following, viewModel.myLocation.value)

        selection.select(place)
        assertEquals(MyLocationControl.Located, viewModel.myLocation.value)
        assertEquals(place.position, map.camera.value.target)
    }

    @Test
    fun `the map's centre is shared as the search area, unless the map shows the whole region`() {
        // The overview shows many districts: its middle is not an area anybody is looking at.
        assertNull(selection.viewCentre)

        map.camera.value = CameraState(target = LatLng(12.0, 22.0), zoom = SEARCH_AREA_MIN_ZOOM)
        assertEquals(LatLng(12.0, 22.0), selection.viewCentre)

        map.camera.value = map.camera.value.copy(zoom = SEARCH_AREA_MIN_ZOOM - 0.5)
        assertNull(selection.viewCentre)
    }

    @Test
    fun `the place survives rotation and process death, without flying the camera again`() {
        selection.select(place)

        // Rotation: the same shared selection, a ViewModel that is still alive. Nothing to do.
        // Process death: everything is new except what Android saved.
        val newEngine = FakeMapEngine()
        val newSelection = MapSelection()
        val restored = HomeViewModel(savedState, newEngine, location, newSelection, RouteDisplay())

        assertEquals(place, restored.selectedPlace.value)
        assertEquals(listOf<MapOverlay>(pin), newEngine.controller.overlays)
        // The camera reopens where the user left it (saved separately); no second flight.
        assertEquals(0, newEngine.controller.cameraMoves.size)

        restored.onPlaceDismiss()
        val afterClose = HomeViewModel(savedState, FakeMapEngine(), location, MapSelection(), RouteDisplay())
        assertNull(afterClose.selectedPlace.value)
    }

    @Test
    fun `a pin is its own shape in the map data, in its own colour`() {
        val palette = OverlayPalette(location = "#0000FF", stale = "#888888", halo = "#FFFFFF", line = "#0000FF", place = "#800080")
        val json = overlaysToGeoJson(listOf(pin), palette)

        assertTrue(""""kind":"${OverlayKind.PIN}"""" in json)
        assertTrue(""""color":"#800080"""" in json)
        assertTrue("[20.5000000,10.5000000]" in json)
        assertFalse(""""kind":"${OverlayKind.DOT}"""" in json)
    }

    // --- the screen ---------------------------------------------------------------------

    private var closes = 0
    private var emergencies = 0
    private var searchOpens = 0
    private var directionsOpens = 0

    private fun setHome(selected: SelectedPlace?) {
        compose.setContent {
            SafeRouteTheme {
                HomeScreen(
                    emergencyDialog = EmergencyDialogState.Hidden,
                    onSearchClick = { searchOpens++ },
                    onSettingsClick = {},
                    onEmergencyClick = { emergencies++ },
                    onCallEmergency = {},
                    onDismissEmergencyDialog = {},
                    selectedPlace = selected,
                    onPlaceDismiss = { closes++ },
                    directionsActions = DirectionsActions(onOpen = { directionsOpens++ }),
                )
            }
        }
    }

    @Test
    fun `the sheet shows the place card with a close button and a Directions button`() {
        setHome(place)

        compose.onNodeWithText("Main Station").assertIsDisplayed()
        compose.onNodeWithText("Station Road, Example District").assertIsDisplayed()
        // The usual sheet content makes way for the card.
        compose.onNodeWithText(string(R.string.home_sheet_title)).assertDoesNotExist()
        // Directions asks for routes to this place (P012c1). Nothing says "coming later" any more.
        compose.onNodeWithText(string(R.string.place_card_directions))
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertMinTouchTarget()
            .performClick()
        assertEquals(1, directionsOpens)
        compose.onNodeWithText(string(R.string.coming_later)).assertDoesNotExist()

        compose.onNodeWithContentDescription(string(R.string.place_card_close))
            .assertMinTouchTarget()
            .performClick()
        assertEquals(1, closes)
    }

    @Test
    fun `with a place shown the emergency button and the search pill still work`() {
        setHome(place)

        compose.onNodeWithText(string(R.string.emergency_button_label)).assertIsDisplayed().performClick()
        compose.onNodeWithText(string(R.string.search_hint)).assertIsDisplayed().performClick()
        assertEquals(1, emergencies)
        assertEquals(1, searchOpens)
    }

    @Test
    fun `without a place the sheet is as before`() {
        setHome(null)
        compose.onNodeWithText(string(R.string.home_sheet_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.place_card_close)).assertDoesNotExist()
    }
}
