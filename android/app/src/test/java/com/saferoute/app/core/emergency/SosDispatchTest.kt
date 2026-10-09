// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import com.saferoute.app.core.data.SosContact
import com.saferoute.app.core.location.GrantedLocation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Obviously fake people: "Test Contact N" and numbers from +91 90000 100NN. Nobody real. */
private fun person(n: Int) = SosContact("id-$n", "Test Contact $n", "+9190000100${n.toString().padStart(2, '0')}")

private val RETRYABLE = SmsOutcome.Retryable("no_service")

/**
 * A running emergency and its messages together: the real runner, engine, trail, dispatcher
 * and sender on fakes of the phone's parts, in virtual time. The gate is open in these tests
 * unless a test says otherwise; in the app it is closed until the notice version 2 exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosDispatchTest {

    private fun TestScope.rig(
        contacts: Int = 2,
        granted: GrantedLocation = GrantedLocation.Precise,
        allowed: Boolean = true,
    ) = SosRig(this, granted).also { rig ->
        rig.contacts.list = (1..contacts).map(::person)
        rig.policy.allowed = allowed
    }

    private fun SosRig.sentTo(n: Int): List<String> = gateway.attempts.filter { it.first == person(n).phoneE164 }.map { it.second }
    private fun SosRig.alertStates() = actions.all.filter { it.type == SosActionType.ALERT }.map { it.state }

    /** Starts an SOS and lets the countdown run; a position arrives in time when [fix] is true. */
    private suspend fun SosRig.trigger(fix: Boolean = true) {
        runner.start(SosEntryPoint.IN_APP)
        advance(2_500)
        if (fix) source.emit()
        advance(2_500)
    }

    @Test
    fun `with the gate closed an SOS runs and nobody is messaged`() = runTest {
        val rig = rig(allowed = false)

        rig.trigger()
        rig.advance(600_000)

        assertEquals(SosState.ACTIVE, rig.stored())
        assertEquals(SosAlertStatus.NotEnabled, rig.dispatch.status.value)
        assertEquals(emptyList<SosAction>(), rig.actions.all)
        assertEquals(emptyList<Pair<String, String>>(), rig.gateway.attempts.toList())
        assertEquals(emptyList<Pair<List<String>, String>>(), rig.composer.shown.toList())
    }

    @Test
    fun `with a position ready the alert leaves at the trigger, once per contact, with the link`() = runTest {
        val rig = rig()

        rig.trigger()

        assertEquals(SosState.ACTIVE, rig.stored())
        assertEquals(1, rig.sentTo(1).size)
        assertEquals(1, rig.sentTo(2).size)
        val text = rig.sentTo(1).single()
        assertTrue(text, text.startsWith("Test User needs help. Location: https://maps.google.com/?q=10.00000,20.00000"))
        assertTrue(text.endsWith("call 112."))
        assertEquals(
            SosAlertStatus.Automatic(SosAlertSummary(sent = 2, waiting = 0, failed = 0, skipped = 0)),
            rig.dispatch.status.value,
        )
        // The position was good: no update follows, whatever arrives later.
        rig.source.emit()
        rig.advance(120_000)
        assertEquals(2, rig.gateway.attempts.size)
        assertTrue(rig.actions.all.none { it.type == SosActionType.LOCATION_UPDATE })
    }

    @Test
    fun `without a position the alert waits 3 seconds at most, and the trigger not at all`() = runTest {
        val rig = rig()

        rig.trigger(fix = false)

        // The emergency is active on time; the message is what waits.
        assertEquals(SosState.ACTIVE, rig.stored())
        assertEquals(SosAlertStatus.Preparing, rig.dispatch.status.value)
        rig.advance(2_999)
        assertEquals(0, rig.gateway.attempts.size)
        rig.advance(1)

        assertEquals(2, rig.gateway.attempts.size)
        assertTrue(rig.sentTo(1).single().contains("Location unavailable."))
    }

    @Test
    fun `a position that arrives inside the 3 seconds is used at once`() = runTest {
        val rig = rig()
        rig.trigger(fix = false)

        rig.advance(1_000)
        rig.source.emit()
        rig.advance(0)

        assertEquals(2, rig.gateway.attempts.size)
        assertTrue(rig.sentTo(1).single().contains("https://maps.google.com/?q="))
        rig.advance(120_000)
        assertTrue("no update: the alert had the position", rig.actions.all.none { it.type == SosActionType.LOCATION_UPDATE })
    }

    @Test
    fun `sent without a position, ONE update follows when a position arrives within 60 seconds`() = runTest {
        val rig = rig()
        rig.trigger(fix = false)
        rig.advance(3_000)
        rig.gateway.attempts.clear()

        rig.advance(20_000)
        rig.source.emit()
        rig.advance(0)

        assertEquals(1, rig.sentTo(1).size)
        assertEquals(1, rig.sentTo(2).size)
        assertTrue(rig.sentTo(1).single().startsWith("Update from Test User. Location: https://maps.google.com/?q="))
        // Later positions change nothing: one update, not a stream.
        rig.source.emit()
        rig.advance(120_000)
        assertEquals(2, rig.gateway.attempts.size)
    }

    @Test
    fun `a position that arrives after 60 seconds is not sent after the alert`() = runTest {
        val rig = rig()
        rig.trigger(fix = false)
        rig.advance(3_000)
        rig.gateway.attempts.clear()

        rig.advance(61_000)
        rig.source.emit()
        rig.advance(120_000)

        assertEquals(emptyList<Pair<String, String>>(), rig.gateway.attempts.toList())
    }

    @Test
    fun `without a location permission there is nothing to wait for`() = runTest {
        val rig = rig(granted = GrantedLocation.None)

        rig.trigger(fix = false)

        assertEquals(2, rig.gateway.attempts.size)
        assertTrue(rig.sentTo(1).single().contains("Location unavailable."))
    }

    @Test
    fun `a message that cannot be sent never holds up or undoes the emergency, and is retried`() = runTest {
        val rig = rig()
        rig.gateway.fallback = RETRYABLE

        rig.trigger()

        assertEquals(SosState.ACTIVE, rig.stored())
        assertTrue(rig.runner.state.value is SosRunState.Active)
        assertEquals(
            SosAlertStatus.Automatic(SosAlertSummary(sent = 0, waiting = 2, failed = 0, skipped = 0)),
            rig.dispatch.status.value,
        )

        rig.gateway.fallback = SmsOutcome.Sent
        rig.advance(30_000)

        assertEquals(listOf(SosActionState.SENT, SosActionState.SENT), rig.alertStates())
        assertEquals(2, rig.sentTo(1).size)
        assertEquals(SosAlertSummary(2, 0, 0, 0), (rig.dispatch.status.value as SosAlertStatus.Automatic).summary)
    }

    @Test
    fun `a contact who opted out is taken off when the fresh list arrives before their message left`() = runTest {
        val rig = rig(contacts = 3)
        // No position, so the alert waits up to 3 s; the server answers after 1 s: contact 2 is gone.
        rig.freshener.allowed = setOf("id-1", "id-3")
        rig.freshener.delayMillis = 1_000

        rig.trigger(fix = false)
        rig.advance(3_000)

        assertEquals(listOf(SosActionState.SENT, SosActionState.SKIPPED, SosActionState.SENT), rig.alertStates())
        assertEquals(0, rig.sentTo(2).size)
        assertEquals(SosAlertSummary(sent = 2, waiting = 0, failed = 0, skipped = 1), (rig.dispatch.status.value as SosAlertStatus.Automatic).summary)
    }

    @Test
    fun `the alert never waits for the server - a slow or absent answer changes nothing`() = runTest {
        val rig = rig()
        // The answer would take longer than the 1.5 s limit, and would take contact 2 off.
        rig.freshener.allowed = setOf("id-1")
        rig.freshener.delayMillis = 5_000

        rig.trigger()

        // Sent at the trigger, to both: the phone's own list decided.
        assertEquals(2, rig.gateway.attempts.size)
        rig.advance(10_000)
        assertEquals(listOf(SosActionState.SENT, SosActionState.SENT), rig.alertStates())
        assertEquals(1, rig.freshener.calls)
    }

    @Test
    fun `with no contact the screen says so and nothing is sent`() = runTest {
        val rig = rig(contacts = 0)

        rig.trigger()

        assertEquals(SosAlertStatus.NoContacts, rig.dispatch.status.value)
        assertEquals(0, rig.gateway.attempts.size)
    }

    @Test
    fun `without the permission the SMS app is opened once with every number and the alert`() = runTest {
        val rig = rig()
        rig.smsMode.mode = SmsMode.COMPOSER

        rig.trigger()

        val (phones, text) = rig.composer.shown.single()
        assertEquals(listOf(person(1).phoneE164, person(2).phoneE164), phones)
        assertTrue(text.startsWith("Test User needs help."))
        assertEquals(SosAlertStatus.Composer(contacts = 2), rig.dispatch.status.value)
        // The app itself sent nothing, and claims nothing was sent.
        assertEquals(0, rig.gateway.attempts.size)
        assertTrue(rig.alertStates().all { it == SosActionState.PENDING })

        // "Open SMS app again" on the screen.
        rig.dispatch.reopenComposer()
        assertEquals(2, rig.composer.shown.size)
        assertEquals(rig.composer.shown[0], rig.composer.shown[1])
    }

    @Test
    fun `cancel during the countdown sends nothing, also with the gate open`() = runTest {
        val rig = rig()
        rig.runner.start(SosEntryPoint.IN_APP)
        rig.advance(3_000)

        rig.runner.cancel()
        rig.advance(600_000)

        assertEquals(emptyList<SosAction>(), rig.actions.all)
        assertEquals(0, rig.gateway.attempts.size)
        assertEquals(SosAlertStatus.NotEnabled, rig.dispatch.status.value)
    }

    @Test
    fun `I am safe with tell my contacts sends the follow-up to those whose alert was sent`() = runTest {
        val rig = rig(contacts = 3)
        rig.gateway.answer(person(2).phoneE164, SmsOutcome.Final("rejected"))
        rig.gateway.fallback = SmsOutcome.Sent
        rig.gateway.answer(person(3).phoneE164, RETRYABLE)
        rig.trigger()
        rig.gateway.attempts.clear()

        assertTrue(rig.runner.markSafe(tellContacts = true))
        rig.advance(0)

        assertEquals(SosState.RESOLVED, rig.stored())
        assertEquals(listOf("Test User says they are safe now. Sent by the SafeRoute app."), rig.sentTo(1))
        assertEquals("their alert was refused", emptyList<String>(), rig.sentTo(2))
        assertEquals("their alert never left", emptyList<String>(), rig.sentTo(3))
        // The alert that was still waiting is not sent after the end.
        rig.advance(600_000)
        assertEquals(1, rig.gateway.attempts.size)
        // The host stayed until the follow-up had left, then ended.
        assertEquals("end", rig.host.events.last())
        assertEquals(SosRunState.Idle, rig.runner.state.value)
    }

    @Test
    fun `I am safe without tell my contacts sends nothing more`() = runTest {
        val rig = rig()
        rig.trigger()
        rig.gateway.attempts.clear()

        assertTrue(rig.runner.markSafe(tellContacts = false))
        rig.advance(600_000)

        assertEquals(0, rig.gateway.attempts.size)
        assertEquals("end", rig.host.events.last())
    }

    @Test
    fun `in composer mode I am safe opens the SMS app with the follow-up`() = runTest {
        val rig = rig()
        rig.smsMode.mode = SmsMode.COMPOSER
        rig.trigger()

        rig.runner.markSafe(tellContacts = true)
        rig.advance(0)

        assertEquals(2, rig.composer.shown.size)
        val (phones, text) = rig.composer.shown.last()
        assertEquals(2, phones.size)
        assertTrue(text.startsWith("Test User says they are safe now."))
    }

    @Test
    fun `after a process death the retries go on, and nobody who has the alert gets it again`() = runTest {
        val rig = rig()
        rig.gateway.answer(person(2).phoneE164, RETRYABLE, RETRYABLE, RETRYABLE)
        rig.trigger()
        assertEquals(listOf(SosActionState.SENT, SosActionState.FAILED_RETRYABLE), rig.alertStates())

        rig.restartProcess()
        rig.advance(10_000)
        assertEquals("nothing runs by itself in a dead process", 1, rig.sentTo(2).size)

        rig.runner.resume()
        rig.advance(60_000)

        assertEquals(listOf(SosActionState.SENT, SosActionState.SENT), rig.alertStates())
        assertEquals(1, rig.sentTo(1).size)
        assertFalse(rig.actions.all.any { it.type == SosActionType.LOCATION_UPDATE })
    }

    @Test
    fun `after a process death in composer mode the SMS app is not opened a second time`() = runTest {
        val rig = rig()
        rig.smsMode.mode = SmsMode.COMPOSER
        rig.trigger()
        assertEquals(1, rig.composer.shown.size)
        rig.composer.shown.clear()

        rig.restartProcess()
        rig.runner.resume()
        runCurrent()

        assertEquals("the new process opened nothing", 0, rig.composer.shown.size)
        assertEquals(SosAlertStatus.Composer(contacts = 2), rig.dispatch.status.value)
    }

    @Test
    fun `the Bengali setting sends the Bengali message, and no name sends the no-name wording`() = runTest {
        val rig = rig(contacts = 1)
        rig.settings.language = SosLanguage.BN
        rig.settings.name = null

        rig.trigger()

        assertTrue(rig.sentTo(1).single().startsWith("যিনি আপনাকে জরুরি পরিচিতি করেছেন, তাঁর সাহায্য দরকার।"))
    }

    @Test
    fun `what the screen may show about the messages never holds a name or a number`() = runTest {
        val rig = rig()
        rig.trigger()

        val printed = "${rig.dispatch.status.value} ${rig.actions.all} ${rig.runner.state.value}"

        assertFalse(printed.contains("Test Contact"))
        assertFalse(printed.contains("90000100"))
        assertFalse(printed.contains("10.0"))
    }
}
