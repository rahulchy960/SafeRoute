// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Tells Hilt how to create the few things it cannot construct by itself.
 *
 * `@InstallIn(SingletonComponent::class)` puts these in the app-wide container, so they can be
 * injected anywhere (activities, ViewModels, later services and workers).
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    /**
     * The time source. Code that needs "now" injects a [Clock] and calls `clock.instant()`
     * instead of reading the system time directly, so tests can pin the time. UTC, like the
     * backend (ADR 0003); convert to the user's zone only when showing a time.
     */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * `SupervisorJob`: one failed piece of work does not cancel the others. The scope is never
     * cancelled; it ends with the process.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
