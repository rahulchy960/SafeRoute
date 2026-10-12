// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.BuildConfig
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.emergency.SmsLimitNotice
import com.saferoute.app.feature.home.EmergencyArm
import com.saferoute.app.feature.home.EmergencyDialog
import com.saferoute.app.feature.home.EmergencyDialogState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/*
 * Obviously fake numbers only: +91 X0000 100NN, the pattern of the contacts tests, and +1 000.
 * Nobody real. No number of the machine's own list is read: the tests build their own lists.
 */
private const val ONE = "+919000010001"
private const val TWO = "+919000010002"
private const val THREE = "+919000010003"
private const val FOREIGN = "+10000000000"

/** The list of test numbers a debug build may text (P014b6): how it is read and what it allows. */
class DebugSmsAllowlistTest {

    @Test
    fun `a debug build always has a limit, and an empty or missing list allows nobody`() {
        for (raw in listOf("", "   ", ",", " , , ")) {
            val list = DebugSmsAllowlist(raw)

            assertTrue(list.limited)
            assertEquals(raw, 0, list.size)
            assertFalse(list.allows(ONE))
        }
    }

    @Test
    fun `numbers are separated by commas and only those on the list are allowed`() {
        val list = DebugSmsAllowlist("$ONE,$TWO")

        assertEquals(2, list.size)
        assertTrue(list.allows(ONE))
        assertTrue(list.allows(TWO))
        assertFalse(list.allows(THREE))
        assertFalse(list.allows(FOREIGN))
    }

    @Test
    fun `spaces, brackets and hyphens are ignored, and a number without +91 is read as Indian`() {
        val spellings = listOf("+91 90000 10001", " +91-90000-10001 ", "9000010001", "09000010001", "919000010001", "(+91) 90000.10001")
        for (spelling in spellings) {
            val list = DebugSmsAllowlist(spelling)

            assertEquals(spelling, 1, list.size)
            assertTrue(spelling, list.allows(ONE))
        }
        // The contact's number is compared in the same form, however it is written.
        assertTrue(DebugSmsAllowlist(ONE).allows("+91 90000 10001"))
    }

    @Test
    fun `a number named twice counts once`() {
        val list = DebugSmsAllowlist("$ONE, 9000010001, +91 90000 10001,$TWO")

        assertEquals(2, list.size)
    }

    @Test
    fun `entries that are not usable numbers are ignored and the others still count`() {
        // Too short, a landline-like start, letters, a lone plus, an empty entry, a foreign
        // number without its plus.
        val list = DebugSmsAllowlist("12345, 1000010001, abc, +, , 1 000 000 0000, $ONE, $FOREIGN")

        assertEquals(2, list.size)
        assertTrue(list.allows(ONE))
        assertTrue(list.allows(FOREIGN))
        assertFalse(list.allows("+911000010001"))
    }

    @Test
    fun `the list never prints its numbers`() {
        val list = DebugSmsAllowlist("$ONE,$TWO")

        assertEquals("DebugSmsAllowlist(hidden)", list.toString())
        assertFalse("${listOf(list)}".contains("9000010001"))
    }

    @Test
    fun `this build's limit is the list it was built with`() {
        val built = BuildSmsRecipientLimit()

        assertTrue(built.limited)
        // Compared by behaviour only: the numbers themselves are never read out by a test.
        val same = DebugSmsAllowlist(BuildConfig.DEBUG_SMS_ALLOWLIST)
        for (probe in listOf(ONE, TWO, FOREIGN)) assertEquals(same.allows(probe), built.allows(probe))
    }
}

/** Where the list lives, and that a release build knows nothing of it. Read from the sources. */
class DebugSmsAllowlistLayoutTest {

    private fun source(path: String) = File(path).also { assertTrue("$path is missing", it.isFile) }.readText()

    @Test
    fun `the debug code of the list never logs or prints`() {
        val forbidden = listOf("android.util.Log", "println(", "Timber", "System.out", "print(")
        for (file in listOf("BuildSmsRecipientLimit.kt", "BuildSmsNotice.kt")) {
            val lines = source("src/debug/java/com/saferoute/app/feature/emergency/$file").lines()
            assertEquals(file, emptyList<String>(), lines.filter { line -> forbidden.any(line::contains) })
        }
    }

    @Test
    fun `the release build sets no limit and reads no list`() {
        val limit = source("src/release/java/com/saferoute/app/feature/emergency/BuildSmsRecipientLimit.kt")
        assertTrue(limit.contains("SmsRecipientLimit by UnlimitedSmsRecipients"))
        assertFalse("a release build has no such field", limit.contains("BuildConfig."))
        assertFalse(limit.contains("DebugSmsAllowlist("))

        val banner = source("src/release/java/com/saferoute/app/feature/emergency/BuildSmsNotice.kt")
        assertTrue(banner.contains("fun BuildSmsNotice(notice: SmsLimitNotice?, modifier: Modifier = Modifier) = Unit"))
        assertFalse(banner.contains("R.string"))
    }

