// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.component.SheetDetent
import com.saferoute.app.core.designsystem.component.SheetSurfaceTag
import com.saferoute.app.core.designsystem.component.SosControlTag
import com.saferoute.app.core.designsystem.component.rememberSafeRouteSheetState
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapLoadState
import com.saferoute.app.core.map.SelectedPlace
import com.saferoute.app.feature.directions.DirectionsStatus
import com.saferoute.app.feature.directions.DirectionsUiState
import com.saferoute.app.feature.directions.FAKE_ROUTE_CREDIT
import com.saferoute.app.feature.directions.FAKE_ROUTE_FAST
import com.saferoute.app.feature.directions.FAKE_ROUTE_OTHER
import com.saferoute.app.feature.directions.RouteCardTag
import com.saferoute.app.feature.directions.TravelMode
import com.saferoute.app.feature.search.FoundPlace
import com.saferoute.app.feature.search.SearchScreen
import com.saferoute.app.feature.search.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where the SOS control sits, and that it covers nothing (ADR 0008, note of 2026-10-08).
 *
 * The rule under test: the control is 48 dp, it is on screen exactly once, and its bounds
 * intersect **no other element that shows text or can be tapped**: no route card, no result
 * row, no row or button of the sheet, no map control, not the search pill. Checked for every
 * kind of sheet content, at every detent, at normal and at double font size, in English and in
 * Bengali, upright and on its side.
 *
 * These are layout tests on the JVM (Robolectric): they prove the geometry. How it looks is
 * for the screenshots from a phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class SosPlacementTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun string(id: Int): String = context.getString(id)

    private val place = SelectedPlace("Main Station", "Station Road, Example District", LatLng(10.5, 20.5))
    private val routes = DirectionsStatus.Results(
        listOf(FAKE_ROUTE_FAST, FAKE_ROUTE_OTHER),
        FAKE_ROUTE_FAST.id,
        FAKE_ROUTE_CREDIT,
    )

    /** What the sheet holds. */
    private enum class Content { HomeList, PlaceCard, RouteCards, RouteMessage }

    private val events = mutableListOf<String>()
    private var mapState: MapLoadState by mutableStateOf(MapLoadState.Ready)

    @Composable
    private fun Home(content: Content, detent: SheetDetent, fontScale: Float = 1f) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme {
                HomeScreen(
                    emergencyDialog = EmergencyDialogState.Hidden,
                    onSearchClick = {},
                    onSettingsClick = {},
                    onEmergencyClick = { events += "emergency" },
                    onCallEmergency = {},
                    onDismissEmergencyDialog = {},
                    mapState = mapState,
                    sheetState = rememberSafeRouteSheetState(detent),
                    selectedPlace = place.takeIf { content != Content.HomeList },
                    directions = when (content) {
                        Content.RouteCards -> DirectionsUiState.Open(place, TravelMode.Walking, routes)
                        Content.RouteMessage -> DirectionsUiState.Open(
                            place,
                            TravelMode.Walking,
                            DirectionsStatus.Failed(com.saferoute.app.feature.directions.RouteError.Offline),
                        )
                        else -> DirectionsUiState.Closed
                    },
                )
            }
        }
    }

    private fun sos() = compose.onNodeWithTag(SosControlTag)

    /** Text, a description or a click: something a person reads or taps. */
    private val meaningful = SemanticsMatcher("shows text or can be tapped") { node ->
        node.config.getOrNull(SemanticsProperties.Text) != null ||
            node.config.getOrNull(SemanticsProperties.ContentDescription) != null ||
            node.config.getOrNull(SemanticsActions.OnClick) != null
    }

    private fun SemanticsNode.isInside(other: SemanticsNode): Boolean {
        var current: SemanticsNode? = this
        while (current != null) {
            if (current.id == other.id) return true
            current = current.parent
        }
        return false
    }

    private fun Rect.overlaps(other: Rect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /**
     * Fails if the control's bounds intersect any other meaningful element on the screen. Uses
     * the unmerged tree, so a text inside a card is checked as well as the card. An element that
     * contains the control (the screen, the map behind everything, the sheet's own group) is
     * not something it covers.
     *
     * When the control is in the sheet, elements that the SHEET itself has slid over (the map
     * controls, at full height the search pill) are not compared: they are under the sheet,
     * and nobody can see or tap them there. Everything in the sheet is compared.
     */
    private fun assertSosCoversNothing(where: String, hasSheet: Boolean = true) {
        compose.waitForIdle()
        compose.onAllNodesWithTag(SosControlTag).assertCountEquals(1)
        val control = sos().fetchSemanticsNode()
        val bounds = control.boundsInRoot
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$where: the control is on the screen", screen.contains(bounds.center))

        val sheet = if (hasSheet) compose.onNodeWithTag(SheetSurfaceTag).fetchSemanticsNode() else null
        val controlInSheet = sheet != null && control.isInside(sheet)
        val mapDescription = string(R.string.map_content_description)
        val others = compose.onAllNodes(meaningful, useUnmergedTree = true).fetchSemanticsNodes()
            .filterNot { it.id == control.id || it.isInside(control) || control.isInside(it) }
            .filterNot { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(mapDescription) == true }
            .filterNot { other ->
                controlInSheet && sheet != null && !other.isInside(sheet) &&
                    sheet.boundsInRoot.overlaps(other.boundsInRoot)
            }
        assertTrue("$where: found other elements to compare with", others.size >= 3)
        for (other in others) {
            assertFalse(
                "$where: the SOS control $bounds covers ${other.boundsInRoot} ${other.config}",
                bounds.overlaps(other.boundsInRoot),
            )
        }
    }

    // --- the rule, everywhere -----------------------------------------------------------

    private fun checkEverything(fontScale: Float) {
        var content by mutableStateOf(Content.HomeList)
        var detent by mutableStateOf(SheetDetent.Peek)
        compose.setContent {
            // A new sheet state for each combination, as if the screen had opened that way.
            androidx.compose.runtime.key(content, detent) { Home(content, detent, fontScale) }
        }
        for (nextContent in Content.entries) {
            for (nextDetent in SheetDetent.entries) {
                content = nextContent
                detent = nextDetent
                assertSosCoversNothing("$nextContent at $nextDetent, font x$fontScale")
                sos().assertIsDisplayed().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            }
        }
    }

    @Test
    fun `the control covers nothing, for every sheet content at every detent`() = checkEverything(fontScale = 1f)

    @Test
    fun `the control covers nothing at double font size`() = checkEverything(fontScale = 2f)

    @Test
    @Config(qualifiers = "bn-w360dp-h640dp")
    fun `the control covers nothing in Bengali at double font size`() = checkEverything(fontScale = 2f)

    @Test
    @Config(qualifiers = "w360dp-h640dp-night")
    fun `the control covers nothing in the dark theme`() = checkEverything(fontScale = 1f)

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `on its side, the control stays on the screen and covers nothing in the sheet`() {
        var detent by mutableStateOf(SheetDetent.Half)
        compose.setContent { androidx.compose.runtime.key(detent) { Home(Content.RouteCards, detent) } }
        for (next in listOf(SheetDetent.Half, SheetDetent.Full)) {
            detent = next
            assertSosCoversNothing("route cards at $next, landscape")
        }
    }

    // --- route cards, named -------------------------------------------------------------

    @Test
    fun `no route card, and not the attribution under them, lies under the control`() {
        var shown by mutableStateOf(SheetDetent.Peek)
        compose.setContent { androidx.compose.runtime.key(shown) { Home(Content.RouteCards, shown) } }
        for (detent in SheetDetent.entries) {
            shown = detent
            compose.waitForIdle()
            val control = sos().fetchSemanticsNode().boundsInRoot
            val cards = compose.onAllNodes(hasTestTag(RouteCardTag)).fetchSemanticsNodes()
            assertEquals("$detent: both cards are laid out", 2, cards.size)
            cards.forEach { assertFalse("$detent: a route card", control.overlaps(it.boundsInRoot)) }
            val credit = compose.onNodeWithText(FAKE_ROUTE_CREDIT).fetchSemanticsNode().boundsInRoot
            assertFalse("$detent: the routes' attribution", control.overlaps(credit))
        }
    }

    // --- where it sits ------------------------------------------------------------------

    @Test
    fun `at peek it is the last of the map controls, above the sheet`() {
        compose.setContent { Home(Content.HomeList, SheetDetent.Peek) }
        compose.waitForIdle()
        val control = sos().fetchSemanticsNode().boundsInRoot
        val myLocation = compose.onNodeWithContentDescription(string(R.string.my_location_off))
            .fetchSemanticsNode().boundsInRoot
        val sheet = compose.onNodeWithContentDescription(string(R.string.sheet_handle_description))
            .fetchSemanticsNode().boundsInRoot

        // Same column as "my location", below it, and wholly above the sheet's top edge.
        assertEquals(myLocation.left, control.left, 0.5f)
        assertTrue(control.top >= myLocation.bottom)
        assertTrue(control.bottom <= sheet.top)
    }

    @Test
    fun `at half and full height it is at the end of the sheet's header row, by the close button`() {
        var shown by mutableStateOf(SheetDetent.Half)
        compose.setContent { androidx.compose.runtime.key(shown) { Home(Content.PlaceCard, shown) } }
        for (detent in listOf(SheetDetent.Half, SheetDetent.Full)) {
            shown = detent
            compose.waitForIdle()
            val control = sos().fetchSemanticsNode().boundsInRoot
            val close = compose.onNodeWithContentDescription(string(R.string.place_card_close))
                .fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithText("Main Station").fetchSemanticsNode().boundsInRoot
            val handle = compose.onNodeWithContentDescription(string(R.string.sheet_handle_description))
                .fetchSemanticsNode().boundsInRoot

            // In the sheet (below its handle), on the title's row, after the close button.
            assertTrue("$detent", control.top >= handle.bottom)
            assertTrue("$detent", control.left >= close.right)
            assertTrue("$detent", control.top < title.bottom && title.top < control.bottom)
        }
    }

    // --- it always works ----------------------------------------------------------------

    @Test
    fun `a tap reports the emergency from every place it can sit, whatever the map is doing`() {
        var content by mutableStateOf(Content.HomeList)
        var detent by mutableStateOf(SheetDetent.Peek)
        compose.setContent { androidx.compose.runtime.key(content, detent) { Home(content, detent) } }

        val mapStates = listOf(
            MapLoadState.Ready,
            MapLoadState.Loading,
            MapLoadState.Error,
            MapLoadState.Offline,
            MapLoadState.RateLimited,
            MapLoadState.NotConfigured,
        )
        var taps = 0
        for (state in mapStates) {
            mapState = state
            for (nextContent in Content.entries) {
                for (nextDetent in SheetDetent.entries) {
                    content = nextContent
                    detent = nextDetent
                    compose.waitForIdle()
                    compose.onNodeWithContentDescription(string(R.string.sos_control_description))
                        .assertIsDisplayed()
                        .performClick()
                    taps++
                }
            }
        }
        assertEquals(List(taps) { "emergency" }, events)
        assertEquals(mapStates.size * Content.entries.size * SheetDetent.entries.size, taps)
    }

    // --- the search screen --------------------------------------------------------------

    private fun result(n: Int) = FoundPlace(
        id = "fake-$n",
        name = "Result $n",
        label = "Example District",
        position = LatLng(10.0 + n, 20.0),
        kind = "fake",
    )

    @Test
    fun `on the search screen it is a top-bar action and covers no result row`() {
        var scale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                SafeRouteTheme {
                    SearchScreen(
                        query = "result",
                        state = SearchUiState.Results((1..6).map(::result), "Fake attribution line"),
                        onQueryChange = {},
                        onSearch = {},
                        onPlaceClick = {},
                        onBack = {},
                        onEmergencyClick = { events += "emergency" },
                    )
                }
            }
        }
        for (fontScale in listOf(1f, 2f)) {
            scale = fontScale
            assertSosCoversNothing("search results, font x$fontScale", hasSheet = false)
            val control = sos().fetchSemanticsNode().boundsInRoot
            val rows = compose.onAllNodes(hasTestTag("search-result-row")).fetchSemanticsNodes()
            assertTrue("rows are laid out", rows.isNotEmpty())
            // Above every row: it is in the bar, not on the list.
            rows.forEach { assertTrue(control.bottom <= it.boundsInRoot.top) }
        }
        sos().performClick()
        assertEquals(listOf("emergency"), events)
    }
}
