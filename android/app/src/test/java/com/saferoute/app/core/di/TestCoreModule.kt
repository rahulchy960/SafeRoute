// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.di

import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob

/*
 * How a Hilt test knows that the app's background work is done (P014e).
 *
 * The app does its work on other threads: the app-lifetime scope, the IO dispatcher, Room's
 * own threads. A test used to poll in real time for the result ("wait up to five seconds
 * until..."), which fails on a slow machine although nothing is wrong.
 *
 * Here every piece of work handed to one of those threads is COUNTED: plus one when it is
 * handed over, minus one when it has run. A count of zero means that nothing is under way.
 * The count is given to the test framework as an IDLING RESOURCE, which is the framework's
 * word for "do not go on while this says busy". `compose.waitForIdle()` and
 * `Espresso.onIdle()` then return exactly when the work has finished: no polling and no
 * time limit of the test's own.
 *
 * A piece of work that hands on to another thread is counted there before it ends here, so
 * the count never drops to zero in the middle of a chain. A coroutine that sleeps
 * (`delay`) is not counted while it sleeps: waiting for time is not work.
 */
class BackgroundWork : IdlingResource {

    private val busy = AtomicInteger()

    @Volatile
    private var callback: IdlingResource.ResourceCallback? = null

    override fun getName(): String = "SafeRoute background work"

    override fun isIdleNow(): Boolean = busy.get() == 0

    override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
        this.callback = callback
    }

    private fun counted(block: Runnable): Runnable {
        busy.incrementAndGet()
        return Runnable {
            try {
                block.run()
            } finally {
                if (busy.decrementAndGet() == 0) callback?.onTransitionToIdle()
            }
        }
    }

    /** [base], with everything dispatched to it counted. */
    fun dispatcher(base: CoroutineDispatcher): CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = base.dispatch(context, counted(block))
    }

    /** [base], with everything handed to it counted. Room's threads use this. */
    fun executor(base: Executor): Executor = Executor { base.execute(counted(it)) }

    companion object {
        /** The one in use by the running Hilt test; `TestResources.release()` ends it. */
        @Volatile
        var current: BackgroundWork? = null
            private set

        fun begin(): BackgroundWork = BackgroundWork().also {
            end()
            current = it
            IdlingRegistry.getInstance().register(it)
        }

        fun end() {
            current?.let { IdlingRegistry.getInstance().unregister(it) }
            current = null
        }
    }
}

/**
 * The app's clock in a Hilt test: the real one until the test calls [stop]. A test that
 * compares a time with "now" (a position "from the last 10 seconds") stops it first, so
 * that a slow machine cannot make the position old while the test is still running.
 */
class TestClock(private val real: Clock) : Clock() {

    @Volatile
    private var stoppedAt: Instant? = null

    fun stop() {
        stoppedAt = real.instant()
    }

    override fun instant(): Instant = stoppedAt ?: real.instant()

    override fun getZone(): ZoneId = real.zone

    override fun withZone(zone: ZoneId): Clock = real.withZone(zone)
}

/**
 * Replaces [CoreModule] in every Hilt test: the same clock, dispatchers and app-lifetime
 * scope as in the app, on the same threads, with their work counted ([BackgroundWork]).
 * `CoreModuleTest` checks the real module directly.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [CoreModule::class])
object TestCoreModule {

    @Provides
    @Singleton
    fun provideBackgroundWork(): BackgroundWork = BackgroundWork.begin()

    @Provides
    @Singleton
    fun provideTestClock(): TestClock = TestClock(CoreModule.provideClock())

    @Provides
    fun provideClock(clock: TestClock): Clock = clock

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(work: BackgroundWork): CoroutineDispatcher = work.dispatcher(CoreModule.provideIoDispatcher())

    @Provides
    @Singleton
    @DefaultDispatcher
    fun provideDefaultDispatcher(work: BackgroundWork): CoroutineDispatcher =
        work.dispatcher(CoreModule.provideDefaultDispatcher())

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}
