// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.data.RoomSosActionStore
import com.saferoute.app.core.data.RoomSosStore
import com.saferoute.app.core.data.SosContact
import com.saferoute.app.feature.contacts.inMemoryDatabase
import java.util.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val SOS_ID = "00000000-0000-7000-8000-000000000001"

/** Obviously fake people: "Test Contact N" and numbers from +91 90000 100NN. Nobody real. */
private fun contact(n: Int) = SosContact("id-$n", "Test Contact $n", "+9190000100${n.toString().padStart(2, '0')}")

private fun phone(n: Int) = contact(n).phoneE164

@OptIn(ExperimentalCoroutinesApi::class)
private class AlertsRig(scope: TestScope, vararg contacts: SosContact) {
    val clock = SchedulerClock(scope.testScheduler)
    val contacts = FakeActiveSosContacts(contacts.toList())
    val store = InMemorySosActionStore()
    val gateway = FakeSmsGateway()
    var alerts: SosAlerts = create()

    private fun create() = SosAlerts(contacts, store, gateway, clock, UuidV7Generator(clock, Random(1)))

    /** A new object on the same store: what a restarted app has. */
    fun newProcess() {
        alerts = create()
    }

    fun states(): Map<String, SosActionState> =
        store.all.filter { it.type == SosActionType.ALERT }.associate { it.phoneE164 to it.state }
}

private val RETRY = SmsOutcome.Retryable("no_service")

/** Who is alerted, what is retried and what is never sent twice (failure rows 1, 2 and 6). */
@OptIn(ExperimentalCoroutinesApi::class)
class SosAlertsTest {

