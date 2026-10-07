// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions.di

import com.saferoute.app.feature.directions.ApiRouteRepository
import com.saferoute.app.feature.directions.DataStoreRoutePreferences
import com.saferoute.app.feature.directions.RoutePreferences
import com.saferoute.app.feature.directions.RouteRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Where route requests go and where the two directions settings are kept. Its own module so
 * that tests replace it with fakes that never call a server or write a file
 * (`FakeDirectionsModule` in the tests).
 */
@Module
@InstallIn(SingletonComponent::class)
interface DirectionsModule {
    @Binds
    fun bindRouteRepository(repository: ApiRouteRepository): RouteRepository

    @Binds
    fun bindRoutePreferences(preferences: DataStoreRoutePreferences): RoutePreferences
}
