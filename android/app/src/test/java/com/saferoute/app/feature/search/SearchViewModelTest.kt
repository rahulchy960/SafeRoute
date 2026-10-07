// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search

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
    private val selection = MapSelection()
    private var language = "en"
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = SearchViewModel(repository, AppLocale { language }, selection)
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
            "nothing here" to (SearchOutcome.Found(emptyList(), FAKE_ATTRIBUTION) to SearchUiState.Empty),
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
    fun `choosing a result hands it to the map`() = scope.runTest {
        assertNull(selection.selected.value)
        viewModel.onPlaceChosen(FAKE_STATION)
        assertEquals(
            SelectedPlace("Main Station", "Station Road, Example District", LatLng(10.5, 20.5)),
            selection.selected.value,
        )
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
