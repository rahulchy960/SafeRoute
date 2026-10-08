// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.testing.assertMinTouchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Quick-search chips, the "within N km" line and the honest empty state (P011f2, ADR 0018).
 * The screen alone, with fake states: no ViewModel, no server.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class SearchNearbyScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private var query by mutableStateOf("")
    private var state: SearchUiState by mutableStateOf(SearchUiState.Idle)
    private var category: SearchCategory? by mutableStateOf(null)
    private var browserMissing by mutableStateOf(false)
    private var choosingStart by mutableStateOf(false)
    private val tapped = mutableListOf<SearchCategory>()
    private val chosen = mutableListOf<FoundPlace>()
    private var mapSiteOpened = 0
    private var emergencyTaps = 0

    @Composable
    private fun Screen(fontScale: Float = 1f) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme {
                SearchScreen(
                    query = query,
                    state = state,
                    onQueryChange = { query = it },
                    onSearch = {},
                    onPlaceClick = { chosen += it },
                    onBack = {},
                    onEmergencyClick = { emergencyTaps++ },
                    choosingStart = choosingStart,
                    category = category,
                    onCategoryClick = { tapped += it },
                    onOpenMapSite = { mapSiteOpened++ },
                    browserMissing = browserMissing,
                )
            }
        }
    }

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private val near = FAKE_STATION.copy(id = "near", name = "Example Medical Hall", distanceMeters = 700)
    private val further = FAKE_MARKET.copy(id = "further", name = "Station Chemist", distanceMeters = 2300)

    private fun results(centre: CircleCentre, from: NearSource?, radiusKm: Int = 10) =
        SearchUiState.Results(listOf(near, further), FAKE_ATTRIBUTION, from, SearchedCircle(radiusKm, centre))

    private val chipLabels = listOf(
        R.string.search_chip_bank,
        R.string.search_chip_atm,
        R.string.search_chip_pharmacy,
        R.string.search_chip_hospital,
        R.string.search_chip_fuel,
        R.string.search_chip_food,
        R.string.search_chip_grocery,
        R.string.search_chip_transit,
    )

    @Test
    fun `the empty screen offers eight chips, each a button of at least 48 dp, and a tap reports its category`() {
        compose.setContent { Screen() }

        compose.onNodeWithTag(SearchTags.Chips).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.search_chips_label)).assertIsDisplayed()
        chipLabels.forEach { label ->
            compose.onNodeWithText(string(label))
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
                .assert(isButton)
                .assertIsNotSelected()
                .assertMinTouchTarget()
                .performClick()
        }
        assertEquals(SearchCategory.entries.toList(), tapped)
        // The hint for typing is still there under the chips.
        compose.onNodeWithText(string(R.string.search_empty_title)).assertIsDisplayed()
    }

    @Test
    fun `the chosen chip is announced as selected and stays with its results`() {
        category = SearchCategory.Pharmacy
        state = results(CircleCentre.SentArea, NearSource.Position)
        compose.setContent { Screen() }

        compose.onNodeWithText(string(R.string.search_chip_pharmacy)).assertIsSelected()
        compose.onNodeWithText(string(R.string.search_chip_bank)).assertIsNotSelected()
        compose.onNodeWithText(near.name).assertIsDisplayed()
    }

    @Test
    fun `typed text makes room for the results - the chips go, and come back with an empty field`() {
        query = "station"
        state = SearchUiState.Results(listOf(FAKE_STATION), FAKE_ATTRIBUTION)
        compose.setContent { Screen() }
        compose.onNodeWithTag(SearchTags.Chips).assertDoesNotExist()

        query = ""
        state = SearchUiState.Idle
        compose.onNodeWithTag(SearchTags.Chips).assertIsDisplayed()
    }

    @Test
    fun `the header says how far around what was searched, and is read out`() {
        compose.setContent { Screen() }
        val note = string(R.string.search_distance_note)
        val cases = listOf(
            results(CircleCentre.SentArea, NearSource.Position) to string(R.string.search_within_position, "10"),
            results(CircleCentre.SentArea, NearSource.MapCentre, 25) to string(R.string.search_within_map, "25"),
            results(CircleCentre.TypedPlace, NearSource.Position) to string(R.string.search_within_place, "10"),
            // No claim about a centre the app cannot name.
            results(CircleCentre.Unknown, NearSource.Position) to string(R.string.search_within, "10"),
            results(CircleCentre.SentArea, null) to string(R.string.search_within, "10"),
        )
        cases.forEach { (next, text) ->
            state = next
            compose.onNodeWithTag(SearchTags.Within).assertIsDisplayed().assert(hasText("$text $note")).assert(polite)
            // One line about distances, not two.
            compose.onNodeWithTag(SearchTags.DistanceNote).assertDoesNotExist()
        }
        assertEquals("Within 10 km of your location", string(R.string.search_within_position, "10"))
    }

    @Test
    fun `every row shows its distance, and TalkBack hears what it is measured from`() {
        state = results(CircleCentre.SentArea, NearSource.Position)
        compose.setContent { Screen() }

        compose.onNodeWithText(string(R.string.search_distance_under_km), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.search_distance_km, "2.3"), useUnmergedTree = true).assertIsDisplayed()
        val spoken = string(R.string.search_distance_from_position, string(R.string.search_distance_km, "2.3"))
        compose.onNodeWithContentDescription(spoken, useUnmergedTree = true).assertIsDisplayed()

        // Around a typed place the distance is from THAT place, never "from your location".
        state = results(CircleCentre.TypedPlace, NearSource.Position)
        val fromPlace = string(R.string.search_distance_from_place, string(R.string.search_distance_km, "2.3"))
        compose.onNodeWithContentDescription(fromPlace, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(spoken, useUnmergedTree = true).assertDoesNotExist()

        compose.onNode(hasText(near.name) and hasText(near.label)).performClick()
        assertEquals(listOf(near), chosen)
    }

    @Test
    fun `nothing in the circle - says so with the radius, explains calmly and links to the map's site`() {
        category = SearchCategory.Atm
        state = SearchUiState.Empty(SearchedCircle(25, CircleCentre.SentArea), NearSource.Position)
        compose.setContent { Screen() }

        compose.onNodeWithText(string(R.string.search_nothing_within, "25")).assertIsDisplayed().assert(polite)
        compose.onNodeWithText(string(R.string.search_missing_from_map)).assertIsDisplayed()
        // Not the name-search advice, and no claim that there is no such place.
        compose.onNodeWithText(string(R.string.search_no_results_body)).assertDoesNotExist()
        assertEquals("Nothing found within 25 km.", string(R.string.search_nothing_within, "25"))
        compose.onNodeWithText(string(R.string.search_no_browser)).assertDoesNotExist()

        compose.onNodeWithText(string(R.string.search_open_osm)).assertMinTouchTarget().performClick()
        assertEquals(1, mapSiteOpened)
        // The chips stay, so another kind is one tap away.
        compose.onNodeWithText(string(R.string.search_chip_bank)).assertIsDisplayed()

        browserMissing = true
        compose.onNodeWithText(string(R.string.search_no_browser)).assertIsDisplayed().assert(polite)
    }

    @Test
    fun `choosing a start point keeps the chips, the results and the emergency control`() {
        choosingStart = true
        compose.setContent { Screen() }

        compose.onNodeWithText(string(R.string.route_start_search_hint)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.search_chip_hospital)).performScrollTo().performClick()
        assertEquals(listOf(SearchCategory.Hospital), tapped)

        category = SearchCategory.Hospital
        state = results(CircleCentre.SentArea, NearSource.MapCentre)
        compose.onNode(hasText(further.name) and hasText(further.label)).performClick()
        assertEquals(listOf(further), chosen)

        // Loading, results, empty and error: the emergency control is in the top bar throughout.
        val sos = string(R.string.sos_control_description)
        listOf(
            SearchUiState.Loading,
            state,
            SearchUiState.Empty(SearchedCircle(25, CircleCentre.SentArea)),
            SearchUiState.Error(SearchError.Unavailable),
        ).forEach { next ->
            state = next
            compose.onNodeWithContentDescription(sos).assertIsDisplayed().assertMinTouchTarget().performClick()
        }
        assertEquals(4, emergencyTaps)
    }

    @Test
    fun `at 200 percent font size every chip, the last result and the empty state's link can be reached`() {
        category = SearchCategory.Pharmacy
        state = results(CircleCentre.SentArea, NearSource.Position)
        compose.setContent { Screen(fontScale = 2f) }

        compose.onNodeWithText(string(R.string.search_chip_transit)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SearchTags.Within).assertIsDisplayed()
        compose.onNodeWithTag(SearchTags.Results).performScrollToNode(hasText(further.name))
        compose.onNodeWithText(further.name).assertIsDisplayed()
        compose.onNodeWithTag(SearchTags.Results).performScrollToNode(hasText(FAKE_ATTRIBUTION))
        compose.onNodeWithText(FAKE_ATTRIBUTION).assertIsDisplayed()

        state = SearchUiState.Empty(SearchedCircle(25, CircleCentre.SentArea))
        compose.onNodeWithText(string(R.string.search_open_osm)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "bn-w360dp-h640dp")
    fun `in Bengali the chips, the header with Bengali digits and the empty state are translated`() {
        category = SearchCategory.Bank
        state = results(CircleCentre.SentArea, NearSource.Position)
        compose.setContent { Screen() }

        compose.onNodeWithText("ব্যাংক").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag(SearchTags.Within).assert(hasText("আপনার অবস্থানের ১০ কিমির মধ্যে দূরত্বগুলি আনুমানিক।"))

        state = SearchUiState.Empty(SearchedCircle(25, CircleCentre.SentArea))
        compose.onNodeWithText("২৫ কিমির মধ্যে কিছু পাওয়া যায়নি।").assertIsDisplayed()
        compose.onNodeWithText("OpenStreetMap খুলুন").assertIsDisplayed()
    }

    @Test
    fun `no chip, header or message rates a place or calls anything safe`() {
        val ids = chipLabels + listOf(
            R.string.search_chips_label,
            R.string.search_within,
            R.string.search_within_position,
            R.string.search_within_map,
            R.string.search_within_place,
            R.string.search_distance_from_place,
            R.string.search_distance_note,
            R.string.search_nothing_within,
            R.string.search_missing_from_map,
            R.string.search_open_osm,
            R.string.search_no_browser,
        )
        val words = Regex("safe|risk|danger|best|top|recommended|rated|nearest|closest", RegexOption.IGNORE_CASE)
        ids.forEach { id -> assertFalse(context.getString(id), words.containsMatchIn(context.getString(id))) }
    }

    @Test
    fun `the link asks Android to show the map's public site and sends nothing else`() {
        val intent = mapSiteIntent()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.openstreetmap.org/", intent.dataString)
        // A fixed address: no query, no fragment, no extras, so nothing about a search or a place.
        assertNull(intent.data?.query)
        assertNull(intent.data?.fragment)
        assertNull(intent.extras)

        val application = RuntimeEnvironment.getApplication()
        assertTrue(openMapSite(application))
        assertEquals("https://www.openstreetmap.org/", shadowOf(application).nextStartedActivity.dataString)
    }

    @Test
    fun `a phone without a browser is reported, not a crash`() {
        val application = RuntimeEnvironment.getApplication()
        // Robolectric then behaves like a phone: an intent no app can handle throws.
        shadowOf(application).checkActivities(true)
        assertFalse(openMapSite(application))
    }
}
