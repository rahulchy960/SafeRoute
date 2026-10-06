// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import android.content.ComponentCallbacks2
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The native map must get every lifecycle call, in order and once, however the screen goes
 * away. A missed `onDestroy` leaks the map; a missed `onStop` keeps it drawing in the
 * background.
 */
class MapLifecycleForwarderTest {

    private val calls = mutableListOf<String>()
    private val target = object : MapLifecycleTarget {
        override fun onStart() { calls += "start" }
        override fun onResume() { calls += "resume" }
        override fun onPause() { calls += "pause" }
        override fun onStop() { calls += "stop" }
        override fun onDestroy() { calls += "destroy" }
        override fun onLowMemory() { calls += "lowMemory" }
    }
    private val forwarder = MapLifecycleForwarder(target)
    // `createUnsafe` skips the main-thread check, which a plain JVM test cannot pass.
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun send(vararg events: Lifecycle.Event) =
        events.forEach { forwarder.onStateChanged(owner, it) }

    @Test
    fun `a normal visit forwards every step in order`() {
        send(
            Lifecycle.Event.ON_CREATE,
            Lifecycle.Event.ON_START,
            Lifecycle.Event.ON_RESUME,
            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP,
            Lifecycle.Event.ON_DESTROY,
        )

        assertEquals(listOf("start", "resume", "pause", "stop", "destroy"), calls)
    }

    @Test
    fun `background and foreground again restarts the map`() {
        send(Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)
        send(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP)
        send(Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)

        assertEquals(listOf("start", "resume", "pause", "stop", "start", "resume"), calls)
    }

    @Test
    fun `leaving the screen while it is showing walks the map down before destroying it`() {
        send(Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)

        // What Compose does when Home leaves the screen or the phone is rotated.
        forwarder.destroy()

        assertEquals(listOf("start", "resume", "pause", "stop", "destroy"), calls)
    }

    @Test
    fun `nothing is forwarded twice or after the map is destroyed`() {
        send(Lifecycle.Event.ON_START, Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)
        forwarder.destroy()
        forwarder.destroy()
        send(Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_DESTROY)
        forwarder.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertEquals(listOf("start", "resume", "pause", "stop", "destroy"), calls)
    }

    @Test
    fun `a map that was never started is only destroyed`() {
        forwarder.destroy()
        assertEquals(listOf("destroy"), calls)
    }

    @Test
    fun `a real lifecycle replays the past for an observer added late`() {
        owner.registry.currentState = Lifecycle.State.RESUMED

        owner.lifecycle.addObserver(forwarder)

        assertEquals(listOf("start", "resume"), calls)
    }

    @Test
    fun `memory pressure is passed on, a mild hint is not`() {
        send(Lifecycle.Event.ON_START)

        forwarder.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        forwarder.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertEquals(listOf("start", "lowMemory"), calls)
    }
}
