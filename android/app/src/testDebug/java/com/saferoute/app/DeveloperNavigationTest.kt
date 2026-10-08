// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.network.ProbeResult
import com.saferoute.app.core.network.ServerCheck
import com.saferoute.app.core.network.ServerCheckModule
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Debug app: Home → Settings → Developer → server check → back to Settings.
 *
 * The real ServerCheck would call the server this machine's build points at, so the test swaps
 * it for a fake (`@UninstallModules` removes the debug binding, `@BindValue` adds this one).
 */
@HiltAndroidTest
@UninstallModules(ServerCheckModule::class, SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class DeveloperNavigationTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    val check: ServerCheck = object : ServerCheck {
        override val isConfigured = true

        override suspend fun health() = ProbeResult.Up("9.9.9-fake", "req-fake-health")

        override suspend fun readiness() = ProbeResult.Up(null, "req-fake-ready")
    }

    @BindValue
    @JvmField
    val session: Session = FakeSession(SessionState.Ready)

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    @Test
    fun `settings has a developer row that opens the server check`() {
        compose.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
        compose.onNodeWithText(string(R.string.developer_settings_title)).performClick()

        compose.onNodeWithText(string(R.string.developer_check_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.developer_check_health_ok, "9.9.9-fake")).assertIsDisplayed()
        compose.onNodeWithText("req-fake-ready").assertIsDisplayed()

        // The session state by name, and "Who am I" through the (fake) session.
        // "Ready" is on the screen twice: the readiness probe and the session state.
        compose.onAllNodesWithText("Ready", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText(string(R.string.developer_check_who_am_i_ask)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.developer_check_who_am_i_known, "user", "en"), useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()

        compose.onNodeWithContentDescription(string(R.string.navigate_back)).performClick()
        // Further down the list since the shortcuts section was added: it exists, it may need a scroll.
        compose.onNodeWithText(string(R.string.settings_about_title)).assertExists()
    }
}
