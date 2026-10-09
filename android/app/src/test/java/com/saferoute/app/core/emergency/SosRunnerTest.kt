// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import com.saferoute.app.core.location.FakeLocationEnvironment
import com.saferoute.app.core.location.FakeTrailLocationSource
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.location.RawFix
import com.saferoute.app.core.location.FAKE_POSITION
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything an emergency needs, as fakes, on the test's virtual time. [restartProcess] acts
 * out the death of the app's process: the store (the "disk") stays, every object in memory is
 * new.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosRig(private val scope: TestScope, granted: GrantedLocation = GrantedLocation.Precise) {
    val clock = SchedulerClock(scope.testScheduler)
    val store = InMemorySosStore()
    val environment = FakeLocationEnvironment(granted = granted)
    val battery = FakeBatteryLevel()

    // The messages. The gate is closed unless a test opens it, as in the app today.
    val contacts = FakeActiveSosContacts()
    val actions = InMemorySosActionStore()
    val gateway = FakeSmsGateway()
    val policy = FakeSosAlertPolicy()
    val settings = FakeSosMessageSettings()
    val freshener = FakeContactsFreshener()
    val smsMode = FakeSmsModeSource()
    val composer = FakeSmsComposer()

    private var process: CoroutineScope? = null

    lateinit var source: FakeTrailLocationSource
    lateinit var host: FakeSosHost
    lateinit var haptics: FakeSosHaptics
    lateinit var trail: SosTrail
    lateinit var engine: SosEngine
    lateinit var runner: SosRunner
    lateinit var alerts: SosAlerts
    lateinit var dispatch: SosDispatch

    /** The scope of the current "process": what an app-lifetime scope is on a phone. */
    lateinit var appScope: CoroutineScope

    init {
        restartProcess()
    }

    fun restartProcess() {
        // Everything the old process was running stops with it.
        process?.cancel()
        val alive = CoroutineScope(scope.backgroundScope.coroutineContext + Job()).also { process = it }
        appScope = alive
        source = FakeTrailLocationSource()
        host = FakeSosHost()
        haptics = FakeSosHaptics()
        trail = SosTrail(source, environment, store, battery, clock, alive)
        engine = SosEngine(store, clock, UuidV7Generator(clock, Random(1)))
        alerts = SosAlerts(contacts, actions, gateway, clock, UuidV7Generator(clock, Random(2)))
        dispatch = SosDispatch(alerts, policy, settings, freshener, smsMode, composer, trail, battery, clock, alive)
        runner = SosRunner(engine, trail, host, haptics, dispatch, clock, alive)
    }

    fun advance(millis: Long) {
        scope.advanceTimeBy(millis)
        scope.runCurrent()
    }

    val seconds: Int? get() = (runner.state.value as? SosRunState.Countdown)?.secondsLeft
    suspend fun stored(): SosState? = store.all.lastOrNull()?.state
}