    @Test
    fun `the build file gives the list to the debug build type only and never prints it`() {
        val build = source("build.gradle.kts")
        val field = "buildConfigField(\"String\", \"DEBUG_SMS_ALLOWLIST\""
        assertEquals("declared once", 1, build.split(field).size - 1)
        val debugBlock = build.substringAfter("buildTypes {").substringAfter("debug {").substringBefore("release {")
        assertTrue("inside debug { }", debugBlock.contains(field))
        // The value is used in one place only, the field: no message and no log line carries it.
        assertEquals(1, build.split("\$debugSmsAllowlist").size - 1)
    }

    @Test
    fun `no tracked properties file holds a list of numbers`() {
        for (path in listOf("../gradle.properties", "gradle.properties")) {
            val file = File(path)
            if (file.isFile) assertFalse(path, file.readText().contains("debugSmsAllowlist"))
        }
    }

    @Test
    fun `nothing in the main source set reads the list or the debug class`() {
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().let { it.contains("DEBUG_SMS_ALLOWLIST") || it.contains("DebugSmsAllowlist") } }
            .map { it.name }
            .toList()

        assertEquals(emptyList<String>(), offenders)
    }
}

/** The banner of debug builds: what it says in each case, and where it is shown. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class BuildSmsNoticeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    @Test
    fun `it says how many allowlisted contacts get a real SMS`() {
        compose.setContent { SafeRouteTheme { BuildSmsNotice(SmsLimitNotice(automaticPossible = true, automaticRecipients = 2)) } }

        compose.onNodeWithTag(BuildSmsNoticeTag).assertExists()
        compose.onNodeWithText("DEBUG build: SMS is sent for real to 2 allowlisted contacts.").assertExists()
    }

    @Test
    fun `with no allowlisted contact it says composer mode and that nothing is sent unseen`() {
        compose.setContent { SafeRouteTheme { BuildSmsNotice(SmsLimitNotice(automaticPossible = true, automaticRecipients = 0)) } }

        val text = string(R.string.debug_sms_banner_none)
        assertTrue(text, text.startsWith("DEBUG build: no allowlisted contacts: composer mode."))
        assertTrue(text, text.contains("nothing is sent until you press Send"))
        compose.onNodeWithText(text).assertExists()
    }

    @Test
    fun `when the app cannot send by itself it says so, whatever the list holds`() {
        compose.setContent { SafeRouteTheme { BuildSmsNotice(SmsLimitNotice(automaticPossible = false, automaticRecipients = 0)) } }

        val text = string(R.string.debug_sms_banner_no_permission)
        assertTrue(text, text.contains("composer mode"))
        compose.onNodeWithText(text).assertExists()
    }

    @Test
    fun `without a notice nothing is shown`() {
        compose.setContent { SafeRouteTheme { BuildSmsNotice(null) } }

        compose.onAllNodesWithTag(BuildSmsNoticeTag).assertCountEquals(0)
    }

    @Test
    fun `the banner holds a count and no digits of a phone number`() {
        for (id in listOf(R.string.debug_sms_banner_sending, R.string.debug_sms_banner_none, R.string.debug_sms_banner_no_permission)) {
            val raw = compose.activity.resources.getText(id).toString().replace("%1\$d", "")
            assertFalse(raw, raw.any(Char::isDigit))
        }
    }

    @Test
    fun `the arm step shows it above the hold button, and not when no SOS can be started`() {
        val notice = SmsLimitNotice(automaticPossible = true, automaticRecipients = 1)
        var canStart by mutableStateOf(true)
        compose.setContent {
            SafeRouteTheme {
                EmergencyDialog(
                    state = EmergencyDialogState.OfferDialer,
                    onCallEmergency = {},
                    onDismiss = {},
                    arm = EmergencyArm(onArmed = ({}).takeIf { canStart }, onPractice = {}, smsNotice = notice),
                )
            }
        }
        compose.onNodeWithText(string(R.string.debug_sms_banner_sending, 1)).assertExists()

        // Without consent there is no hold button and nothing would be sent: no banner.
        canStart = false
        compose.onAllNodesWithTag(BuildSmsNoticeTag).assertCountEquals(0)
    }

    @Test
    fun `the countdown shows it, and a practice countdown does not`() {
        val notice = SmsLimitNotice(automaticPossible = true, automaticRecipients = 3)
        var practice by mutableStateOf(false)
        compose.setContent {
            SafeRouteTheme {
                SosCountdownScreen(
                    secondsLeft = 4,
                    practice = practice,
                    dialerMissing = false,
                    onCancel = {},
                    onCall112 = {},
                    smsNotice = notice,
                )
            }
        }
        compose.onNodeWithText(string(R.string.debug_sms_banner_sending, 3)).assertExists()

        practice = true
        compose.onAllNodesWithTag(BuildSmsNoticeTag).assertCountEquals(0)
    }
}
