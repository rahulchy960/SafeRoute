// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.location

import com.saferoute.app.core.map.LatLng
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * Stand-ins for location in JVM tests: no Google Play services, no real positions. The
 * coordinates are round numbers that belong to nobody.
 */

val FAKE_POSITION = LatLng(10.0, 20.0)

fun fakeFix(
    position: LatLng = FAKE_POSITION,
    accuracyMeters: Float = 12f,
    timeMillis: Long = 0,
    isApproximate: Boolean = false,
    headingDegrees: Float? = null,
) = LocationFix(position, accuracyMeters, timeMillis, isApproximate, headingDegrees)

class FakeLocationEnvironment(
    var granted: GrantedLocation = GrantedLocation.None,
    var locationEnabled: Boolean = true,
    var playServices: Boolean = true,
) : LocationEnvironment {
    override fun granted(): GrantedLocation = granted
    override fun isLocationEnabled(): Boolean = locationEnabled
    override fun isPlayServicesAvailable(): Boolean = playServices
}

class FakeLocationSource : LocationSource {
    val starts = mutableListOf<Boolean>()
    var stops = 0
    private var listener: ((RawFix) -> Unit)? = null

    val isRunning: Boolean get() = listener != null

    override fun start(precise: Boolean, onFix: (RawFix) -> Unit) {
        starts += precise
        listener = onFix
    }

    override fun stop() {
        stops++
        listener = null
    }

    /** What the phone already has: a position and its age in milliseconds, or nothing. */
    var lastKnown: Pair<LatLng, Long>? = null
    var lastKnownRequests = 0

    override fun lastKnown(onResult: (fix: RawFix, ageMillis: Long) -> Unit) {
        lastKnownRequests++
        lastKnown?.let { (position, age) ->
            onResult(RawFix(position, accuracyMeters = 40f, headingDegrees = null, isMock = false), age)
        }
    }

    /** Delivers a position as the phone would. Returns false when nobody is listening. */
    fun emit(
        position: LatLng = FAKE_POSITION,
        accuracyMeters: Float = 12f,
        headingDegrees: Float? = null,
        isMock: Boolean = false,
    ): Boolean {
        val target = listener ?: return false
        target(RawFix(position, accuracyMeters, headingDegrees, isMock))
        return true
    }

    /** A callback that was already on its way when updates were stopped. */
    fun lateCallback(): ((RawFix) -> Unit)? = listener
}

/** A repository a UI test can put into any state. */
class FakeLocationRepository : LocationRepository {
    override val state = MutableStateFlow<LocationState>(LocationState.NoPermission)
    var starts = 0
    var stops = 0
    var running = false

    /** What [start] turns the state into, as the real one would decide from the phone. */
    var stateOnStart: LocationState = LocationState.Searching

    override fun start() {
        starts++
        running = true
        if (state.value == LocationState.NoPermission || state.value == LocationState.Unavailable) {
            state.value = stateOnStart
        }
    }

    override fun stop() {
        stops++
        running = false
    }
}

/** Replaces [LocationModule] in every Hilt test. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [LocationModule::class])
object FakeLocationModule {

    @Provides
    @Singleton
    fun provideFakeRepository(): FakeLocationRepository = FakeLocationRepository()

    @Provides
    fun provideRepository(fake: FakeLocationRepository): LocationRepository = fake

    @Provides
    @Singleton
    fun provideFakeEnvironment(): FakeLocationEnvironment = FakeLocationEnvironment()

    @Provides
    fun provideEnvironment(fake: FakeLocationEnvironment): LocationEnvironment = fake
}
