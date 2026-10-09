// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.content.ComponentName
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.MainActivity
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.emergency.FakeSosHaptics
import com.saferoute.app.core.emergency.FakeSosAlertPolicy
import com.saferoute.app.core.emergency.FakeSosHost
import com.saferoute.app.core.emergency.SosEngine
import com.saferoute.app.core.emergency.SosLocationReport
import com.saferoute.app.core.emergency.SosRunState
import com.saferoute.app.core.emergency.SosRunner
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import com.saferoute.app.feature.home.EmergencyArm
import com.saferoute.app.feature.home.EmergencyDialog
import com.saferoute.app.feature.home.EmergencyDialogState
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private val ACTIVE = SosUi.Active(location = SosLocationReport.Precise, notificationsOff = false)

/** The SOS screens as pictures of a state: what is on them, how big, and what a tap reports. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class SosScreensTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<String>()
    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SafeRouteTheme { content() }
            }
        }
    }

    @Composable
    private fun Countdown(seconds: Int = 5, practice: Boolean = false, dialerMissing: Boolean = false) =
        SosCountdownScreen(
            secondsLeft = seconds,
            practice = practice,
            dialerMissing = dialerMissing,
            onCancel = { events += "cancel" },
            onCall112 = { events += "call" },
        )

    @Composable
    private fun Active(state: SosUi.Active = ACTIVE, locked: Boolean = false) = SosActiveScreen(
        state = state,
        locked = locked,
        dialerMissing = false,
        onSafe = { events += "safe" },
        onSafeConfirm = { events += "confirm" },
        onSafeDismiss = { events += "dismiss" },
        onContinue = { events += "continue" },
        onCall112 = { events += "call" },
    )

    private fun heightDp(tag: String): Float {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return node.size.height / node.layoutInfo.density.density
    }

    // --- hold to arm --------------------------------------------------------------------

    private fun showHold() {
        compose.mainClock.autoAdvance = false
        show { HoldToArmButton(onArmed = { events += "armed" }) }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `a hold released before two seconds arms nothing`() {
        showHold()

        compose.onNodeWithTag(HoldToArmTag).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_800)
        compose.onNodeWithTag(HoldToArmTag).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(3_000)

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a hold of two seconds arms exactly once, and a tap does nothing`() {
        showHold()

        // A short tap first: nothing.
        compose.onNodeWithTag(HoldToArmTag).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag(HoldToArmTag).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(emptyList<String>(), events)

        compose.onNodeWithTag(HoldToArmTag).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(2_300)
        assertEquals(listOf("armed"), events)
        // Holding on does not arm again.
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag(HoldToArmTag).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf("armed"), events)
    }

    @Test
    fun `the hold button has a name and an action for people who cannot hold a finger down`() {
        show { HoldToArmButton(onArmed = { events += "armed" }) }

        compose.onNodeWithContentDescription(string(R.string.sos_arm_hold))
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("armed"), events)
    }

    @Test
    fun `without consent the dialog offers no hold, says why, and leads to the setup`() {
        show {
            EmergencyDialog(
                state = EmergencyDialogState.OfferDialer,
                onCallEmergency = { events += "call" },
                onDismiss = {},
                arm = EmergencyArm(onArmed = null, onPractice = { events += "practice" }, onSetUp = { events += "setup" }),
            )
        }

        compose.onNodeWithTag(HoldToArmTag).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.sos_arm_not_set_up)).assertExists()
        compose.onNodeWithText(string(R.string.sos_arm_set_up)).performScrollTo().performClick()
        // Call 112 and a practice run need no consent.
        compose.onNodeWithText(string(R.string.sos_arm_practice)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()
        assertEquals(listOf("setup", "practice", "call"), events)
    }

    @Test
    fun `the dialog offers the hold, practice and the location note only where an SOS can start`() {
        show {
            EmergencyDialog(
                state = EmergencyDialogState.OfferDialer,
                onCallEmergency = { events += "call" },
                onDismiss = { events += "dismiss" },
                arm = EmergencyArm(onArmed = { events += "armed" }, onPractice = { events += "practice" }),
            )
        }

        compose.onNodeWithTag(HoldToArmTag).assertExists()
        // What location is used for is said BEFORE anything starts.
        compose.onNodeWithText(string(R.string.sos_arm_location_note)).assertExists()
        compose.onNodeWithText(string(R.string.not_emergency_service)).assertExists()
        compose.onNodeWithText(string(R.string.sos_arm_practice)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()
        assertEquals(listOf("practice", "call"), events)
    }

    @Test
    fun `without the arm part the dialog offers Call 112 only`() {
        show { EmergencyDialog(EmergencyDialogState.OfferDialer, onCallEmergency = {}, onDismiss = {}) }

        compose.onNodeWithTag(HoldToArmTag).assertDoesNotExist()
        compose.onAllNodesWithText(string(R.string.sos_arm_practice)).assertCountEquals(0)
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).assertIsDisplayed()
    }

    // --- countdown ----------------------------------------------------------------------

    @Test
    fun `the countdown shows the number, a 72 dp cancel, Call 112 and what SafeRoute is not`() {
        show { Countdown(seconds = 4) }

        compose.onNodeWithTag(SosCountdownNumberTag).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.sos_countdown_seconds_description, 4)).assertExists()
        assertTrue("cancel is ${heightDp(SosCancelTag)} dp tall", heightDp(SosCancelTag) >= 72f)
        compose.onNodeWithText(string(R.string.sos_not_emergency_service_call)).assertExists()
        compose.onAllNodesWithText(string(R.string.sos_practice_banner)).assertCountEquals(0)

        compose.onNodeWithTag(SosCancelTag).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performScrollTo().performClick()
        assertEquals(listOf("cancel", "call"), events)
    }

    @Test
    fun `at 200 percent font cancel and Call 112 can still be reached and stay big`() {
        show(fontScale = 2f) { Countdown() }

        compose.onNodeWithTag(SosCancelTag).performScrollTo().assertIsDisplayed()
        assertTrue(heightDp(SosCancelTag) >= 72f)
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("call"), events)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `in landscape cancel and Call 112 can still be reached`() {
        show { Countdown() }

        compose.onNodeWithTag(SosCancelTag).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("cancel", "call"), events)
    }

    @Test
    fun `a practice countdown carries the practice banner`() {
        show { Countdown(practice = true) }

        compose.onNodeWithText(string(R.string.sos_practice_banner)).assertIsDisplayed()
    }

    @Test
    fun `without a phone app the screens show the number instead of the button`() {
        show { Countdown(dialerMissing = true) }

        compose.onNodeWithText(string(R.string.emergency_dialog_no_dialer)).assertExists()
        compose.onAllNodesWithText(string(R.string.emergency_dialog_call)).assertCountEquals(0)
    }

    // --- active -------------------------------------------------------------------------

    @Test
    fun `the active screen says in words what is and is not happening`() {
        show { Active(ACTIVE.copy(notificationsOff = true)) }

        compose.onNodeWithText(string(R.string.sos_active_title)).assertIsDisplayed()
        // Honest until the SMS alerts exist: nobody is messaged.
        compose.onNodeWithText(string(R.string.sos_active_contacts_none)).assertExists()
        compose.onNodeWithText(string(R.string.sos_location_precise)).assertExists()
        compose.onNodeWithText(string(R.string.sos_active_notifications_off)).assertExists()
        compose.onNodeWithText(string(R.string.sos_not_emergency_service_call)).assertExists()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.sos_active_safe)).performScrollTo().performClick()
        assertEquals(listOf("call", "safe"), events)
    }

    @Test
    fun `every way of having no good position has its own sentence`() {
        val lines = mapOf(
            SosLocationReport.Approximate to string(R.string.sos_location_approximate),
            SosLocationReport.Searching to string(R.string.sos_location_searching),
            SosLocationReport.NoPermission to string(R.string.sos_location_no_permission),
            SosLocationReport.Unavailable to string(R.string.sos_location_unavailable),
            SosLocationReport.Stale(45) to string(R.string.sos_location_stale_seconds, 45),
            SosLocationReport.Stale(600) to string(R.string.sos_location_stale_minutes, 10),
        )
        var report: SosLocationReport by mutableStateOf(SosLocationReport.Precise)
        show { Active(ACTIVE.copy(location = report)) }

        for ((value, line) in lines) {
            report = value
            compose.onNodeWithText(line).assertExists()
        }
    }

    @Test
    fun `on the lock screen the way out says that the unlock comes first`() {
        show { Active(locked = true) }

        compose.onNodeWithText(string(R.string.sos_active_safe_locked)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.sos_active_safe)).assertCountEquals(0)
        // Statuses in words only: the screen is the same as when unlocked.
        compose.onNodeWithText(string(R.string.sos_active_contacts_none)).assertExists()
    }

    @Test
    fun `I am safe is confirmed in a dialog`() {
        show { Active(ACTIVE.copy(confirmingSafe = true)) }

        compose.onNodeWithText(string(R.string.sos_safe_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sos_safe_confirm_no)).performClick()
        compose.onNodeWithText(string(R.string.sos_safe_confirm_yes)).performClick()
        assertEquals(listOf("dismiss", "confirm"), events)
    }

    @Test
    fun `an SOS found after a restart says so and offers continue or I am safe`() {
        show { Active(ACTIVE.copy(recovered = true)) }

        compose.onNodeWithText(string(R.string.sos_recovered_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sos_recovered_continue)).performClick()
        assertEquals(listOf("continue"), events)
    }

    @Test
    fun `a practice active screen has the banner and no location or notification lines`() {
        show { Active(ACTIVE.copy(practice = true, notificationsOff = true)) }

        compose.onNodeWithText(string(R.string.sos_practice_banner)).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.sos_location_precise)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.sos_active_notifications_off)).assertCountEquals(0)
    }

    // --- the question and the end of a practice run --------------------------------------

    @Test
    fun `the question after a restart offers start now, cancel and Call 112`() {
        show {
            SosAskScreen(
                dialerMissing = false,
                onSendNow = { events += "send" },
                onCancel = { events += "cancel" },
                onCall112 = { events += "call" },
            )
        }

        compose.onNodeWithText(string(R.string.sos_ask_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sos_ask_send)).performScrollTo().performClick()
        compose.onNodeWithTag(SosCancelTag).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performScrollTo().performClick()
        assertEquals(listOf("send", "cancel", "call"), events)
    }

    @Test
    fun `practice ends with practice finished`() {
        show { SosPracticeFinishedScreen(onClose = { events += "close" }) }

        compose.onNodeWithText(string(R.string.sos_practice_finished_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sos_practice_close)).performClick()
        assertEquals(listOf("close"), events)
    }

    @Test
    fun `no SOS text calls a message delivered or read, or the user safe`() {
        val ids = R.string::class.java.fields
            .filter { it.name.startsWith("sos_") || it.name.startsWith("sms_") || it.name.startsWith("contacts_notice_") }
            .map { it.getInt(null) }
        assertTrue(ids.size > 60)
        // "Sent" is what the phone knows. Delivery, reading, and anybody's safety it does not.
        val overclaiming = Regex(
            "(was|were|has been|have been|is|are) (delivered|received|read)\\b|help is (coming|on the way)|(?<!say )you are (now )?safe\\b",
            RegexOption.IGNORE_CASE,
        )

        val offenders = ids.map { compose.activity.resources.getText(it).toString() }.filter { overclaiming.containsMatchIn(it) }

        assertEquals(emptyList<String>(), offenders)
    }
}

/**
 * The SOS flow in the real app, from the control on Home to the emergency screen and back:
 * the real runner, engine and database (in memory), fakes for the service and the vibration.
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class SosFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    val session: Session = FakeSession(SessionState.Ready)

    @Inject lateinit var runner: SosRunner

    @Inject lateinit var engine: SosEngine

    @Inject lateinit var host: FakeSosHost

    @Inject lateinit var haptics: FakeSosHaptics

    @Inject lateinit var policy: FakeSosAlertPolicy

    @Before
    fun setUp() {
        hilt.inject()
        // The user has agreed to the alerts notice, unless a test says otherwise.
        policy.open = true
    }

    private fun string(id: Int): String = compose.activity.getString(id)
    private fun nextStarted(): Intent? = shadowOf(compose.activity.application).nextStartedActivity

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out waiting until $what", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }

    @Test
    fun `opening the app with nothing going on opens no emergency screen`() {
        compose.waitForIdle()

        assertNull(nextStarted())
        assertEquals(emptyList<String>(), host.events.toList())
    }

    @Test
    fun `the SOS control opens the dialog, and a completed hold leads to the emergency screen`() {
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).performClick()
        compose.onNodeWithTag(HoldToArmTag).assertExists()
        // Opening the dialog starts nothing.
        assertNull(runBlocking { engine.current() })

        compose.onNodeWithTag(HoldToArmTag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        val started = nextStarted()
        assertEquals(ComponentName(compose.activity, EmergencyActivity::class.java), started?.component)
        assertEquals(SosOpenMode.START.name, started?.getStringExtra(EmergencyShortcut.EXTRA_MODE))
        // The dialog is gone; the countdown belongs to the emergency screen.
        compose.onNodeWithTag(HoldToArmTag).assertDoesNotExist()
    }

    @Test
    fun `without consent to the alerts notice the SOS control offers Call 112 and the way to the setup`() {
        policy.open = false

        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).performClick()

        compose.onNodeWithTag(HoldToArmTag).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sos_arm_set_up)).performScrollTo().performClick()
        compose.waitForIdle()
        // It leads to Emergency contacts, where the notice is; no emergency screen opened.
        compose.onNodeWithText(string(R.string.contacts_title)).assertIsDisplayed()
        assertNull(nextStarted())
        assertNull(runBlocking { engine.current() })
    }

    @Test
    fun `practice from the dialog opens the emergency screen in practice mode and starts nothing real`() {
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).performClick()
        compose.onNodeWithText(string(R.string.sos_arm_practice)).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(SosOpenMode.PRACTICE.name, nextStarted()?.getStringExtra(EmergencyShortcut.EXTRA_MODE))
        assertNull(runBlocking { engine.current() })
        assertEquals(emptyList<String>(), host.events.toList())
    }

    @Test
    fun `an emergency nobody is running opens the emergency screen when the app comes to the front`() {
        // As if the process had died: the record exists, the runner of this process is idle.
        runBlocking { engine.startCountdown(com.saferoute.app.core.emergency.SosEntryPoint.IN_APP) }
        assertEquals(SosRunState.Idle, runner.state.value)

        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)

        waitUntil("the emergency screen was started") {
            compose.waitForIdle()
            shadowOf(compose.activity.application).peekNextStartedActivity() != null
        }
        val started = nextStarted()
        assertEquals(ComponentName(compose.activity, EmergencyActivity::class.java), started?.component)
        assertEquals(SosOpenMode.OPEN.name, started?.getStringExtra(EmergencyShortcut.EXTRA_MODE))
        assertFalse("looking is not sending", "triggered" in haptics.events)
        runBlocking { engine.cancel() }
    }
}

/** The emergency screen itself, started the way the app starts it. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class SosEmergencyScreenTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = androidx.compose.ui.test.junit4.v2.createEmptyComposeRule()

    @Inject lateinit var runner: SosRunner

    @Inject lateinit var engine: SosEngine

    @Inject lateinit var host: FakeSosHost

    @Inject lateinit var policy: FakeSosAlertPolicy

    private val context: android.content.Context = ApplicationProvider.getApplicationContext()
    private fun string(id: Int): String = context.getString(id)

    @Before
    fun setUp() {
        hilt.inject()
        policy.open = true
    }

    private fun launch(mode: SosOpenMode) =
        androidx.test.core.app.ActivityScenario.launch<EmergencyActivity>(EmergencyShortcut.modeIntent(context, mode))

    @Test
    fun `started after the hold it shows the countdown over the lock screen, and cancel leaves nothing`() {
        val scenario = launch(SosOpenMode.START)

        compose.waitUntil(5_000) { runner.state.value is SosRunState.Countdown }
        compose.onNodeWithTag(SosCountdownNumberTag).assertIsDisplayed()
        assertEquals(listOf("begin"), host.events.toList())

        compose.onNodeWithTag(SosCancelTag).performClick()

        compose.waitUntil(5_000) { runner.state.value == SosRunState.Idle }
        assertNull(runBlocking { engine.current() })
        assertEquals("end", host.events.last())
        compose.waitUntil(5_000) {
            var finishing = false
            scenario.onActivity { finishing = it.isFinishing }
            finishing
        }
        scenario.close()
    }

    @Test
    fun `a practice run shows the banner and starts no service and no record`() {
        val scenario = launch(SosOpenMode.PRACTICE)

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(string(R.string.sos_practice_banner)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(SosCountdownNumberTag).assertIsDisplayed()
        assertEquals(SosRunState.Idle, runner.state.value)
        assertNull(runBlocking { engine.current() })
        assertEquals(emptyList<String>(), host.events.toList())

        compose.onNodeWithTag(SosCancelTag).performClick()
        scenario.close()
    }
}
