// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import com.saferoute.app.core.data.SosContact
import com.saferoute.app.core.emergency.SmsMode
import com.saferoute.app.core.emergency.SmsOutcome
import com.saferoute.app.core.emergency.SosAlertStatus
import com.saferoute.app.core.emergency.SosAlertSummary
import com.saferoute.app.core.emergency.SosEntryPoint
import com.saferoute.app.core.emergency.SosLocationReport
import com.saferoute.app.core.emergency.SosRecord
import com.saferoute.app.core.emergency.SosRig
import com.saferoute.app.core.emergency.SosRunState
import com.saferoute.app.core.emergency.SosState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emergency screen's state, with the real runner, engine and trail on fakes of the
 * phone's parts, in virtual time. "The screen was re-created" is a second call of `onShown`;
 * "the process died" is a new rig process and a new ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val gate = FakeNotificationGate()

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.(SosRig) -> Unit) = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        block(SosRig(this))
    }

    private fun SosRig.viewModel() = SosViewModel(runner, trail, gate, haptics, dispatch, appScope)

    private fun SosRig.open(mode: SosOpenMode, fresh: Boolean = true): SosViewModel =
        viewModel().also {
            it.onShown(mode, fresh)
            advance(0)
        }

    @Test
    fun `opened with nothing running it shows the options and starts nothing`() = test { rig ->
        val viewModel = rig.open(SosOpenMode.OPEN)

        assertEquals(SosUi.Options, viewModel.ui.value)
        assertEquals(emptyList<SosRecord>(), rig.store.all)
        assertEquals(emptyList<String>(), rig.host.events.toList())
    }

    @Test
    fun `a completed hold starts the countdown, which counts down and becomes active`() = test { rig ->
        val viewModel = rig.open(SosOpenMode.START)

        assertEquals(SosUi.Countdown(secondsLeft = 5), viewModel.ui.value)
        rig.advance(2_000)
        assertEquals(SosUi.Countdown(secondsLeft = 3), viewModel.ui.value)

        rig.advance(3_000)
        val active = viewModel.ui.value as SosUi.Active
        assertFalse(active.practice)
        assertFalse(active.recovered)
        assertEquals(SosLocationReport.Searching, active.location)
        assertEquals(SosState.ACTIVE, rig.stored())
    }

    @Test
    fun `cancel in the countdown closes the screen and leaves nothing`() = test { rig ->
        val viewModel = rig.open(SosOpenMode.START)
        rig.advance(1_500)

        viewModel.onCancel()
        rig.advance(0)

        assertEquals(SosUi.Closed, viewModel.ui.value)
        assertEquals(emptyList<SosRecord>(), rig.store.all)
        rig.advance(60_000)
        assertFalse("triggered" in rig.haptics.events)
    }

    @Test
    fun `a rotated or re-created screen never starts a second emergency`() = test { rig ->
        val viewModel = rig.open(SosOpenMode.START)
        viewModel.onCancel()
        rig.advance(0)

        // The same screen comes back (rotation keeps the ViewModel) ...
        viewModel.onShown(SosOpenMode.START, fresh = true)
        // ... and Android re-creates it from its saved state with the same START intent.
        val recreated = rig.open(SosOpenMode.START, fresh = false)

        assertEquals(emptyList<SosRecord>(), rig.store.all)
        assertEquals(SosUi.Options, recreated.ui.value)
    }

    @Test
    fun `the active screen reports the location in words and says when notifications are off`() = test { rig ->
        gate.block = NotificationBlock.PermissionMissing
        val viewModel = rig.open(SosOpenMode.START)
        rig.advance(5_000)
        rig.source.emit()
        rig.advance(0)

        val active = viewModel.ui.value as SosUi.Active
        assertEquals(SosLocationReport.Precise, active.location)
        assertTrue(active.notificationsOff)

        // Coming back to the screen later reads the location again: now it is old.
        rig.advance(90_000)
        viewModel.onShown(SosOpenMode.OPEN, fresh = false)
        rig.advance(0)
        assertEquals(SosLocationReport.Stale(ageSeconds = 90), (viewModel.ui.value as SosUi.Active).location)
    }

    @Test
    fun `I am safe asks first, and only the confirmation ends the SOS`() = test { rig ->
        val viewModel = rig.open(SosOpenMode.START)
        rig.advance(5_000)

        viewModel.onSafeClick()
        rig.advance(0)
        assertTrue((viewModel.ui.value as SosUi.Active).confirmingSafe)
        assertEquals(SosState.ACTIVE, rig.stored())

        viewModel.onSafeDismiss()
        rig.advance(0)
        assertFalse((viewModel.ui.value as SosUi.Active).confirmingSafe)

        viewModel.onSafeClick()
        viewModel.onSafeConfirm()
        rig.advance(0)
        assertEquals(SosUi.Closed, viewModel.ui.value)
        assertEquals(SosState.RESOLVED, rig.stored())
        assertEquals("end", rig.host.events.last())
    }

    @Test
    fun `after a process death in the countdown the screen shows the SAME countdown`() = test { rig ->
        rig.open(SosOpenMode.START)
        rig.advance(2_000)
        rig.restartProcess()

        val viewModel = rig.open(SosOpenMode.OPEN, fresh = false)

        assertEquals(SosUi.Countdown(secondsLeft = 3), viewModel.ui.value)
    }

    @Test
    fun `a countdown that ended while the app was closed is asked about - start now`() = test { rig ->
        rig.open(SosOpenMode.START)
        rig.advance(2_000)
        rig.restartProcess()
        rig.advance(600_000)

        val viewModel = rig.open(SosOpenMode.OPEN)

        assertEquals(SosUi.AskSendOrCancel, viewModel.ui.value)
        assertEquals("looking at the question starts nothing", SosState.COUNTDOWN, rig.stored())
        assertEquals(emptyList<String>(), rig.host.events.toList())

        viewModel.onSendNow()
        rig.advance(0)
        assertTrue(viewModel.ui.value is SosUi.Active)
        assertEquals(SosState.ACTIVE, rig.stored())
    }

    @Test
    fun `a countdown that ended while the app was closed is asked about - cancel`() = test { rig ->
        rig.open(SosOpenMode.START)
        rig.restartProcess()
        rig.advance(600_000)
        val viewModel = rig.open(SosOpenMode.OPEN)

        viewModel.onCancel()
        rig.advance(0)

        assertEquals(SosUi.Closed, viewModel.ui.value)
        assertEquals(emptyList<SosRecord>(), rig.store.all)
    }

    @Test
    fun `an SOS found still active says so, and continue keeps it running`() = test { rig ->
        rig.open(SosOpenMode.START)
        rig.advance(5_000)
        rig.restartProcess()
        rig.advance(3_600_000)

        val viewModel = rig.open(SosOpenMode.OPEN)

        assertTrue((viewModel.ui.value as SosUi.Active).recovered)
        viewModel.onContinue()
        rig.advance(0)
        val active = viewModel.ui.value as SosUi.Active
        assertFalse(active.recovered)
        assertEquals(SosState.ACTIVE, rig.stored())
        assertTrue(rig.runner.state.value is SosRunState.Active)
    }

    @Test
    fun `I am safe on the notification opens the confirmation, and does not end anything by itself`() = test { rig ->
        rig.open(SosOpenMode.START)
        rig.advance(5_000)

        val viewModel = rig.open(SosOpenMode.SAFE)

        assertTrue((viewModel.ui.value as SosUi.Active).confirmingSafe)
        assertEquals(SosState.ACTIVE, rig.stored())
    }

    @Test
    fun `practice runs the whole flow and touches nothing real`() = test { rig ->
        val viewModel = rig.open(SosOpenMode.PRACTICE)

        assertEquals(SosUi.Countdown(secondsLeft = 5, practice = true), viewModel.ui.value)
        rig.advance(4_000)
        assertEquals(SosUi.Countdown(secondsLeft = 1, practice = true), viewModel.ui.value)
        rig.advance(1_000)
        assertTrue((viewModel.ui.value as SosUi.Active).practice)

        viewModel.onSafeClick()
        rig.advance(0)
        assertTrue((viewModel.ui.value as SosUi.Active).confirmingSafe)
        viewModel.onSafeConfirm()
        rig.advance(0)
        assertEquals(SosUi.PracticeFinished, viewModel.ui.value)
        viewModel.onClose()
        rig.advance(0)
        assertEquals(SosUi.Closed, viewModel.ui.value)

        // No record, no service, no location, no real emergency at any moment.
        assertEquals(emptyList<SosRecord>(), rig.store.all)
        assertEquals(emptyList<String>(), rig.host.events.toList())
        assertEquals(emptyList<Pair<Boolean, Long>>(), rig.source.starts)
        assertEquals(SosRunState.Idle, rig.runner.state.value)
        // It can be felt like the real one.
        assertEquals(List(5) { "tick" } + "triggered", rig.haptics.events.toList())
    }

    @Test
    fun `cancel during a practice countdown ends the practice`() = test { rig ->
        val viewModel = rig.open(SosOpenMode.PRACTICE)
        rig.advance(2_000)

        viewModel.onCancel()
        rig.advance(10_000)

        assertEquals(SosUi.Closed, viewModel.ui.value)
        assertEquals(emptyList<SosRecord>(), rig.store.all)
        assertFalse("triggered" in rig.haptics.events)
    }

    @Test
    fun `a practice run never replaces or hides a real SOS`() = test { rig ->
        rig.runner.start(SosEntryPoint.IN_APP)
        rig.advance(5_000)

        val viewModel = rig.open(SosOpenMode.PRACTICE)
        rig.advance(10_000)

        val active = viewModel.ui.value as SosUi.Active
        assertFalse(active.practice)
        assertEquals(SosState.ACTIVE, rig.stored())
    }

    // --- the messages (the gate is opened by these tests; in the app it is closed) ------------

    private fun SosRig.withContacts(count: Int) {
        contacts.list = (1..count).map {
            SosContact("id-$it", "Test Contact $it", "+9190000100${it.toString().padStart(2, '0')}")
        }
        policy.allowed = true
    }

    private fun SosRig.safeMessages() = gateway.attempts.count { it.second.contains("safe now") }

    @Test
    fun `with alerts not switched on the screen says so and I am safe offers nothing to tell`() = test { rig ->
        rig.contacts.list = listOf(SosContact("id-1", "Test Contact 1", "+919000010001"))
        val viewModel = rig.open(SosOpenMode.START)
        rig.advance(10_000)

        val active = viewModel.ui.value as SosUi.Active
        assertEquals(SosAlertStatus.NotEnabled, active.alerts)
        assertFalse(active.canTellContacts)
        assertEquals(0, rig.gateway.attempts.size)
    }

    @Test
    fun `the active screen shows the counts of the alerts as they change`() = test { rig ->
        rig.withContacts(2)
        rig.gateway.answer("+919000010002", SmsOutcome.Retryable("no_service"))
        val viewModel = rig.open(SosOpenMode.START)
        rig.advance(2_000)
        rig.source.emit()
        rig.advance(3_000)

        val first = viewModel.ui.value as SosUi.Active
        assertEquals(SosAlertStatus.Automatic(SosAlertSummary(sent = 1, waiting = 1, failed = 0, skipped = 0)), first.alerts)
        assertTrue(first.canTellContacts)

        rig.advance(30_000)
        val later = viewModel.ui.value as SosUi.Active
        assertEquals(SosAlertSummary(sent = 2, waiting = 0, failed = 0, skipped = 0), (later.alerts as SosAlertStatus.Automatic).summary)
    }

    @Test
    fun `I am safe tells the alerted contacts by default`() = test { rig ->
        rig.withContacts(2)
        val viewModel = rig.open(SosOpenMode.START)
        rig.advance(2_000)
        rig.source.emit()
        rig.advance(3_000)

        viewModel.onSafeClick()
        rig.advance(0)
        assertTrue((viewModel.ui.value as SosUi.Active).tellContacts)
        viewModel.onSafeConfirm()
        rig.advance(0)

        assertEquals(SosUi.Closed, viewModel.ui.value)
        assertEquals(SosState.RESOLVED, rig.stored())
        assertEquals(2, rig.safeMessages())
    }

    @Test
    fun `with tell my contacts unticked I am safe sends nothing more`() = test { rig ->
        rig.withContacts(2)
        val viewModel = rig.open(SosOpenMode.START)
        rig.advance(2_000)
        rig.source.emit()
        rig.advance(3_000)

        viewModel.onSafeClick()
        viewModel.onTellContactsChange(false)
        rig.advance(0)
        assertFalse((viewModel.ui.value as SosUi.Active).tellContacts)
        viewModel.onSafeConfirm()
        rig.advance(600_000)

        assertEquals(SosState.RESOLVED, rig.stored())
        assertEquals(0, rig.safeMessages())
    }

    @Test
    fun `in composer mode the screen offers the SMS app again`() = test { rig ->
        rig.withContacts(2)
        rig.smsMode.mode = SmsMode.COMPOSER
        val viewModel = rig.open(SosOpenMode.START)
        rig.advance(2_000)
        rig.source.emit()
        rig.advance(3_000)

        assertEquals(SosAlertStatus.Composer(contacts = 2), (viewModel.ui.value as SosUi.Active).alerts)
        assertEquals(1, rig.composer.shown.size)

        viewModel.onOpenComposer()

        assertEquals(2, rig.composer.shown.size)
        assertEquals(0, rig.gateway.attempts.size)
    }
}
