// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.location

import com.saferoute.app.core.di.ApplicationScope
import com.saferoute.app.core.map.LatLng
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * Where the phone is, for the parts of the app that may know (ADR 0015, "Location policy").
 *
 * Rules that hold for everything in this package:
 * - foreground only: updates run between start() and stop(), and the screen calls stop() as
 *   soon as it is no longer visible;
 * - memory only: the last position is a field of an object. Nothing is written to disk, sent
 *   to a server or logged;
 * - positions never print (LatLng and LocationFix hide their values in toString()).
 */

/** Which location permission the user has granted, if any. */
enum class GrantedLocation {
    None,

    /** "Approximate" in the system dialog: accurate to a few kilometres at best. */
    Approximate,

    /** "Precise": GPS-level accuracy. */
    Precise,
}

/**
 * One position.
 *
 * @property accuracyMeters radius within which the phone probably is (68% confidence).
 * @property timeMillis when the position was received, on the app's [Clock].
 * @property isApproximate true when only approximate location is permitted.
 * @property headingDegrees direction of travel, clockwise from north; null when standing still.
 */
data class LocationFix(
    val position: LatLng,
    val accuracyMeters: Float,
    val timeMillis: Long,
    val isApproximate: Boolean,
    val headingDegrees: Float? = null,
) {
    override fun toString(): String = "LocationFix(hidden)"
}

sealed interface LocationState {
    /** Not started, or the permission is missing. */
    data object NoPermission : LocationState

    /** Waiting for the first position. */
    data object Searching : LocationState

    data class Fix(val fix: LocationFix) : LocationState

    /** No new position for a while: the last one, and how old it is. */
    data class Stale(val lastFix: LocationFix, val ageSeconds: Long) : LocationState

    /** No position can be had: location is switched off, or there is no way to get one. */
    data object Unavailable : LocationState
}

/**
 * The one source of the phone's position for the whole app. The map uses it now; SOS and live
 * sharing will use the same one (P014, P016).
 */
interface LocationRepository {
    val state: StateFlow<LocationState>

    /** Begins updates. Call when the screen becomes visible and the permission is granted. */
    fun start()

    /** Ends updates at once. Call when the screen stops being visible. */
    fun stop()
}

/** What the phone reports, before the app decides what to make of it. */
data class RawFix(
    val position: LatLng,
    val accuracyMeters: Float,
    val headingDegrees: Float?,
    /** From a "mock location" app (developer tool). Kept for P014; not shown or exposed. */
    val isMock: Boolean,
) {
    override fun toString(): String = "RawFix(hidden)"
}

/** The system service that produces positions (Fused Location on a real phone). */
interface LocationSource {
    /** @param precise ask for GPS-level accuracy and frequent updates. */
    fun start(precise: Boolean, onFix: (RawFix) -> Unit)

    /**
     * The position the phone already has, if any: no new measurement, so it costs no battery
     * and arrives at once. [onResult] is called at most once, with how old the position is;
     * never when the phone has none.
     */
    fun lastKnown(onResult: (fix: RawFix, ageMillis: Long) -> Unit)

    fun stop()
}

/** Facts about the phone that decide whether a position can be had at all. */
interface LocationEnvironment {
    fun granted(): GrantedLocation

    /** The phone's own "Location" switch (quick settings). */
    fun isLocationEnabled(): Boolean

    /** Fused Location is part of Google Play services; some phones do not have it. */
    fun isPlayServicesAvailable(): Boolean
}

