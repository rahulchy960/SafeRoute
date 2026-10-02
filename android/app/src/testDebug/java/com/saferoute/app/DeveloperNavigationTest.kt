// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.network.ProbeResult
import com.saferoute.app.core.network.ServerCheck
import com.saferoute.app.core.network.ServerCheckModule
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
@UninstallModules(ServerCheckModule::class)
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

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    @Test
    fun `settings has a developer row that opens the server check`() {
        compose.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
        compose.onNodeWithText(string(R.string.developer_settings_title)).performClick()

        compose.onNodeWithText(string(R.string.developer_check_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.developer_check_health_ok, "9.9.9-fake")).assertIsDisplayed()
        compose.onNodeWithText("req-fake-ready").assertIsDisplayed()

        compose.onNodeWithContentDescription(string(R.string.navigate_back)).performClick()
        compose.onNodeWithText(string(R.string.settings_about_title)).assertIsDisplayed()
    }
}
