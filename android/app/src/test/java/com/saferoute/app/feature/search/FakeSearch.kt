// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search

import com.saferoute.app.core.map.LatLng
import com.saferoute.app.feature.search.di.SearchModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred

/** Obviously fake places with round coordinates; no real place of a person. */
val FAKE_STATION = FoundPlace("fake-1", "Main Station", "Station Road, Example District", LatLng(10.5, 20.5), "station")
val FAKE_MARKET = FoundPlace("fake-2", "Station Market", "Example Town", LatLng(10.25, 20.75), "market")
const val FAKE_ATTRIBUTION = "Fake provider · © Fake map data"

/**
 * A search that answers from a script and records what it was asked. It never touches a network.
 *
 * [gates] lets a test hold an answer back: a query with a gate waits until the test completes
 * it, which is how "a slow answer arrives late" is played out.
 */
class FakeSearchRepository : SearchRepository {

    data class Call(val query: String, val near: LatLng?, val language: String)

    val calls = mutableListOf<Call>()
    val answers = mutableMapOf<String, SearchOutcome>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** What a query without a scripted answer gets. */
    var defaultAnswer: SearchOutcome = SearchOutcome.Found(listOf(FAKE_STATION, FAKE_MARKET), FAKE_ATTRIBUTION)

    /** How many searches ran to the end (were not cancelled). */
    var completed = 0

    override suspend fun search(query: String, near: LatLng?, language: String): SearchOutcome {
        calls += Call(query, near, language)
        gates[query]?.await()
        completed++
        return answers[query] ?: defaultAnswer
    }
}

/**
 * Replaces [SearchModule] in every Hilt test, so a test that starts `MainActivity` and types in
 * the search field never calls the server this machine's build points at.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SearchModule::class])
object FakeSearchModule {

    @Provides
    @Singleton
    fun provideFakeSearchRepository(): FakeSearchRepository = FakeSearchRepository()

    @Provides
    fun provideSearchRepository(fake: FakeSearchRepository): SearchRepository = fake
}
