// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import com.saferoute.app.core.map.LatLng
import com.saferoute.app.feature.directions.di.DirectionsModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** Hand-made lines with round, invented coordinates. Not real routes, not a person's places. */
val FAKE_ROUTE_FAST = RouteOption(
    id = "fake-route-1",
    distanceMeters = 2100,
    durationSeconds = 1560,
    points = listOf(LatLng(10.0, 20.0), LatLng(10.25, 20.25), LatLng(10.5, 20.5)),
)
val FAKE_ROUTE_OTHER = RouteOption(
    id = "fake-route-2",
    distanceMeters = 2440,
    durationSeconds = 1740,
    points = listOf(LatLng(10.0, 20.0), LatLng(10.5, 20.0), LatLng(10.5, 20.5)),
)
const val FAKE_ROUTE_CREDIT = "© Fake map data"

/**
 * Routes from a script. It never touches a network.
 *
 * [answers] are handed out in order, the last one repeating; [gate] holds an answer back until
 * the test completes it, which is how "a slow request" is played out.
 */
class FakeRouteRepository : RouteRepository {

    data class Call(val origin: LatLng, val destination: LatLng, val mode: TravelMode)

    val calls = mutableListOf<Call>()
    var answers: List<RouteOutcome> = listOf(RouteOutcome.Found(listOf(FAKE_ROUTE_FAST, FAKE_ROUTE_OTHER), FAKE_ROUTE_CREDIT))
    var gate: CompletableDeferred<Unit>? = null

    /** How many requests ran to the end (were not cancelled). */
    var completed = 0

    override suspend fun routes(origin: LatLng, destination: LatLng, mode: TravelMode): RouteOutcome {
        calls += Call(origin, destination, mode)
        val answer = answers[minOf(calls.size - 1, answers.lastIndex)]
        gate?.await()
        completed++
        return answer
    }
}

class FakeRoutePreferences(initial: RouteSettings = RouteSettings()) : RoutePreferences {
    override val settings = MutableStateFlow(initial)

    override suspend fun setMode(mode: TravelMode) {
        settings.value = settings.value.copy(mode = mode)
    }

    override suspend fun setIntroSeen() {
        settings.value = settings.value.copy(introSeen = true)
    }
}

/**
 * Replaces [DirectionsModule] in every Hilt test, so a test that starts `MainActivity` never
 * asks the server this machine's build points at for a route.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DirectionsModule::class])
object FakeDirectionsModule {

    @Provides
    @Singleton
    fun provideFakeRouteRepository(): FakeRouteRepository = FakeRouteRepository()

    @Provides
    fun provideRouteRepository(fake: FakeRouteRepository): RouteRepository = fake

    @Provides
    @Singleton
    fun provideFakeRoutePreferences(): FakeRoutePreferences = FakeRoutePreferences()

    @Provides
    fun provideRoutePreferences(fake: FakeRoutePreferences): RoutePreferences = fake
}
