// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.location.FAKE_POSITION
import com.saferoute.app.core.location.FakeLocationEnvironment
import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.location.fakeFix
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapSelection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** A clock the test moves by hand. */
class TestClock(var nowMillis: Long = 1_000_000_000L) : Clock() {
    override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
}

/** Everything [SearchAreaProvider] reads, as fakes a test can set. */
class SearchAreaParts(
    granted: GrantedLocation = GrantedLocation.Precise,
    val clock: TestClock = TestClock(),
) {
    val location = FakeLocationRepository()
    val environment = FakeLocationEnvironment(granted = granted)
    val selection = MapSelection()
    val provider = SearchAreaProvider(location, environment, selection, clock)

    /** A position received [ageMillis] ago. */
    fun fixAged(ageMillis: Long, position: LatLng = FAKE_POSITION) =
        fakeFix(position = position, timeMillis = clock.nowMillis - ageMillis)
}

/**
 * Where a search looks first (P011e2, ADR 0018): the user's recent position when the app may
 * know it, otherwise the middle of the map, otherwise nowhere in particular.
 */
@RunWith(AndroidJUnit4::class)
class SearchAreaProviderTest {

    private val mapCentre = LatLng(12.0, 22.0)

    @Test
    fun `a recent position, with the permission, is the area`() {
        val parts = SearchAreaParts()
        parts.selection.viewCentre = mapCentre
        parts.location.state.value = LocationState.Fix(parts.fixAged(30_000))

        assertEquals(SearchArea(FAKE_POSITION, NearSource.Position), parts.provider.current())
    }

    @Test
    fun `five minutes is still recent, a moment more is not - then the map centre is the area`() {
        val parts = SearchAreaParts()
        parts.selection.viewCentre = mapCentre

        parts.location.state.value = LocationState.Fix(parts.fixAged(SEARCH_POSITION_MAX_AGE_MILLIS))
        assertEquals(NearSource.Position, parts.provider.current()?.source)

        parts.location.state.value = LocationState.Fix(parts.fixAged(SEARCH_POSITION_MAX_AGE_MILLIS + 1))
        assertEquals(SearchArea(mapCentre, NearSource.MapCentre), parts.provider.current())
        assertEquals(5 * 60_000L, SEARCH_POSITION_MAX_AGE_MILLIS)
    }

    @Test
    fun `a position goes stale while the search screen is open - the clock decides, not the label`() {
        // Location updates stop when Home is not on screen, so the state still says "Fix".
        val parts = SearchAreaParts()
        parts.selection.viewCentre = mapCentre
        parts.location.state.value = LocationState.Fix(parts.fixAged(0))
        assertEquals(NearSource.Position, parts.provider.current()?.source)

        parts.clock.nowMillis += 6 * 60_000L

        assertEquals(NearSource.MapCentre, parts.provider.current()?.source)
    }

    @Test
    fun `a position shown as old on the map but younger than five minutes still counts`() {
        val parts = SearchAreaParts()
        parts.location.state.value = LocationState.Stale(parts.fixAged(120_000), ageSeconds = 120)

        assertEquals(SearchArea(FAKE_POSITION, NearSource.Position), parts.provider.current())
    }

    @Test
    fun `without the permission a position in memory is not used`() {
        // The permission was taken away in system Settings while the app stayed open.
        val parts = SearchAreaParts(granted = GrantedLocation.None)
        parts.selection.viewCentre = mapCentre
        parts.location.state.value = LocationState.Fix(parts.fixAged(1_000))

        assertEquals(SearchArea(mapCentre, NearSource.MapCentre), parts.provider.current())
    }

    @Test
    fun `approximate permission is enough`() {
        val parts = SearchAreaParts(granted = GrantedLocation.Approximate)
        parts.location.state.value = LocationState.Fix(parts.fixAged(1_000))

        assertEquals(NearSource.Position, parts.provider.current()?.source)
    }