    @Test
    fun `every contact the phone knows gets one message, and the result is recorded`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2), contact(3))

        val prepared = rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        assertEquals(3, prepared.size)
        assertTrue(prepared.all { it.state == SosActionState.PENDING && it.attemptCount == 0 })
        // Written down before anything is sent.
        assertEquals(emptyList<Pair<String, String>>(), rig.gateway.attempts.toList())

        val retryIn = rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" }

        assertNull("nothing is left to send", retryIn)
        assertEquals(listOf(phone(1), phone(2), phone(3)), rig.gateway.attempts.map { it.first })
        assertTrue(rig.gateway.attempts.all { it.second == "the alert" })
        assertTrue(rig.store.all.all { it.state == SosActionState.SENT && it.attemptCount == 1 })
        assertEquals(SosAlertSummary(sent = 3, waiting = 0, failed = 0, skipped = 0), summarise(rig.store.all))
    }

    @Test
    fun `preparing twice, or after the contact list changed, alerts the same people once`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2))
        val first = rig.alerts.prepare(SOS_ID, SosActionType.ALERT)

        // A contact is added and one is renamed while the emergency runs.
        rig.contacts.list = listOf(contact(1).copy(name = "Renamed"), contact(2), contact(3))
        val second = rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" }

        assertEquals(first, second)
        assertEquals(listOf(phone(1), phone(2)), rig.gateway.attempts.map { it.first })
        assertEquals(listOf("Test Contact 1", "Test Contact 2"), rig.store.all.map { it.name })
    }

    @Test
    fun `with no contact nothing is sent and nothing fails`() = runTest {
        val rig = AlertsRig(this)

        assertEquals(emptyList<SosAction>(), rig.alerts.prepare(SOS_ID, SosActionType.ALERT))
        assertNull(rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" })
        assertEquals(0, summarise(rig.store.all).total)
    }

    @Test
    fun `a failed message is tried again every 30 seconds, and a sent one never again`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2))
        rig.gateway.answer(phone(2), RETRY, RETRY, SmsOutcome.Sent)
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)

        val loop = launch { rig.alerts.sendUntilDone(SOS_ID, SosActionType.ALERT, { true }) { "the alert" } }
        runCurrent()

        assertEquals(mapOf(phone(1) to SosActionState.SENT, phone(2) to SosActionState.FAILED_RETRYABLE), rig.states())
        assertEquals("no_service", rig.store.all.last().lastErrorCategory)

        advanceTimeBy(29_999)
        runCurrent()
        assertEquals("not before 30 seconds", 1, rig.gateway.attemptsTo(phone(2)))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, rig.gateway.attemptsTo(phone(2)))
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(mapOf(phone(1) to SosActionState.SENT, phone(2) to SosActionState.SENT), rig.states())
        assertEquals(3, rig.gateway.attemptsTo(phone(2)))
        // The contact whose message was sent at once got exactly one.
        assertEquals(1, rig.gateway.attemptsTo(phone(1)))
        assertTrue("the loop ends when nothing is left", loop.isCompleted)
    }

    @Test
    fun `an alert is tried again past 20 tries, for as long as the emergency is active`() = runTest {
        val rig = AlertsRig(this, contact(1))
        rig.gateway.fallback = RETRY
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        val rule = alertRetryRule(rig.clock.instant())

        val loop = launch { rig.alerts.sendUntilDone(SOS_ID, SosActionType.ALERT, { true }, rule = rule) { "the alert" } }
        advanceTimeBy(3 * 3_600_000L)
        runCurrent()

        // Eleven tries in the first five minutes, then four more as the wait grows to five
        // minutes (at 6, 8, 12 and 17 minutes), then one every five minutes.
        assertTrue("more than 40 tries in three hours", rig.gateway.attemptsTo(phone(1)) > 40)
        val action = rig.store.all.single()
        assertEquals(SosActionState.FAILED_RETRYABLE, action.state)
        assertEquals(SosAlertSummary(sent = 0, waiting = 1, failed = 0, skipped = 0), summarise(rig.store.all))
        assertFalse("still trying", loop.isCompleted)

        // The signal returns: the next try sends it, and then nothing more is tried.
        rig.gateway.fallback = SmsOutcome.Sent
        advanceTimeBy(ALERT_RETRY_CAP_MILLIS)
        runCurrent()
        assertEquals(SosActionState.SENT, rig.store.all.single().state)
        assertTrue(loop.isCompleted)
        val tries = rig.gateway.attemptsTo(phone(1))
        advanceTimeBy(3_600_000)
        assertEquals("a sent alert is never sent again", tries, rig.gateway.attemptsTo(phone(1)))
    }

    @Test
    fun `the wait is 30 seconds for the first ten tries, then 1, 2 and 4 minutes, then 5 minutes`() = runTest {
        assertEquals(List(10) { 30_000L }, (1..10).map(::alertRetryDelayMillis))
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 300_000L, 300_000L), (11..15).map(::alertRetryDelayMillis))
        assertEquals(ALERT_RETRY_CAP_MILLIS, alertRetryDelayMillis(10_000))
        assertEquals(ALERT_RETRY_CAP_MILLIS, alertRetryDelayMillis(Int.MAX_VALUE))

        // And the tries really happen at those moments.
        val rig = AlertsRig(this, contact(1))
        rig.gateway.fallback = RETRY
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        val rule = alertRetryRule(rig.clock.instant())
        launch { rig.alerts.sendUntilDone(SOS_ID, SosActionType.ALERT, { true }, rule = rule) { "the alert" } }
        runCurrent()

        // Seconds after the first try at which the tries 2 to 17 are made.
        val expected = (1..10).map { it * 30 } + listOf(360, 480, 720, 1_020, 1_320, 1_620)
        var elapsed = 0
        for ((index, second) in expected.withIndex()) {
            advanceTimeBy((second - elapsed) * 1_000L - 1)
            runCurrent()
            assertEquals("not before second $second", index + 1, rig.gateway.attemptsTo(phone(1)))
            advanceTimeBy(1)
            runCurrent()
            assertEquals("at second $second", index + 2, rig.gateway.attemptsTo(phone(1)))
            elapsed = second
        }
        coroutineContext.cancelChildren()
    }

    @Test
    fun `24 hours after the trigger what is still unsent is given up, on time, and says why`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2))
        rig.gateway.answer(phone(2), SmsOutcome.Sent)
        rig.gateway.fallback = RETRY
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        val rule = alertRetryRule(rig.clock.instant())

        val loop = launch { rig.alerts.sendUntilDone(SOS_ID, SosActionType.ALERT, { true }, rule = rule) { "the alert" } }
        advanceTimeBy(ALERT_RETRY_LIMIT_MILLIS - 1)
        runCurrent()
        assertEquals(SosActionState.FAILED_RETRYABLE, rig.states()[phone(1)])
        assertFalse(loop.isCompleted)
        val tries = rig.gateway.attemptsTo(phone(1))

        advanceTimeBy(1)
        runCurrent()

        assertEquals(SosActionState.FAILED_FINAL, rig.states()[phone(1)])
        assertEquals(FAILED_EXPIRED, rig.store.all.first().lastErrorCategory)
        assertEquals("the sent one is untouched", SosActionState.SENT, rig.states()[phone(2)])
        assertTrue(loop.isCompleted)
        assertEquals(SosAlertSummary(sent = 1, waiting = 0, failed = 1, skipped = 0), summarise(rig.store.all))
        // No try is made at the moment of giving up, and none ever after.
        advanceTimeBy(ALERT_RETRY_LIMIT_MILLIS)
        assertEquals(tries, rig.gateway.attemptsTo(phone(1)))
        rig.alerts.sendNow(SOS_ID, SosActionType.ALERT, rule) { "the alert" }
        assertEquals("not even when asked for now", tries, rig.gateway.attemptsTo(phone(1)))
    }

    @Test
    fun `a try asked for now is made at once, whatever the wait, and counts as a try`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2))
        rig.gateway.answer(phone(2), SmsOutcome.Sent)
        rig.gateway.fallback = RETRY
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        val rule = alertRetryRule(rig.clock.instant())
        assertEquals(ALERT_RETRY_MILLIS, rig.alerts.sendDue(SOS_ID, SosActionType.ALERT, rule) { "the alert" })

        // One second later the regular round has nothing due...
        advanceTimeBy(1_000)
        rig.alerts.sendDue(SOS_ID, SosActionType.ALERT, rule) { "the alert" }
        assertEquals(1, rig.gateway.attemptsTo(phone(1)))
        // ...but a try asked for now is made.
        assertTrue(rig.alerts.sendNow(SOS_ID, SosActionType.ALERT, rule) { "the alert" })

        assertEquals(2, rig.gateway.attemptsTo(phone(1)))
        assertEquals(2, rig.store.all.first().attemptCount)
        assertEquals("the sent alert is not sent again", 1, rig.gateway.attemptsTo(phone(2)))
        // The wait starts again from this try.
        assertEquals(ALERT_RETRY_MILLIS, rig.alerts.sendDue(SOS_ID, SosActionType.ALERT, rule) { "the alert" })
    }

    @Test
    fun `a try asked for while one is in flight is ignored`() = runTest {
        val rig = AlertsRig(this, contact(1))
        rig.gateway.fallback = RETRY
        rig.gateway.holdMillis = 20_000
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        val rule = alertRetryRule(rig.clock.instant())

        val round = launch { rig.alerts.sendDue(SOS_ID, SosActionType.ALERT, rule) { "the alert" } }
        runCurrent()
        assertEquals(SosActionState.IN_PROGRESS, rig.store.all.single().state)

        // The phone has not answered yet: three taps change nothing.
        repeat(3) { assertFalse(rig.alerts.sendNow(SOS_ID, SosActionType.ALERT, rule) { "the alert" }) }
        assertEquals(1, rig.gateway.attemptsTo(phone(1)))

        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(round.isCompleted)
        // Once it has answered, a tap is a try again.
        rig.gateway.holdMillis = 0
        assertTrue(rig.alerts.sendNow(SOS_ID, SosActionType.ALERT, rule) { "the alert" })
        assertEquals(2, rig.gateway.attemptsTo(phone(1)))
    }

    @Test
    fun `a final failure stays final, also when a try is asked for now`() = runTest {
        val rig = AlertsRig(this, contact(1))
        rig.gateway.answer(phone(1), SmsOutcome.Final("rejected"))
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        val rule = alertRetryRule(rig.clock.instant())

        assertNull(rig.alerts.sendDue(SOS_ID, SosActionType.ALERT, rule) { "the alert" })
        rig.alerts.sendNow(SOS_ID, SosActionType.ALERT, rule) { "the alert" }
        advanceTimeBy(3_600_000)
        rig.alerts.sendDue(SOS_ID, SosActionType.ALERT, rule) { "the alert" }

        assertEquals(1, rig.gateway.attemptsTo(phone(1)))
        assertEquals(SosActionState.FAILED_FINAL, rig.store.all.single().state)
        assertEquals("rejected", rig.store.all.single().lastErrorCategory)
    }

    @Test
    fun `a new process continues the same schedule instead of starting it again`() = runTest {
        val rig = AlertsRig(this, contact(1))
        rig.gateway.fallback = RETRY
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        val rule = alertRetryRule(rig.clock.instant())
        val first = launch { rig.alerts.sendUntilDone(SOS_ID, SosActionType.ALERT, { true }, rule = rule) { "the alert" } }
        // Twelve tries: the last at 6 minutes, the next is due 2 minutes later, at 8 minutes.
        advanceTimeBy(360_000)
        runCurrent()
        assertEquals(12, rig.gateway.attemptsTo(phone(1)))

        // The process dies half a minute later and a new one starts at once.
        advanceTimeBy(30_000)
        first.cancel()
        rig.newProcess()
        launch { rig.alerts.sendUntilDone(SOS_ID, SosActionType.ALERT, { true }, rule = rule) { "the alert" } }
        runCurrent()

        assertEquals("no try at the restart: the wait is not over", 12, rig.gateway.attemptsTo(phone(1)))
        advanceTimeBy(89_999)
        runCurrent()
        assertEquals(12, rig.gateway.attemptsTo(phone(1)))
        advanceTimeBy(1)
        runCurrent()
        assertEquals("at 8 minutes, as planned before the restart", 13, rig.gateway.attemptsTo(phone(1)))
        // And the count went on: the next wait is 4 minutes, not 30 seconds.
        advanceTimeBy(239_999)
        runCurrent()
        assertEquals(13, rig.gateway.attemptsTo(phone(1)))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(14, rig.gateway.attemptsTo(phone(1)))
        coroutineContext.cancelChildren()
    }

    @Test
    fun `the user is told about unsent alerts once, only while one is unsent, also across a new process`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2))
        rig.gateway.answer(phone(1), SmsOutcome.Sent)
        rig.gateway.fallback = RETRY
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" }

        assertTrue(rig.alerts.markUnsentNoticeOnce(SOS_ID))
        assertFalse(rig.alerts.markUnsentNoticeOnce(SOS_ID))
        rig.newProcess()
        assertFalse(rig.alerts.markUnsentNoticeOnce(SOS_ID))

        // The mark is not a message: no contact, number or name, and nothing was sent for it.
        val mark = rig.store.all.single { it.type == SosActionType.UNSENT_NOTICE }
        assertEquals(listOf("", "", ""), listOf(mark.contactId, mark.phoneE164, mark.name))
        assertEquals(2, rig.gateway.attempts.size)
        // It is not counted among the alerts, and no follow-up is ever written for it.
        assertEquals(SosAlertSummary(sent = 1, waiting = 1, failed = 0, skipped = 0), summarise(rig.store.all))
        assertEquals(emptyList<SosAction>(), rig.alerts.prepare(SOS_ID, SosActionType.UNSENT_NOTICE).filter { it.contactId.isNotEmpty() })
    }

    @Test
    fun `nobody is told about unsent alerts when every alert was sent or given up`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2))
        rig.gateway.answer(phone(2), SmsOutcome.Final("rejected"))
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" }

        assertFalse(rig.alerts.markUnsentNoticeOnce(SOS_ID))
        assertTrue(rig.store.all.none { it.type == SosActionType.UNSENT_NOTICE })
    }

    @Test
    fun `a follow-up is given up after 20 tries, about ten minutes in`() = runTest {
        val rig = AlertsRig(this, contact(1))
        rig.gateway.fallback = RETRY
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)

        val loop = launch { rig.alerts.sendUntilDone(SOS_ID, SosActionType.ALERT, { true }) { "the alert" } }
        advanceTimeBy(19 * ALERT_RETRY_MILLIS)
        runCurrent()

        // The rule of the "safe" message and the location update (the default of the loop):
        // they run beside or after the emergency and must come to an end.
        assertEquals(MAX_FOLLOW_UP_ATTEMPTS, rig.gateway.attemptsTo(phone(1)))
        val action = rig.store.all.single()
        assertEquals(SosActionState.FAILED_FINAL, action.state)
        assertEquals(20, action.attemptCount)
        assertEquals("no_service", action.lastErrorCategory)
        assertTrue(loop.isCompleted)
        // Nothing more, however long the emergency lasts.
        advanceTimeBy(3_600_000)
        assertEquals(20, rig.gateway.attemptsTo(phone(1)))
        assertEquals(SosAlertSummary(sent = 0, waiting = 0, failed = 1, skipped = 0), summarise(rig.store.all))
    }

    @Test
    fun `a failure that cannot pass is not tried again`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2))
        rig.gateway.answer(phone(1), SmsOutcome.Final("no_permission"))
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)

        val loop = launch { rig.alerts.sendUntilDone(SOS_ID, SosActionType.ALERT, { true }) { "the alert" } }
        advanceTimeBy(600_000)
        runCurrent()

        assertEquals(mapOf(phone(1) to SosActionState.FAILED_FINAL, phone(2) to SosActionState.SENT), rig.states())
        assertEquals(1, rig.gateway.attemptsTo(phone(1)))
        assertEquals("no_permission", rig.store.all.first().lastErrorCategory)
        assertTrue(loop.isCompleted)
    }

    @Test
    fun `retries stop the moment the emergency is over`() = runTest {
        val rig = AlertsRig(this, contact(1))
        rig.gateway.fallback = RETRY
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        var active = true

        val loop = launch { rig.alerts.sendUntilDone(SOS_ID, SosActionType.ALERT, { active }) { "the alert" } }
        advanceTimeBy(65_000)
        runCurrent()
        assertEquals(3, rig.gateway.attemptsTo(phone(1)))

        active = false
        advanceTimeBy(600_000)
        runCurrent()

        assertEquals(3, rig.gateway.attemptsTo(phone(1)))
        assertTrue(loop.isCompleted)
    }

    @Test
    fun `each try carries the text of that moment`() = runTest {
        val rig = AlertsRig(this, contact(1))
        rig.gateway.answer(phone(1), RETRY)
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        var position = "first position"

        launch { rig.alerts.sendUntilDone(SOS_ID, SosActionType.ALERT, { true }) { position } }
        runCurrent()
        position = "better position"
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(listOf("first position", "better position"), rig.gateway.attempts.map { it.second })
    }

    @Test
    fun `numbers a limited build may not text are skipped with their own reason, and counted apart`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2), contact(3))
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        val limit = FakeSmsRecipientLimit(allowed = setOf(phone(2)))

        val held = rig.alerts.skipNotAllowed(SOS_ID, limit)
        rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" }

        assertEquals(2, held)
        assertEquals(
            mapOf(phone(1) to SosActionState.SKIPPED, phone(2) to SosActionState.SENT, phone(3) to SosActionState.SKIPPED),
            rig.states(),
        )
        assertEquals(0, rig.gateway.attemptsTo(phone(1)))
        assertEquals(SKIP_DEBUG_NOT_ALLOWLISTED, rig.store.all[0].lastErrorCategory)
        // "Opted out" and "held by this build" are different facts and are not added up.
        assertEquals(SosAlertSummary(sent = 1, waiting = 0, failed = 0, skipped = 0, held = 2), summarise(rig.store.all))
        assertEquals(3, summarise(rig.store.all).total)
        // Asked again, nothing changes; and a sent message is never touched.
        assertEquals(0, rig.alerts.skipNotAllowed(SOS_ID, FakeSmsRecipientLimit(allowed = emptySet())))
        assertEquals(SosActionState.SENT, rig.states()[phone(2)])
    }

    @Test
    fun `a build without a limit skips nobody`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2))
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)

        assertEquals(0, rig.alerts.skipNotAllowed(SOS_ID, UnlimitedSmsRecipients))
        assertEquals(0, rig.alerts.skipNotAllowed(SOS_ID, FakeSmsRecipientLimit(allowed = null)))

        assertEquals(mapOf(phone(1) to SosActionState.PENDING, phone(2) to SosActionState.PENDING), rig.states())
    }

    @Test
    fun `a contact who opted out before their message left is skipped and never texted`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2), contact(3))
        rig.gateway.fallback = RETRY
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" }

        // The fresh list from the server no longer has contact 2 (opted out) nor 3 (removed).
        val dropped = rig.alerts.dropOptedOut(SOS_ID, stillAllowed = setOf("id-1"))
        rig.gateway.fallback = SmsOutcome.Sent
        // The next round is due 30 seconds after the failed one.
        advanceTimeBy(ALERT_RETRY_MILLIS)
        rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" }

        assertEquals(2, dropped)
        assertEquals(
            mapOf(phone(1) to SosActionState.SENT, phone(2) to SosActionState.SKIPPED, phone(3) to SosActionState.SKIPPED),
            rig.states(),
        )
        assertEquals(1, rig.gateway.attemptsTo(phone(2)))
        assertEquals("opted_out", rig.store.all[1].lastErrorCategory)
        assertEquals(SosAlertSummary(sent = 1, waiting = 0, failed = 0, skipped = 2), summarise(rig.store.all))
    }

    @Test
    fun `an opt-out that arrives after the message was sent changes nothing about it`() = runTest {
        val rig = AlertsRig(this, contact(1))
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" }

        assertEquals(0, rig.alerts.dropOptedOut(SOS_ID, stillAllowed = emptySet()))

        assertEquals(SosActionState.SENT, rig.store.all.single().state)
    }

    @Test
    fun `the safe follow-up goes only to the contacts whose alert was really sent`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2), contact(3))
        rig.gateway.answer(phone(2), SmsOutcome.Final("rejected"))
        rig.gateway.answer(phone(3), RETRY)
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" }
        rig.gateway.attempts.clear()

        val safe = rig.alerts.prepare(SOS_ID, SosActionType.SAFE)
        rig.alerts.sendDue(SOS_ID, SosActionType.SAFE) { "safe now" }

        assertEquals(listOf(phone(1)), safe.map { it.phoneE164 })
        assertEquals(listOf(phone(1) to "safe now"), rig.gateway.attempts.toList())
        // The alert that was still waiting is not touched by the follow-up round.
        assertEquals(SosActionState.FAILED_RETRYABLE, rig.states()[phone(3)])
        assertEquals(SosAlertSummary(1, 0, 0, 0), summarise(rig.store.all, SosActionType.SAFE))
    }

    @Test
    fun `two senders at once never send the same message twice`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2))
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)

        (1..10).map { async { rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" } } }.awaitAll()

        assertEquals(listOf(phone(1), phone(2)), rig.gateway.attempts.map { it.first })
    }

    @Test
    fun `after a process death a sent message stays sent and an unanswered one is tried again`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2))
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" }
        // Contact 2's message was handed to the phone and the process died before the answer.
        val second = rig.store.all[1]
        rig.store.moveIf(second.id, setOf(SosActionState.SENT), SosActionState.IN_PROGRESS, 1, null, rig.clock.instant())
        rig.gateway.attempts.clear()

        rig.newProcess()
        assertEquals(rig.store.all.size, rig.alerts.prepare(SOS_ID, SosActionType.ALERT).size)
        rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" }

        // Known and accepted: this contact may get the alert twice. Nobody else does.
        assertEquals(listOf(phone(2)), rig.gateway.attempts.map { it.first })
        assertEquals(2, rig.store.all[1].attemptCount)
        assertTrue(rig.store.all.all { it.state == SosActionState.SENT })
    }

    @Test
    fun `an action never prints the name or the number`() = runTest {
        val rig = AlertsRig(this, contact(1))
        val printed = rig.alerts.prepare(SOS_ID, SosActionType.ALERT).toString() + contact(1)

        assertFalse(printed.contains("Test Contact"))
        assertFalse(printed.contains("90000100"))
    }
}

