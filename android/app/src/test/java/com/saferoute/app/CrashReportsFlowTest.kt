// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.diagnostics.DiagnosticsSettings
import com.saferoute.app.core.diagnostics.FakeDiagnosticsPreferences
import com.saferoute.app.core.diagnostics.FakeDiagnosticsReporter
import com.saferoute.app.core.emergency.SosEngine
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import com.saferoute.app.feature.diagnostics.CrashReportsHomeCardTag
import com.saferoute.app.feature.diagnostics.CrashReportsSwitchTag
import com.saferoute.app.feature.diagnostics.ProblemReportTextTag
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The choice about crash reports in the real [MainActivity] (ADR 0028): the onboarding step
 * for a new person, and the card on Home for a person who was onboarded before the choice
 * existed. Session, stored choice and reporter are fakes; no library and no server.
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class CrashReportsFlowTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val fakeSession = FakeSession(SessionState.SignedOut)

    @BindValue
    @JvmField
    val session: Session = fakeSession

    @Inject lateinit var preferences: FakeDiagnosticsPreferences

    @Inject lateinit var reporter: FakeDiagnosticsReporter

    @Inject lateinit var engine: SosEngine

    @Before
    fun inject() {
        hilt.inject()
    }

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun waitForText(id: Int) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(string(id)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `a new person is asked before sign-in, and continuing unticked leads on with a no`() {
        preferences.clearAsked()
        waitForText(R.string.diagnostics_title)
        // The phone number is not asked for yet.
        compose.onNodeWithText(string(R.string.phone_title)).assertDoesNotExist()

        compose.onNodeWithText(string(R.string.onboarding_continue)).performScrollTo().performClick()

        waitForText(R.string.phone_title)
        assertEquals(DiagnosticsSettings(crashReports = false, asked = true), preferences.settings.value)
        assertFalse(reporter.collecting)
    }

    @Test
    fun `ticking the box in onboarding stores a yes`() {
        preferences.clearAsked()
        waitForText(R.string.diagnostics_title)

        compose.onNodeWithTag(CrashReportsSwitchTag).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.onboarding_continue)).performScrollTo().performClick()

        waitForText(R.string.phone_title)
        assertEquals(DiagnosticsSettings(crashReports = true, asked = true), preferences.settings.value)
    }

    @Test
    fun `a person who was asked before goes straight to sign-in`() {
        waitForText(R.string.phone_title)
        compose.onNodeWithText(string(R.string.diagnostics_title)).assertDoesNotExist()
    }

    @Test
    fun `a person onboarded earlier sees the card on Home once, beside a working SOS control`() {
        preferences.clearAsked()
        fakeSession.setState(SessionState.Ready)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(string(R.string.sheet_handle_description)).performClick()
        waitForTag(CrashReportsHomeCardTag)

        // A card, not a dialog: the emergency control is still there and usable.
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.diagnostics_home_no)).performClick()

        compose.waitUntil(5_000) { preferences.settings.value.asked }
        compose.onNodeWithTag(CrashReportsHomeCardTag).assertDoesNotExist()
        assertEquals(DiagnosticsSettings(crashReports = false, asked = true), preferences.settings.value)
        // Answering starts nothing else.
        assertNull(runBlocking { engine.current() })
    }

    @Test
    fun `Settings leads to Report a problem, which shows the text and notes what was recorded`() {
        fakeSession.setState(SessionState.Ready)
        compose.waitForIdle()
        reporter.events.clear()

        compose.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
        compose.onNodeWithText(string(R.string.report_title)).performScrollTo().performClick()
        waitForTag(ProblemReportTextTag)

        compose.onNodeWithText("SafeRoute problem report", substring = true).assertExists()
        compose.onNodeWithText(string(R.string.report_share)).assertExists()
        compose.onNodeWithText(string(R.string.report_note_hint)).assertExists()
        // Opening and reading the report records nothing and starts nothing.
        assertEquals(emptyList<Any>(), reporter.events)
        assertNull(runBlocking { engine.current() })
    }

    @Test
    fun `by default in tests Home shows no such card`() {
        fakeSession.setState(SessionState.Ready)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(string(R.string.sheet_handle_description)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(CrashReportsHomeCardTag).assertDoesNotExist()
    }
}