@Singleton
class DefaultLocationRepository @Inject constructor(
    private val source: LocationSource,
    private val environment: LocationEnvironment,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) : LocationRepository {

    private val _state = MutableStateFlow<LocationState>(LocationState.NoPermission)
    override val state: StateFlow<LocationState> = _state.asStateFlow()

    private var started = false
    private var lastFix: LocationFix? = null
    private var lastWasMock = false
    private var ticker: Job? = null

    @Synchronized
    override fun start() {
        val granted = environment.granted()
        if (granted == GrantedLocation.None) {
            stop()
            _state.value = LocationState.NoPermission
            return
        }
        if (!environment.isPlayServicesAvailable() || !environment.isLocationEnabled()) {
            stop()
            _state.value = LocationState.Unavailable
            return
        }
        if (started) return
        started = true
        val approximate = granted == GrantedLocation.Approximate
        val startedAt = clock.millis()
        // A position from before the pause is shown as old until a new one arrives.
        _state.value = lastFix?.let { stale(it) } ?: LocationState.Searching
        source.start(precise = !approximate) { raw -> onFix(raw, approximate) }
        // Until the first new position arrives, the one the phone already has is better than
        // nothing: the map can open where the user is instead of waiting for GPS.
        if (lastFix == null) source.lastKnown { raw, age -> onLastKnown(raw, age, approximate) }
        ticker = scope.launch {
            while (true) {
                delay(TICK_MILLIS)
                tick(startedAt)
            }
        }
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        started = false
        source.stop()
        ticker?.cancel()
        ticker = null
    }

    @Synchronized
    private fun onFix(raw: RawFix, approximate: Boolean) {
        // A late callback after stop() must not move the dot.
        if (!started) return
        val fix = LocationFix(
            position = raw.position,
            accuracyMeters = raw.accuracyMeters,
            timeMillis = clock.millis(),
            isApproximate = approximate,
            headingDegrees = raw.headingDegrees,
        )
        lastFix = fix
        lastWasMock = raw.isMock
        _state.value = LocationState.Fix(fix)
    }

    /**
     * The phone's last known position. Used only while there is nothing better, and only when
     * it is recent: an old one may be from another town. Like every position here it lives in
     * this object's memory and nowhere else.
     */
    @Synchronized
    private fun onLastKnown(raw: RawFix, ageMillis: Long, approximate: Boolean) {
        if (!started || lastFix != null) return
        if (ageMillis !in 0..LAST_KNOWN_MAX_AGE_MILLIS) return
        val fix = LocationFix(
            position = raw.position,
            accuracyMeters = raw.accuracyMeters,
            timeMillis = clock.millis() - ageMillis,
            isApproximate = approximate,
            headingDegrees = null,
        )
        lastFix = fix
        lastWasMock = raw.isMock
        // Older than STALE_AFTER_MILLIS: shown grey, with its age, until a new one arrives.
        _state.value = stale(fix)
    }

    @Synchronized
    private fun tick(startedAt: Long) {
        if (!started) return
        val fix = lastFix
        val now = clock.millis()
        _state.value = when {
            fix != null && now - fix.timeMillis >= STALE_AFTER_MILLIS -> stale(fix)
            fix != null -> return
            now - startedAt >= SEARCH_TIMEOUT_MILLIS -> LocationState.Unavailable
            else -> return
        }
    }

    private fun stale(fix: LocationFix): LocationState {
        val age = (clock.millis() - fix.timeMillis).coerceAtLeast(0)
        return if (age >= STALE_AFTER_MILLIS) {
            LocationState.Stale(fix, age / 1000)
        } else {
            LocationState.Fix(fix)
        }
    }

    companion object {
        /** A position older than this is drawn grey: the person may have moved on. */
        const val STALE_AFTER_MILLIS = 30_000L

        /** After this long without a first position the control says so instead of spinning. */
        const val SEARCH_TIMEOUT_MILLIS = 45_000L

        /** A last known position older than this is ignored (ADR 0015, "Initial camera"). */
        const val LAST_KNOWN_MAX_AGE_MILLIS = 10 * 60_000L
        const val TICK_MILLIS = 5_000L
    }
}

/** What the debug Developer screen may show about location: buckets, never values. */
data class LocationDebugSummary(val granted: GrantedLocation, val locationEnabled: Boolean, val fixAge: FixAge) {
    enum class FixAge { None, UnderTenSeconds, UnderOneMinute, Older }
}

fun locationDebugSummary(
    environment: LocationEnvironment,
    state: LocationState,
    nowMillis: Long,
): LocationDebugSummary {
    val fix = when (state) {
        is LocationState.Fix -> state.fix
        is LocationState.Stale -> state.lastFix
        else -> null
    }
    val age = fix?.let { nowMillis - it.timeMillis }
    return LocationDebugSummary(
        granted = environment.granted(),
        locationEnabled = environment.isLocationEnabled(),
        fixAge = when {
            age == null -> LocationDebugSummary.FixAge.None
            age < 10_000 -> LocationDebugSummary.FixAge.UnderTenSeconds
            age < 60_000 -> LocationDebugSummary.FixAge.UnderOneMinute
            else -> LocationDebugSummary.FixAge.Older
        },
    )
}
