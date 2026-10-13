// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.data.HiltTestCleanup
import com.saferoute.app.core.emergency.FakeSosAlertPolicy
import com.saferoute.app.core.emergency.FakeSosHost
import com.saferoute.app.core.emergency.InMemorySosStore
import com.saferoute.app.core.emergency.SosEngine
import com.saferoute.app.core.emergency.SosEntryPoint
import com.saferoute.app.core.emergency.SosHousekeeping
import com.saferoute.app.core.emergency.SosRunState
import com.saferoute.app.core.emergency.SosRunner
import com.saferoute.app.core.emergency.SosState
import com.saferoute.app.core.emergency.UuidV7Generator
import com.saferoute.app.core.location.FakeLocationEnvironment
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.session.BlockReason
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.settled
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Records what would be shown; shows nothing. */
class FakeRestartNotifier : RestartNotifier {
    val shown = mutableListOf<RestartReason>()
    var dismissed = 0

    override fun show(reason: RestartReason) {
        shown += reason
    }

    override fun dismiss() {
        dismissed++
    }
}

private val NOW: Instant = Instant.parse("2026-10-13T12:00:00Z")
private val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

private fun engineOn(store: InMemorySosStore) = SosEngine(store, CLOCK, UuidV7Generator(CLOCK, Random(1)))

