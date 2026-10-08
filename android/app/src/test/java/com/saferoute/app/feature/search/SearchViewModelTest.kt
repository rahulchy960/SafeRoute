// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search

import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.location.FAKE_POSITION
import androidx.lifecycle.ViewModelStore
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapSelection
import com.saferoute.app.core.map.SelectedPlace
import com.saferoute.app.core.session.AppLocale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * Debounce, "newest answer wins", cancellation and the states of [SearchViewModel].
 *
 * Time is virtual: `StandardTestDispatcher` runs nothing until the test says so, and
 * `advanceTimeBy(300)` moves the clock forward without waiting. That makes "typed quickly" and
 * "a slow answer" exact instead of a matter of luck.
 *
 * Robolectric is used only so that Android's log can be captured (the last test).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repository = FakeSearchRepository()
    private val area = SearchAreaParts(granted = GrantedLocation.None)
    private val selection = area.selection
    private var language = "en"
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = SearchViewModel(repository, AppLocale { language }, selection, area.provider)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.type(text: String, thenWaitMillis: Long = 0) {
        viewModel.onQueryChange(text)
        advanceTimeBy(thenWaitMillis)
        runCurrent()
    }

    private val queries get() = repository.calls.map { it.query }

    @Test
    fun `starts idle and searches nothing`() = scope.runTest {
        advanceUntilIdle()
        assertEquals(SearchUiState.Idle, viewModel.state.value)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `typing quickly makes one call, for the last text`() = scope.runTest {
        type("ab", thenWaitMillis = 100)
        type("abc", thenWaitMillis = 100)
        type("abcd", thenWaitMillis = 299)
        assertTrue(repository.calls.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("abcd"), queries)
        assertEquals(
            SearchUiState.Results(listOf(FAKE_STATION, FAKE_MARKET), FAKE_ATTRIBUTION),
            viewModel.state.value,
        )
    }

    @Test
    fun `one character is too short and makes no call, however long the wait`() = scope.runTest {
        type("a", thenWaitMillis = 5_000)
        assertEquals(SearchUiState.TooShort, viewModel.state.value)
        type("   a   ", thenWaitMillis = 5_000)
        assertEquals(SearchUiState.TooShort, viewModel.state.value)
        assertTrue(repository.calls.isEmpty())

        type("", thenWaitMillis = 5_000)
        assertEquals(SearchUiState.Idle, viewModel.state.value)
        type("    ", thenWaitMillis = 5_000)
        assertEquals(SearchUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `the query is trimmed, and the same text again does not search again`() = scope.runTest {
        type("  station ", thenWaitMillis = 400)
        assertEquals(listOf("station"), queries)

        // More spaces, or the same text set again (a recomposition): nothing new to search.
        type("station   ", thenWaitMillis = 400)
        type("station", thenWaitMillis = 400)
        assertEquals(listOf("station"), queries)
    }

    @Test
    fun `Bengali text is searched as typed, joiners included, counted in code points`() = scope.runTest {
        val twoSigns = "হা"
        type(twoSigns, thenWaitMillis = 400)
        assertEquals(listOf(twoSigns), queries)

        val withJoiner = "হাওড়া‌ স্টেশন"
        type(withJoiner, thenWaitMillis = 400)
        assertEquals(withJoiner, queries.last())

        // One emoji is two UTF-16 units and one code point: still too short.
        type("😀", thenWaitMillis = 400)
        assertEquals(SearchUiState.TooShort, viewModel.state.value)
    }

    @Test
    fun `a query longer than 100 code points is cut, never mid-character`() {
        val emoji = "😀"
        assertEquals(100, limitCodePoints("a".repeat(150)).codePointLength())
        val cut = limitCodePoints(emoji.repeat(120))
        assertEquals(100, cut.codePointLength())
        assertEquals(emoji.repeat(100), cut)
        assertEquals("short", limitCodePoints("short"))
    }

    @Test
    fun `a slow answer to an old query never replaces a newer one`() = scope.runTest {
        val slow = CompletableDeferred<Unit>()
        repository.gates["old query"] = slow
        repository.answers["old query"] = SearchOutcome.Found(listOf(FAKE_MARKET), null)
        repository.answers["new query"] = SearchOutcome.Found(listOf(FAKE_STATION), null)

        type("old query", thenWaitMillis = 400)
        assertEquals(SearchUiState.Loading, viewModel.state.value)

        type("new query", thenWaitMillis = 400)
        assertEquals(SearchUiState.Results(listOf(FAKE_STATION), null), viewModel.state.value)

        // The old answer "arrives" now. Its coroutine was cancelled: nothing changes.
        slow.complete(Unit)
        advanceUntilIdle()
        assertEquals(SearchUiState.Results(listOf(FAKE_STATION), null), viewModel.state.value)
        assertEquals(listOf("old query", "new query"), queries)
        assertEquals(1, repository.completed)
    }

    @Test
    fun `leaving the screen cancels the search in flight`() = scope.runTest {
        val never = CompletableDeferred<Unit>()
        repository.gates["station"] = never
        // A ViewModelStore is what the screen's owner holds; clearing it is "the screen is gone".
        val store = ViewModelStore()
        store.put("search", viewModel)

        type("station", thenWaitMillis = 400)
        assertEquals(SearchUiState.Loading, viewModel.state.value)

        store.clear()
        never.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, repository.completed)
        assertEquals(SearchUiState.Loading, viewModel.state.value)
    }

    @Test
    fun `each outcome has its state`() = scope.runTest {
        val outcomes = mapOf(
            "nothing here" to (SearchOutcome.Found(emptyList(), FAKE_ATTRIBUTION) to SearchUiState.Empty()),
            "offline" to failed(SearchError.NoConnection),
            "too many" to failed(SearchError.RateLimited(retryAfterSeconds = 17)),
            "too many unknown" to failed(SearchError.RateLimited(retryAfterSeconds = null)),
            "down" to failed(SearchError.Unavailable),
            "odd" to failed(SearchError.Unexpected),
            "no credit" to (
                SearchOutcome.Found(listOf(FAKE_STATION), null) to SearchUiState.Results(listOf(FAKE_STATION), null)
                ),
        )
        outcomes.forEach { (query, pair) ->
            repository.answers[query] = pair.first
            type(query, thenWaitMillis = 400)
            assertEquals(query, pair.second, viewModel.state.value)
        }
    }

    private fun failed(error: SearchError) = SearchOutcome.Failed(error) to SearchUiState.Error(error)

    @Test
    fun `the keyboard's Search key searches at once, and not again while the answer shows`() = scope.runTest {
        type("station")
        viewModel.onSearchNow()
        runCurrent()
        assertEquals(listOf("station"), queries)
        assertTrue(viewModel.state.value is SearchUiState.Results)

        viewModel.onSearchNow()
        advanceUntilIdle()
        assertEquals(listOf("station"), queries)

        // Too short: the key does nothing.
        type("a")
        viewModel.onSearchNow()
        advanceUntilIdle()
        assertEquals(listOf("station"), queries)
    }

    @Test
    fun `Try again repeats a failed search without waiting`() = scope.runTest {
        repository.answers["station"] = SearchOutcome.Failed(SearchError.NoConnection)
        type("station", thenWaitMillis = 400)
        assertEquals(SearchUiState.Error(SearchError.NoConnection), viewModel.state.value)

        repository.answers.remove("station")
        viewModel.onSearchNow()
        runCurrent()
        assertEquals(listOf("station", "station"), queries)
        assertTrue(viewModel.state.value is SearchUiState.Results)
    }

    @Test
    fun `the search prefers the area the map shows and follows the app language`() = scope.runTest {
        type("station", thenWaitMillis = 400)
        // The map has not reported yet: no area is sent, the server uses its default.
        assertEquals(FakeSearchRepository.Call("station", null, "en"), repository.calls.last())

        selection.viewCentre = LatLng(10.123456, 20.987654)
        language = "bn"
        type("বাজার", thenWaitMillis = 400)
        assertEquals(
            FakeSearchRepository.Call("বাজার", LatLng(10.123456, 20.987654), "bn"),
            repository.calls.last(),
        )
    }

    @Test
    fun `with the permission and a recent position the search is sent from there, and says so`() = scope.runTest {
        selection.viewCentre = LatLng(12.0, 22.0)
        area.environment.granted = GrantedLocation.Precise
        area.location.state.value = LocationState.Fix(area.fixAged(60_000))

        type("bank", thenWaitMillis = SEARCH_DEBOUNCE_MILLIS)

        assertEquals(FAKE_POSITION, repository.calls.single().near)
        assertEquals(NearSource.Position, (viewModel.state.value as SearchUiState.Results).distancesFrom)
    }

    @Test
    fun `a stale position falls back to the map centre, and the answer is labelled so`() = scope.runTest {
        selection.viewCentre = LatLng(12.0, 22.0)
        area.environment.granted = GrantedLocation.Precise
        area.location.state.value = LocationState.Fix(area.fixAged(SEARCH_POSITION_MAX_AGE_MILLIS + 1))

        type("bank", thenWaitMillis = SEARCH_DEBOUNCE_MILLIS)

        assertEquals(LatLng(12.0, 22.0), repository.calls.single().near)
        assertEquals(NearSource.MapCentre, (viewModel.state.value as SearchUiState.Results).distancesFrom)
    }

    @Test
    fun `no position and a map that shows the whole region - no area is sent and nothing is labelled`() = scope.runTest {
        type("bank", thenWaitMillis = SEARCH_DEBOUNCE_MILLIS)

        assertNull(repository.calls.single().near)
        assertNull((viewModel.state.value as SearchUiState.Results).distancesFrom)
    }

    @Test
    fun `the label belongs to the answer - a position that arrives later does not relabel it`() = scope.runTest {
        selection.viewCentre = LatLng(12.0, 22.0)
        type("bank", thenWaitMillis = SEARCH_DEBOUNCE_MILLIS)
        assertEquals(NearSource.MapCentre, (viewModel.state.value as SearchUiState.Results).distancesFrom)

        area.environment.granted = GrantedLocation.Precise
        area.location.state.value = LocationState.Fix(area.fixAged(0))
        runCurrent()

        // Still the answer of the search that was sent from the map centre.
        assertEquals(NearSource.MapCentre, (viewModel.state.value as SearchUiState.Results).distancesFrom)
        assertEquals(1, repository.calls.size)
    }

    @Test
    fun `choosing a result hands it to the map`() = scope.runTest {
        assertNull(selection.selected.value)
        viewModel.onPlaceChosen(FAKE_STATION)
        assertEquals(
            SelectedPlace("Main Station", "Station Road, Example District", LatLng(10.5, 20.5)),
            selection.selected.value,
        )
    }

    private val circle = SearchedCircle(10, CircleCentre.SentArea)
    private val pharmacies = SearchOutcome.Found(listOf(FAKE_MARKET), FAKE_ATTRIBUTION, circle)

    @Test
    fun `a chip searches its category at once, near the user, and the answer carries the circle`() = scope.runTest {
        area.environment.granted = GrantedLocation.Precise
        area.location.state.value = LocationState.Fix(area.fixAged(60_000))
        repository.categoryAnswers[SearchCategory.Pharmacy] = pharmacies

        viewModel.onCategoryClick(SearchCategory.Pharmacy)
        // No debounce: a tap is a decision, not typing.
        runCurrent()

        assertEquals(
            listOf(FakeSearchRepository.Call("", FAKE_POSITION, "en", SearchCategory.Pharmacy)),
            repository.calls,
        )
        assertEquals(SearchCategory.Pharmacy, viewModel.category.value)
        assertEquals(
            SearchUiState.Results(listOf(FAKE_MARKET), FAKE_ATTRIBUTION, NearSource.Position, circle),
            viewModel.state.value,
        )
    }

    @Test
    fun `a chip without a position uses the map centre, like typed text`() = scope.runTest {
        selection.viewCentre = LatLng(12.0, 22.0)
        viewModel.onCategoryClick(SearchCategory.Bank)
        runCurrent()
        assertEquals(LatLng(12.0, 22.0), repository.calls.single().near)
        assertEquals(NearSource.MapCentre, (viewModel.state.value as SearchUiState.Results).distancesFrom)
    }

    @Test
    fun `typed category and brand words go to the server as text, and its circle is kept`() = scope.runTest {
        selection.viewCentre = LatLng(12.0, 22.0)
        repository.answers["bank"] = pharmacies
        val around = SearchedCircle(25, CircleCentre.TypedPlace)
        repository.answers["sbi exampletown"] = SearchOutcome.Found(listOf(FAKE_STATION), null, around)

        type("bank", thenWaitMillis = SEARCH_DEBOUNCE_MILLIS)
        assertNull(repository.calls.last().category)
        assertEquals(circle, (viewModel.state.value as SearchUiState.Results).circle)

        type("sbi exampletown", thenWaitMillis = SEARCH_DEBOUNCE_MILLIS)
        assertEquals("sbi exampletown", repository.calls.last().query)
        assertEquals(around, (viewModel.state.value as SearchUiState.Results).circle)
        assertNull(viewModel.category.value)
    }

    @Test
    fun `nothing in the circle is Empty with the circle, for a chip and for typed text`() = scope.runTest {
        val wide = SearchedCircle(25, CircleCentre.SentArea)
        repository.categoryAnswers[SearchCategory.Atm] = SearchOutcome.Found(emptyList(), FAKE_ATTRIBUTION, wide)
        repository.answers["pharmacy"] = SearchOutcome.Found(emptyList(), null, wide)
        repository.answers["nowhere"] = SearchOutcome.Found(emptyList(), null)
        selection.viewCentre = LatLng(12.0, 22.0)

        viewModel.onCategoryClick(SearchCategory.Atm)
        runCurrent()
        assertEquals(SearchUiState.Empty(wide, NearSource.MapCentre), viewModel.state.value)

        type("pharmacy", thenWaitMillis = SEARCH_DEBOUNCE_MILLIS)
        assertEquals(SearchUiState.Empty(wide, NearSource.MapCentre), viewModel.state.value)
        // A name search that finds nothing has no circle to talk about.
        type("nowhere", thenWaitMillis = SEARCH_DEBOUNCE_MILLIS)
        assertEquals(SearchUiState.Empty(null, NearSource.MapCentre), viewModel.state.value)
    }

    @Test
    fun `a chip's search can fail like any other - offline, 429, 503 - and Try again repeats it`() = scope.runTest {
        val errors = listOf(
            SearchError.NoConnection,
            SearchError.RateLimited(12),
            SearchError.Unavailable,
        )
        errors.forEach { error ->
            repository.categoryAnswers[SearchCategory.Hospital] = SearchOutcome.Failed(error)
            viewModel.onSearchNow()
            if (viewModel.category.value == null) viewModel.onCategoryClick(SearchCategory.Hospital)
            runCurrent()
            assertEquals(SearchUiState.Error(error), viewModel.state.value)
            assertEquals(SearchCategory.Hospital, viewModel.category.value)
        }
        assertEquals(3, repository.calls.size)

        repository.categoryAnswers.remove(SearchCategory.Hospital)
        viewModel.onSearchNow()
        runCurrent()
        assertEquals(SearchCategory.Hospital, repository.calls.last().category)
        assertTrue(viewModel.state.value is SearchUiState.Results)
        // The answer is on screen: asking again does nothing.
        viewModel.onSearchNow()
        runCurrent()
        assertEquals(4, repository.calls.size)
    }

    @Test
    fun `typing ends a chip's search, an empty field does not, and tapping the chip again takes it back`() = scope.runTest {
        viewModel.onCategoryClick(SearchCategory.Food)
        runCurrent()
        // The screen reports its (empty) text again, as after a rotation: the chip stays.
        type("")
        assertEquals(SearchCategory.Food, viewModel.category.value)
        assertTrue(viewModel.state.value is SearchUiState.Results)
        assertEquals(1, repository.calls.size)

        type("st", thenWaitMillis = SEARCH_DEBOUNCE_MILLIS)
        assertNull(viewModel.category.value)
        assertEquals(FakeSearchRepository.Call("st", null, "en"), repository.calls.last())

        type("")
        assertEquals(SearchUiState.Idle, viewModel.state.value)
        viewModel.onCategoryClick(SearchCategory.Food)
        runCurrent()
        viewModel.onCategoryClick(SearchCategory.Food)
        runCurrent()
        assertNull(viewModel.category.value)
        assertEquals(SearchUiState.Idle, viewModel.state.value)
        assertEquals(3, repository.calls.size)
    }

    @Test
    fun `another chip replaces a slow one, and the old answer never shows`() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        repository.gates[""] = gate
        repository.categoryAnswers[SearchCategory.Bank] = SearchOutcome.Found(listOf(FAKE_STATION), null, circle)
        repository.categoryAnswers[SearchCategory.Grocery] = pharmacies

        viewModel.onCategoryClick(SearchCategory.Bank)
        runCurrent()
        assertEquals(SearchUiState.Loading, viewModel.state.value)
        viewModel.onCategoryClick(SearchCategory.Grocery)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(FAKE_MARKET), (viewModel.state.value as SearchUiState.Results).places)
        assertEquals(1, repository.completed)
    }

    @Test
    fun `choosing a start point works from a chip's results too`() = scope.runTest {
        selection.choosingStart = true
        val forStart = SearchViewModel(repository, AppLocale { language }, selection, area.provider)
        assertTrue(forStart.choosingStart)
        forStart.onCategoryClick(SearchCategory.Transit)
        runCurrent()
        forStart.onPlaceChosen((forStart.state.value as SearchUiState.Results).places.first())
        // The place went where a start point goes, not to the map's pin.
        assertNull(selection.selected.value)
    }

    @Test
    fun `a chip's search leaves nothing in the log or a toString`() = scope.runTest {
        ShadowLog.clear()
        area.environment.granted = GrantedLocation.Precise
        area.location.state.value = LocationState.Fix(area.fixAged(0))
        repository.categoryAnswers[SearchCategory.Pharmacy] = pharmacies
        viewModel.onCategoryClick(SearchCategory.Pharmacy)
        runCurrent()

        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        val printed = listOf(viewModel.state.value, pharmacies, SearchUiState.Empty(circle)).joinToString(" ")
        listOf("pharmacy", "Pharmacy", "Station Market", "Example Town", FAKE_POSITION.latitude.toString())
            .forEach { secret ->
                assertTrue("log contains $secret", secret !in logged)
                assertTrue("toString contains $secret", secret !in printed.replace("Empty(circle=", ""))
            }
    }

    @Test
    fun `nothing about a search reaches the log or a toString`() = scope.runTest {
        ShadowLog.clear()
        selection.viewCentre = LatLng(10.123456, 20.987654)
        val query = "হাওড়া SECRETQUERY"
        type(query, thenWaitMillis = 400)
        viewModel.onPlaceChosen(FAKE_STATION)
        repository.answers["broken"] = SearchOutcome.Failed(SearchError.Unexpected)
        type("broken", thenWaitMillis = 400)

        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        val printed = listOf(
            viewModel.state.value,
            SearchUiState.Results(listOf(FAKE_STATION), FAKE_ATTRIBUTION),
            SearchOutcome.Found(listOf(FAKE_STATION), FAKE_ATTRIBUTION),
            FAKE_STATION,
            selection.selected.value,
        ).joinToString(" ")
        listOf("SECRETQUERY", "হাওড়া", "10.12", "20.98", "10.5", "20.5", "Main Station", "Station Road")
            .forEach { secret ->
                assertTrue("log contains $secret", secret !in logged)
                assertTrue("toString contains $secret", secret !in printed)
            }
    }
}
