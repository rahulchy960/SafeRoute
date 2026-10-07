// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.location.LocationRepository
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapSelection
import com.saferoute.app.core.map.RouteDisplay
import com.saferoute.app.core.map.RouteLine
import com.saferoute.app.core.map.SelectedPlace
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why directions cannot start from "My location" yet. */
enum class OriginProblem {
    /** The app may not read the location: the screen offers the usual permission flow. */
    NoPermission,

    /** Permitted, and waiting for the first position. */
    Searching,

    /** No position can be had (location switched off, no signal). */
    Unavailable,
}

/** What the directions sheet shows under the destination and the mode toggle. */
sealed interface DirectionsStatus {
    /**
     * A request is on its way.
     *
     * @property starting true once it is clear that the routing service is waking up: the
     * request has taken a while, or the server said "ask again shortly" and the app is waiting
     * to do that. The screen then says so instead of showing a bare spinner.
     */
    data class Loading(val starting: Boolean = false) : DirectionsStatus

    data class Results(
        val routes: List<RouteOption>,
        val selectedId: String,
        val attribution: String,
    ) : DirectionsStatus {
        override fun toString(): String = "Results(hidden)"
    }

    data class NeedsOrigin(val problem: OriginProblem) : DirectionsStatus

    /** @property stillStarting the waiting ran out while the service was still waking up. */
    data class Failed(val error: RouteError, val stillStarting: Boolean = false) : DirectionsStatus
}

sealed interface DirectionsUiState {
    data object Closed : DirectionsUiState

    /** The one-time note, shown before the first request ever leaves the phone. */
    data class Intro(val destination: SelectedPlace) : DirectionsUiState

    data class Open(
        val destination: SelectedPlace,
        val mode: TravelMode,
        val status: DirectionsStatus,
    ) : DirectionsUiState
}

/**
 * Directions from "My location" to the place chosen on the map.
 *
 * Rules this class keeps:
 * - **One request at a time.** A new one (another mode, Try again) cancels the one before; a
 *   second tap on the same thing while it is loading does nothing.
 * - **Loading always ends.** The routing service sleeps when nobody uses it. The first request
 *   after a quiet time can be slow or be answered "ask again in N seconds". The app waits and
 *   asks again by itself at most [MAX_AUTO_RETRIES] times, says "Starting the routing service"
 *   while it does, and then stops with a Try again button. It never spins for ever.
 * - **Nothing is kept.** The origin, the destination and the routes live in this object and in
 *   [RouteDisplay], in memory, until directions are closed. Only the mode and the "intro seen"
 *   flag are stored ([RoutePreferences]). Nothing here is logged.
 * - **Before the first request ever**, the user reads what is sent ([DirectionsUiState.Intro]).
 *
 * The origin is the phone's position as the location dot shows it. This class only reads
 * [LocationRepository.state]; starting, stopping and the permission stay with the Home screen.
 */