/** The countdown, the trigger, cancel, "I'm safe" and recovery, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SosRunnerTest {

    @Test
    fun `the countdown shows 5 to 1 with one vibration each and triggers once at the end`() = runTest {
        val rig = SosRig(this)

        assertTrue(rig.runner.start(SosEntryPoint.IN_APP) is SosStart.Started)
        runCurrent()

        val shown = mutableListOf(rig.seconds)
        repeat(4) {
            rig.advance(1_000)
            shown += rig.seconds
        }
        assertEquals(listOf<Int?>(5, 4, 3, 2, 1), shown)
        assertEquals(List(5) { "tick" }, rig.haptics.events.toList())
        assertEquals("not triggered one millisecond early", SosState.COUNTDOWN, rig.advance(999).let { rig.stored() })

        rig.advance(1)

        assertTrue(rig.runner.state.value is SosRunState.Active)
        assertEquals(SosState.ACTIVE, rig.stored())
        assertEquals("triggered", rig.haptics.events.last())
        // Nothing fires a second time, however long it runs.
        rig.advance(60_000)
        assertEquals(1, rig.haptics.events.count { it == "triggered" })
        assertEquals(1, rig.store.all.size)
        assertTrue(rig.host.running)
    }

    @Test
    fun `the state is on disk before the host or the vibration hear of it`() = runTest {
        val rig = SosRig(this)

        rig.runner.start(SosEntryPoint.TILE)

        // No time has passed and no coroutine has run yet: the record is already there.
        assertEquals(SosState.COUNTDOWN, rig.stored())
        assertEquals(SosEntryPoint.TILE, rig.store.all.single().entryPoint)
        assertEquals(listOf("begin"), rig.host.events.toList())
    }

    @Test
    fun `cancel is local only - record gone, trail stopped, host ended, nothing fires later`() = runTest {
        val rig = SosRig(this)
        rig.runner.start(SosEntryPoint.IN_APP)
        rig.advance(2_500)
        rig.source.emit()
        runCurrent()

        assertTrue(rig.runner.cancel())

        assertEquals(SosRunState.Idle, rig.runner.state.value)
        assertEquals(emptyList<SosRecord>(), rig.store.all)
        assertFalse(rig.source.isRunning)
        assertEquals("end", rig.host.events.last())
        rig.advance(60_000)
        assertFalse("triggered" in rig.haptics.events)
        assertEquals(emptyList<SosRecord>(), rig.store.all)
        assertFalse("nothing left to cancel", rig.runner.cancel())
    }

    @Test
    fun `a second start during the countdown starts nothing new`() = runTest {
        val rig = SosRig(this)
        val first = rig.runner.start(SosEntryPoint.IN_APP).record
        rig.advance(2_000)

        val second = rig.runner.start(SosEntryPoint.WIDGET)

        assertEquals(SosStart.AlreadyActive(first), second)
        rig.advance(3_000)
        // One countdown ran: five ticks, one trigger, at the first one's end.
        assertEquals(5, rig.haptics.events.count { it == "tick" })
        assertEquals(1, rig.haptics.events.count { it == "triggered" })
        assertEquals(1, rig.store.all.size)
    }

    @Test
    fun `a start while an SOS is active shows the active one`() = runTest {
        val rig = SosRig(this)
        rig.runner.start(SosEntryPoint.IN_APP)
        rig.advance(5_000)

        val again = rig.runner.start(SosEntryPoint.NOTIFICATION)

        assertTrue(again is SosStart.AlreadyActive)
        assertTrue(rig.runner.state.value is SosRunState.Active)
        assertEquals(1, rig.store.all.size)
    }

    @Test
    fun `after the alert went out cancel is refused and I am safe ends everything`() = runTest {
        val rig = SosRig(this)
        rig.runner.start(SosEntryPoint.IN_APP)
        assertFalse("no alert went out yet", rig.runner.markSafe())
        rig.advance(5_000)

        assertFalse(rig.runner.cancel())
        assertTrue(rig.runner.state.value is SosRunState.Active)

        assertTrue(rig.runner.markSafe())
        assertEquals(SosRunState.Idle, rig.runner.state.value)
        assertEquals(SosState.RESOLVED, rig.stored())
        assertFalse(rig.source.isRunning)
        assertEquals("end", rig.host.events.last())
        // The next emergency is a new one.
        assertTrue(rig.runner.start(SosEntryPoint.IN_APP) is SosStart.Started)
    }

    @Test
    fun `process death during the countdown - the new process finishes the SAME countdown`() = runTest {
        val rig = SosRig(this)
        rig.runner.start(SosEntryPoint.IN_APP)
        rig.advance(2_000)

        rig.restartProcess()
        // The old process is gone: nothing counts down by itself.
        rig.advance(500)
        assertEquals(SosRunState.Idle, rig.runner.state.value)

        assertTrue(rig.runner.resume() is SosRecovery.ResumeCountdown)
        runCurrent()
        assertEquals(3, rig.seconds)
        assertEquals(listOf("begin"), rig.host.events.toList())

        rig.advance(2_499)
        assertEquals(SosState.COUNTDOWN, rig.stored())
        rig.advance(1)
        assertEquals("five seconds after the ORIGINAL start", SosState.ACTIVE, rig.stored())
    }

    @Test
    fun `process death past the end of the countdown - nothing is sent until the user says so`() = runTest {
        val rig = SosRig(this)
        rig.runner.start(SosEntryPoint.IN_APP)
        rig.advance(3_000)
        rig.restartProcess()
        rig.advance(120_000)

        assertTrue(rig.runner.resume() is SosRecovery.AskSendOrCancel)
        rig.advance(120_000)
        assertEquals(SosRunState.Idle, rig.runner.state.value)
        assertEquals(SosState.COUNTDOWN, rig.stored())
        assertEquals(emptyList<String>(), rig.haptics.events.toList())
        assertEquals(emptyList<String>(), rig.host.events.toList())

        assertTrue(rig.runner.sendNow())

        assertTrue(rig.runner.state.value is SosRunState.Active)
        assertEquals(SosState.ACTIVE, rig.stored())
        assertEquals(listOf("triggered"), rig.haptics.events.toList())
        assertTrue(rig.host.running)
        assertFalse("only once", rig.runner.sendNow())
    }

    @Test
    fun `process death past the end of the countdown, then cancel - nothing remains`() = runTest {
        val rig = SosRig(this)
        rig.runner.start(SosEntryPoint.IN_APP)
        rig.restartProcess()
        rig.advance(120_000)
        rig.runner.resume()

        assertTrue(rig.runner.cancel())

        assertEquals(emptyList<SosRecord>(), rig.store.all)
        assertEquals(SosRecovery.Nothing, rig.runner.resume())
    }

    @Test
    fun `process death while active - the new process picks it up and I am safe still works`() = runTest {
        val rig = SosRig(this)
        val id = rig.runner.start(SosEntryPoint.IN_APP).record.clientSosId
        rig.advance(5_000)
        rig.source.emit()
        runCurrent()

        rig.restartProcess()
        // A day later, for example after the phone was restarted.
        rig.advance(86_400_000)

        val recovery = rig.runner.resume()
        assertEquals(id, (recovery as SosRecovery.StillActive).record.clientSosId)
        assertTrue(rig.runner.state.value is SosRunState.Active)
        assertTrue(rig.host.running)
        assertTrue("the trail runs again", rig.source.isRunning)
        assertEquals("the alert is not repeated", emptyList<String>(), rig.haptics.events.toList())
        assertEquals("the earlier point is still there", 1, rig.store.points(id).size)
        // Asking again changes nothing.
        assertEquals(SosRecovery.Nothing, rig.runner.resume())

        assertTrue(rig.runner.markSafe())
        assertEquals(SosState.RESOLVED, rig.stored())
    }

    @Test
    fun `process death between the trigger and the first action - the new process goes on`() = runTest {
        val rig = SosRig(this)
        rig.runner.start(SosEntryPoint.IN_APP)
        rig.advance(4_000)
        // The trigger is written, and the process dies before anything else happens.
        rig.restartProcess()
        rig.advance(1_000)
        assertTrue(rig.engine.trigger() != null)
        rig.restartProcess()

        assertTrue(rig.runner.resume() is SosRecovery.StillActive)

        assertEquals(SosState.ACTIVE, rig.stored())
        assertNull(rig.engine.trigger(force = true))
    }

    @Test
    fun `a clock set back during the countdown neither sends early nor gets stuck`() = runTest {
        val rig = SosRig(this)
        rig.runner.start(SosEntryPoint.IN_APP)
        rig.advance(4_000)

        // Two seconds back: on the clock, three seconds are left again.
        rig.clock.shiftMillis = -2_000
        rig.advance(1_000)
        assertEquals(SosState.COUNTDOWN, rig.stored())

        rig.advance(2_500)
        assertEquals(SosState.ACTIVE, rig.stored())
        assertEquals(1, rig.haptics.events.count { it == "triggered" })
    }

    @Test
    fun `location never blocks an SOS - without permission it triggers on time`() = runTest {
        val rig = SosRig(this, granted = GrantedLocation.None)

        rig.runner.start(SosEntryPoint.IN_APP)
        rig.advance(5_000)

        assertEquals(SosState.ACTIVE, rig.stored())
        assertEquals(SosLocationReport.NoPermission, rig.trail.report())
        assertEquals(emptyList<Pair<Boolean, Long>>(), rig.source.starts)
    }

    @Test
    fun `location never blocks an SOS - with location off and no fix it triggers on time`() = runTest {
        val rig = SosRig(this)
        rig.environment.locationEnabled = false

        rig.runner.start(SosEntryPoint.IN_APP)
        rig.advance(5_000)

        assertEquals(SosState.ACTIVE, rig.stored())
        assertEquals(SosLocationReport.Unavailable, rig.trail.report())
    }
}

/** The location trail and its fallbacks (failure row 8). */
@OptIn(ExperimentalCoroutinesApi::class)
class SosTrailTest {

