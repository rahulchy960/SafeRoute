// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.app.Application
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.MainActivity
import com.saferoute.app.R
import com.saferoute.app.core.emergency.SosEntryPoint
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.w3c.dom.Element

/**
 * The emergency shortcuts outside the app (P012f1, P014c1): every one of them leads to the
 * same destination and asks it to start an SOS, the destination offers 112 through the dialer
 * and never calls, and the manifest declares exactly what is needed.
 */
@RunWith(AndroidJUnit4::class)
class EmergencyShortcutTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val destination = ComponentName(context, EmergencyActivity::class.java)

    @Test
    fun `every entry leads to the one emergency destination, as a new task`() {
        for (entryPoint in SosEntryPoint.entries) {
            val intent = EmergencyShortcut.startIntent(context, entryPoint)
            assertEquals(destination, intent.component)
            // Explicit: no other app can be the target, and no action or data is carried.
            assertNull(intent.action)
            assertNull(intent.data)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            // Two extras: "start", and which shortcut it was. Nothing about the user.
            assertEquals(setOf(EmergencyShortcut.EXTRA_MODE, EmergencyShortcut.EXTRA_ENTRY), intent.extras?.keySet())
            assertEquals(SosOpenMode.START.name, intent.getStringExtra(EmergencyShortcut.EXTRA_MODE))
            assertEquals(entryPoint, EmergencyShortcut.entryPointOf(intent))
        }
    }

    @Test
    fun `an intent that names no entry, or one that does not exist, counts as the app`() {
        assertEquals(SosEntryPoint.IN_APP, EmergencyShortcut.entryPointOf(null))
        assertEquals(SosEntryPoint.IN_APP, EmergencyShortcut.entryPointOf(EmergencyShortcut.modeIntent(context, SosOpenMode.OPEN)))
        val unknown = EmergencyShortcut.modeIntent(context, SosOpenMode.START).putExtra(EmergencyShortcut.EXTRA_ENTRY, "VOICE")
        assertEquals(SosEntryPoint.IN_APP, EmergencyShortcut.entryPointOf(unknown))
    }

    @Test
    fun `the pending intent starts an activity, is immutable, and differs per entry`() {
        val starts = SosEntryPoint.entries.map { EmergencyShortcut.startPendingIntent(context, it) }
        val all = starts + EmergencyShortcut.openPendingIntent(context) + EmergencyShortcut.safePendingIntent(context)

        for (pendingIntent in all) {
            val shadow = shadowOf(pendingIntent)
            // An activity, started directly: no receiver or service in between ("trampoline").
            assertTrue(shadow.isActivityIntent)
            assertFalse(shadow.isBroadcastIntent)
            assertFalse(shadow.isServiceIntent)
            assertTrue("immutable", shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
            assertEquals(0, shadow.flags and PendingIntent.FLAG_MUTABLE)
            assertEquals(destination, shadow.savedIntent.component)
        }
        // Android tells them apart by the request code, not by the extras.
        assertEquals(all.size, all.map { shadowOf(it).requestCode }.toSet().size)
        // Each start says where it came from; "open" and "I'm safe" start nothing.
        assertEquals(SosEntryPoint.entries, starts.map { EmergencyShortcut.entryPointOf(shadowOf(it).savedIntent) })
        assertEquals(
            SosOpenMode.OPEN.name,
            shadowOf(EmergencyShortcut.openPendingIntent(context)).savedIntent.getStringExtra(EmergencyShortcut.EXTRA_MODE),
        )
    }

    @Test
    fun `a tile uses a pending intent from Android 14 and a plain intent before`() {
        // Android 14 (API 34) throws for the Intent form; Android 13 has no other.
        val newer = EmergencyShortcut.tileLaunch(context, sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val newest = EmergencyShortcut.tileLaunch(context, sdkInt = 36)
        val older = EmergencyShortcut.tileLaunch(context, sdkInt = Build.VERSION_CODES.TIRAMISU)
        val oldest = EmergencyShortcut.tileLaunch(context, sdkInt = Build.VERSION_CODES.O)

        assertTrue(newer is TileLaunch.WithPendingIntent)
        val pending = shadowOf((newest as TileLaunch.WithPendingIntent).pendingIntent)
        assertEquals(SosEntryPoint.TILE, EmergencyShortcut.entryPointOf(pending.savedIntent))
        assertTrue(pending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(destination, (older as TileLaunch.WithIntent).intent.component)
        assertEquals(destination, (oldest as TileLaunch.WithIntent).intent.component)
        assertEquals(SosEntryPoint.TILE, EmergencyShortcut.entryPointOf(oldest.intent))
    }

    @Test
    fun `Android's answer to the add-tile request is read by its number`() {
        assertEquals(TileAddResult.NotAdded, tileAddResult(0))
        assertEquals(TileAddResult.AlreadyAdded, tileAddResult(1))
        assertEquals(TileAddResult.Added, tileAddResult(2))
        // Errors (wrong package, request in progress, bad component, not the current user, the
        // app not in the foreground, no status bar) all end in the manual instructions.
        for (error in 1000..1005) assertEquals(TileAddResult.NotAdded, tileAddResult(error))
        assertEquals(TileAddResult.NotAdded, tileAddResult(-1))
    }
}

/** The tile itself, on the last Android version where Robolectric can follow its tap. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SosTileServiceTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a tap asks the emergency destination to start an SOS from the tile, and nothing else`() {
        ShadowLog.clear()
        val service = Robolectric.setupService(SosTileService::class.java)

        service.onClick()

        val started = shadowOf(application).nextStartedActivity
        assertEquals(ComponentName(application, EmergencyActivity::class.java), started.component)
        assertEquals(SosOpenMode.START.name, started.getStringExtra(EmergencyShortcut.EXTRA_MODE))
        assertEquals(SosEntryPoint.TILE, EmergencyShortcut.entryPointOf(started))
        assertNull(shadowOf(application).nextStartedActivity)
        // The tile starts no service itself: the emergency screen does, once it is visible.
        assertNull(shadowOf(application).nextStartedService)
        // The tile says nothing to Logcat.
        assertTrue(ShadowLog.getLogs().none { it.tag.contains("Sos", ignoreCase = true) })
        Log.d("SosTileServiceTest", "log capture works")
        assertTrue(ShadowLog.getLogs().any { it.msg == "log capture works" })
    }

    @Test
    fun `on a locked phone the tap does the same, and never waits for the unlock`() {
        val keyguard = application.getSystemService(KeyguardManager::class.java)
        shadowOf(keyguard).setKeyguardLocked(true)
        shadowOf(keyguard).setIsDeviceSecure(true)
        val service = Robolectric.setupService(SosTileService::class.java)

        service.onClick()

        // The same start as on an unlocked phone: the emergency screen may show over the
        // lock screen, and it is the screen, not the tile, that asks for an unlock later.
        val started = shadowOf(application).nextStartedActivity
        assertEquals(ComponentName(application, EmergencyActivity::class.java), started.component)
        assertEquals(SosEntryPoint.TILE, EmergencyShortcut.entryPointOf(started))
        assertTrue("the phone is still locked", keyguard.isKeyguardLocked)
        val source = File("src/main/java/com/saferoute/app/feature/emergency/SosTileService.kt").readLines()
            .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }
        assertTrue("the tile never asks for the unlock", source.none { it.contains("unlockAndRun") })
    }

    @Test
    fun `the tile is tappable, not shown as switched on, and named for TalkBack`() {
        val service = Robolectric.setupService(SosTileService::class.java)

        service.onStartListening()

        val tile = service.qsTile
        assertEquals(application.getString(R.string.sos_tile_label), tile.label)
        assertEquals(Tile.STATE_INACTIVE, tile.state)
        assertTrue(tile.contentDescription.contains("112"))
    }

    @Test
    fun `the label fits a tile`() {
        // Android shows about 18 characters of a tile's label.
        assertTrue(application.getString(R.string.sos_tile_label).length <= 18)
    }
}

/** The destination: the same dialog as in the app, started the way a shortcut starts it. */
@RunWith(AndroidJUnit4::class)
class EmergencyActivityTest {

    @get:Rule
    val compose = createAndroidComposeRule<EmergencyActivity>()

    private fun string(id: Int): String = compose.activity.getString(id)
    private fun nextStartedActivity(): Intent? = shadowOf(compose.activity.application).nextStartedActivity

    @Test
    fun `it shows the emergency dialog with the not-an-emergency-service note`() {
        compose.onNodeWithText(string(R.string.emergency_dialog_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.not_emergency_service), substring = true).assertExists()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).assertIsDisplayed()
        // Opening it starts nothing: no call, no dialer, no other screen.
        assertNull(nextStartedActivity())
    }

    @Test
    fun `call 112 opens the dialer with 112, never calls, and closes this screen`() {
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()
        compose.waitForIdle()

        val intent = nextStartedActivity()
        assertEquals(Intent.ACTION_DIAL, intent?.action)
        assertNotEquals(Intent.ACTION_CALL, intent?.action)
        assertEquals("tel:112", intent?.dataString)
        assertNull(nextStartedActivity())
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `cancel closes this screen and starts nothing`() {
        compose.onNodeWithText(string(R.string.emergency_dialog_cancel)).performClick()
        compose.waitForIdle()

        assertNull(nextStartedActivity())
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `without a dialer app it shows the number instead of crashing, and stays open`() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)

        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()

        compose.onNodeWithText(string(R.string.emergency_dialog_no_dialer)).assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun `it is not the main activity and carries nothing of the app's session`() {
        assertNotEquals(MainActivity::class.java, compose.activity.javaClass)
        // No extras needed: it opens with an empty intent as well, and then shows the dialog.
        assertTrue(compose.activity.intent.extras?.isEmpty ?: true)
    }
}

/** What the manifest declares for the shortcuts, read from the manifest and the package. */
@RunWith(AndroidJUnit4::class)
class EmergencyShortcutsManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val android = "http://schemas.android.com/apk/res/android"

    private val manifest: Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))
        .documentElement

    private fun elements(tag: String): List<Element> =
        manifest.getElementsByTagName(tag).let { nodes -> List(nodes.length) { nodes.item(it) as Element } }

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    @Test
    fun `the tile service is declared the way Android asks`() {
        val service = elements("service").single { it.attr("name").endsWith("SosTileService") }
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", service.attr("permission"))
        assertEquals("true", service.attr("exported"))
        assertEquals("@drawable/ic_sos_tile", service.attr("icon"))
        assertEquals("@string/sos_tile_label", service.attr("label"))

        val actions = service.getElementsByTagName("action")
        assertEquals("android.service.quicksettings.action.QS_TILE", (actions.item(0) as Element).attr("name"))
        val meta = service.getElementsByTagName("meta-data").item(0) as Element
        assertEquals("android.service.quicksettings.ACTIVE_TILE", meta.attr("name"))
        assertEquals("true", meta.attr("value"))

        // And Android finds it when it looks for tiles.
        val found = context.packageManager.queryIntentServices(
            Intent("android.service.quicksettings.action.QS_TILE").setPackage(context.packageName),
            PackageManager.GET_META_DATA,
        )
        assertEquals(listOf(SosTileService::class.java.name), found.map { it.serviceInfo.name })
    }

    @Test
    fun `only the emergency screen may show over the lock screen, and it is not exported`() {
        val activities = elements("activity")
        val emergency = activities.single { it.attr("name").endsWith("EmergencyActivity") }
        assertEquals("true", emergency.attr("showWhenLocked"))
        assertEquals("false", emergency.attr("exported"))
        assertEquals("true", emergency.attr("excludeFromRecents"))
        assertFalse("it has no intent filter", emergency.getElementsByTagName("intent-filter").length > 0)

        val others = activities - emergency
        assertTrue(others.isNotEmpty())
        others.forEach { assertEquals(it.attr("name"), "", it.attr("showWhenLocked")) }
        // Nothing turns the screen on or dismisses the lock.
        activities.forEach { assertEquals("", it.attr("turnScreenOn")) }
    }

    @Test
    fun `the shortcuts run no foreground service, and the only one belongs to a running SOS`() {
        // The tile is not a foreground service, and the pinned notification is an ordinary
        // notification with nothing running behind it. The one foreground service of the app
        // is the running SOS (P014a2, ADR 0027): type "location", not exported.
        val services = elements("service")
        assertEquals(2, services.size)
        val foreground = services.filter { it.attr("foregroundServiceType").isNotEmpty() }
        assertEquals(1, foreground.size)
        assertTrue(foreground.single().attr("name").endsWith("SosForegroundService"))
        assertEquals("location", foreground.single().attr("foregroundServiceType"))
        assertEquals("false", foreground.single().attr("exported"))

        val asked = elements("uses-permission").map { it.attr("name") }
        assertTrue("android.permission.POST_NOTIFICATIONS" in asked)
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in asked)
        // The two foreground-service permissions are exactly these, for that one service.
        assertEquals(
            listOf("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_LOCATION"),
            asked.filter { it.contains("FOREGROUND_SERVICE") },
        )
        for (forbidden in listOf("CALL_PHONE", "USE_FULL_SCREEN_INTENT", "SYSTEM_ALERT_WINDOW", "WAKE_LOCK", "BACKGROUND", "SEND_SMS")) {
            assertTrue("$forbidden is not asked for", asked.none { it.contains(forbidden) })
        }
        // BIND_QUICK_SETTINGS_TILE is required OF the system by the service; the app does not
        // ask for it.
        assertTrue(asked.none { it.contains("BIND_QUICK_SETTINGS_TILE") })
    }

    @Test
    fun `one receiver, not exported, for a restart and an update of this app only`() {
        // Two receivers in all: this one and the widget's provider (SosWidgetTest).
        assertEquals(2, elements("receiver").size)
        val receiver = elements("receiver").single { it.attr("name").endsWith("EmergencyShortcutReceiver") }
        assertEquals("false", receiver.attr("exported"))
        val actions = receiver.getElementsByTagName("action")
        assertEquals(
            setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"),
            List(actions.length) { (actions.item(it) as Element).attr("name") }.toSet(),
        )
    }
}