@HiltViewModel
class DirectionsViewModel @Inject constructor(
    private val repository: RouteRepository,
    private val preferences: RoutePreferences,
    private val location: LocationRepository,
    private val selection: MapSelection,
    private val display: RouteDisplay,
) : ViewModel() {

    private val _state = MutableStateFlow<DirectionsUiState>(DirectionsUiState.Closed)
    val state: StateFlow<DirectionsUiState> = _state.asStateFlow()

    private var request: Job? = null

    init {
        // Routes belong to one destination: another place, or none, ends them.
        viewModelScope.launch { selection.selected.drop(1).collect { close() } }
        // Waiting for a position: the moment one arrives, ask for routes.
        viewModelScope.launch {
            location.state.collect { state ->
                val open = _state.value as? DirectionsUiState.Open ?: return@collect
                if (open.status is DirectionsStatus.NeedsOrigin) {
                    if (state.origin() != null) load() else setStatus(DirectionsStatus.NeedsOrigin(state.problem()))
                }
            }
        }
    }

    /** "Directions" on the place card. */
    fun onDirectionsClick() {
        val destination = selection.selected.value ?: return
        if (_state.value != DirectionsUiState.Closed) return
        viewModelScope.launch {
            val settings = preferences.read()
            if (!settings.introSeen) {
                _state.value = DirectionsUiState.Intro(destination)
            } else {
                open(destination, settings.mode)
            }
        }
    }

    /** "Continue" on the one-time note. */
    fun onIntroContinue() {
        val intro = _state.value as? DirectionsUiState.Intro ?: return
        viewModelScope.launch {
            preferences.setIntroSeen()
            open(intro.destination, preferences.read().mode)
        }
    }

    fun onModeChange(mode: TravelMode) {
        val open = _state.value as? DirectionsUiState.Open ?: return
        if (open.mode == mode) return
        _state.value = open.copy(mode = mode)
        viewModelScope.launch { preferences.setMode(mode) }
        load()
    }

    /** A route card was tapped. */
    fun onRouteSelect(routeId: String) {
        val open = _state.value as? DirectionsUiState.Open ?: return
        val results = open.status as? DirectionsStatus.Results ?: return
        if (results.routes.none { it.id == routeId }) return
        _state.value = open.copy(status = results.copy(selectedId = routeId))
        display.select(routeId)
    }

    /** "Try again". Ignored while a request is already running: a double tap sends one. */
    fun onRetry() {
        val open = _state.value as? DirectionsUiState.Open ?: return
        if (open.status is DirectionsStatus.Loading) return
        load()
    }

    /** Close, back, or the note's "Not now". */
    fun onClose() = close()

    private fun open(destination: SelectedPlace, mode: TravelMode) {
        _state.value = DirectionsUiState.Open(destination, mode, DirectionsStatus.Loading())
        load()
    }

    private fun close() {
        request?.cancel()
        request = null
        display.clear()
        _state.value = DirectionsUiState.Closed
    }

    private fun setStatus(status: DirectionsStatus) {
        _state.update { (it as? DirectionsUiState.Open)?.copy(status = status) ?: it }
    }

    private fun load() {
        val open = _state.value as? DirectionsUiState.Open ?: return
        request?.cancel()
        display.clear()
        val origin = location.state.value.origin()
        if (origin == null) {
            request = null
            setStatus(DirectionsStatus.NeedsOrigin(location.state.value.problem()))
            return
        }
        setStatus(DirectionsStatus.Loading())
        request = viewModelScope.launch { loadWithWaiting(origin, open.destination, open.mode) }
    }

    private suspend fun loadWithWaiting(origin: LatLng, destination: SelectedPlace, mode: TravelMode) {
        var retries = 0
        while (true) {
            // A request that is simply slow gets the same explanation after a few seconds.
            val slowHint = viewModelScope.launch {
                delay(SLOW_HINT_MILLIS)
                setStatus(DirectionsStatus.Loading(starting = true))
            }
            val outcome = try {
                repository.routes(origin, destination.position, mode)
            } finally {
                slowHint.cancel()
            }
            when (outcome) {
                is RouteOutcome.Found -> {
                    val first = outcome.routes.first()
                    display.show(outcome.routes.map { RouteLine(it.id, it.points) }, first.id, origin)
                    setStatus(DirectionsStatus.Results(outcome.routes, first.id, outcome.attribution))
                    return
                }
                is RouteOutcome.Failed -> {
                    val error = outcome.error
                    if (error !is RouteError.Starting || retries == MAX_AUTO_RETRIES) {
                        setStatus(DirectionsStatus.Failed(error, stillStarting = error is RouteError.Starting))
                        return
                    }
                    retries++
                    setStatus(DirectionsStatus.Loading(starting = true))
                    delay(error.retryAfterSeconds.coerceIn(1, MAX_WAIT_SECONDS) * 1000L)
                }
            }
        }
    }

    companion object {
        /** How often the app asks again by itself while the routing service wakes up. */
        const val MAX_AUTO_RETRIES = 2

        /** The longest single wait the app accepts from a `Retry-After`. */
        const val MAX_WAIT_SECONDS = 15

        /** After this long a running request is explained instead of only spinning. */
        const val SLOW_HINT_MILLIS = 5_000L
    }
}

private fun LocationState.origin(): LatLng? = when (this) {
    is LocationState.Fix -> fix.position
    // The last known position: the dot on the map is grey, and the route starts there.
    is LocationState.Stale -> lastFix.position
    else -> null
}

private fun LocationState.problem(): OriginProblem = when (this) {
    LocationState.Searching -> OriginProblem.Searching
    LocationState.Unavailable -> OriginProblem.Unavailable
    else -> OriginProblem.NoPermission
}