    private suspend fun SosRig.startedSos(): String {
        val id = runner.start(SosEntryPoint.IN_APP).record.clientSosId
        advance(0)
        return id
    }

    @Test
    fun `precise permission - a position every 5 seconds, stored with its accuracy`() = runTest {
        val rig = SosRig(this)
        val id = rig.startedSos()

        assertEquals(listOf(true to TRAIL_INTERVAL_MILLIS), rig.source.starts)
        assertEquals(SosLocationReport.Searching, rig.trail.report())

        rig.source.emit(accuracyMeters = 8f)
        rig.advance(0)

        assertEquals(SosLocationReport.Precise, rig.trail.report())
        val point = rig.store.points(id).single()
        assertEquals(8f, point.accuracyMeters)
        assertFalse(point.mock)
        assertEquals(rig.clock.instant(), point.recordedAt)
    }

    @Test
    fun `approximate permission only - it is used and reported as approximate`() = runTest {
        val rig = SosRig(this, granted = GrantedLocation.Approximate)
        val id = rig.startedSos()

        assertEquals(listOf(false to TRAIL_INTERVAL_MILLIS), rig.source.starts)
        rig.source.emit(accuracyMeters = 2_000f)
        rig.advance(0)

        assertEquals(SosLocationReport.Approximate, rig.trail.report())
        assertEquals(1, rig.store.points(id).size)
    }

