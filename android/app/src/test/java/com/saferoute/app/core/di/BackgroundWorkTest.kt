// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.di

import androidx.test.espresso.IdlingResource
import java.time.Clock
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The count behind "the app's background work is done" (P014e). The threads are stood in
 * for by a list the test runs itself, so every state read here is decided by the test.
 */
class BackgroundWorkTest {

    private val handedOver = ArrayDeque<Runnable>()
    private val thread = Executor { handedOver += it }
    private val threadDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            handedOver += block
        }
    }
    private val work = BackgroundWork()

    private fun runNext() = handedOver.removeFirst().run()

    @Test
    fun `it is busy from the moment work is handed over until that work has run`() {
        assertTrue(work.isIdleNow)

        work.executor(thread).execute { }
        assertFalse("handed over, not yet run", work.isIdleNow)

        runNext()
        assertTrue(work.isIdleNow)
    }

    @Test
    fun `work that hands on to another thread keeps it busy until the last piece has run`() {
        val dispatcher = work.dispatcher(threadDispatcher)
        val room = work.executor(thread)
        dispatcher.dispatch(EmptyCoroutineContext, Runnable { room.execute { } })

        runNext()
        assertFalse("the first piece ended, the second is under way", work.isIdleNow)

        runNext()
        assertTrue(work.isIdleNow)
    }

    @Test
    fun `it tells the test framework once, when the last piece of work has run`() {
        var told = 0
        work.registerIdleTransitionCallback(IdlingResource.ResourceCallback { told++ })
        val executor = work.executor(thread)
        executor.execute { }
        executor.execute { }

        runNext()
        assertEquals(0, told)
        runNext()
        assertEquals(1, told)
    }

    @Test
    fun `work that fails is still counted as over`() {
        work.executor(thread).execute { error("failed") }

        runCatching { runNext() }

        assertTrue(work.isIdleNow)
    }

    @Test
    fun `a stopped clock stands still and the real one goes on`() {
        val clock = TestClock(Clock.systemUTC())
        clock.stop()
        val stopped = clock.millis()

        assertEquals(stopped, clock.millis())
        assertEquals(Clock.systemUTC().zone, clock.zone)
    }
}
