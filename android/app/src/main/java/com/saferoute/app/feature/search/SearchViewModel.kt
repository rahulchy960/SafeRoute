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

    data class Results(val places: List<FoundPlace>, val attribution: String?) : SearchUiState {
        override fun toString(): String = "Results(hidden)"
    }

    /** The search worked and found nothing. */
    data object Empty : SearchUiState

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
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val locale: AppLocale,
    private val selection: MapSelection,
) : ViewModel() {

    /** The trimmed query, plus a counter that makes "search now" a new value. */
    private data class Request(val query: String, val submitted: Int) {
        override fun toString(): String = "Request(hidden)"
    }

    private val requests = MutableStateFlow(Request(query = "", submitted = 0))
    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** The query the current [state] answers; a repeated "search now" for it does nothing. */
    private var answered: String? = null

    init {
        viewModelScope.launch {
            // A StateFlow drops a value equal to the last one: typing a space after a word, or
            // the same text again, starts nothing.
            requests.collectLatest { request ->
                val length = request.query.codePointLength()
                when {
                    length == 0 -> show(SearchUiState.Idle)
                    length < SEARCH_MIN_CODE_POINTS -> show(SearchUiState.TooShort)
                    else -> {
                        // The keyboard's Search key skips the wait.
                        if (request.submitted == 0) delay(SEARCH_DEBOUNCE_MILLIS)
                        _state.value = SearchUiState.Loading
                        val outcome = repository.search(
                            query = request.query,
                            near = selection.viewCentre,
                            language = locale.current(),
                        )
                        answered = request.query
                        _state.value = when (outcome) {
                            is SearchOutcome.Failed -> SearchUiState.Error(outcome.error)
                            is SearchOutcome.Found ->
                                if (outcome.places.isEmpty()) {
                                    SearchUiState.Empty
                                } else {
                                    SearchUiState.Results(outcome.places, outcome.attribution)
                                }
                        }
                    }
                }
            }
        }
    }

    private fun show(state: SearchUiState) {
        answered = null
        _state.value = state
    }

    /** The text field changed. */
    fun onQueryChange(text: String) {
        requests.value = Request(query = limitCodePoints(text.trim()), submitted = 0)
    }

    /** The keyboard's Search key, or "Try again". Searches at once, unless the answer is on screen. */
    fun onSearchNow() {
        val current = requests.value
        if (current.query.codePointLength() < SEARCH_MIN_CODE_POINTS) return
        val showsAnswer = answered == current.query && _state.value !is SearchUiState.Error
        if (showsAnswer) return
        requests.value = current.copy(submitted = current.submitted + 1)
    }

    /** A result was tapped: hand it to the map. The caller then goes back to Home. */
    fun onPlaceChosen(place: FoundPlace) {
        selection.select(SelectedPlace(name = place.name, label = place.label, position = place.position))
    }
}
