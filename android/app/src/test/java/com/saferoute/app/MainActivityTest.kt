// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Starts the real [MainActivity] (splash theme, edge-to-edge, Hilt, Compose) and checks the
 * promises the manifest makes.
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class MainActivityTest {

    // Hilt's container must exist before the activity is created, hence the order.
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    // Signed in and ready: the app's own screens are shown. The fake replaces the real session,
    // so the test needs no Firebase and never calls the server this machine's build points at.
    @BindValue
    @JvmField
    val session: Session = FakeSession(SessionState.Ready)

    @Test
    fun `a signed-in, ready user launches into the home screen`() {
        val activity = compose.activity

        compose.onNodeWithText(activity.getString(R.string.search_hint)).assertIsDisplayed()
        compose.onNodeWithContentDescription(activity.getString(R.string.sos_control_description)).assertIsDisplayed()
    }

    @Test
    fun `application id is the permanent one`() {
        assertEquals("com.saferoute.app", compose.activity.packageName)
    }

    @Test
    fun `the app asks for exactly these permissions and never for background location`() {
        val activity = compose.activity
        val info = activity.packageManager.getPackageInfo(
            activity.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        // AndroidX adds one private permission named after the app itself (used to keep its own
        // broadcast receivers unexported). Anything else is a real permission and needs a prompt
        // that asks for it. INTERNET came with the API client (P008).
        //
        // ACCESS_NETWORK_STATE and READ_GSERVICES are not in our manifest: they are merged in
        // from the Firebase sign-in libraries (P009b, ADR 0012). Both are granted at install
        // without a dialog.
        //
        // The two location permissions (P010b, ADR 0015) show where the user is on the map.
        // They are "dangerous" permissions: Android asks the user at runtime, and the app asks
        // only after the user tapped "my location" and read the app's own explanation.
        val requested = info.requestedPermissions.orEmpty()
            .filterNot { it.startsWith(activity.packageName) }
        assertEquals(
            setOf(
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                "com.google.android.providers.gsf.permission.READ_GSERVICES",
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            requested.toSet(),
        )
        // Foreground only (Plan v7 5.3): none of these may ever appear without a new prompt.
        listOf(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE,
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            Manifest.permission.ACCESS_WIFI_STATE,
        ).forEach { assertFalse(it, it in requested) }
    }

    @Test
    fun `backup is switched off`() {
        val flags = compose.activity.applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
