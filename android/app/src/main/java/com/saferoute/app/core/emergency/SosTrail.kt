// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import com.saferoute.app.core.di.ApplicationScope
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.location.LocationEnvironment
import com.saferoute.app.core.location.RawFix
import com.saferoute.app.core.location.TrailLocationSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A position every 5 seconds during an emergency. */
const val TRAIL_INTERVAL_MILLIS = 5_000L

/** Every 15 seconds when the battery is nearly empty: a phone that dies helps nobody. */
const val TRAIL_LOW_BATTERY_INTERVAL_MILLIS = 15_000L
const val TRAIL_LOW_BATTERY_PERCENT = 10

/** A last position older than this is called stale and shown with its age. */
val TRAIL_STALE_AFTER: Duration = Duration.ofSeconds(30)

fun trailIntervalMillis(batteryPercent: Int?): Long =
    if (batteryPercent != null && batteryPercent < TRAIL_LOW_BATTERY_PERCENT) {
        TRAIL_LOW_BATTERY_INTERVAL_MILLIS
    } else {
        TRAIL_INTERVAL_MILLIS
    }

/** The battery's charge in percent, or null when the phone does not say. */
fun interface BatteryLevel {
    fun percent(): Int?
}

/** Why there is, or is not, a trail. */
enum class SosTrailMode {
    OFF,

    /** No location permission: the emergency goes on without positions. */
    NO_PERMISSION,

    /** Location is switched off on the phone, or the phone has no way to locate itself. */
    UNAVAILABLE,
    PRECISE,
    APPROXIMATE,
}

data class SosTrailState(
    val mode: SosTrailMode = SosTrailMode.OFF,
    /** The newest position; a last-known one counts until a new one arrives. */
    val last: SosPoint? = null,
)

/** What a screen or a message may say about the position. Never the position itself. */
sealed interface SosLocationReport {
    data object NoPermission : SosLocationReport
    data object Unavailable : SosLocationReport

    /** Running, nothing received yet. */
    data object Searching : SosLocationReport
    data object Precise : SosLocationReport
    data object Approximate : SosLocationReport
    data class Stale(val ageSeconds: Long) : SosLocationReport
}

fun locationReport(state: SosTrailState, now: Instant): SosLocationReport {
    when (state.mode) {
        SosTrailMode.OFF, SosTrailMode.UNAVAILABLE -> return SosLocationReport.Unavailable
        SosTrailMode.NO_PERMISSION -> return SosLocationReport.NoPermission
        SosTrailMode.PRECISE, SosTrailMode.APPROXIMATE -> Unit
    }
    val last = state.last ?: return SosLocationReport.Searching
    val age = Duration.between(last.recordedAt, now)
    return when {
        age >= TRAIL_STALE_AFTER -> SosLocationReport.Stale(age.seconds)
        state.mode == SosTrailMode.APPROXIMATE -> SosLocationReport.Approximate
        else -> SosLocationReport.Precise
    }
}

/**
 * The location trail of one emergency. **Location never blocks an SOS**: every way of not
 * getting a position ends in a mode, never in an error or a wait.
 *
 * - no permission → [SosTrailMode.NO_PERMISSION]; nothing is requested (an emergency is not
 *   the moment for a permission dialog);
 * - location switched off, or no Play services → [SosTrailMode.UNAVAILABLE];
 * - approximate permission only → it is used and the mode says so;
 * - no fix yet → the phone's last known position is stored with its real time, so its age
 *   shows.
 *
 * Positions go to `sos_points` and stay on the phone. Nothing here logs.
 */
@Singleton
class SosTrail @Inject constructor(
    private val source: TrailLocationSource,
    private val environment: LocationEnvironment,
    private val store: SosStore,
    private val battery: BatteryLevel,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SosTrailState())
    val state: StateFlow<SosTrailState> = _state.asStateFlow()

    private var sosId: String? = null
    private var intervalMillis = 0L

    fun report(): SosLocationReport = locationReport(_state.value, clock.instant())

    /** Begins the trail of emergency [id]. Calling it again for the same one changes nothing. */
    @Synchronized
    fun start(id: String) {
        if (sosId == id) return
        stop()
        val mode = when {
            environment.granted() == GrantedLocation.None -> SosTrailMode.NO_PERMISSION
            !environment.isPlayServicesAvailable() || !environment.isLocationEnabled() -> SosTrailMode.UNAVAILABLE
            environment.granted() == GrantedLocation.Approximate -> SosTrailMode.APPROXIMATE
            else -> SosTrailMode.PRECISE
        }
        sosId = id
        _state.value = SosTrailState(mode)
        if (mode != SosTrailMode.PRECISE && mode != SosTrailMode.APPROXIMATE) return
        listen(id)
        source.lastKnown { raw, ageMillis -> onLastKnown(id, raw, ageMillis) }
    }

    @Synchronized
    fun stop() {
        if (sosId == null) return
        sosId = null
        source.stop()
        _state.value = SosTrailState()
    }

    private fun listen(id: String) {
        intervalMillis = trailIntervalMillis(battery.percent())
        val precise = _state.value.mode == SosTrailMode.PRECISE
        source.start(precise, intervalMillis) { raw -> onFix(id, raw) }
    }

    @Synchronized
    private fun onFix(id: String, raw: RawFix) {
        // A late callback after stop(), or from an earlier emergency, is dropped.
        if (sosId != id) return
        record(id, raw, clock.instant())
        // The battery may have crossed the line since the last position.
        if (trailIntervalMillis(battery.percent()) != intervalMillis) listen(id)
    }

    @Synchronized
    private fun onLastKnown(id: String, raw: RawFix, ageMillis: Long) {
        if (sosId != id || _state.value.last != null || ageMillis < 0) return
        record(id, raw, clock.instant().minusMillis(ageMillis))
    }

    private fun record(id: String, raw: RawFix, at: Instant) {
        val point = SosPoint(
            latitude = raw.position.latitude,
            longitude = raw.position.longitude,
            accuracyMeters = raw.accuracyMeters.takeIf { it > 0f },
            recordedAt = at,
            mock = raw.isMock,
        )
        _state.value = _state.value.copy(last = point)
        scope.launch { store.addPoint(id, point) }
    }
}
