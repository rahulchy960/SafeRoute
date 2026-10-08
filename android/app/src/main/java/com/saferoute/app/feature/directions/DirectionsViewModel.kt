// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.location.LocationFix
import com.saferoute.app.core.location.LocationRepository
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.map.FollowView
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapSelection
import com.saferoute.app.core.map.RouteDisplay
import com.saferoute.app.core.map.RouteLine
import com.saferoute.app.core.map.SelectedPlace
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/** Why "Start" did not begin following the route. Each has its own sentence and way out. */
enum class StartProblem {
    /** The app may not read the location at all. */
    NoPermission,

    /** Only approximate location is allowed: too coarse to say where on a route someone is. */
    NeedsPrecise,

    /** Permitted, but there is no position from the last few seconds. */
    NoRecentFix,
}

/** The answer to "Recalculate" while a route is followed. */
sealed interface Recalculation {
    data object Idle : Recalculation
    data object Running : Recalculation
    data class Failed(val error: RouteError) : Recalculation
}

/**
 * A route is being followed (ADR 0022). Numbers and flags only: no position is in here.
 *
 * @property arrivalMillis the clock time at which [remainingSeconds] will have passed.
 * @property gpsLost no position for [DirectionsViewModel.GPS_LOST_AFTER_MILLIS]: "Searching for GPS".
 * @property arrived the end of the route was reached; following has stopped.
 * @property pausedNote the app was in the background, where it does not follow; said once.
 * @property confirmEnd the "End navigation?" question is open.
 */
data class FollowState(
    val remainingMeters: Int,
    val remainingSeconds: Int,
    val arrivalMillis: Long,
    val gpsLost: Boolean = false,
    val offRoute: Boolean = false,
    val recalculation: Recalculation = Recalculation.Idle,
    val arrived: Boolean = false,
    val pausedNote: Boolean = false,
    val confirmEnd: Boolean = false,
) {
    /** Still on the way: the camera follows and the screen stays on. */
    val active: Boolean get() = !arrived
}

sealed interface DirectionsUiState {
    data object Closed : DirectionsUiState

    /** The one-time note, shown before the first request ever leaves the phone. */
    data class Intro(val destination: SelectedPlace) : DirectionsUiState

