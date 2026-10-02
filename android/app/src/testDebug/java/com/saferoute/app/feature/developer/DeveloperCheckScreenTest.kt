// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.developer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.testing.assertMinTouchTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The developer screen and the Settings row on their own (debug builds only). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class DeveloperCheckScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var checks = 0
    private var backs = 0

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun show(state: DeveloperCheckUiState) = compose.setContent {
        SafeRouteTheme {
            DeveloperCheckScreen(state = state, onBack = { backs++ }, onCheckAgain = { checks++ })
        }
    }

    @Test
    fun `a healthy server shows version, ready and the request id`() {
        show(
            DeveloperCheckUiState(
                serverConfigured = true,
                health = ProbeState.Ok("0.1.0"),
                readiness = ProbeState.Ok(null),
                lastRequestId = "req-shown-abcdef",
            ),
        )

        compose.onNodeWithText(string(R.string.developer_check_configured)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.developer_check_health_ok, "0.1.0")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.developer_check_ready)).assertIsDisplayed()
        compose.onNodeWithText("req-shown-abcdef").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.developer_check_again))
            .performScrollTo()
            .assertIsEnabled()
            .assertMinTouchTarget()
            .performClick()
        assertEquals(1, checks)
    }

    @Test
    fun `no configured server says so and offers no check`() {
        show(DeveloperCheckUiState(serverConfigured = false))

        compose.onNodeWithText(string(R.string.developer_check_not_configured)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.developer_check_request_id_none)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.developer_check_again)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `each failure has its own message`() {
        show(
            DeveloperCheckUiState(
                serverConfigured = true,
                health = ProbeState.Failed(FailureReason.NoConnection),
                readiness = ProbeState.Failed(FailureReason.Unavailable),
            ),
        )

        compose.onNodeWithText(string(R.string.developer_check_no_connection)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.developer_check_unavailable)).assertIsDisplayed()
    }

    @Test
    fun `server errors and unexpected answers show status and code`() {
        show(
            DeveloperCheckUiState(
                serverConfigured = true,
                health = ProbeState.Failed(FailureReason.Unexpected(502)),
                readiness = ProbeState.Failed(FailureReason.ServerError(500, "internal_error")),
            ),
        )

        compose.onNodeWithText(string(R.string.developer_check_unexpected, 502)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.developer_check_server_error, 500, "internal_error"))
            .assertIsDisplayed()
    }

    @Test
    fun `while checking both lines say so and the button is off`() {
        show(
            DeveloperCheckUiState(
                serverConfigured = true,
                health = ProbeState.Checking,
                readiness = ProbeState.Checking,
            ),
        )

        compose.onNodeWithText(string(R.string.developer_check_again)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the back arrow goes back`() {
        show(DeveloperCheckUiState(serverConfigured = true))

        compose.onNodeWithContentDescription(string(R.string.navigate_back))
            .assertMinTouchTarget()
            .performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `the settings row is a large enough button`() {
        var opened = 0
        compose.setContent { SafeRouteTheme { DeveloperSettingsRow(onClick = { opened++ }) } }

        compose.onNodeWithText(string(R.string.developer_settings_title))
            .assertMinTouchTarget()
            .performClick()
        assertEquals(1, opened)
    }
}