    @Test
    fun `no position - the map centre, and without one nothing at all`() {
        val parts = SearchAreaParts()
        for (state in listOf(LocationState.NoPermission, LocationState.Searching, LocationState.Unavailable)) {
            parts.location.state.value = state
            parts.selection.viewCentre = mapCentre
            assertEquals(state.toString(), SearchArea(mapCentre, NearSource.MapCentre), parts.provider.current())

            // The map shows the whole region: no area.
            parts.selection.viewCentre = null
            assertNull(state.toString(), parts.provider.current())
        }
    }

    @Test
    fun `a position dated in the future is not trusted`() {
        val parts = SearchAreaParts()
        parts.location.state.value = LocationState.Fix(parts.fixAged(-60_000))

        assertNull(parts.provider.current())
    }

    @Test
    fun `asking for the area starts nothing, requests nothing and prints nothing`() {
        ShadowLog.clear()
        val parts = SearchAreaParts()
        parts.location.state.value = LocationState.Fix(parts.fixAged(1_000, LatLng(12.345678, 98.765432)))

        val area = parts.provider.current()

        // It only reads: location is neither started nor stopped by a search.
        assertEquals(0, parts.location.starts)
        assertEquals(0, parts.location.stops)
        // The type that holds the area hides it.
        assertEquals("SearchArea(hidden)", area.toString())
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg}" }
        for (secret in listOf("12.345678", "98.765432", "12.35", "98.77")) {
            assertFalse(secret, logged.contains(secret) || "$area".contains(secret))
        }
        Log.d("SearchAreaProviderTest", "log capture works")
        assertTrue(ShadowLog.getLogs().any { it.msg == "log capture works" })
    }
}

