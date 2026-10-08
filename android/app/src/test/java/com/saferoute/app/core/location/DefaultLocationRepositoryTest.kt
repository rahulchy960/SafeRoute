// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.location

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.map.LatLng
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

/**
 * The repository's rules with a fake phone: it starts only when permitted and possible, stops
 * when told, ages a position into "stale", and never stores or logs one. Time is virtual, and
 * the clock follows it. Runs under Robolectric so that everything written to Logcat is captured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DefaultLocationRepositoryTest {

    private val source = FakeLocationSource()
    private val environment = FakeLocationEnvironment(granted = GrantedLocation.Precise)

    private fun TestScope.repository() = DefaultLocationRepository(
        source = source,
        environment = environment,
        clock = object : Clock() {
            override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
        },
        scope = backgroundScope,
    )

    // --- the phone's last known position (ADR 0015, "Initial camera") ---------------------

    @Test
    fun `a recent last known position is shown as old until a new one arrives`() = runTest {
        advanceTimeBy(3_600_000)
        source.lastKnown = FAKE_POSITION to 4 * 60_000L
        val repository = repository()

        repository.start()

        val stale = repository.state.value as LocationState.Stale
        assertEquals(FAKE_POSITION, stale.lastFix.position)
        assertEquals(240, stale.ageSeconds)
        assertEquals(1, source.lastKnownRequests)

        val next = LatLng(10.5, 20.5)
        source.emit(next)
        assertEquals(next, (repository.state.value as LocationState.Fix).fix.position)
    }

    @Test
    fun `a last known position of a few seconds ago counts as current`() = runTest {
        advanceTimeBy(3_600_000)
        source.lastKnown = FAKE_POSITION to 5_000L
        val repository = repository()

        repository.start()

        assertTrue(repository.state.value is LocationState.Fix)
    }

    @Test
    fun `a last known position older than ten minutes is ignored`() = runTest {
        advanceTimeBy(3_600_000)
        source.lastKnown = FAKE_POSITION to DefaultLocationRepository.LAST_KNOWN_MAX_AGE_MILLIS + 1
        val repository = repository()

        repository.start()

        assertEquals(LocationState.Searching, repository.state.value)
        assertEquals(10 * 60_000L, DefaultLocationRepository.LAST_KNOWN_MAX_AGE_MILLIS)
    }

    @Test
    fun `the last known position is not asked for without the permission, and never replaces a newer one`() = runTest {
        environment.granted = GrantedLocation.None
        source.lastKnown = FAKE_POSITION to 1_000L
        val repository = repository()
        repository.start()
        assertEquals(0, source.lastKnownRequests)
        assertEquals(LocationState.NoPermission, repository.state.value)

        // With the permission: a position of this session is kept across a pause, and the
        // phone's older one is not even asked for.
        environment.granted = GrantedLocation.Precise
        source.lastKnown = null
        repository.start()
        val fresh = LatLng(11.0, 21.0)
        source.emit(fresh)
        repository.stop()
        source.lastKnown = FAKE_POSITION to 1_000L
        repository.start()

        assertEquals(1, source.lastKnownRequests)
        assertEquals(fresh, (repository.state.value as LocationState.Fix).fix.position)
    }

    @Test
    fun `nothing runs until start is called`() = runTest {
        val repository = repository()

        assertEquals(LocationState.NoPermission, repository.state.value)
        assertTrue(source.starts.isEmpty())
    }

    @Test
    fun `without the permission it does not start`() = runTest {
        environment.granted = GrantedLocation.None
        val repository = repository()

        repository.start()

        assertEquals(LocationState.NoPermission, repository.state.value)
        assertFalse(source.isRunning)
    }

    @Test
    fun `without play services or with location switched off it is unavailable`() = runTest {
        val repository = repository()

        environment.playServices = false
        repository.start()
        assertEquals(LocationState.Unavailable, repository.state.value)

        environment.playServices = true
        environment.locationEnabled = false
        repository.start()
        assertEquals(LocationState.Unavailable, repository.state.value)
        assertTrue(source.starts.isEmpty())

        // Switched on again: the next start works.
        environment.locationEnabled = true
        repository.start()
        assertEquals(LocationState.Searching, repository.state.value)
    }

    @Test
    fun `searching, then a fix with its accuracy and heading`() = runTest {
        val repository = repository()
        repository.start()
        assertEquals(LocationState.Searching, repository.state.value)
        assertEquals(listOf(true), source.starts)

        advanceTimeBy(2_000)
        source.emit(accuracyMeters = 8f, headingDegrees = 90f)

        val fix = (repository.state.value as LocationState.Fix).fix
        assertEquals(FAKE_POSITION, fix.position)
        assertEquals(8f, fix.accuracyMeters)
        assertEquals(90f, fix.headingDegrees)
        assertEquals(2_000L, fix.timeMillis)
        assertFalse(fix.isApproximate)
    }

    @Test
    fun `approximate permission asks for coarse updates and marks the fix`() = runTest {
        environment.granted = GrantedLocation.Approximate
        val repository = repository()

        repository.start()
        source.emit(accuracyMeters = 2_000f)

        assertEquals(listOf(false), source.starts)
        assertTrue((repository.state.value as LocationState.Fix).fix.isApproximate)
    }

    @Test
    fun `a fix that is not renewed becomes stale and a new one makes it fresh again`() = runTest {
        val repository = repository()
        repository.start()
        source.emit()

        advanceTimeBy(DefaultLocationRepository.STALE_AFTER_MILLIS - 1_000)
        assertTrue(repository.state.value is LocationState.Fix)

        advanceTimeBy(DefaultLocationRepository.TICK_MILLIS + 1_000)
        val stale = repository.state.value as LocationState.Stale
        assertEquals(FAKE_POSITION, stale.lastFix.position)
        assertTrue(stale.ageSeconds >= 30)

        advanceTimeBy(60_000)
        assertTrue((repository.state.value as LocationState.Stale).ageSeconds >= 90)

        source.emit(position = LatLng(11.0, 21.0))
        assertEquals(LatLng(11.0, 21.0), (repository.state.value as LocationState.Fix).fix.position)
    }

    @Test
    fun `no fix for a long time is unavailable, and a late fix still counts`() = runTest {
        val repository = repository()
        repository.start()

        advanceTimeBy(DefaultLocationRepository.SEARCH_TIMEOUT_MILLIS - 5_001)
        assertEquals(LocationState.Searching, repository.state.value)
        advanceTimeBy(10_000)
        assertEquals(LocationState.Unavailable, repository.state.value)

        source.emit()
        assertTrue(repository.state.value is LocationState.Fix)
    }

    @Test
    fun `stop ends updates at once and nothing arrives afterwards`() = runTest {
        val repository = repository()
        repository.start()
        source.emit()
        val late = source.lateCallback()!!
        val before = repository.state.value

        repository.stop()

        assertFalse(source.isRunning)
        assertEquals(1, source.stops)
        assertFalse(source.emit(position = LatLng(12.0, 22.0)))
        // A callback that was already on its way is ignored too.
        late(RawFix(LatLng(12.0, 22.0), 5f, null, isMock = false))
        advanceTimeBy(120_000)
        assertEquals(before, repository.state.value)
    }

    @Test
    fun `starting twice starts once, and after a pause the old fix is shown as old`() = runTest {
        val repository = repository()
        repository.start()
        repository.start()
        assertEquals(1, source.starts.size)

        source.emit()
        repository.stop()
        advanceTimeBy(120_000)
        repository.start()

        val stale = repository.state.value as LocationState.Stale
        assertTrue(stale.ageSeconds >= 120)
        assertEquals(2, source.starts.size)
    }

    @Test
    fun `losing the permission while running stops on the next start`() = runTest {
        val repository = repository()
        repository.start()
        source.emit()

        environment.granted = GrantedLocation.None
        repository.start()

        assertEquals(LocationState.NoPermission, repository.state.value)
        assertFalse(source.isRunning)
    }

    @Test
    fun `positions are never logged or printed`() = runTest {
        ShadowLog.clear()
        val repository = repository()
        repository.start()
        // Distinctive digits, so that any trace of them in a log is unmistakable.
        val secret = LatLng(12.345678, 98.765432)
        source.emit(position = secret, accuracyMeters = 13.57f, headingDegrees = 246.8f, isMock = true)
        advanceTimeBy(60_000)
        Log.d("test", "state is ${repository.state.value} / ${(repository.state.value as LocationState.Stale).lastFix}")
        repository.stop()

        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        listOf("12.34", "98.76", "13.57", "246.8").forEach { digits ->
            assertFalse("found $digits in the log", logged.contains(digits))
        }
        assertTrue(logged.contains("hidden"))
        assertNull(ShadowLog.getLogs().firstOrNull { it.tag.contains("Location", ignoreCase = true) })
    }

    @Test
    fun `the debug summary shows buckets, never values`() = runTest {
        val fresh = LocationState.Fix(fakeFix(timeMillis = 95_000))
        val older = LocationState.Stale(fakeFix(timeMillis = 0), ageSeconds = 100)

        assertEquals(
            LocationDebugSummary(GrantedLocation.Precise, true, LocationDebugSummary.FixAge.UnderTenSeconds),
            locationDebugSummary(environment, fresh, nowMillis = 100_000),
        )
        assertEquals(
            LocationDebugSummary.FixAge.UnderOneMinute,
            locationDebugSummary(environment, fresh, nowMillis = 140_000).fixAge,
        )
        assertEquals(
            LocationDebugSummary.FixAge.Older,
            locationDebugSummary(environment, older, nowMillis = 100_000).fixAge,
        )
        assertEquals(
            LocationDebugSummary.FixAge.None,
            locationDebugSummary(environment, LocationState.Searching, nowMillis = 0).fixAge,
        )
    }
}
