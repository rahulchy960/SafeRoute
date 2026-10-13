// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.data.HiltTestCleanup
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.Clock
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Proves the Hilt graph builds and hands out what [CoreModule] promises. `@HiltAndroidTest`
 * with [HiltTestApplication] gives the test its own dependency container.
 *
 * Since P014e the graph of a test gets its dispatchers from `TestCoreModule`, which wraps the
 * real ones to count their work. So what [CoreModule] itself hands out is checked on the
 * module, and the graph is checked for what a test can see of it: one clock, and a
 * different dispatcher for each qualifier.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class CoreModuleTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @get:Rule
    val cleanup = HiltTestCleanup()

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
        assertSame(Dispatchers.IO, CoreModule.provideIoDispatcher())
        assertSame(Dispatchers.Default, CoreModule.provideDefaultDispatcher())
        assertNotSame(ioDispatcher, defaultDispatcher)
    }

    @Test
    fun `the real module promises one clock for the whole app`() {
        assertEquals(ZoneOffset.UTC, CoreModule.provideClock().zone)
        assertTrue(CoreModule::class.java.getMethod("provideClock").isAnnotationPresent(Singleton::class.java))
    }
}
