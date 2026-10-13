// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.feature.diagnostics

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.diagnostics.DIAGNOSTICS_NOTICE_VERSION
import com.saferoute.app.core.diagnostics.DiagnosticsSettings
import com.saferoute.app.core.diagnostics.FakeDiagnosticsPreferences
import com.saferoute.app.core.diagnostics.TEST_BUILD
import com.saferoute.app.core.session.BlockReason
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.feature.onboarding.OnboardingViewModel
import com.saferoute.app.navigation.OnboardingCrashReportsDestination
import com.saferoute.app.navigation.OnboardingNoticeDestination
import com.saferoute.app.navigation.OnboardingSignInDestination
import com.saferoute.app.navigation.OnboardingWorkingDestination
import com.saferoute.app.navigation.onboardingDestinationFor
import com.saferoute.app.settled
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * The choice "Send crash reports" on its three screens (ADR 0028): the onboarding step, the
 * section in Settings and the card on Home. The stored choice is a fake; nothing here
 * reaches the crash-reporting library.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class CrashReportsChoiceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = FakeDiagnosticsPreferences()

    private fun string(id: Int): String = context.getString(id)

    @Composable
    private fun Themed(fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme(content = content)
        }
    }

    // --- Where the step sits in onboarding ---

    @Test
    fun `the choice comes after the account notice and before sign-in, once`() {
        assertEquals(OnboardingCrashReportsDestination, onboardingDestinationFor(SessionState.SignedOut, true, false))
        assertEquals(OnboardingSignInDestination, onboardingDestinationFor(SessionState.SignedOut, true, true))
        // Not known yet: wait, and show neither.
        assertEquals(OnboardingWorkingDestination, onboardingDestinationFor(SessionState.SignedOut, true, null))
        // Never before the account notice, whatever the flag says.
        assertEquals(OnboardingNoticeDestination, onboardingDestinationFor(SessionState.NeedsConsent, true, false))
        // And never in the way of Home or of a blocked account.
        assertEquals(null, onboardingDestinationFor(SessionState.Ready, true, false))
        assertFalse(
            onboardingDestinationFor(SessionState.Blocked(BlockReason.UNDER_18), true, false) ==
                OnboardingCrashReportsDestination,
        )
    }

    // --- The onboarding step ---

    @Test
    fun `the box is not ticked, and continuing without it records a no`() {
        val answers = mutableListOf<Boolean>()
        compose.setContent { Themed { CrashReportsChoiceScreen(onContinue = { answers += it }) } }

        compose.onNodeWithTag(CrashReportsSwitchTag).performScrollTo().assertIsOff()
        compose.onNodeWithText(string(R.string.onboarding_continue)).performScrollTo().performClick()

        assertEquals(listOf(false), answers)
    }

    @Test
    fun `ticking the box and continuing records a yes`() {
        val answers = mutableListOf<Boolean>()
        compose.setContent { Themed { CrashReportsChoiceScreen(onContinue = { answers += it }) } }

        compose.onNodeWithTag(CrashReportsSwitchTag).performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText(string(R.string.onboarding_continue)).performScrollTo().performClick()

        assertEquals(listOf(true), answers)
    }

    @Test
    fun `the step says what is sent, what is not, where it goes and how to stop`() {
        compose.setContent { Themed { CrashReportsChoiceScreen(onContinue = {}) } }

        for (id in listOf(
            R.string.diagnostics_intro,
            R.string.diagnostics_sent_body,
            R.string.diagnostics_not_sent_body,
            R.string.diagnostics_where_body,
            R.string.diagnostics_off_body,
        )) {
            compose.onNodeWithText(string(id)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `at twice the font size the box and the button can still be reached`() {
        val answers = mutableListOf<Boolean>()
        compose.setContent { Themed(fontScale = 2f) { CrashReportsChoiceScreen(onContinue = { answers += it }) } }

        compose.onNodeWithText(string(R.string.diagnostics_off_body)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CrashReportsSwitchTag).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.onboarding_continue)).performScrollTo().performClick()

        assertEquals(listOf(true), answers)
    }

    @Test
    fun `the onboarding answer is stored and counts as asked`() {
        val viewModel = OnboardingViewModel(FakeSession(SessionState.SignedOut), preferences)
        assertEquals(DiagnosticsSettings(crashReports = false, asked = false), preferences.settings.value)

        viewModel.onCrashReportsChoice(false)
        compose.settled { viewModel.crashReportsAsked.value == true }
        assertEquals(DiagnosticsSettings(crashReports = false, asked = true), preferences.settings.value)

        viewModel.onCrashReportsChoice(true)
        compose.settled { preferences.settings.value.crashReports }
    }

    // --- Settings ---

    @Test
    fun `the switch in Settings shows the choice and changes it both ways`() {
        var enabled by mutableStateOf(false)
        compose.setContent {
            Themed { CrashReportsSettings(enabled = enabled, buildSendsReports = true, onToggle = { enabled = it }) }
        }

        compose.onNodeWithTag(CrashReportsSwitchTag).assertIsOff().performClick().assertIsOn()
        assertTrue(enabled)
        compose.onNodeWithTag(CrashReportsSwitchTag).performClick().assertIsOff()
        assertFalse(enabled)
        // What switching off does and does not undo is on the screen all the time.
        compose.onNodeWithText(string(R.string.diagnostics_off_body)).assertExists()
        compose.onNodeWithText(string(R.string.diagnostics_debug_off)).assertDoesNotExist()
    }

    @Test
    fun `a debug build that sends nothing says so next to the switch`() {
        compose.setContent {
            Themed { CrashReportsSettings(enabled = true, buildSendsReports = false, onToggle = {}) }
        }

        compose.onNodeWithText(string(R.string.diagnostics_debug_off)).assertIsDisplayed()
    }

    @Test
    fun `the Settings switch writes the choice`() {
        val viewModel = CrashReportsViewModel(preferences, TEST_BUILD)
        compose.setContent { Themed { CrashReportsSettingsSection(viewModel = viewModel) } }

        compose.onNodeWithTag(CrashReportsSwitchTag).assertIsOff().performClick()
        compose.settled { preferences.settings.value.crashReports }
        compose.onNodeWithTag(CrashReportsSwitchTag).assertIsOn().performClick()
        compose.settled { !preferences.settings.value.crashReports }
    }

    // --- The card on Home ---

    @Test
    fun `a person who was never asked sees the card once, and no thanks is a no`() {
        val viewModel = CrashReportsViewModel(preferences, TEST_BUILD)
        compose.setContent { Themed { CrashReportsHomeCard(viewModel = viewModel) } }

        compose.onNodeWithTag(CrashReportsHomeCardTag).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.diagnostics_home_body)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.diagnostics_home_no)).performClick()

        compose.settled { preferences.settings.value.asked }
        compose.onNodeWithTag(CrashReportsHomeCardTag).assertDoesNotExist()
        assertEquals(DiagnosticsSettings(crashReports = false, asked = true), preferences.settings.value)
    }

    @Test
    fun `the card's other button is a yes, and the card goes away too`() {
        val viewModel = CrashReportsViewModel(preferences, TEST_BUILD)
        compose.setContent { Themed { CrashReportsHomeCard(viewModel = viewModel) } }

        compose.onNodeWithText(string(R.string.diagnostics_switch)).performClick()

        compose.settled { preferences.settings.value.asked }
        compose.onNodeWithTag(CrashReportsHomeCardTag).assertDoesNotExist()
        assertEquals(DiagnosticsSettings(crashReports = true, asked = true), preferences.settings.value)
    }

    @Test
    fun `a person who answered before never sees the card`() {
        val answered = FakeDiagnosticsPreferences(DiagnosticsSettings(crashReports = false, asked = true))
        val viewModel = CrashReportsViewModel(answered, TEST_BUILD)
        compose.setContent { Themed { CrashReportsHomeCard(viewModel = viewModel) } }

        compose.waitForIdle()
        compose.onNodeWithTag(CrashReportsHomeCardTag).assertDoesNotExist()
    }

    // --- The document a lawyer and a translator review ---

    private val document = File("../../docs/legal/diagnostics-notice-v1.md")

    private fun strings(path: String): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement
        val result = linkedMapOf<String, String>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val element = children.item(i) as? Element ?: continue
            if (element.tagName == "string") result[element.getAttribute("name")] = element.textContent
        }
        return result
    }

    @Test
    fun `the notice document holds every sentence of the choice in both languages`() {
        assertTrue("Not found: ${document.path}", document.isFile)
        val text = document.readText()
        val keys = listOf("diagnostics_title", "diagnostics_intro", "diagnostics_switch") +
            listOf("sent", "not_sent", "where", "off").flatMap {
                listOf("diagnostics_${it}_title", "diagnostics_${it}_body")
            }
        val missing = mutableListOf<String>()
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-bn/strings.xml")) {
            val strings = strings(path)
            for (key in keys) {
                val value = strings[key] ?: error("$key is missing from $path")
                if (!text.contains(value)) missing += "$path $key"
            }
        }
        assertEquals("The document and the app's text differ", emptyList<String>(), missing)
        assertTrue(text.contains("`$DIAGNOSTICS_NOTICE_VERSION`"))
        assertTrue(text.contains("DRAFT") && text.contains("lawyer") && text.contains("native speaker"))
    }

    @Test
    fun `the text promises nothing it cannot keep`() {
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-bn/strings.xml")) {
            val all = strings(path).filterKeys { it.startsWith("diagnostics_") }.values.joinToString(" ")
            // Reports that were sent cannot be taken back by the switch, and the text says so.
            assertFalse(all.contains("anonymous", ignoreCase = true))
            assertFalse(all.contains("deleted immediately", ignoreCase = true))
        }
        val english = strings("src/main/res/values/strings.xml")
        assertTrue(english.getValue("diagnostics_off_body").contains("stay until the provider deletes them"))
        assertTrue(english.getValue("diagnostics_sent_body").contains("random ID"))
    }
}
