// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import android.text.format.DateFormat
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.component.SheetDetent
import com.saferoute.app.core.designsystem.component.SosControlTag
import com.saferoute.app.core.designsystem.component.rememberSafeRouteSheetState
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.SelectedPlace
import com.saferoute.app.feature.home.EmergencyDialogState
import com.saferoute.app.feature.home.HomeScreen
import com.saferoute.app.testing.assertMinTouchTarget
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What Home shows while a route is followed: the banner and each of its lines, Start and the
 * reasons it gives, the question before ending, the screen staying on only while following,
 * and the SOS control staying usable and clear of the banner.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class FollowScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private val place = SelectedPlace("Main Station", "Station Road, Example District", LatLng(10.5, 20.5))
    private val results = DirectionsStatus.Results(listOf(FAKE_ROUTE_FAST, FAKE_ROUTE_OTHER), FAKE_ROUTE_FAST.id, FAKE_ROUTE_CREDIT)
    private val onTheWay = FollowState(remainingMeters = 1400, remainingSeconds = 1020, arrivalMillis = 1_000_000_000L)
    private val events = mutableListOf<String>()

    private var directions: DirectionsUiState by mutableStateOf(DirectionsUiState.Closed)
    private var recentre by mutableStateOf(false)
    private var fontScale by mutableStateOf(1f)
    private var view: View? = null

    private fun following(follow: FollowState?, problem: StartProblem? = null) =
        DirectionsUiState.Open(place, TravelMode.Walking, results, follow = follow, startProblem = problem)

    private fun show(state: DirectionsUiState, detent: SheetDetent = SheetDetent.Peek) {
        directions = state
        compose.setContent {
            view = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SafeRouteTheme {
                    HomeScreen(
                        emergencyDialog = EmergencyDialogState.Hidden,
                        onSearchClick = { events += "search" },
                        onSettingsClick = {},
                        onEmergencyClick = { events += "emergency" },
                        onCallEmergency = {},
                        onDismissEmergencyDialog = {},
                        sheetState = rememberSafeRouteSheetState(detent),
                        onMyLocationClick = { events += "my-location" },
                        selectedPlace = place,
                        directions = directions,
                        directionsActions = DirectionsActions(
                            onClose = { events += "close" },
                            onStart = { events += "start" },
                            onUsePrecise = { events += "precise" },
                            onEnd = { events += "end" },
                            onEndCancel = { events += "end-cancel" },
                            onEndConfirm = { events += "end-confirm" },
                            onRecalculate = { events += "recalculate" },
                            onPausedNoteDismiss = { events += "note-ok" },
                            onChangeStart = { events += "change-start" },
                            onUseMyLocationAsStart = { events += "from-my-location" },
                            onPreview = { events += "preview" },
                        ),
                        recentreOffered = recentre,
                        onRecentre = { events += "recentre" },
                    )
                }
            }
        }
    }

    private fun set(state: DirectionsUiState) {
        directions = state
        compose.waitForIdle()
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun `the banner says how far, how long and when, in place of the search pill`() {
        show(following(onTheWay))
        compose.onNodeWithText("1.4 km · 17 min").assertIsDisplayed()
        val time = DateFormat.getTimeFormat(compose.activity).format(Date(1_000_000_000L))
        compose.onNodeWithText(string(R.string.route_follow_arrival, time)).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.search_hint)).assertCountEquals(0)
        // None of the lines that need a reason.
        listOf(R.string.route_follow_gps, R.string.route_follow_off_route, R.string.route_follow_paused)
            .forEach { compose.onAllNodesWithText(string(it)).assertCountEquals(0) }

        compose.onNodeWithText(string(R.string.route_follow_end)).assertMinTouchTarget().performClick()
        back()
        compose.onNodeWithTag(SosControlTag).assertIsDisplayed().performClick()
        // End and the back button both ask; neither ends anything by itself.
        assertEquals(listOf("end", "end", "emergency"), events)
    }

    @Test
    fun `each thing that needs saying has its line, and TalkBack is told when it appears`() {
        show(following(onTheWay.copy(gpsLost = true, pausedNote = true, offRoute = true)))
        listOf(R.string.route_follow_gps, R.string.route_follow_off_route).forEach { id ->
            val node = compose.onNodeWithText(string(id)).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            assertNotNull("${string(id)} is a live region", node.config.getOrNull(SemanticsProperties.LiveRegion))
        }
        compose.onNodeWithText(string(R.string.route_follow_paused)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_follow_paused_ok)).performScrollTo().assertMinTouchTarget().performClick()
        compose.onNodeWithText(string(R.string.route_follow_recalculate)).performScrollTo().assertMinTouchTarget().performClick()
        assertEquals(listOf("note-ok", "recalculate"), events)

        // Asking: a sentence beside the spinner, and no second button to tap.
        set(following(onTheWay.copy(offRoute = true, recalculation = Recalculation.Running)))
        compose.onNodeWithText(string(R.string.route_follow_recalculating)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.route_follow_recalculate)).assertCountEquals(0)

        // Failed: the same sentences as for a first request, and the button again.
        val failures = listOf(
            RouteError.Offline to string(R.string.route_error_offline),
            RouteError.Starting(5) to string(R.string.route_error_still_starting),
            RouteError.RateLimited(null) to string(R.string.route_error_rate_limited),
        )
        failures.forEach { (error, text) ->
            set(following(onTheWay.copy(offRoute = true, recalculation = Recalculation.Failed(error))))
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(string(R.string.route_follow_recalculate)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `arriving is said in words, with Done`() {
        show(following(FollowState(0, 0, 0, arrived = true)))
        val node = compose.onNodeWithText(string(R.string.route_follow_arrived)).assertIsDisplayed().fetchSemanticsNode()
        assertNotNull(node.config.getOrNull(SemanticsProperties.LiveRegion))
        compose.onAllNodesWithText(string(R.string.route_follow_end)).assertCountEquals(0)
        compose.onNodeWithText(string(R.string.route_follow_done)).assertMinTouchTarget().performClick()
        // Nothing is being followed any more: back closes directions without a question.
        back()
        assertEquals(listOf("close", "close"), events)
    }

    @Test
    fun `End navigation asks with Cancel and End`() {
        show(following(onTheWay.copy(confirmEnd = true)))
        compose.onNodeWithText(string(R.string.route_follow_end_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_follow_end_text)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_follow_end_cancel)).assertMinTouchTarget().performClick()
        assertEquals(listOf("end-cancel"), events)
        set(following(onTheWay))
        compose.onAllNodesWithText(string(R.string.route_follow_end_title)).assertCountEquals(0)
    }

    @Test
    fun `the screen is kept on while following and at no other time`() {
        show(following(null))
        assertFalse(view!!.keepScreenOn)
        val exits = listOf(
            "arrived" to following(onTheWay.copy(arrived = true)),
            "ended or permission lost" to following(null, StartProblem.NoPermission),
            "directions closed" to DirectionsUiState.Closed,
        )
        exits.forEach { (name, after) ->
            set(following(onTheWay.copy(offRoute = true, gpsLost = true)))
            assertTrue("following", view!!.keepScreenOn)
            set(after)
            assertFalse(name, view!!.keepScreenOn)
        }
    }

    @Test
    fun `leaving the screen while following lets it switch off again`() {
        var shown by mutableStateOf(true)
        var seen: View? = null
        compose.setContent {
            seen = LocalView.current
            if (shown) KeepScreenOn(active = true)
        }
        assertTrue(seen!!.keepScreenOn)
        shown = false
        compose.waitForIdle()
        assertFalse(seen!!.keepScreenOn)
    }

    @Test
    fun `Start is on the route list, and says what is missing when it cannot begin`() {
        show(following(null), detent = SheetDetent.Full)
        compose.onNodeWithText(string(R.string.route_start)).assertIsDisplayed().assertMinTouchTarget().performClick()

        set(following(null, StartProblem.NeedsPrecise))
        compose.onNodeWithText(string(R.string.route_start_needs_precise)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_start_use_precise)).assertMinTouchTarget().performClick()

        set(following(null, StartProblem.NoPermission))
        compose.onNodeWithText(string(R.string.route_start_no_permission)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_use_my_location)).assertMinTouchTarget().performClick()

        set(following(null, StartProblem.NoRecentFix))
        compose.onNodeWithText(string(R.string.route_start_no_fix)).assertIsDisplayed()
        assertEquals(listOf("start", "precise", "my-location"), events)
    }

    @Test
    fun `with a chosen start the sheet names it and offers Preview, the reason and the way back`() {
        val market = SelectedPlace("Station Market", "Example Town", LatLng(10.25, 20.75))
        show(DirectionsUiState.Open(place, TravelMode.Walking, results, origin = market), detent = SheetDetent.Full)
        compose.onNodeWithText(string(R.string.route_from_place, "Station Market"), substring = true).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.route_start)).assertCountEquals(0)
        compose.onNodeWithText(string(R.string.route_preview_note)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_preview)).assertMinTouchTarget().performClick()
        compose.onNodeWithText(string(R.string.route_start_from_my_location)).assertMinTouchTarget().performClick()
        compose.onNodeWithText(string(R.string.route_change_start), substring = true).assertMinTouchTarget().performClick()
        assertEquals(listOf("preview", "from-my-location", "change-start"), events)

        // From the user's own location: Start, and no way "back" to offer.
        set(following(null))
        compose.onNodeWithText(string(R.string.route_from_my_location), substring = true).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.route_preview)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.route_start_from_my_location)).assertCountEquals(0)
        // While a route is followed its start cannot be changed.
        set(following(onTheWay))
        compose.onAllNodesWithText(string(R.string.route_change_start), substring = true).assertCountEquals(0)
    }

    @Test
    fun `Re-centre shows only after the user moved the map while following`() {
        show(following(onTheWay))
        compose.onAllNodesWithText(string(R.string.route_follow_recentre)).assertCountEquals(0)
        recentre = true
        compose.onNodeWithText(string(R.string.route_follow_recentre)).assertIsDisplayed().assertMinTouchTarget().performClick()
        assertEquals(listOf("recentre"), events)
        // Not following: nothing to come back to.
        set(following(null))
        compose.onAllNodesWithText(string(R.string.route_follow_recentre)).assertCountEquals(0)
    }

    private fun assertBannerClearOfControls() {
        fontScale = 2f
        recentre = true
        val everything = onTheWay.copy(
            gpsLost = true,
            pausedNote = true,
            offRoute = true,
            recalculation = Recalculation.Failed(RouteError.RateLimited(30)),
        )
        show(following(everything))
        val banner = bounds(FollowBannerTag)
        val sos = bounds(SosControlTag)
        val button = compose.onNodeWithText(string(R.string.route_follow_recentre)).fetchSemanticsNode().boundsInRoot
        assertTrue("banner $banner, SOS control $sos", banner.bottom <= sos.top)
        assertTrue("banner $banner, Re-centre $button", banner.bottom <= button.top)
        compose.onNodeWithTag(SosControlTag).assertIsDisplayed().assertMinTouchTarget().performClick()
        // Everything in the banner can still be reached, by scrolling inside it.
        compose.onNodeWithText(string(R.string.route_follow_recalculate)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("emergency", "recalculate"), events)
    }

    @Test
    fun `at double font size the banner with every line stays clear of the SOS control`() = assertBannerClearOfControls()

    @Test
    @Config(qualifiers = "bn-w360dp-h640dp")
    fun `the same in Bengali`() = assertBannerClearOfControls()
}
