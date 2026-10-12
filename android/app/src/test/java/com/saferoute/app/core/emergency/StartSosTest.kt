// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one way an SOS starts (P014c1): every entry point goes through the same consent gate
 * and the same countdown, with the real runner and engine on fakes, in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartSosTest {

    private fun SosRig.startSos() = StartSos(policy, runner)

    @Test
    fun `every entry point starts the same five-second countdown, and the record says which`() = runTest {
        for (entryPoint in SosEntryPoint.entries) {
            val rig = SosRig(this).also { it.policy.open = true }

            val result = rig.startSos()(entryPoint)
            runCurrent()

            assertTrue("$entryPoint", result is StartSosResult.Started)
            assertEquals("$entryPoint", 5, rig.seconds)
            assertEquals(entryPoint, rig.store.all.single().entryPoint)
            assertEquals("the host starts with the countdown", listOf("begin"), rig.host.events.toList())

            // One millisecond early nothing has happened; at five seconds it is active.
            rig.advance(4_999)
            assertEquals("$entryPoint", SosState.COUNTDOWN, rig.stored())
            rig.advance(1)
            assertEquals("$entryPoint", SosState.ACTIVE, rig.stored())
            assertEquals(1, rig.haptics.events.count { it == "triggered" })
            rig.runner.markSafe()
        }
    }

    @Test
    fun `with the gate closed no entry point starts a countdown or writes anything down`() = runTest {
        for (entryPoint in SosEntryPoint.entries) {
            val rig = SosRig(this)
            assertFalse("the gate is closed until the user agreed", rig.policy.open)

            val result = rig.startSos()(entryPoint)
            rig.advance(60_000)

            assertEquals("$entryPoint", StartSosResult.NotSetUp, result)
            assertEquals("$entryPoint", emptyList<SosRecord>(), rig.store.all)
            assertEquals("$entryPoint", SosRunState.Idle, rig.runner.state.value)
            assertEquals("no service, no notification", emptyList<String>(), rig.host.events.toList())
            assertEquals("no vibration", emptyList<String>(), rig.haptics.events.toList())
            assertEquals(0, rig.gateway.attempts.size)
        }
    }

    @Test
    fun `the gate is asked at every start, not once`() = runTest {
        val rig = SosRig(this)
        val startSos = rig.startSos()

        assertEquals(StartSosResult.NotSetUp, startSos(SosEntryPoint.WIDGET))

        rig.policy.open = true
        assertTrue(startSos(SosEntryPoint.WIDGET) is StartSosResult.Started)
        rig.runner.cancel()

        // Consent withdrawn afterwards: the next tap starts nothing.
        rig.policy.open = false
        assertEquals(StartSosResult.NotSetUp, startSos(SosEntryPoint.TILE))
        assertEquals(emptyList<SosRecord>(), rig.store.all)
    }

    @Test
    fun `a second start from another entry point shows the same emergency`() = runTest {
        val rig = SosRig(this).also { it.policy.open = true }
        val startSos = rig.startSos()

        val first = startSos(SosEntryPoint.TILE) as StartSosResult.Started
        rig.advance(2_000)
        val duringCountdown = startSos(SosEntryPoint.WIDGET)

        assertEquals(StartSosResult.AlreadyRunning(first.record), duringCountdown)
        assertEquals("the countdown was not started again", 3, rig.seconds)

        rig.advance(3_000)
        val whileActive = startSos(SosEntryPoint.NOTIFICATION) as StartSosResult.AlreadyRunning
        rig.advance(10_000)

        assertEquals(first.record.clientSosId, whileActive.record.clientSosId)
        assertEquals("one emergency", 1, rig.store.all.size)
        assertEquals("it keeps the entry point that started it", SosEntryPoint.TILE, rig.store.all.single().entryPoint)
        assertEquals("one trigger", 1, rig.haptics.events.count { it == "triggered" })
        rig.runner.markSafe()
    }

    @Test
    fun `nothing in the app starts the runner except the use case`() {
        // Plain file reading: Gradle runs unit tests with android/app as the working directory.
        val callers = java.io.File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines()
                    .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }
                    .any { it.contains("runner.start(") || it.contains("Runner.start(") || it.contains("startCountdown(") }
            }
            .map { it.name }
            .toSet()

        // The engine defines the countdown, the runner calls it, the use case calls the runner.
        assertEquals(setOf("SosEngine.kt", "SosRunner.kt", "StartSos.kt"), callers)
    }

    @Test
    fun `a start in a new process picks up the emergency the old process left`() = runTest {
        val rig = SosRig(this).also { it.policy.open = true }
        val first = rig.startSos()(SosEntryPoint.IN_APP) as StartSosResult.Started
        rig.advance(5_000)

        rig.restartProcess()
        assertEquals(SosRunState.Idle, rig.runner.state.value)
        val again = rig.startSos()(SosEntryPoint.WIDGET)
        runCurrent()

        assertEquals(first.record.clientSosId, (again as StartSosResult.AlreadyRunning).record.clientSosId)
        assertTrue("the new process runs it", rig.runner.state.value is SosRunState.Active)
        assertEquals(1, rig.store.all.size)
        rig.runner.markSafe()
    }
}
