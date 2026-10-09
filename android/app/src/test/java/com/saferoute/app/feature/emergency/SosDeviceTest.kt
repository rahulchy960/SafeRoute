// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.ListenableWorker
import com.saferoute.app.R
import com.saferoute.app.core.emergency.FakeSosHaptics
import com.saferoute.app.core.emergency.FakeSosHost
import com.saferoute.app.core.emergency.SosEntryPoint
import com.saferoute.app.core.emergency.SosRunState
import com.saferoute.app.core.emergency.SosRunner
import com.saferoute.app.core.location.FakeLocationEnvironment
import com.saferoute.app.core.location.GrantedLocation
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What a running SOS shows and uses on the phone: notification, battery, daily job. */
@RunWith(AndroidJUnit4::class)
class SosDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private fun string(id: Int): String = context.getString(id)

    @Test
    fun `the channel is silent and of low importance`() {
        ensureSosRunningChannel(context)

        val channel = manager.getNotificationChannel(SOS_RUNNING_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertEquals(string(R.string.sos_running_channel), channel.name.toString())
    }

    @Test
    fun `the notification is ongoing, public, and made of fixed text only`() {
        for ((active, title) in listOf(false to R.string.sos_running_title_countdown, true to R.string.sos_running_title_active)) {
            val notification = buildSosRunningNotification(context, active)

            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
            assertEquals(SOS_RUNNING_CHANNEL_ID, notification.channelId)
            assertEquals(string(title), notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            assertEquals(string(R.string.sos_running_text), text)
            // It says what SafeRoute is not, and names the number in Latin digits.
            assertTrue(text.contains("112"))
            // Nothing else is in it: no sub-text, no big text, no people, no place.
            for (key in listOf(Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_INFO_TEXT)) {
                assertNull(key, notification.extras.getCharSequence(key))
            }
        }
    }

    @Test
    fun `its one button opens the dialer with 112 and a tap opens the emergency screen`() {
        val notification = buildSosRunningNotification(context, active = true)

        val action = notification.actions.single()
        assertEquals(string(R.string.emergency_notification_call), action.title.toString())
        val dial = shadowOf(action.actionIntent).savedIntent
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:112", dial.dataString)
        assertTrue(shadowOf(action.actionIntent).isImmutable)

        val open = shadowOf(notification.contentIntent)
        assertTrue(open.isImmutable)
        assertEquals(ComponentName(context, EmergencyActivity::class.java), open.savedIntent.component)
    }

    @Test
    fun `the service is declared for location only and is not exported`() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, SosForegroundService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, info.foregroundServiceType)
        assertFalse(info.exported)
    }

    @Test
    fun `the battery level is a percentage, or nothing when the phone gives nonsense`() {
        val battery = shadowOf(context.getSystemService(BatteryManager::class.java))
        val level = AndroidBatteryLevel(context)

        battery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 7)
        assertEquals(7, level.percent())
        battery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, Int.MIN_VALUE)
        assertNull(level.percent())
    }

    @Test
    fun `the vibration plays without a crash`() {
        val haptics = AndroidSosHaptics(context)

        haptics.countdownTick()
        haptics.triggered()
    }

    @Test
    fun `the daily clean-up is planned once, however often it is asked for, and runs`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = WorkManagerPurgeScheduler(context)

        scheduler.scheduleDaily()
        scheduler.scheduleDaily()

        val jobs = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SosPurgeWorker.UNIQUE_NAME).get()
        assertEquals(1, jobs.size)
        assertEquals(WorkInfo.State.ENQUEUED, jobs.single().state)
        assertEquals(24 * 60 * 60 * 1_000L, jobs.single().periodicityInfo?.repeatIntervalMillis)

        val result = runBlocking { TestListenableWorkerBuilder<SosPurgeWorker>(context).build().doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `the device code of a running SOS never logs and holds no means to send a message`() {
        val forbidden = listOf("android.util.Log", "println(", "SmsManager", "SEND_SMS", "okhttp3", "retrofit2")
        val offenders = listOf("SosForegroundService.kt", "SosDevice.kt")
            .map { File("src/main/java/com/saferoute/app/feature/emergency/$it") }
            .flatMap { file -> file.readLines().filter { line -> forbidden.any(line::contains) }.map { file.name to it } }

        assertEquals(emptyList<Pair<String, String>>(), offenders)
    }
}

