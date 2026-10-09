// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.data.ActiveSosContacts
import com.saferoute.app.core.data.RoomSosActionStore
import com.saferoute.app.core.data.RoomSosStore
import com.saferoute.app.core.data.SosContact
import com.saferoute.app.feature.contacts.inMemoryDatabase
import java.util.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val SOS_ID = "00000000-0000-7000-8000-000000000001"

/** Obviously fake people: "Test Contact N" and numbers from +91 90000 100NN. Nobody real. */
private fun contact(n: Int) = SosContact("id-$n", "Test Contact $n", "+9190000100${n.toString().padStart(2, '0')}")

private fun phone(n: Int) = contact(n).phoneE164

/** The phone's contact list as a test sets it. Never holds an opted-out contact, as the real one. */
private class FakeActiveSosContacts(var list: List<SosContact>) : ActiveSosContacts {
    override suspend fun current(): List<SosContact> = list
}

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

        val retryLater = rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" }

        assertFalse(retryLater)
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
        assertFalse(rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" })
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
    fun `after 20 tries a message is given up, about ten minutes in`() = runTest {
        val rig = AlertsRig(this, contact(1))
        rig.gateway.fallback = RETRY
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)

        val loop = launch { rig.alerts.sendUntilDone(SOS_ID, SosActionType.ALERT, { true }) { "the alert" } }
        advanceTimeBy(19 * ALERT_RETRY_MILLIS)
        runCurrent()

        assertEquals(MAX_ALERT_ATTEMPTS, rig.gateway.attemptsTo(phone(1)))
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
    fun `a contact who opted out before their message left is skipped and never texted`() = runTest {
        val rig = AlertsRig(this, contact(1), contact(2), contact(3))
        rig.gateway.fallback = RETRY
        rig.alerts.prepare(SOS_ID, SosActionType.ALERT)
        rig.alerts.sendDue(SOS_ID, SosActionType.ALERT) { "the alert" }

        // The fresh list from the server no longer has contact 2 (opted out) nor 3 (removed).
        val dropped = rig.alerts.dropOptedOut(SOS_ID, stillAllowed = setOf("id-1"))
        rig.gateway.fallback = SmsOutcome.Sent
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
        assertTrue(alerts.sendDue(id, SosActionType.ALERT) { "the alert" })

        val stored = store.actions(id)
        assertEquals(listOf("Test Contact 1", "Test Contact 2"), stored.map { it.name })
        assertEquals(listOf("id-1", "id-2"), stored.map { it.contactId })
        assertEquals(listOf(SosActionState.SENT, SosActionState.FAILED_RETRYABLE), stored.map { it.state })
        assertEquals(listOf(null, "no_service"), stored.map { it.lastErrorCategory })

        // SENT is not among the states a sender may take a message from.
        assertFalse(
            store.moveIf(stored[0].id, setOf(SosActionState.PENDING), SosActionState.IN_PROGRESS, 2, null, clock.instant()),
        )
        assertFalse(alerts.sendDue(id, SosActionType.ALERT) { "the alert" })
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