/** What the phone does about an SOS when it has just restarted (P014c3). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SosRestartNoticeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = InMemorySosStore()
    private val engine = engineOn(store)
    private val notifier = FakeRestartNotifier()
    private val notice = SosRestartNotice(engine, notifier)

    // The pinned notification is switched off here: only the notice is looked at.
    private fun controller(scope: CoroutineScope) = EmergencyNotificationController(
        FakeEmergencyShortcutPreferences(enabled = false),
        FakeNotificationGate(),
        FakeEmergencyNotifier(),
        scope,
    )

    @Test
    fun `with no emergency on the phone a restart shows nothing`() = runTest {
        assertFalse(notice.check(RestartReason.PHONE_RESTARTED))
        assertEquals(emptyList<RestartReason>(), notifier.shown)
    }

    @Test
    fun `an SOS whose alert went out is announced once, and the record is left exactly as it was`() = runTest {
        engine.startCountdown(SosEntryPoint.TILE)
        engine.trigger(force = true)
        val triggered = store.all.single()
        assertEquals(SosState.TRIGGERED_LOCAL, triggered.state)

        assertTrue(notice.check(RestartReason.PHONE_RESTARTED))
        assertEquals(listOf(RestartReason.PHONE_RESTARTED), notifier.shown)
        assertEquals("looking changes nothing", listOf(triggered), store.all)

        engine.markActive()
        val active = store.all.single()
        assertTrue(notice.check(RestartReason.APP_UPDATED))
        assertEquals(listOf(RestartReason.PHONE_RESTARTED, RestartReason.APP_UPDATED), notifier.shown)
        assertEquals(listOf(active), store.all)
    }

    @Test
    fun `a countdown that never finished, and an SOS that is over, are not announced`() = runTest {
        // Nothing was sent: the app asks "Send now or Cancel" when it is next opened.
        engine.startCountdown(SosEntryPoint.IN_APP)
        assertFalse(notice.check(RestartReason.PHONE_RESTARTED))

        engine.trigger(force = true)
        engine.resolve()
        assertFalse(notice.check(RestartReason.PHONE_RESTARTED))
        assertEquals(emptyList<RestartReason>(), notifier.shown)
    }

    @Test
    fun `the receiver reacts to a restart and to an update of the app, and to nothing else`() = runTest {
        engine.startCountdown(SosEntryPoint.WIDGET)
        engine.trigger(force = true)
        val controller = controller(backgroundScope)

        EmergencyShortcutReceiver.react(Intent.ACTION_LOCKED_BOOT_COMPLETED, controller, notice)
        EmergencyShortcutReceiver.react(Intent.ACTION_PACKAGE_REPLACED, controller, notice)
        EmergencyShortcutReceiver.react("anything", controller, notice)
        EmergencyShortcutReceiver.react(null, controller, notice)
        assertEquals("not before the first unlock, and not for other apps", emptyList<RestartReason>(), notifier.shown)

        EmergencyShortcutReceiver.react(Intent.ACTION_BOOT_COMPLETED, controller, notice)
        EmergencyShortcutReceiver.react(Intent.ACTION_MY_PACKAGE_REPLACED, controller, notice)
        assertEquals(listOf(RestartReason.PHONE_RESTARTED, RestartReason.APP_UPDATED), notifier.shown)
    }

    @Test
    fun `nothing at boot continues the SOS, starts a service or asks for a position`() {
        // The code that runs at boot holds no way to do so: it does not know the runner, the
        // host, the trail or a service.
        for (name in listOf("SosRestartNotice.kt", "EmergencyNotificationController.kt")) {
            val code = File("src/main/java/com/saferoute/app/feature/emergency/$name").readLines()
                .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }
                .joinToString("\n")
            val forbidden = listOf(
                "SosRunner", "SosHost", "SosTrail", "SosDispatch", "startForegroundService", "startService",
                "startActivity", "SosForegroundService", "LocationRepository", "TrailLocationSource",
                "Log.", "println", "Timber",
            )
            for (word in forbidden) assertFalse("$name must not use $word", code.contains(word))
        }
        // No direct boot: the app's database is readable only after the first unlock.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("directBootAware"))
        assertFalse(manifest.contains("LOCKED_BOOT_COMPLETED"))
        assertNotNull(context)
    }

    @Test
    fun `the notification is urgent, public, fixed text, and offers continue, I am safe and Call 112`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureSosRestartChannel(context)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(SOS_RESTART_CHANNEL_ID).importance)

        for (reason in RestartReason.entries) {
            val notification = buildSosRestartNotification(context, reason)
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

            assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
            assertEquals(SOS_RESTART_CHANNEL_ID, notification.channelId)
            assertEquals(
                context.getString(
                    if (reason == RestartReason.PHONE_RESTARTED) R.string.sos_restart_title_phone else R.string.sos_restart_title_update,
                ),
                title,
            )
            assertEquals(context.getString(R.string.sos_restart_text), text)
            // Shown on the lock screen: nothing in it could be about a person or a place.
            assertFalse((title + text).contains(Regex("""\d{4,}|@|\+91|https?://""")))
            assertTrue("it says that nothing is running", text.contains("not running"))
            assertTrue(text.contains("112"))

            assertEquals(
                listOf(R.string.sos_recovered_continue, R.string.sos_active_safe, R.string.emergency_notification_call)
                    .map { context.getString(it) },
                notification.actions.map { it.title.toString() },
            )
            val (proceed, safe, call) = notification.actions.map { shadowOf(it.actionIntent) }
            val emergency = ComponentName(context, EmergencyActivity::class.java)
            for (pending in listOf(proceed, safe, shadowOf(notification.contentIntent))) {
                assertTrue("an activity, with no receiver or service in between", pending.isActivityIntent)
                assertEquals(emergency, pending.savedIntent.component)
            }
            assertEquals(SosOpenMode.OPEN.name, proceed.savedIntent.getStringExtra(EmergencyShortcut.EXTRA_MODE))
            assertEquals(SosOpenMode.SAFE.name, safe.savedIntent.getStringExtra(EmergencyShortcut.EXTRA_MODE))
            assertEquals(Intent.ACTION_DIAL, call.savedIntent.action)
            assertEquals("tel:112", call.savedIntent.dataString)
            for (pending in listOf(proceed, safe, call)) {
                assertTrue(pending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
                assertEquals(0, pending.flags and PendingIntent.FLAG_MUTABLE)
            }
        }
    }

    @Test
    fun `showing posts one notification, and the running SOS or its end takes it away`() {
        val application: Application = ApplicationProvider.getApplicationContext()
        val manager = application.getSystemService(NotificationManager::class.java)
        val notifier = AndroidRestartNotifier(application)

        notifier.show(RestartReason.PHONE_RESTARTED)
        notifier.show(RestartReason.PHONE_RESTARTED)
        assertNotNull(shadowOf(manager).getNotification(SOS_RESTART_NOTIFICATION_ID))
        assertEquals(1, shadowOf(manager).allNotifications.size)

        // The SOS is picked up again (Continue): the host starts, and the notice goes.
        val host = AndroidSosHost(application, FakeLocationEnvironment(granted = GrantedLocation.Precise)) {
            error("not needed while the service starts")
        }
        host.begin()
        assertNull(shadowOf(manager).getNotification(SOS_RESTART_NOTIFICATION_ID))

        notifier.show(RestartReason.APP_UPDATED)
        host.end()
        assertNull("I am safe ends it too", shadowOf(manager).getNotification(SOS_RESTART_NOTIFICATION_ID))

        notifier.show(RestartReason.PHONE_RESTARTED)
        notifier.dismiss()
        assertNull(shadowOf(manager).getNotification(SOS_RESTART_NOTIFICATION_ID))
    }
}

/** A sign-out or a blocked account while an SOS is going on: the SOS finishes first. */
@OptIn(ExperimentalCoroutinesApi::class)
class SosWipeAfterEmergencyTest {

    private val store = InMemorySosStore()
    private val engine = engineOn(store)

    private fun kotlinx.coroutines.test.TestScope.housekeeping(session: FakeSession) = SosHousekeeping(
        session,
        store,
        engine,
        CLOCK,
        {},
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
    )

    @Test
    fun `waiting for no emergency returns at once when there is none`() = runTest {
        engine.awaitNoEmergency()

        engine.startCountdown(SosEntryPoint.IN_APP)
        engine.cancel()
        engine.awaitNoEmergency()
    }

    @Test
    fun `it waits through the countdown and the active SOS, and returns on I am safe`() = runTest(UnconfinedTestDispatcher()) {
        engine.startCountdown(SosEntryPoint.NOTIFICATION)
        val waiting = async { engine.awaitNoEmergency() }
        assertFalse(waiting.isCompleted)

        engine.trigger(force = true)
        engine.markActive()
        assertFalse("still going on", waiting.isCompleted)

        assertTrue(engine.resolve())
        assertTrue(waiting.isCompleted)
    }

