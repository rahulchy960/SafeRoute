// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.component

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.MinTouchTarget
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behaviour and accessibility of the design-system components.
 *
 * These run on the JVM with Robolectric, a library that stands in for the Android framework,
 * so no phone or emulator is needed.
 */
@RunWith(AndroidJUnit4::class)
class ComponentsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(id: Int): String = context.getString(id)

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun SemanticsNodeInteraction.assertMinTouchTarget(): SemanticsNodeInteraction =
        assertWidthIsAtLeast(MinTouchTarget).assertHeightIsAtLeast(MinTouchTarget)

    /** The four components as they appear together, at the given font scale. */
    @Composable
    private fun Gallery(fontScale: Float = 1f, onEvent: (String) -> Unit = {}) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme {
                Column(Modifier.width(360.dp)) {
                    SearchPill(hint = string(R.string.search_hint), onClick = { onEvent("search") })
                    MapControlButton(
                        painter = painterResource(R.drawable.ic_my_location),
                        contentDescription = string(R.string.my_location_off),
                        onClick = { onEvent("location") },
                        enabled = false,
                    )
                    MapControlButton(
                        painter = painterResource(R.drawable.ic_layers),
                        contentDescription = string(R.string.map_control_layers_unavailable),
                        onClick = { onEvent("layers") },
                    )
                    EmergencyButton(onClick = { onEvent("emergency") })
                    SheetHandle(
                        stateDescription = string(R.string.sheet_state_half),
                        onClick = { onEvent("handle") },
                        onExpand = { onEvent("expand") },
                        onCollapse = null,
                    )
                }
            }
        }
    }

    @Test
    fun `search pill is a button that reports taps`() {
        val events = mutableListOf<String>()
        compose.setContent { Gallery(onEvent = events::add) }

        compose.onNodeWithText(string(R.string.search_hint))
            .assertIsDisplayed()
            .assert(hasRole(Role.Button))
            .assertMinTouchTarget()
            .performClick()

        assertEquals(listOf("search"), events)
    }

    @Test
    fun `map control buttons are described and a disabled one does not fire`() {
        val events = mutableListOf<String>()
        compose.setContent { Gallery(onEvent = events::add) }

        compose.onNodeWithContentDescription(string(R.string.my_location_off))
            .assertIsDisplayed()
            .assert(hasRole(Role.Button))
            .assertIsNotEnabled()
            .assertMinTouchTarget()
            .performClick()
        compose.onNodeWithContentDescription(string(R.string.map_control_layers_unavailable))
            .assertIsEnabled()
            .assertMinTouchTarget()
            .performClick()

        assertEquals(listOf("layers"), events)
    }

    @Test
    fun `emergency button shows 112, has a spoken action and reports taps`() {
        val events = mutableListOf<String>()
        compose.setContent { Gallery(onEvent = events::add) }

        val label = string(R.string.emergency_button_label)
        assert(label.contains("112"))
        compose.onNodeWithText(label)
            .assertIsDisplayed()
            .assert(hasRole(Role.Button))
            .assertHasClickAction()
            .assert(
                SemanticsMatcher("click action is labelled") {
                    it.config.getOrNull(SemanticsActions.OnClick)?.label ==
                        string(R.string.emergency_button_click_label)
                },
            )
            .assertMinTouchTarget()
            .performClick()

        assertEquals(listOf("emergency"), events)
    }

    @Test
    fun `sheet handle exposes state, tap and only the actions it was given`() {
        val events = mutableListOf<String>()
        compose.setContent { Gallery(onEvent = events::add) }

        compose.onNodeWithContentDescription(string(R.string.sheet_handle_description))
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    string(R.string.sheet_state_half),
                ),
            )
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.Collapse))
            .assertMinTouchTarget()
            .performSemanticsAction(SemanticsActions.Expand)
            .performClick()

        assertEquals(listOf("expand", "handle"), events)
    }

    @Test
    fun `at 200 percent font every control is still there and big enough`() {
        compose.setContent { Gallery(fontScale = 2f) }

        compose.onNodeWithText(string(R.string.search_hint)).assertIsDisplayed().assertMinTouchTarget()
        compose.onNodeWithText(string(R.string.emergency_button_label))
            .assertIsDisplayed()
            .assertMinTouchTarget()
        compose.onNodeWithContentDescription(string(R.string.map_control_layers_unavailable))
            .assertIsDisplayed()
            .assertMinTouchTarget()
        compose.onNodeWithContentDescription(string(R.string.sheet_handle_description))
            .assertIsDisplayed()
            .assertMinTouchTarget()
    }

    @Test
    @Config(qualifiers = "bn")
    fun `bengali resources are used when the language is bengali`() {
        compose.setContent { Gallery() }

        // Literal expected values: this must fail if the app fell back to English.
        compose.onNodeWithText("জরুরি 112").assertIsDisplayed()
        compose.onNodeWithText("জায়গা খুঁজুন").assertIsDisplayed()
    }
}
