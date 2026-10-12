// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.DarkSafeRouteColors
import com.saferoute.app.core.designsystem.theme.LightSafeRouteColors
import com.saferoute.app.core.emergency.SosEntryPoint
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.w3c.dom.Element

/**
 * The home-screen widget (P014c1): one button, the same start as the tile, declared the way
 * Android asks, and nothing running behind it. Robolectric stands in for the launcher.
 */
@RunWith(AndroidJUnit4::class)
class SosWidgetTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val android = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File(path))
        .documentElement

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    private fun views(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { views(group.getChildAt(it)) } }.orEmpty())

    private fun widget(): View {
        val manager = shadowOf(AppWidgetManager.getInstance(application))
        val id = manager.createWidget(SosWidgetProvider::class.java, R.layout.emergency_widget)
        return manager.getViewFor(id)
    }

    @Test
    fun `a tap on the button starts the SOS as the widget entry, and nothing else on it reacts`() {
        ShadowLog.clear()
        val root = widget()
        val button = root.findViewById<TextView>(R.id.emergency_widget_button)

        // Only the button can be tapped: the card around it is dead space.
        assertEquals(listOf<View>(button), views(root).filter { it.hasOnClickListeners() })
        assertNull("placing the widget starts nothing", shadowOf(application).nextStartedActivity)

        button.performClick()

        val started = shadowOf(application).nextStartedActivity
        assertEquals(ComponentName(application, EmergencyActivity::class.java), started.component)
        assertEquals(SosOpenMode.START.name, started.getStringExtra(EmergencyShortcut.EXTRA_MODE))
        assertEquals(SosEntryPoint.WIDGET, EmergencyShortcut.entryPointOf(started))
        assertNull(shadowOf(application).nextStartedActivity)
        assertNull("no service is started", shadowOf(application).nextStartedService)
        // The widget says nothing to Logcat.
        assertTrue(ShadowLog.getLogs().none { it.tag.contains("Widget", ignoreCase = true) })
        Log.d("SosWidgetTest", "log capture works")
        assertTrue(ShadowLog.getLogs().any { it.msg == "log capture works" })
    }

    @Test
    fun `the button is named Start SOS for TalkBack, shows fixed text, and is big enough`() {
        val button = widget().findViewById<TextView>(R.id.emergency_widget_button)

        assertEquals("Start SOS", application.getString(R.string.sos_widget_description))
        assertEquals(application.getString(R.string.sos_widget_description), button.contentDescription.toString())
        assertEquals(application.getString(R.string.sos_control_label), button.text.toString())

        val layout = xml("src/main/res/layout/emergency_widget.xml")
        val declared = layout.getElementsByTagName("TextView").let { nodes -> List(nodes.length) { nodes.item(it) as Element } }
            .single { it.attr("id") == "@+id/emergency_widget_button" }
        assertEquals("48dp", declared.attr("minHeight"))
        // Every text in the layout is a string resource: nothing could be about a person.
        val texts = layout.getElementsByTagName("TextView").let { nodes -> List(nodes.length) { (nodes.item(it) as Element).attr("text") } }
        assertTrue(texts.toString(), texts.all { it.startsWith("@string/") })
    }

    @Test
    fun `its pending intent starts an activity directly, is immutable, and is its own`() {
        val widget = shadowOf(EmergencyShortcut.startPendingIntent(application, SosEntryPoint.WIDGET))

        assertTrue("an activity, with no receiver or service in between", widget.isActivityIntent)
        assertTrue(widget.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(0, widget.flags and PendingIntent.FLAG_MUTABLE)
        for (other in listOf(SosEntryPoint.TILE, SosEntryPoint.NOTIFICATION, SosEntryPoint.IN_APP)) {
            assertNotEquals(widget.requestCode, shadowOf(EmergencyShortcut.startPendingIntent(application, other)).requestCode)
        }
    }

    @Test
    fun `the widget is two cells square, cannot be resized, is never updated and is for the home screen`() {
        val info = xml("src/main/res/xml/emergency_widget_info.xml")

        assertEquals("0", info.attr("updatePeriodMillis"))
        assertEquals("none", info.attr("resizeMode"))
        assertEquals("home_screen", info.attr("widgetCategory"))
        assertEquals("2", info.attr("targetCellWidth"))
        assertEquals("2", info.attr("targetCellHeight"))
        assertEquals("110dp", info.attr("minWidth"))
        assertEquals("110dp", info.attr("minHeight"))
        assertEquals("@layout/emergency_widget", info.attr("initialLayout"))
        assertEquals("@layout/emergency_widget", info.attr("previewLayout"))
        assertTrue(info.attr("previewImage").startsWith("@drawable/"))
        assertEquals("@string/sos_widget_picker_description", info.attr("description"))
        // No screen to configure it, so nothing of the account can be put on it.
        assertEquals("", info.attr("configure"))
    }

    @Test
    fun `the manifest declares the provider for widget updates only, and not exported`() {
        val receivers = xml("src/main/AndroidManifest.xml").getElementsByTagName("receiver")
            .let { nodes -> List(nodes.length) { nodes.item(it) as Element } }
        val provider = receivers.single { it.attr("name").endsWith("SosWidgetProvider") }

        assertEquals("false", provider.attr("exported"))
        assertEquals("@string/sos_widget_label", provider.attr("label"))
        val actions = provider.getElementsByTagName("action").let { nodes -> List(nodes.length) { (nodes.item(it) as Element).attr("name") } }
        assertEquals(listOf("android.appwidget.action.APPWIDGET_UPDATE"), actions)
        val meta = provider.getElementsByTagName("meta-data").item(0) as Element
        assertEquals("android.appwidget.provider", meta.attr("name"))
        assertEquals("@xml/emergency_widget_info", meta.attr("resource"))

        // And Android finds it when it looks for the receivers of a widget update.
        val found = application.packageManager.queryBroadcastReceivers(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(application.packageName),
            0,
        )
        assertEquals(listOf(SosWidgetProvider::class.java.name), found.map { it.activityInfo.name })
    }

    @Test
    fun `the red of the button is the emergency red, and only the button uses it`() {
        fun colour(path: String, name: String): Int {
            val nodes = xml(path).getElementsByTagName("color")
            val value = List(nodes.length) { nodes.item(it) as Element }.single { it.getAttribute("name") == name }.textContent
            return java.lang.Long.decode(value.trim()).toInt()
        }
        assertEquals(LightSafeRouteColors.sos.toArgb(), colour("src/main/res/values/colors.xml", "emergency_widget_button"))
        assertEquals(LightSafeRouteColors.onSos.toArgb(), colour("src/main/res/values/colors.xml", "emergency_widget_on_button"))
        assertEquals(DarkSafeRouteColors.sos.toArgb(), colour("src/main/res/values-night/colors.xml", "emergency_widget_button"))
        assertEquals(DarkSafeRouteColors.onSos.toArgb(), colour("src/main/res/values-night/colors.xml", "emergency_widget_on_button"))

        val users = File("src/main/res").walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.readText().contains("@color/emergency_widget_button") }
            .map { it.name }
            .toList()
        assertEquals(listOf("emergency_widget_button.xml"), users)
    }

    @Test
    fun `the widget and the ways to the emergency screen never log, print or touch the network`() {
        val sources = listOf(
            "feature/emergency/SosWidgetProvider.kt",
            "feature/emergency/EmergencyShortcut.kt",
            "feature/emergency/SosTileService.kt",
            "navigation/OpenRequests.kt",
        ).map { File("src/main/java/com/saferoute/app/$it") }

        for (source in sources) {
            assertTrue("Not found: ${source.path}", source.isFile)
            val code = source.readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }.joinToString("\n")
            for (forbidden in listOf("Log.", "println", "Timber", "okhttp", "Retrofit", "HttpURLConnection", "SmsManager")) {
                assertFalse("${source.name} must not use $forbidden", code.contains(forbidden))
            }
        }
    }
}
