// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.BuildConfig
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.feature.contacts.AlertsNotSetUpCard
import com.saferoute.app.testing.assertMinTouchTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Send alerts automatically": the explanation first, Android's dialog second, never in an SOS. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class SmsPermissionCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<String>()
    private fun string(id: Int): String = compose.activity.getString(id)

    private var mode by mutableStateOf(SmsAlertMode.OFF)
    private var disclosureOpen by mutableStateOf(false)

    private fun show(fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SafeRouteTheme {
                    SmsAlertModeContent(
                        mode = mode,
                        disclosureOpen = disclosureOpen,
                        onAllowClick = {
                            events += "allow"
                            disclosureOpen = true
                        },
                        onDisclosureContinue = {
                            events += "continue"
                            disclosureOpen = false
                        },
                        onDisclosureDismiss = {
                            events += "not now"
                            disclosureOpen = false
                        },
                        onOpenSettings = { events += "settings" },
                    )
                }
            }
        }
    }

    @Test
    fun `the mode follows the build, the grant, and whether Android still asks`() {
        assertEquals(SmsAlertMode.NOT_IN_BUILD, smsAlertMode(declared = false, granted = false, blocked = false))
        // A build without the permission cannot be "on", whatever else is claimed.
        assertEquals(SmsAlertMode.NOT_IN_BUILD, smsAlertMode(declared = false, granted = true, blocked = true))
        assertEquals(SmsAlertMode.OFF, smsAlertMode(declared = true, granted = false, blocked = false))
        assertEquals(SmsAlertMode.BLOCKED, smsAlertMode(declared = true, granted = false, blocked = true))
        assertEquals(SmsAlertMode.ON, smsAlertMode(declared = true, granted = true, blocked = false))
        // Granted in system Settings after a refusal: on.
        assertEquals(SmsAlertMode.ON, smsAlertMode(declared = true, granted = true, blocked = true))
    }

    @Test
    fun `off - it explains the SMS app, and Allow opens the app's own explanation first`() {
        show()

        compose.onNodeWithText(string(R.string.sms_mode_off)).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.sms_disclosure_title)).assertCountEquals(0)

        compose.onNodeWithText(string(R.string.sms_mode_allow)).assertMinTouchTarget().performClick()

        // The disclosure says what is sent, to whom, that it may cost, and that nothing is
        // sent unless an SOS is started. Only "Continue" leads on to Android's dialog.
        compose.onNodeWithText(string(R.string.sms_disclosure_title)).assertIsDisplayed()
        for (id in listOf(R.string.sms_disclosure_p1, R.string.sms_disclosure_p2, R.string.sms_disclosure_p3, R.string.sms_disclosure_p4)) {
            compose.onNodeWithText(string(id)).assertExists()
        }
        assertTrue(string(R.string.sms_disclosure_p3).contains("charge"))
        assertTrue(string(R.string.sms_disclosure_p3).contains("unless you start an SOS"))
        assertEquals(listOf("allow"), events)

        compose.onNodeWithText(string(R.string.sms_disclosure_continue)).performClick()
        assertEquals(listOf("allow", "continue"), events)
        compose.onAllNodesWithText(string(R.string.sms_disclosure_title)).assertCountEquals(0)
    }

    @Test
    fun `not now on the explanation asks Android for nothing`() {
        show()
        compose.onNodeWithText(string(R.string.sms_mode_allow)).performClick()

        compose.onNodeWithText(string(R.string.sms_disclosure_not_now)).performClick()

        assertEquals(listOf("allow", "not now"), events)
        assertNull(shadowOf(compose.activity).lastRequestedPermission)
    }

    @Test
    fun `on - it says so and how to turn it off, and offers nothing to tap`() {
        mode = SmsAlertMode.ON
        show()

        compose.onNodeWithText(string(R.string.sms_mode_on)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sms_mode_on_how_to_stop)).assertExists()
        compose.onAllNodesWithText(string(R.string.sms_mode_allow)).assertCountEquals(0)
    }

    @Test
    fun `blocked - it explains that Android no longer asks and leads to the app's settings`() {
        mode = SmsAlertMode.BLOCKED
        show()

        compose.onNodeWithText(string(R.string.sms_mode_blocked)).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.sms_mode_allow)).assertCountEquals(0)
        compose.onNodeWithText(string(R.string.sms_mode_open_settings)).assertMinTouchTarget().performClick()
        assertEquals(listOf("settings"), events)
    }

    @Test
    fun `a build without the permission explains the SMS app and asks for nothing`() {
        mode = SmsAlertMode.NOT_IN_BUILD
        show()

        compose.onNodeWithText(string(R.string.sms_mode_not_in_build)).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.sms_mode_allow)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.sms_mode_open_settings)).assertCountEquals(0)
    }

    @Test
    fun `at 200 percent font the explanation can be read to its end and continued`() {
        disclosureOpen = true
        show(fontScale = 2f)

        compose.onNodeWithText(string(R.string.sms_disclosure_p4)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sms_disclosure_continue)).assertIsDisplayed().performClick()
        assertEquals(listOf("continue"), events)
    }

    @Test
    fun `the real card asks Android only after Continue, and shows on once granted`() {
        assumeTrue(BuildConfig.SEND_SMS_DECLARED)
        compose.setContent { SafeRouteTheme { SmsAlertModeCard() } }

        compose.onNodeWithText(string(R.string.sms_mode_off)).assertIsDisplayed()
        assertNull("showing the card asks for nothing", shadowOf(compose.activity).lastRequestedPermission)

        compose.onNodeWithText(string(R.string.sms_mode_allow)).performClick()
        assertNull("nor does the explanation", shadowOf(compose.activity).lastRequestedPermission)
        compose.onNodeWithText(string(R.string.sms_disclosure_continue)).performClick()
        compose.waitForIdle()

        assertEquals(
            Manifest.permission.SEND_SMS,
            shadowOf(compose.activity).lastRequestedPermission?.requestedPermissions?.single(),
        )
    }

    @Test
    fun `the SMS permission is requested in this one file and nowhere during an SOS`() {
        val askers = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                text.contains("RequestPermission") && text.contains("SEND_SMS")
            }
            .map { it.name }
            .toList()

        assertEquals(listOf("SmsPermissionCard.kt"), askers)
        // The emergency screen and everything that runs an SOS never ask for a permission.
        val sosCode = listOf("EmergencyActivity.kt", "SosScreens.kt", "SosViewModel.kt", "SosForegroundService.kt")
            .map { File("src/main/java/com/saferoute/app/feature/emergency/$it").readText() } +
            File("src/main/java/com/saferoute/app/core/emergency").walkTopDown().filter { it.isFile }.map { it.readText() }
        assertTrue(sosCode.none { it.contains("RequestPermission") || it.contains("requestPermissions") })
    }

    @Test
    fun `before the user agreed, the contacts list says that alerts are off and leads to the notice`() {
        compose.setContent { SafeRouteTheme { AlertsNotSetUpCard(onReadNotice = { events += "notice" }) } }

        compose.onNodeWithText(string(R.string.contacts_alerts_off)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.contacts_alerts_read_notice)).assertMinTouchTarget().performClick()
        assertEquals(listOf("notice"), events)
        compose.onNodeWithTag(SmsAlertModeCardTag).assertDoesNotExist()
    }
}