    @Test
    fun `a blocked account during an SOS wipes nothing until the user says I am safe`() = runTest {
        val session = FakeSession(SessionState.Ready)
        housekeeping(session).start()
        engine.startCountdown(SosEntryPoint.TILE)
        engine.trigger(force = true)
        engine.markActive()

        session.setState(SessionState.Blocked(BlockReason.ACCOUNT_DELETED))

        assertEquals("the SOS goes on", SosState.ACTIVE, store.all.single().state)

        assertTrue(engine.resolve())
        assertEquals("and afterwards nothing is left", emptyList<Any>(), store.all)
    }

    @Test
    fun `a forced sign-out during the countdown wipes after the cancel`() = runTest {
        val session = FakeSession(SessionState.Ready)
        housekeeping(session).start()
        engine.startCountdown(SosEntryPoint.WIDGET)

        session.setState(SessionState.SignedOut)
        assertEquals(1, store.all.size)

        assertTrue(engine.cancel())
        assertEquals(emptyList<Any>(), store.all)
    }

    @Test
    fun `a process that dies in between waits the same way after its next start`() = runTest {
        engine.startCountdown(SosEntryPoint.IN_APP)
        engine.trigger(force = true)
        // The new process finds the account blocked and the SOS still on the phone.
        val session = FakeSession(SessionState.Blocked(BlockReason.UNDER_18))
        housekeeping(session).start()
        assertEquals(1, store.all.size)

        assertTrue(engine.resolve())
        assertEquals(emptyList<Any>(), store.all)
    }

    @Test
    fun `signed in again before the SOS ended, the waiting wipe is dropped`() = runTest {
        val session = FakeSession(SessionState.Ready)
        housekeeping(session).start()
        engine.startCountdown(SosEntryPoint.IN_APP)
        engine.trigger(force = true)

        session.setState(SessionState.SignedOut)
        session.setState(SessionState.Ready)
        assertTrue(engine.resolve())

        assertEquals("kept like every finished SOS, for 30 days", SosState.RESOLVED, store.all.single().state)
    }

    @Test
    fun `without an emergency the wipe happens at once, as before`() = runTest {
        val session = FakeSession(SessionState.Ready)
        housekeeping(session).start()
        engine.startCountdown(SosEntryPoint.IN_APP)
        engine.trigger(force = true)
        engine.resolve()
        assertEquals(1, store.all.size)

        session.setState(SessionState.SignedOut)

        assertEquals(emptyList<Any>(), store.all)
    }
}

/** "I'm safe" from a notification on a locked phone, in the real emergency screen. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class SosSafeFromNotificationTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 0)
    val cleanup = HiltTestCleanup()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var runner: SosRunner

    @Inject lateinit var engine: SosEngine

    @Inject lateinit var host: FakeSosHost

    @Inject lateinit var policy: FakeSosAlertPolicy

    @Inject lateinit var restart: FakeRestartNotifier

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun string(id: Int): String = context.getString(id)
    private fun confirmationShown() =
        compose.onAllNodesWithText(string(R.string.sos_safe_confirm_title)).fetchSemanticsNodes().isNotEmpty()

    @Before
    fun setUp() {
        hilt.inject()
        policy.open = true
    }

    @Test
    fun `on a locked phone I am safe from a notification asks for the unlock before the confirmation`() {
        // An SOS that was active when the phone restarted: on the phone, and nobody runs it.
        runBlocking {
            engine.startCountdown(SosEntryPoint.TILE)
            engine.trigger(force = true)
            engine.markActive()
        }
        assertEquals(SosRunState.Idle, runner.state.value)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        shadowOf(keyguard).setKeyguardLocked(true)

        val scenario = ActivityScenario.launch<EmergencyActivity>(EmergencyShortcut.modeIntent(context, SosOpenMode.SAFE))
        compose.settled { runner.state.value is SosRunState.Active }
        compose.waitForIdle()

        assertFalse("locked: no way to end the SOS yet", confirmationShown())
        assertEquals(SosState.ACTIVE, runBlocking { engine.current() }?.state)

        shadowOf(keyguard).setKeyguardLocked(false)
        compose.settled { confirmationShown() }
        // And still nothing ended by itself: the user has to confirm.
        assertEquals(SosState.ACTIVE, runBlocking { engine.current() }?.state)
        runBlocking { runner.markSafe() }
        scenario.close()
    }

    @Test
    fun `on an unlocked phone it leads straight to the confirmation`() {
        runBlocking {
            engine.startCountdown(SosEntryPoint.IN_APP)
            engine.trigger(force = true)
            engine.markActive()
        }

        val scenario = ActivityScenario.launch<EmergencyActivity>(EmergencyShortcut.modeIntent(context, SosOpenMode.SAFE))

        compose.settled { confirmationShown() }
        assertEquals(SosState.ACTIVE, runBlocking { engine.current() }?.state)
        runBlocking { runner.markSafe() }
        scenario.close()
    }
}
