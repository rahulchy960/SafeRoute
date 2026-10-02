// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.Clock
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Proves the Hilt graph builds and hands out what [CoreModule] promises. `@HiltAndroidTest`
 * with [HiltTestApplication] gives the test its own dependency container.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class CoreModuleTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var clock: Clock

    @Inject lateinit var sameClock: Clock

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    @Inject
    @DefaultDispatcher
    lateinit var defaultDispatcher: CoroutineDispatcher

    @Before
    fun inject() = hilt.inject()

    @Test
    fun `clock is a single UTC clock`() {
        assertEquals(ZoneOffset.UTC, clock.zone)
        assertSame(clock, sameClock)
    }

    @Test
    fun `qualifiers select the matching dispatcher`() {
        assertSame(Dispatchers.IO, ioDispatcher)
        assertSame(Dispatchers.Default, defaultDispatcher)
    }
}