    data class Open(
        val destination: SelectedPlace,
        val mode: TravelMode,
        val status: DirectionsStatus,
        val follow: FollowState? = null,
        val startProblem: StartProblem? = null,
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
 * - **Following a route** (ADR 0022) happens here too, with [RouteTracker] doing the arithmetic.
 *   It runs only while the app is on screen, needs precise location and a position from the
 *   last few seconds, never asks for a new route by itself, and is gone when this object is:
 *   nothing about it is saved, so it does not come back after the app's process was ended.
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
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow<DirectionsUiState>(DirectionsUiState.Closed)
    val state: StateFlow<DirectionsUiState> = _state.asStateFlow()

    private var request: Job? = null

    private var tracker: RouteTracker? = null
    private var followJob: Job? = null
    private var recalculation: Job? = null
    private var lastFixMillis = 0L
    private var paused = false
    private var pausedNoteShown = false

    init {
        // Routes belong to one destination: another place, or none, ends them.
        viewModelScope.launch { selection.selected.drop(1).collect { close() } }
        // Waiting for a position: the moment one arrives, ask for routes.
        viewModelScope.launch {
            location.state.collect { state ->
                val open = _state.value as? DirectionsUiState.Open ?: return@collect
                // The reason Start gave is no longer true: take the sentence away.
                if (open.startProblem != null && state is LocationState.Fix && !state.fix.isApproximate) {
                    _state.value = open.copy(startProblem = null)
                }
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
        if (open.mode == mode || open.follow != null) return
        _state.value = open.copy(mode = mode, startProblem = null)
        viewModelScope.launch { preferences.setMode(mode) }
        load()
    }

    /** A route card was tapped. */
    fun onRouteSelect(routeId: String) {
        val open = _state.value as? DirectionsUiState.Open ?: return
        val results = open.status as? DirectionsStatus.Results ?: return
        if (results.routes.none { it.id == routeId } || open.follow != null) return
        _state.value = open.copy(status = results.copy(selectedId = routeId), startProblem = null)
        display.select(routeId)
    }

    /** "Try again". Ignored while a request is already running: a double tap sends one. */
    fun onRetry() {
        val open = _state.value as? DirectionsUiState.Open ?: return
        if (open.status is DirectionsStatus.Loading || open.follow != null) return
        load()
    }

    /**
     * "Start" on the selected route. Following begins only with precise location and a position
     * from the last few seconds; otherwise the screen says what is missing and offers the usual
     * permission flow. Nothing is requested from here.
     */
    fun onStartClick() {
        val open = _state.value as? DirectionsUiState.Open ?: return
        val results = open.status as? DirectionsStatus.Results ?: return
        if (open.follow != null) return
        val route = results.routes.firstOrNull { it.id == results.selectedId } ?: return
        val state = location.state.value
        val fix = (state as? LocationState.Fix)?.fix
        val problem = when {
            state == LocationState.NoPermission -> StartProblem.NoPermission
            state.isApproximate() -> StartProblem.NeedsPrecise
            fix == null || clock.millis() - fix.timeMillis > GPS_LOST_AFTER_MILLIS -> StartProblem.NoRecentFix
            else -> null
        }
        if (problem != null || fix == null) {
            _state.value = open.copy(startProblem = problem)
            return
        }
        val started = RouteTracker(route, fix.timeMillis)
        tracker = started
        lastFixMillis = fix.timeMillis
        paused = false
        pausedNoteShown = false
        _state.value = open.copy(follow = followState(started.onFix(fix)), startProblem = null)
        showFollow(started.progress, fix)
        followJob = viewModelScope.launch {
            launch { location.state.collect { onFollowLocation(it) } }
            while (true) {
                delay(FOLLOW_TICK_MILLIS)
                // No position for a while: say so. The route and the numbers stay.
                if (!paused && clock.millis() - lastFixMillis > GPS_LOST_AFTER_MILLIS) {
                    updateFollow { it.copy(gpsLost = true) }
                }
            }
        }
    }

    /** Back, or "End", while a route is followed: ask first. */
    fun onEndClick() = updateFollow { it.copy(confirmEnd = true) }

    fun onEndCancel() = updateFollow { it.copy(confirmEnd = false) }

    /** "End" in the question: back to the routes, which stay on the map. */
    fun onEndConfirm() = stopFollowing(problem = null)

    fun onPausedNoteDismiss() = updateFollow { it.copy(pausedNote = false) }

    /** The app left the screen. It does not follow in the background: nothing runs there. */
    fun onBackground() {
        if (activeFollow() != null) paused = true
    }

    /** The app is on screen again. */
    fun onForeground() {
        if (!paused) return
        paused = false
        // The time away is not "no GPS" and not "off the route": both are counted from now.
        lastFixMillis = clock.millis()
        tracker?.onResume()
        val note = !pausedNoteShown
        pausedNoteShown = true
        updateFollow { it.copy(offRoute = false, pausedNote = it.pausedNote || note) }
    }

    /** The location permission was taken away (system Settings) while Home was not looking. */
    fun onLocationPermissionLost() {
        if (activeFollow() != null) stopFollowing(StartProblem.NoPermission)
    }

    /**
     * "Recalculate": new routes from where the user is now to the same place, the same way of
     * travelling. Only on a tap, never by itself; one request at a time; and it gives up after
     * [RECALCULATE_TIMEOUT_MILLIS] with a sentence instead of waiting for ever. The route that
     * was followed stays until a new one has arrived.
     */
    fun onRecalculate() {
        val open = _state.value as? DirectionsUiState.Open ?: return
        val follow = activeFollow() ?: return
        if (follow.recalculation == Recalculation.Running) return
        val origin = location.state.value.origin()
        if (origin == null) {
            updateFollow { it.copy(recalculation = Recalculation.Failed(RouteError.Unavailable)) }
            return
        }
        updateFollow { it.copy(recalculation = Recalculation.Running) }
        recalculation = viewModelScope.launch {
            val outcome = withTimeoutOrNull(RECALCULATE_TIMEOUT_MILLIS) {
                repository.routes(origin, open.destination.position, open.mode)
            } ?: RouteOutcome.Failed(RouteError.Unavailable)
            when (outcome) {
                is RouteOutcome.Failed ->
                    updateFollow { it.copy(recalculation = Recalculation.Failed(outcome.error)) }
                is RouteOutcome.Found -> {
                    val first = outcome.routes.first()
                    val restarted = RouteTracker(first, clock.millis())
                    tracker = restarted
                    display.show(outcome.routes.map { RouteLine(it.id, it.points) }, first.id, origin)
                    display.follow(FollowView(emptyList(), origin, display.following.value?.bearing ?: 0.0))
                    setStatus(DirectionsStatus.Results(outcome.routes, first.id, outcome.attribution))
                    updateFollow { followState(restarted.progress).copy(pausedNote = it.pausedNote, confirmEnd = it.confirmEnd) }
                }
            }
        }
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
        endFollowWork()
        display.clear()
        _state.value = DirectionsUiState.Closed
    }

    private fun setStatus(status: DirectionsStatus) {
        _state.update { (it as? DirectionsUiState.Open)?.copy(status = status) ?: it }
    }

    private fun onFollowLocation(state: LocationState) {
        val follow = activeFollow() ?: return
        if (state == LocationState.NoPermission) return stopFollowing(StartProblem.NoPermission)
        // "Precise" was switched off in system Settings: a rough position cannot follow a route.
        if (state.isApproximate()) return stopFollowing(StartProblem.NeedsPrecise)
        val fix = (state as? LocationState.Fix)?.fix ?: return
        // The flow repeats its current value to a new collector: not a new position.
        if (fix.timeMillis <= lastFixMillis) return
        lastFixMillis = fix.timeMillis
        val progress = tracker?.onFix(fix) ?: return
        if (progress.arrived) {
            endFollowWork()
            _state.update { open ->
                (open as? DirectionsUiState.Open)?.copy(
                    follow = FollowState(0, 0, clock.millis(), arrived = true),
                ) ?: open
            }
            return
        }
        showFollow(progress, fix)
        val next = followState(progress)
        // A failed "Recalculate" is forgotten once the user is back on the route.
        val keep = follow.recalculation.takeIf { progress.offRoute || it == Recalculation.Running }
        _state.update { open ->
            (open as? DirectionsUiState.Open)?.copy(
                follow = next.copy(
                    recalculation = keep ?: Recalculation.Idle,
                    pausedNote = follow.pausedNote,
                    confirmEnd = follow.confirmEnd,
                ),
            ) ?: open
        }
    }

    private fun followState(progress: RouteProgress) = FollowState(
        remainingMeters = progress.remainingMeters,
        remainingSeconds = progress.remainingSeconds,
        arrivalMillis = clock.millis() + progress.remainingSeconds * 1000L,
        offRoute = progress.offRoute,
    )

    private fun showFollow(progress: RouteProgress, fix: LocationFix) {
        // North up while standing still; the direction of travel while moving.
        display.follow(FollowView(progress.travelled, fix.position, fix.headingDegrees?.toDouble() ?: 0.0))
    }

    /** The route that is followed right now, if one is and its end was not reached yet. */
    private fun activeFollow(): FollowState? =
        (_state.value as? DirectionsUiState.Open)?.follow?.takeIf { it.active }

    private fun updateFollow(change: (FollowState) -> FollowState) {
        _state.update { open ->
            val follow = (open as? DirectionsUiState.Open)?.follow?.takeIf { it.active } ?: return@update open
            open.copy(follow = change(follow))
        }
    }

    /** Stops everything that runs while a route is followed. The screen state is the caller's. */
    private fun endFollowWork() {
        followJob?.cancel()
        followJob = null
        recalculation?.cancel()
        recalculation = null
        tracker = null
        paused = false
        display.follow(null)
    }

    private fun stopFollowing(problem: StartProblem?) {
        endFollowWork()
        _state.update { (it as? DirectionsUiState.Open)?.copy(follow = null, startProblem = problem) ?: it }
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

        /** No position for this long while following: "Searching for GPS". Also the oldest
         * position "Start" accepts. */
        const val GPS_LOST_AFTER_MILLIS = 10_000L
        const val FOLLOW_TICK_MILLIS = 1_000L

        /** The longest "Recalculate" waits for an answer. */
        const val RECALCULATE_TIMEOUT_MILLIS = 20_000L
    }
}

private fun LocationState.origin(): LatLng? = when (this) {
    is LocationState.Fix -> fix.position
    // The last known position: the dot on the map is grey, and the route starts there.
    is LocationState.Stale -> lastFix.position
    else -> null
}

private fun LocationState.isApproximate(): Boolean = when (this) {
    is LocationState.Fix -> fix.isApproximate
    is LocationState.Stale -> lastFix.isApproximate
    else -> false
}

private fun LocationState.problem(): OriginProblem = when (this) {
    LocationState.Searching -> OriginProblem.Searching
    LocationState.Unavailable -> OriginProblem.Unavailable
    else -> OriginProblem.NoPermission
}