/** The distance on each result row. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class SearchDistanceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun place(n: Int, distanceMeters: Int?) = FoundPlace(
        id = "fake-$n",
        name = "Result $n",
        label = "Example District, a fairly long label that wraps",
        position = LatLng(10.0 + n, 20.0),
        kind = "fake",
        distanceMeters = distanceMeters,
    )

    private var state: SearchUiState by mutableStateOf(SearchUiState.Idle)

    @Composable
    private fun Screen(fontScale: Float = 1f) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme {
                SearchScreen(
                    query = "bank",
                    state = state,
                    onQueryChange = {},
                    onSearch = {},
                    onPlaceClick = {},
                    onBack = {},
                )
            }
        }
    }

    private fun results(source: NearSource?, vararg distances: Int?) = SearchUiState.Results(
        places = distances.mapIndexed { index, distance -> place(index + 1, distance) },
        attribution = "Fake attribution line",
        distancesFrom = source,
    )

    @Test
    fun `kilometres have one decimal below ten and none from there, in the language's digits`() {
        assertEquals("2.3", searchKilometres(2_300, Locale.ENGLISH))
        assertEquals("1.0", searchKilometres(1_000, Locale.ENGLISH))
        assertEquals("9.9", searchKilometres(9_900, Locale.ENGLISH))
        assertEquals("10", searchKilometres(10_000, Locale.ENGLISH))
        assertEquals("334", searchKilometres(333_600, Locale.ENGLISH))
        // A comma where the language uses one; Bengali digits where the phone shows them.
        assertEquals("2,3", searchKilometres(2_300, Locale.GERMAN))
        assertEquals("২.৩", searchKilometres(2_300, Locale.forLanguageTag("bn-BD-u-nu-beng")))
    }

    @Test
    fun `each row shows its distance, and under a kilometre it only says so`() {
        state = results(NearSource.Position, 0, 900, 2_300, 12_400)
        compose.setContent { Screen() }

        // 0 m and 900 m: the server measures from a point rounded to about 1 km.
        compose.onAllNodes(androidx.compose.ui.test.hasText(string(R.string.search_distance_under_km)), useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onNodeWithText("2.3 km", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("12 km", useUnmergedTree = true).assertIsDisplayed()
        // The note says once, in words, what they are measured from.
        compose.onNodeWithText(string(R.string.search_distance_note_position)).assertIsDisplayed()
    }

    @Test
    fun `TalkBack hears where the distance is measured from, for each source`() {
        state = results(NearSource.Position, 2_300)
        compose.setContent { Screen() }
        compose.onNodeWithContentDescription("2.3 km from your location", useUnmergedTree = true).assertIsDisplayed()
        // The row is one stop, and its spoken text carries the place and the distance.
        val row = compose.onNodeWithTag(SearchTags.Row).fetchSemanticsNode().config.toString()
        assertTrue(row, row.contains("from your location"))

        state = results(NearSource.MapCentre, 2_300)
        compose.onNodeWithContentDescription("2.3 km from the centre of the map", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText(string(R.string.search_distance_note_map)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.search_distance_note_position)).assertDoesNotExist()
    }

    @Test
    fun `no distance from the server, or no area, shows no distance and no note`() {
        state = results(NearSource.Position, null, null)
        compose.setContent { Screen() }
        compose.onAllNodesWithTag(SearchTags.DistanceNote).assertCountEquals(0)
        compose.onAllNodes(androidx.compose.ui.test.hasText("km", substring = true), useUnmergedTree = true)
            .assertCountEquals(0)

        // The search went out without an area: a stray distance would have no meaning.
        state = results(null, 2_300)
        compose.onAllNodesWithTag(SearchTags.DistanceNote).assertCountEquals(0)
        compose.onAllNodes(androidx.compose.ui.test.hasText("km", substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun `the words make no claim about the places`() {
        // A distance and where it is from. Nothing about safety, quality or being open.
        val banned = Regex("safe|risk|danger|secure|recommended|best|open now|verified", RegexOption.IGNORE_CASE)
        for (id in listOf(
            R.string.search_distance_km,
            R.string.search_distance_under_km,
            R.string.search_distance_from_position,
            R.string.search_distance_from_map,
            R.string.search_distance_note_position,
            R.string.search_distance_note_map,
        )) {
            assertFalse(string(id, "2.3"), banned.containsMatchIn(string(id, "2.3")))
        }
        assertTrue(string(R.string.search_distance_note_position).contains("approximate"))
    }

    @Test
    @Config(qualifiers = "bn-w360dp-h640dp")
    fun `in Bengali at double font size every row keeps its name and its distance`() {
        // A different distance on every row, so each can be looked for by itself.
        val distances = listOf(1_100, 2_300, 3_400, 4_500, 45_000, 120_000)
        // The digits follow the language of the app: Bengali digits on a phone set to Bengali.
        val shown = distances.map { searchKilometres(it, Locale.forLanguageTag("bn")) }
        state = results(NearSource.Position, *distances.toTypedArray())
        compose.setContent { Screen(fontScale = 2f) }

        compose.onNodeWithText("দূরত্বগুলি আনুমানিক, আপনার অবস্থান থেকে।").assertIsDisplayed()
        val list = compose.onNodeWithTag(SearchTags.Results)
        for (n in 1..6) {
            list.performScrollToNode(androidx.compose.ui.test.hasText("Result $n"))
            compose.onNodeWithText("Result $n", useUnmergedTree = true).assertIsDisplayed()
            // The distance sits at the end of the same row and is not squeezed to nothing.
            val spoken = string(
                R.string.search_distance_from_position,
                string(R.string.search_distance_km, shown[n - 1]),
            )
            val distance = compose.onNodeWithContentDescription(spoken, useUnmergedTree = true).fetchSemanticsNode()
            val row = compose.onNode(hasTestTag(SearchTags.Row) and androidx.compose.ui.test.hasText("Result $n"))
                .fetchSemanticsNode()
            assertTrue("row $n: ${distance.size}", distance.size.width > 0 && distance.size.height > 0)
            assertTrue("row $n", row.boundsInRoot.contains(distance.boundsInRoot.center))
        }
    }
}