    @Test
    fun `no fix yet - the last known position is stored with its real time and reported stale`() = runTest {
        val rig = SosRig(this)
        rig.source.lastKnown = RawFix(FAKE_POSITION, 40f, headingDegrees = null, isMock = false) to 120_000L
        val startedAt = rig.clock.instant()

        val id = rig.startedSos()

        assertEquals(startedAt.minusSeconds(120), rig.store.points(id).single().recordedAt)
        assertEquals(SosLocationReport.Stale(ageSeconds = 120), rig.trail.report())

        // A new position replaces "stale" at once.
        rig.source.emit()
        rig.advance(0)
        assertEquals(SosLocationReport.Precise, rig.trail.report())
        assertEquals(2, rig.store.points(id).size)
    }

    @Test
    fun `a position goes stale after 30 seconds without a new one, and its age is told`() = runTest {
        val rig = SosRig(this)
        rig.startedSos()
        rig.source.emit()

        rig.advance(29_000)
        assertEquals(SosLocationReport.Precise, rig.trail.report())
        rig.advance(16_000)
        assertEquals(SosLocationReport.Stale(ageSeconds = 45), rig.trail.report())
    }

    @Test
    fun `a mock position is kept and flagged, and an unknown accuracy is stored as unknown`() = runTest {
        val rig = SosRig(this)
        val id = rig.startedSos()

        rig.source.emit(accuracyMeters = 0f, isMock = true)
        rig.advance(0)

        val point = rig.store.points(id).single()
        assertTrue(point.mock)
        assertNull(point.accuracyMeters)
    }

    @Test
    fun `below 10 percent battery the interval is 15 seconds, and it follows the battery`() = runTest {
        assertEquals(TRAIL_INTERVAL_MILLIS, trailIntervalMillis(null))
        assertEquals(TRAIL_INTERVAL_MILLIS, trailIntervalMillis(10))
        assertEquals(TRAIL_LOW_BATTERY_INTERVAL_MILLIS, trailIntervalMillis(9))

        val rig = SosRig(this)
        rig.battery.percent = 9
        rig.startedSos()
        assertEquals(listOf(true to 15_000L), rig.source.starts)

        // Plugged in during the emergency: the next position switches back to 5 seconds.
        rig.battery.percent = 40
        rig.source.emit()
        assertEquals(listOf(true to 15_000L, true to 5_000L), rig.source.starts)
        // No change, no restart.
        rig.source.emit()
        assertEquals(2, rig.source.starts.size)
    }

    @Test
    fun `after the end no position is stored, not even one that was on its way`() = runTest {
        val rig = SosRig(this)
        val id = rig.startedSos()
        rig.advance(5_000)
        rig.source.emit()
        rig.advance(0)

        rig.runner.markSafe()
        assertFalse(rig.source.emit())
        rig.advance(0)

        assertEquals(1, rig.store.points(id).size)
        assertEquals(SosTrailState(), rig.trail.state.value)
    }

    @Test
    fun `what is said about the position never contains the position`() = runTest {
        val rig = SosRig(this)
        rig.startedSos()
        rig.source.emit()

        val printed = "${rig.trail.state.value} ${rig.trail.report()} ${rig.runner.state.value}"

        listOf("10.0", "20.0").forEach { assertFalse(it, printed.contains(it)) }
    }
}
