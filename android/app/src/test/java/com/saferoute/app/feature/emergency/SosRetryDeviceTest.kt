// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The phone's side of "keep trying while the SOS is active" (P014b7): the one notification
 * about unsent alerts, and the two signals that ask for an extra try.
 */
@RunWith(AndroidJUnit4::class)
class SosRetryDeviceTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val signals = AndroidRetrySignals(application)
    private fun string(id: Int): String = application.getString(id)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After
    fun tearDown() = signals.stop()

    /**
     * On a phone ACCESS_NETWORK_STATE is granted at install (it is in the merged manifest).
     * The test framework grants nothing by itself, so a test that needs it says so.
     */
    private fun grantNetworkState() = shadowOf(application).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)

    @Test
    fun `the notification says the fixed sentence and nothing about anyone`() {
        val notification = buildSosUnsentNotification(application)

        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals("Some alerts have not been sent", title)
        assertEquals("Call 112 or send a text yourself.", text)
        // Nothing else is in it: no sub-text, no big text, no people, no place.
        for (key in listOf(Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_INFO_TEXT)) {
            assertNull(key, notification.extras.getCharSequence(key))
        }
        assertFalse((title + text).any { it.isDigit() && it !in "112" })
        // Fixed text may be shown on the lock screen.
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertEquals(SOS_UNSENT_CHANNEL_ID, notification.channelId)
    }

    @Test
    fun `a tap opens the emergency screen and the one button opens the dialer with 112`() {
        val notification = buildSosUnsentNotification(application)

        val open = shadowOf(notification.contentIntent)
        assertTrue(open.isImmutable && open.isActivity)
        assertEquals(ComponentName(application, EmergencyActivity::class.java), open.savedIntent.component)
        assertEquals(SosOpenMode.OPEN.name, open.savedIntent.getStringExtra(EmergencyShortcut.EXTRA_MODE))

        val action = notification.actions.single()
        assertEquals(string(R.string.emergency_notification_call), action.title.toString())
        val dial = shadowOf(action.actionIntent).savedIntent
        // The dialer, never a call.
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:112", dial.dataString)
    }

    @Test
    fun `showing posts it once on a high-importance channel, and dismissing removes it`() {
        val notifier = AndroidUnsentAlertNotifier(application)

        notifier.show()
        notifier.show()

        assertNotNull(shadowOf(manager).getNotification(SOS_UNSENT_NOTIFICATION_ID))
        assertEquals(1, shadowOf(manager).allNotifications.count { it.channelId == SOS_UNSENT_CHANNEL_ID })
        val channel = manager.getNotificationChannel(SOS_UNSENT_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(string(R.string.sos_unsent_channel), channel.name.toString())

        notifier.dismiss()
        assertNull(shadowOf(manager).getNotification(SOS_UNSENT_NOTIFICATION_ID))
    }

    @Test
    fun `airplane mode switched off is a signal, and switched on is not`() {
        var count = 0
        signals.start { count++ }
        // A network that is already there when listening begins may report itself once.
        idle()
        val atStart = count

        application.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", true))
        idle()
        assertEquals("airplane mode on: nothing can be sent", atStart, count)

        application.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", false))
        idle()
        assertEquals(atStart + 1, count)
    }

    @Test
    fun `a network that becomes available is a signal`() {
        grantNetworkState()
        var count = 0
        signals.start { count++ }
        idle()
        val atStart = count
        val connectivity = application.getSystemService(ConnectivityManager::class.java)
        val callbacks = shadowOf(connectivity).networkCallbacks
        assertEquals("one callback is held while listening", 1, callbacks.size)

        callbacks.single().onAvailable(connectivity.activeNetwork!!)

        assertEquals(atStart + 1, count)
    }

    @Test
    fun `without the network-state permission that signal is left out and airplane mode still works`() {
        var count = 0
        signals.start { count++ }
        idle()
        val connectivity = application.getSystemService(ConnectivityManager::class.java)

        assertEquals("no callback is asked for without the permission", 0, shadowOf(connectivity).networkCallbacks.size)
        application.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", false))
        idle()
        assertEquals(1, count)
    }

    @Test
    fun `after stop nothing is listened to, and starting twice listens once`() {
        grantNetworkState()
        var count = 0
        signals.start { count++ }
        signals.start { count++ }
        idle()
        val connectivity = application.getSystemService(ConnectivityManager::class.java)
        assertEquals(1, shadowOf(connectivity).networkCallbacks.size)

        signals.stop()
        val atStop = count
        application.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", false))
        idle()

        assertEquals(atStop, count)
        assertEquals(0, shadowOf(connectivity).networkCallbacks.size)
    }

    @Test
    fun `the retry code of the phone never logs, sends nothing and asks for no permission`() {
        val forbidden = listOf(
            "android.util.Log", "println(", "Timber", "SmsManager", "okhttp3", "retrofit2",
            "READ_PHONE_STATE", "requestPermissions", "WakeLock", "AlarmManager",
        )
        val lines = File("src/main/java/com/saferoute/app/feature/emergency/SosRetryDevice.kt").readLines()
            // The comment that explains why READ_PHONE_STATE is not used may name it.
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }

        assertEquals(emptyList<String>(), lines.filter { line -> forbidden.any(line::contains) })
    }

    @Test
    fun `the main manifest gained no permission for the retries`() {
        // The permissions this app's own manifest declares (comments may name others).
        val declared = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"")
            .findAll(File("src/main/AndroidManifest.xml").readText())
            .map { it.groupValues[1].substringAfterLast('.') }
            .toSet()

        for (name in listOf("READ_PHONE_STATE", "WAKE_LOCK", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM")) {
            assertFalse(name, name in declared)
        }
        // ACCESS_NETWORK_STATE comes from the sign-in libraries (MainActivityTest pins the
        // merged list); this app's own manifest still does not declare it.
        assertFalse("ACCESS_NETWORK_STATE" in declared)
        assertTrue("the check reads the manifest", "INTERNET" in declared)
    }
}
