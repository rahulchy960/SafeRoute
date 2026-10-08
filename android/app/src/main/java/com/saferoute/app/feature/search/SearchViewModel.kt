// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.map.MapSelection
import com.saferoute.app.core.map.SelectedPlace
import com.saferoute.app.core.session.AppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What the search screen shows under the text field. */
sealed interface SearchUiState {
    /** Nothing typed yet. */
    data object Idle : SearchUiState

    /** One character: not enough to search. */
    data object TooShort : SearchUiState

    data object Loading : SearchUiState

    /**
     * @property distancesFrom what the places' distances are measured from: the area THIS
     * answer's search was sent with. Null when it was sent without an area.
     */
    data class Results(
        val places: List<FoundPlace>,
        val attribution: String?,
        val distancesFrom: NearSource? = null,
        /** Set when the server searched a circle (a kind of place or a brand). */
        val circle: SearchedCircle? = null,
    ) : SearchUiState {
        override fun toString(): String = "Results(hidden)"
    }

    /**
     * The search worked and found nothing. With a [circle], nothing of that kind is on the map
     * inside it, which is all the screen may say.
     */
    data class Empty(val circle: SearchedCircle? = null, val distancesFrom: NearSource? = null) : SearchUiState

    data class Error(val error: SearchError) : SearchUiState
}

/** How long the app waits after the last keystroke before it searches. */
internal const val SEARCH_DEBOUNCE_MILLIS = 300L
internal const val SEARCH_MIN_CODE_POINTS = 2
internal const val SEARCH_MAX_CODE_POINTS = 100

/** Characters as people count them, not UTF-16 units: one emoji or one Bengali sign is one. */
internal fun String.codePointLength(): Int = codePointCount(0, length)

/** Cuts [text] to at most [max] code points without splitting one in the middle. */
internal fun limitCodePoints(text: String, max: Int = SEARCH_MAX_CODE_POINTS): String =
    if (text.codePointLength() <= max) text else text.substring(0, text.offsetByCodePoints(0, max))

/**
 * Turns typing into searches.
 *
 * - **Debounce:** it waits [SEARCH_DEBOUNCE_MILLIS] after the last change, so "kol", "kolk",
 *   "kolka" typed quickly make one request, not three.
 * - **Only the newest answer counts:** `collectLatest` cancels the block that is still running
 *   when a new query arrives. A slow answer to an old query is thrown away with its coroutine
 *   and can never replace a newer one.
 * - **Leaving the screen** clears the ViewModel, which cancels `viewModelScope` and with it any
 *   request in flight.
 *
 * The query is never stored, logged or sent anywhere but the search call. There is no history.
 * The same holds for the area the search prefers ([SearchAreaProvider]).
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val locale: AppLocale,
    private val selection: MapSelection,
    private val area: SearchAreaProvider,
) : ViewModel() {

    /**
     * The trimmed query or a chip's category, plus a counter that makes "search now" a new
     * value. Never both: typing ends a chip's search, and a chip is chosen with an empty field.
     */
    private data class Request(val query: String, val submitted: Int, val category: SearchCategory? = null) {
        override fun toString(): String = "Request(hidden)"
    }

    private val requests = MutableStateFlow(Request(query = "", submitted = 0))
    private val _category = MutableStateFlow<SearchCategory?>(null)

    /** The chip whose search is on screen, if any. */
    val category: StateFlow<SearchCategory?> = _category.asStateFlow()
    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** Opened from directions' "Change": the place chosen becomes the start of the route. */
    val choosingStart: Boolean = selection.choosingStart

    /** The request the current [state] answers; a repeated "search now" for it does nothing. */
    private var answered: Request? = null

    init {
        viewModelScope.launch {
            // A StateFlow drops a value equal to the last one: typing a space after a word, or
            // the same text again, starts nothing.
            requests.collectLatest { request ->
                val length = request.query.codePointLength()
                when {
                    request.category != null -> run(request)
                    length == 0 -> show(SearchUiState.Idle)
                    length < SEARCH_MIN_CODE_POINTS -> show(SearchUiState.TooShort)
                    else -> {
                        // The keyboard's Search key skips the wait.
                        if (request.submitted == 0) delay(SEARCH_DEBOUNCE_MILLIS)
                        run(request)
                    }
                }
            }
        }
    }

    /** One search, for typed text or for a chip. Runs inside `collectLatest`: a newer request cancels it. */
    private suspend fun run(request: Request) {
        _state.value = SearchUiState.Loading
        // Decided once per search and kept with its answer, so the distances on screen are
        // always labelled with what they were measured from.
        val near = area.current()
        val outcome = repository.search(
            query = request.query,
            near = near?.point,
            language = locale.current(),
            category = request.category,
        )
        answered = request.copy(submitted = 0)
        _state.value = when (outcome) {
            is SearchOutcome.Failed -> SearchUiState.Error(outcome.error)
            is SearchOutcome.Found ->
                if (outcome.places.isEmpty()) {
                    SearchUiState.Empty(outcome.circle, near?.source)
                } else {
                    SearchUiState.Results(outcome.places, outcome.attribution, near?.source, outcome.circle)
                }
        }
    }

    private fun show(state: SearchUiState) {
        answered = null
        _state.value = state
    }

    /**
     * The text field changed. Typing ends a chip's search. An EMPTY field does not: a chip is
     * chosen with an empty field, and the screen reports that empty text again after a rotation.
     */
    fun onQueryChange(text: String) {
        val query = limitCodePoints(text.trim())
        if (query.isEmpty() && requests.value.category != null) return
        _category.value = null
        requests.value = Request(query = query, submitted = 0)
    }

    /**
     * A quick-search chip was tapped: search that kind of place near the user, at once. Tapping
     * the chip that is already chosen takes it back.
     */
    fun onCategoryClick(category: SearchCategory) {
        val current = requests.value
        if (current.category == category) {
            _category.value = null
            requests.value = Request(query = "", submitted = 0)
            return
        }
        _category.value = category
        requests.value = Request(query = "", submitted = current.submitted + 1, category = category)
    }

    /** The keyboard's Search key, or "Try again". Searches at once, unless the answer is on screen. */
    fun onSearchNow() {
        val current = requests.value
        if (current.category == null && current.query.codePointLength() < SEARCH_MIN_CODE_POINTS) return
        val showsAnswer = answered == current.copy(submitted = 0) && _state.value !is SearchUiState.Error
        if (showsAnswer) return
        requests.value = current.copy(submitted = current.submitted + 1)
    }

    /**
     * A result was tapped: hand it to the map, as the place to show or as the start of the
     * route. The caller then goes back to Home.
     */
    fun onPlaceChosen(place: FoundPlace) {
        selection.choose(SelectedPlace(name = place.name, label = place.label, position = place.position))
    }

    /** Search was left without a choice: the next search is an ordinary one again. */
    override fun onCleared() {
        selection.choosingStart = false
    }
}
