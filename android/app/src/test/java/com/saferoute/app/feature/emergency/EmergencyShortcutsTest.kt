// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.app.Application
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
 * The emergency shortcuts outside the app (P012f1): every one of them leads to the same
 * destination, the destination offers 112 through the dialer and never calls, and the manifest
 * declares exactly what is needed.
 */
@RunWith(AndroidJUnit4::class)
class EmergencyShortcutTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val destination = ComponentName(context, EmergencyActivity::class.java)

    @Test
    fun `every entry leads to the one emergency destination, as a new task`() {
        for (entry in EmergencyEntry.entries) {
            val intent = EmergencyShortcut.intent(context, entry)
            assertEquals(destination, intent.component)
            // Explicit: no other app can be the target, and no action or data is carried.
            assertNull(intent.action)
            assertNull(intent.data)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            // The only extra says which shortcut it was; nothing about the user.
            assertEquals(setOf(EmergencyShortcut.EXTRA_ENTRY), intent.extras?.keySet())
        }
    }

    @Test
    fun `the pending intent starts an activity, is immutable, and differs per entry`() {
        val tile = EmergencyShortcut.pendingIntent(context, EmergencyEntry.QuickSettingsTile)
        val notification = EmergencyShortcut.pendingIntent(context, EmergencyEntry.Notification)

        for (pendingIntent in listOf(tile, notification)) {
            val shadow = shadowOf(pendingIntent)
            assertTrue(shadow.isActivityIntent)
            assertTrue("immutable", shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
            assertEquals(0, shadow.flags and PendingIntent.FLAG_MUTABLE)
            assertEquals(destination, shadow.savedIntent.component)
        }
        assertNotEquals(shadowOf(tile).requestCode, shadowOf(notification).requestCode)
    }

    @Test
    fun `a tile uses a pending intent from Android 14 and a plain intent before`() {
        // Android 14 (API 34) throws for the Intent form; Android 13 has no other.
        val newer = EmergencyShortcut.tileLaunch(context, sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val newest = EmergencyShortcut.tileLaunch(context, sdkInt = 36)
        val older = EmergencyShortcut.tileLaunch(context, sdkInt = Build.VERSION_CODES.TIRAMISU)
        val oldest = EmergencyShortcut.tileLaunch(context, sdkInt = Build.VERSION_CODES.O)

        assertTrue(newer is TileLaunch.WithPendingIntent)
        assertTrue(newest is TileLaunch.WithPendingIntent)
        assertEquals(destination, (older as TileLaunch.WithIntent).intent.component)
        assertEquals(destination, (oldest as TileLaunch.WithIntent).intent.component)
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
    fun `a tap opens the emergency destination and nothing else`() {
        ShadowLog.clear()
        val service = Robolectric.setupService(SosTileService::class.java)

        service.onClick()

        val started = shadowOf(application).nextStartedActivity
        assertEquals(ComponentName(application, EmergencyActivity::class.java), started.component)
        assertNull(shadowOf(application).nextStartedActivity)
        // The tile says nothing to Logcat.
        assertTrue(ShadowLog.getLogs().none { it.tag.contains("Sos", ignoreCase = true) })
        Log.d("SosTileServiceTest", "log capture works")
        assertTrue(ShadowLog.getLogs().any { it.msg == "log capture works" })
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
        // No Hilt, no ViewModel, no extras needed: it opens with an empty intent as well.
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
    fun `no foreground service, no receiver and no new permission came with the tile`() {
        // The tile is the only service, and it is not a foreground service.
        val services = elements("service")
        assertEquals(1, services.size)
        services.forEach { assertEquals("", it.attr("foregroundServiceType")) }
        assertTrue(elements("receiver").isEmpty())

        val asked = elements("uses-permission").map { it.attr("name") }
        for (forbidden in listOf("FOREGROUND_SERVICE", "POST_NOTIFICATIONS", "RECEIVE_BOOT_COMPLETED", "CALL_PHONE")) {
            assertTrue("$forbidden is not asked for", asked.none { it.contains(forbidden) })
        }
        // BIND_QUICK_SETTINGS_TILE is required OF the system by the service; the app does not
        // ask for it.
        assertTrue(asked.none { it.contains("BIND_QUICK_SETTINGS_TILE") })
    }
}
