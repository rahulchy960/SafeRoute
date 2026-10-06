// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map.di

import com.saferoute.app.BuildConfig
import com.saferoute.app.core.map.ConnectivityNetworkStatus
import com.saferoute.app.core.map.MapEngine
import com.saferoute.app.core.map.MapLibreEngine
import com.saferoute.app.core.map.MapProviderConfig
import com.saferoute.app.core.map.NetworkStatus
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Which map the app uses. One module, so that tests replace all of it at once with a fake that
 * loads no native library and never contacts the tile provider (`FakeMapModule` in the tests).
 */
@Module
@InstallIn(SingletonComponent::class)
interface MapModule {

    @Binds
    fun bindMapEngine(engine: MapLibreEngine): MapEngine

    @Binds
    fun bindNetworkStatus(status: ConnectivityNetworkStatus): NetworkStatus

    companion object {
        @Provides
        @Singleton
        fun provideMapProviderConfig(): MapProviderConfig = MapProviderConfig.fromBuild(
            key = BuildConfig.MAPTILER_KEY,
            configured = BuildConfig.MAPTILER_KEY_CONFIGURED,
        )
    }
}
