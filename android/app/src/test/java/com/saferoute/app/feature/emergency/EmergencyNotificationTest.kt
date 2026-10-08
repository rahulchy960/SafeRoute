// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The pinned emergency notification (P012f2): what is in it, when Android lets it show, and
 * the one rule for showing it (the switch is on AND Android allows it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EmergencyNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private fun string(id: Int): String = context.getString(id)

    // --- what is in it ------------------------------------------------------------------

    @Test
    fun `the channel is silent and of low importance`() {
        ensureEmergencyChannel(context)

        val channel = manager.getNotificationChannel(EMERGENCY_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.shouldShowLights())
        assertFalse(channel.canShowBadge())
        assertEquals(string(R.string.emergency_notification_channel), channel.name.toString())
    }

    @Test
    fun `it is ongoing, public on the lock screen, and shows fixed text only`() {
        val notification = buildEmergencyNotification(context)

        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertEquals(EMERGENCY_CHANNEL_ID, notification.channelId)
        // It is a plain notification: not the notification of a foreground service.
        assertEquals(0, notification.flags and Notification.FLAG_FOREGROUND_SERVICE)

        // Every text in it is a fixed resource. Nothing in it could be about a person.
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals(string(R.string.emergency_notification_title), title)
        assertEquals("SafeRoute emergency shortcut", title)
        assertEquals(string(R.string.emergency_notification_text), text)
        for (key in listOf(Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_INFO_TEXT)) {
            assertNull(key, notification.extras.getCharSequence(key))
        }
        assertFalse((title + text).contains(Regex("""\d{4,}|@|\+91""")))
    }

    @Test
    fun `call 112 opens the dialer and open SOS opens the emergency screen, both immutable`() {
        val notification = buildEmergencyNotification(context)

        assertEquals(
            listOf(string(R.string.emergency_notification_call), string(R.string.emergency_notification_open)),
            notification.actions.map { it.title.toString() },
        )
        val (call, open) = notification.actions.map { shadowOf(it.actionIntent) }

        // Straight to an activity: no receiver or service in between (no "trampoline").
        assertTrue(call.isActivityIntent)
        assertEquals(Intent.ACTION_DIAL, call.savedIntent.action)
        assertNotEquals(Intent.ACTION_CALL, call.savedIntent.action)
        assertEquals("tel:112", call.savedIntent.dataString)

        val emergency = ComponentName(context, EmergencyActivity::class.java)
        assertTrue(open.isActivityIntent)
        assertEquals(emergency, open.savedIntent.component)
        // A tap on the notification itself goes to the same place.
        assertEquals(emergency, shadowOf(notification.contentIntent).savedIntent.component)

        for (pending in listOf(call, open, shadowOf(notification.contentIntent))) {
            assertTrue(pending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
            assertEquals(0, pending.flags and PendingIntent.FLAG_MUTABLE)
        }
        // The dialer's PendingIntent is not the tile's or the notification's "open" one.
        assertNotEquals(call.requestCode, open.requestCode)
    }

    @Test
    fun `posting shows exactly one notification, posting again replaces it, cancel removes it`() {
        ShadowLog.clear()
        val notifier = AndroidEmergencyNotifier(context)

        notifier.post()
        notifier.post()
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertNotNull(shadowOf(manager).getNotification(EMERGENCY_NOTIFICATION_ID))

        notifier.cancel()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        // Nothing was started, and nothing was written to Logcat by the app.
        assertNull(shadowOf(application).nextStartedActivity)
        assertNull(shadowOf(application).nextStartedService)
        assertTrue(ShadowLog.getLogs().none { it.tag.contains("Emergency", ignoreCase = true) })
        Log.d("EmergencyNotificationTest", "log capture works")
        assertTrue(ShadowLog.getLogs().any { it.msg == "log capture works" })
    }

    // --- when Android lets it show ------------------------------------------------------

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `on Android 13 the permission decides, then the app switch, then the channel`() {
        val gate = AndroidNotificationGate(context)
        assertEquals(NotificationBlock.PermissionMissing, gate.block())

        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(NotificationBlock.None, gate.block())

        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(NotificationBlock.AppBlocked, gate.block())
        shadowOf(manager).setNotificationsEnabled(true)

        // The user switches the category off in system settings: its importance becomes "none".
        ensureEmergencyChannel(context)
        manager.getNotificationChannel(EMERGENCY_CHANNEL_ID).importance = NotificationManager.IMPORTANCE_NONE
        assertEquals(NotificationBlock.ChannelBlocked, gate.block())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun `before Android 13 there is no permission to ask for`() {
        val gate = AndroidNotificationGate(context)
        assertEquals(NotificationBlock.None, gate.block())

        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(NotificationBlock.AppBlocked, gate.block())
    }

    // --- the one rule -------------------------------------------------------------------

    private class Parts(scope: TestScope, enabled: Boolean, block: NotificationBlock) {
        val preferences = FakeEmergencyShortcutPreferences(enabled = enabled)
        val gate = FakeNotificationGate(block)
        val notifier = FakeEmergencyNotifier()
        val controller = EmergencyNotificationController(preferences, gate, notifier, scope.backgroundScope)
    }

    @Test
    fun `it is shown only when the switch is on and Android allows it`() = runTest(UnconfinedTestDispatcher()) {
        for (enabled in listOf(true, false)) {
            for (block in NotificationBlock.entries) {
                val parts = Parts(this, enabled, block)

                parts.controller.sync()

                val expected = if (enabled && block == NotificationBlock.None) "post" else "cancel"
                assertEquals("enabled=$enabled, $block", listOf(expected), parts.notifier.events)
            }
        }
    }

    @Test
    fun `app open, restart and update all use the same rule - swiped away, it comes back`() =
        runTest(UnconfinedTestDispatcher()) {
            val parts = Parts(this, enabled = true, block = NotificationBlock.None)

            // The app opens (MainActivity.onStart), the phone restarted, the app was updated:
            // each of them only calls sync.
            repeat(3) { parts.controller.sync() }
            assertEquals(listOf("post", "post", "post"), parts.notifier.events)

            // The user took the permission away in system settings; the next open removes it.
            parts.gate.block = NotificationBlock.PermissionMissing
            parts.controller.syncInBackground()
            runCurrent()
            assertEquals("cancel", parts.notifier.events.last())
        }

    @Test
    fun `with the switch off nothing is posted at boot, at update or at app open`() =
        runTest(UnconfinedTestDispatcher()) {
            val parts = Parts(this, enabled = false, block = NotificationBlock.None)

            repeat(3) { parts.controller.sync() }

            assertFalse("post" in parts.notifier.events)
        }

    @Test
    fun `once started it follows the switch - off removes it at once, and so does a cleared store`() =
        runTest(UnconfinedTestDispatcher()) {
            val parts = Parts(this, enabled = false, block = NotificationBlock.None)
            parts.controller.start()
            assertFalse(parts.notifier.shown)

            parts.preferences.setNotificationEnabled(true)
            assertTrue(parts.notifier.shown)

            // Turning the switch off, or signing out (which clears the stored switch).
            parts.preferences.setNotificationEnabled(false)
            assertEquals("cancel", parts.notifier.events.last())
        }

    @Test
    fun `the receiver answers to a restart and to an update of this app, and to nothing else`() {
        assertTrue(EmergencyShortcutReceiver.handles(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(EmergencyShortcutReceiver.handles(Intent.ACTION_MY_PACKAGE_REPLACED))
        for (other in listOf(Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_LOCKED_BOOT_COMPLETED, "anything", null)) {
            assertFalse(other.toString(), EmergencyShortcutReceiver.handles(other))
        }
    }

    // --- what is stored -----------------------------------------------------------------

    @Test
    fun `only two flags are stored, both off until the user or the app sets them`() = runTest {
        val file = File(context.cacheDir, "emergency-test-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = DataStoreEmergencyShortcutPreferences(dataStore)

        assertFalse(preferences.notificationEnabled.first())
        assertFalse(preferences.offerShown.first())

        preferences.setNotificationEnabled(true)
        preferences.setOfferShown()

        assertTrue(preferences.notificationEnabled.first())
        assertTrue(preferences.offerShown.first())
        assertEquals(
            setOf("emergency_notification_enabled", "emergency_notification_offer_shown"),
            dataStore.data.first().asMap().keys.map { it.name }.toSet(),
        )
        assertTrue(dataStore.data.first().asMap().values.all { it is Boolean })
    }

    // --- the one-time offer -------------------------------------------------------------

    @Test
    fun `the offer appears once, after the SOS dialog was closed, and never again`() =
        runTest(UnconfinedTestDispatcher()) {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val preferences = FakeEmergencyShortcutPreferences()
            val viewModel = ShortcutOfferViewModel(preferences)
            // Creating it (the app starting, Home showing) offers nothing.
            assertFalse(viewModel.offer.value)

            viewModel.onSosDialogClosed()
            assertTrue(viewModel.offer.value)
            assertTrue(preferences.offerShown.value)

            viewModel.onOfferDismiss()
            viewModel.onSosDialogClosed()
            assertFalse(viewModel.offer.value)
            Dispatchers.resetMain()
        }

    @Test
    fun `no offer when the shortcut is already on`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = ShortcutOfferViewModel(FakeEmergencyShortcutPreferences(enabled = true))

        viewModel.onSosDialogClosed()

        assertFalse(viewModel.offer.value)
        Dispatchers.resetMain()
    }
}