/** The same rules on the real table (`sos_actions`). */
@RunWith(AndroidJUnit4::class)
class RoomSosActionStoreTest {

    private val database = inMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
    private val records = RoomSosStore(database.sosDao())
    private val store = RoomSosActionStore(database.sosDao())
    private val clock = com.saferoute.app.feature.search.TestClock(nowMillis = 1_760_000_000_000L)
    private val gateway = FakeSmsGateway()
    private val alerts = SosAlerts(
        FakeActiveSosContacts(listOf(contact(1), contact(2))),
        store,
        gateway,
        clock,
        UuidV7Generator(clock, Random(1)),
    )

    @After
    fun tearDown() = database.close()

    private suspend fun startedSos(): String {
        val engine = SosEngine(records, clock, UuidV7Generator(clock, Random(2)))
        return engine.startCountdown(SosEntryPoint.IN_APP).record.clientSosId
    }

    @Test
    fun `actions are stored with their snapshot and move only from the expected states`() = runTest {
        val id = startedSos()
        gateway.answer(phone(2), RETRY)

        alerts.prepare(id, SosActionType.ALERT)
        assertEquals("due again in 30 seconds", ALERT_RETRY_MILLIS, alerts.sendDue(id, SosActionType.ALERT) { "the alert" })

        val stored = store.actions(id)
        assertEquals(listOf("Test Contact 1", "Test Contact 2"), stored.map { it.name })
        assertEquals(listOf("id-1", "id-2"), stored.map { it.contactId })
        assertEquals(listOf(SosActionState.SENT, SosActionState.FAILED_RETRYABLE), stored.map { it.state })
        assertEquals(listOf(null, "no_service"), stored.map { it.lastErrorCategory })

        // SENT is not among the states a sender may take a message from.
        assertFalse(
            store.moveIf(stored[0].id, setOf(SosActionState.PENDING), SosActionState.IN_PROGRESS, 2, null, clock.instant()),
        )
        assertTrue(alerts.sendNow(id, SosActionType.ALERT, FOLLOW_UP_RETRY_RULE) { "the alert" })
        assertEquals(1, gateway.attemptsTo(phone(1)))
        assertEquals(2, gateway.attemptsTo(phone(2)))
    }

    @Test
    fun `cancelling the emergency deletes its actions, and a late insert is dropped`() = runTest {
        val id = startedSos()
        val prepared = alerts.prepare(id, SosActionType.ALERT)

        assertTrue(records.deleteIf(id, SosState.COUNTDOWN))
        assertEquals(emptyList<SosAction>(), store.actions(id))

        // A sender that was a moment late: no crash, nothing stored.
        store.insertAll(prepared)
        assertEquals(emptyList<SosAction>(), store.actions(id))
    }
}