/** Where a running SOS lives: the foreground service where Android allows it, else a notification. */
@RunWith(AndroidJUnit4::class)
class AndroidSosHostTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val environment = FakeLocationEnvironment(granted = GrantedLocation.Precise)

    // The host only asks the runner what to show; in these tests nothing is running.
    private val host = AndroidSosHost(application, environment) { error("not needed while the service starts") }

    @Test
    fun `with a location permission it starts the foreground service and posts nothing itself`() {
        host.begin()

        val started = shadowOf(application).nextStartedService
        assertEquals(ComponentName(application, SosForegroundService::class.java), started.component)
        assertNull(shadowOf(manager).getNotification(SOS_RUNNING_NOTIFICATION_ID))
    }

    @Test
    fun `ending stops the service and removes the notification`() {
        host.begin()

        host.end()

        val stopped = shadowOf(application).nextStoppedService
        assertEquals(ComponentName(application, SosForegroundService::class.java), stopped.component)
        assertNull(shadowOf(manager).getNotification(SOS_RUNNING_NOTIFICATION_ID))
    }
}

/**
 * The service and the host inside the real app graph (fakes for the phone's parts): the
 * service follows the runner and never outlives the emergency.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class SosForegroundServiceTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var runner: SosRunner

    @Inject lateinit var host: FakeSosHost

    @Inject lateinit var haptics: FakeSosHaptics

    @Inject lateinit var environment: FakeLocationEnvironment

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() = hilt.inject()

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out waiting until $what", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }

    @Test
    fun `started with nothing running, the service stops itself at once`() {
        val controller = Robolectric.buildService(SosForegroundService::class.java).create()

        val mode = controller.get().onStartCommand(null, 0, 1)

        assertEquals("never restarted by the system", android.app.Service.START_NOT_STICKY, mode)
        waitUntil("the service stopped itself") { shadowOf(controller.get()).isStoppedBySelf }
    }

    @Test
    fun `during an SOS it is in the foreground with the fixed notification, and ends on cancel`() {
        val start = runBlocking { runner.start(SosEntryPoint.IN_APP) }
        assertEquals(listOf("begin"), host.events.toList())
        assertTrue(runner.state.value is SosRunState.Countdown)
        val controller = Robolectric.buildService(SosForegroundService::class.java).create()

        controller.get().onStartCommand(null, 0, 1)

        val service = shadowOf(controller.get())
        assertEquals(SOS_RUNNING_NOTIFICATION_ID, service.lastForegroundNotificationId)
        val title = service.lastForegroundNotification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertEquals(application.getString(R.string.sos_running_title_countdown), title)
        assertFalse(service.isStoppedBySelf)
        assertNotNull(start.record.clientSosId)

        assertTrue(runBlocking { runner.cancel() })

        waitUntil("the service stopped itself") { service.isStoppedBySelf }
        assertTrue(service.isForegroundStopped)
        assertEquals("end", host.events.last())
        assertFalse("triggered" in haptics.events)
        assertEquals(SosRunState.Idle, runner.state.value)
    }

    @Test
    fun `without a location permission the plain notification stands in for the service`() {
        environment.granted = GrantedLocation.None
        // The real host, with the fake environment and the real runner.
        val realHost = AndroidSosHost(application, environment) { runner }

        realHost.begin()

        assertNull("no service was started", shadowOf(application).nextStartedService)
        val posted = shadowOf(manager).getNotification(SOS_RUNNING_NOTIFICATION_ID)
        assertNotNull(posted)
        assertEquals(0, posted.flags and Notification.FLAG_FOREGROUND_SERVICE)

        realHost.end()
        assertNull(shadowOf(manager).getNotification(SOS_RUNNING_NOTIFICATION_ID))
    }
}
