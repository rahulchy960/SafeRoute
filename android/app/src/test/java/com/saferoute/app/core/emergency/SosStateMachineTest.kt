// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import com.saferoute.app.feature.search.TestClock
import java.time.Duration
import java.time.Instant
import java.util.Random
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val T0: Instant = Instant.parse("2026-10-09T09:00:00Z")

internal fun sosRecord(state: SosState, startedAt: Instant = T0) = SosRecord(
    clientSosId = "00000000-0000-7000-8000-000000000001",
    state = state,
    entryPoint = SosEntryPoint.IN_APP,
    practice = false,
    startedAt = startedAt,
    countdownEndsAt = startedAt.plus(SOS_COUNTDOWN),
)

/** The state machine as a table: every state against every event (Plan v7 section 7.2). */
class SosStateMachineTest {

    private val allowed = mapOf(
        (SosState.IDLE to SosEvent.HOLD_STARTED) to SosState.ARMING,
        (SosState.IDLE to SosEvent.COUNTDOWN_STARTED) to SosState.COUNTDOWN,
        (SosState.ARMING to SosEvent.HOLD_RELEASED) to SosState.IDLE,
        (SosState.ARMING to SosEvent.CANCELLED) to SosState.IDLE,
        (SosState.ARMING to SosEvent.COUNTDOWN_STARTED) to SosState.COUNTDOWN,
        (SosState.COUNTDOWN to SosEvent.CANCELLED) to SosState.IDLE,
        (SosState.COUNTDOWN to SosEvent.COUNTDOWN_ELAPSED) to SosState.TRIGGERED_LOCAL,
        (SosState.TRIGGERED_LOCAL to SosEvent.LOCAL_ACTIONS_STARTED) to SosState.ACTIVE,
        (SosState.TRIGGERED_LOCAL to SosEvent.MARKED_SAFE) to SosState.RESOLVED,
        (SosState.SYNCING to SosEvent.MARKED_SAFE) to SosState.RESOLVED,
        (SosState.ACTIVE to SosEvent.MARKED_SAFE) to SosState.RESOLVED,
    )

    @Test
    fun `every pair of state and event gives the listed state or is refused`() {
        for (state in SosState.entries) {
            for (event in SosEvent.entries) {
                assertEquals("$state + $event", allowed[state to event], nextSosState(state, event))
            }
        }
    }

    @Test
    fun `an alert that went out cannot be cancelled, only marked safe`() {
        for (state in listOf(SosState.TRIGGERED_LOCAL, SosState.SYNCING, SosState.ACTIVE)) {
            assertNull(nextSosState(state, SosEvent.CANCELLED))
            assertEquals(SosState.RESOLVED, nextSosState(state, SosEvent.MARKED_SAFE))
        }
    }

    @Test
    fun `nothing leaves resolved and nothing triggers without a countdown`() {
        SosEvent.entries.forEach { assertNull(nextSosState(SosState.RESOLVED, it)) }
        SosState.entries.filterNot { it == SosState.COUNTDOWN }.forEach {
            assertNull(nextSosState(it, SosEvent.COUNTDOWN_ELAPSED))
        }
    }

    @Test
    fun `the countdown is five seconds and records are kept thirty days`() {
        assertEquals(Duration.ofSeconds(5), SOS_COUNTDOWN)
        assertEquals(Duration.ofDays(30), SOS_RETENTION)
    }
}

/** What the app shows when it finds an emergency nobody is running (failure rows 4 and 5). */
class SosRecoveryTest {

    @Test
    fun `no record, or one that is over, needs nothing`() {
        assertEquals(SosRecovery.Nothing, recoveryFor(null, T0))
        assertEquals(SosRecovery.Nothing, recoveryFor(sosRecord(SosState.RESOLVED), T0))
    }

    @Test
    fun `a countdown with time left is shown again with what remains`() {
        val record = sosRecord(SosState.COUNTDOWN)

        assertEquals(
            SosRecovery.ResumeCountdown(record, Duration.ofMillis(3_500)),
            recoveryFor(record, T0.plusMillis(1_500)),
        )
    }

    @Test
    fun `a countdown whose end passed is always asked about, never sent or dropped`() {
        val record = sosRecord(SosState.COUNTDOWN)

        // Exactly at the end, one second late, two minutes late, a day late.
        for (late in listOf(0L, 1L, 120L, 86_400L)) {
            val now = record.countdownEndsAt.plusSeconds(late)
            assertEquals("late by $late s", SosRecovery.AskSendOrCancel(record), recoveryFor(record, now))
        }
    }

    @Test
    fun `a countdown that starts in the future means the clock changed, so it is asked about`() {
        val record = sosRecord(SosState.COUNTDOWN)

        assertEquals(SosRecovery.AskSendOrCancel(record), recoveryFor(record, T0.minusSeconds(3_600)))
    }

    @Test
    fun `an alert that went out is still active until the user says they are safe`() {
        for (state in listOf(SosState.TRIGGERED_LOCAL, SosState.SYNCING, SosState.ACTIVE)) {
            val record = sosRecord(state)
            assertEquals(SosRecovery.StillActive(record), recoveryFor(record, T0.plusSeconds(86_400)))
        }
    }
}

/** UUID version 7 (RFC 9562). */
class UuidV7Test {

    private val clock = TestClock(nowMillis = 1_760_000_000_000L)

    @Test
    fun `an id has version 7, the RFC variant and the canonical form`() {
        val id = UuidV7Generator(clock, Random(1)).next()

        val uuid = UUID.fromString(id)
        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id))
    }

    @Test
    fun `the first 48 bits are the time in milliseconds`() {
        val uuid = UUID.fromString(UuidV7Generator(clock, Random(2)).next())

        assertEquals(1_760_000_000_000L, uuid.mostSignificantBits ushr 16)
    }

    @Test
    fun `ids made later sort after ids made earlier`() {
        val generator = UuidV7Generator(clock, Random(3))
        val ids = (0 until 50).map {
            clock.nowMillis += 1
            generator.next()
        }

        assertEquals(ids, ids.sorted())
    }

    @Test
    fun `ids made in the same millisecond differ, and the random part follows the source`() {
        val generator = UuidV7Generator(clock)
        assertEquals(1_000, (0 until 1_000).map { generator.next() }.toSet().size)

        assertEquals(UuidV7Generator(clock, Random(4)).next(), UuidV7Generator(clock, Random(4)).next())
        assertFalse(UuidV7Generator(clock, Random(4)).next() == UuidV7Generator(clock, Random(5)).next())
    }

    @Test
    fun `a time beyond 48 bits is cut, not spilled into the version`() {
        val uuid = UUID.fromString(UuidV7Generator(TestClock(nowMillis = Long.MAX_VALUE), Random(6)).next())

        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
    }
}
