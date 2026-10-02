// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.feature.search.SearchScreen
import com.saferoute.app.feature.settings.SettingsScreen
import com.saferoute.app.testing.assertMinTouchTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Search and Settings screens on their own, without navigation. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class ScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var backPresses = 0

    private fun string(id: Int): String = context.getString(id)

    @Composable
    private fun Themed(fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme(content = content)
        }
    }

    @Composable
    private fun Settings(fontScale: Float = 1f) = Themed(fontScale) {
        SettingsScreen(versionName = "9.8.7", versionCode = 42, onBack = { backPresses++ })
    }

    private fun backButton() = compose.onNodeWithContentDescription(string(R.string.navigate_back))

    @Test
    fun `search has a focused text field, an empty state and a back button`() {
        compose.setContent { Themed { SearchScreen(onBack = { backPresses++ }) } }

        val field = compose.onNode(hasSetTextAction())
        field.assertIsDisplayed().assertIsFocused()
        field.performTextInput("museum")
        field.assertTextContains("museum")

        compose.onNodeWithText(string(R.string.search_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.search_empty_body)).assertIsDisplayed()

        backButton().assertMinTouchTarget()
        backButton().performClick()
        assertEquals(1, backPresses)
    }

    @Test
    fun `settings shows the version, the licence, the repository and the safety notice`() {
        compose.setContent { Settings() }

        compose.onNodeWithText(string(R.string.settings_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_version, "9.8.7", 42))
            .assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_licence)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_source_code)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.not_emergency_service)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_safety_notice_body)).assertIsDisplayed()

        backButton().performClick()
        assertEquals(1, backPresses)
    }

    @Test
    fun `the language row is announced as disabled and says coming soon`() {
        compose.setContent { Settings() }

        compose.onNodeWithText(string(R.string.settings_language_title))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText(string(R.string.settings_language_supporting), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `at 200 percent font settings can be scrolled to every item`() {
        compose.setContent { Settings(fontScale = 2f) }

        backButton().assertIsDisplayed().assertMinTouchTarget()
        compose.onNodeWithText(string(R.string.settings_language_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_version, "9.8.7", 42), useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(string(R.string.not_emergency_service), useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
