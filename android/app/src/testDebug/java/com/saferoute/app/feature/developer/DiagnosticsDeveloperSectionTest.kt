// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.feature.developer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.diagnostics.DiagnosticsSettings
import com.saferoute.app.core.diagnostics.ErrorCategory
import com.saferoute.app.core.diagnostics.FakeDiagnosticsPreferences
import com.saferoute.app.core.diagnostics.FakeDiagnosticsReporter
import com.saferoute.app.core.diagnostics.TEST_BUILD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The crash-reporting test buttons of the debug Developer screen (ADR 0028). */
@RunWith(AndroidJUnit4::class)
class DiagnosticsDeveloperSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val reporter = FakeDiagnosticsReporter()

    private fun string(id: Int): String = context.getString(id)

    private fun viewModel(choice: Boolean, buildAllows: Boolean) = DiagnosticsDeveloperViewModel(
        FakeDiagnosticsPreferences(DiagnosticsSettings(crashReports = choice, asked = true)),
        reporter,
        TEST_BUILD.copy(reportsAllowed = buildAllows),
    )

    @Test
    fun `the state line says why reports are or are not collected`() {
        assertEquals(CollectionState.ON, viewModel(choice = true, buildAllows = true).collection.value)
        assertEquals(CollectionState.CHOICE_OFF, viewModel(choice = false, buildAllows = true).collection.value)
        // The build's flag wins over the choice.
        assertEquals(CollectionState.BUILD_OFF, viewModel(choice = true, buildAllows = false).collection.value)
    }

    @Test
    fun `test non-fatal goes through the reporter with the test category`() {
        viewModel(choice = true, buildAllows = true).testNonFatal()

        assertEquals(listOf(DeveloperTestCrash::class.java.name to ErrorCategory.TEST), reporter.errors)
    }

    @Test
    fun `test crash throws an error nobody catches`() {
        assertThrows(DeveloperTestCrash::class.java) { viewModel(choice = true, buildAllows = true).testCrash() }
    }

    @Test
    fun `the buttons and the state are on the screen`() {
        val taps = mutableListOf<String>()
        compose.setContent {
            SafeRouteTheme {
                DiagnosticsDeveloperButtons(
                    collection = CollectionState.BUILD_OFF,
                    onTestNonFatal = { taps += "non-fatal" },
                    onTestCrash = { taps += "crash" },
                )
            }
        }

        compose.onNodeWithText(string(R.string.developer_crash_reports_build_off)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.developer_test_non_fatal)).performClick()
        compose.onNodeWithText(string(R.string.developer_test_crash)).performClick()

        assertEquals(listOf("non-fatal", "crash"), taps)
    }
}
