// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Starts the real [MainActivity] (splash theme, edge-to-edge, Hilt, Compose) and checks the
 * promises the manifest makes.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class MainActivityTest {

    // Hilt's container must exist before the activity is created, hence the order.
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `launches into the home screen`() {
        val activity = compose.activity

        compose.onNodeWithText(activity.getString(R.string.search_hint)).assertIsDisplayed()
        compose.onNodeWithText(activity.getString(R.string.emergency_button_label)).assertIsDisplayed()
    }

    @Test
    fun `application id is the permanent one`() {
        assertEquals("com.saferoute.app", compose.activity.packageName)
    }

    @Test
    fun `the app asks for network permissions only, none of them shown to the user`() {
        val activity = compose.activity
        val info = activity.packageManager.getPackageInfo(
            activity.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        // AndroidX adds one private permission named after the app itself (used to keep its own
        // broadcast receivers unexported). Anything else is a real permission and needs a prompt
        // that asks for it. INTERNET came with the API client (P008).
        //
        // The other two are not in our manifest: they are merged in from the Firebase sign-in
        // libraries (P009b, ADR 0012). ACCESS_NETWORK_STATE (firebase-auth, reCAPTCHA) lets the
        // SDK see whether there is a connection. READ_GSERVICES (reCAPTCHA) reads Google Play
        // services' settings. Both are granted at install without a dialog, and neither gives
        // access to location, contacts, SMS or the phone number.
        val requested = info.requestedPermissions.orEmpty()
            .filterNot { it.startsWith(activity.packageName) }
        assertEquals(
            setOf(
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                "com.google.android.providers.gsf.permission.READ_GSERVICES",
            ),
            requested.toSet(),
        )
    }

    @Test
    fun `backup is switched off`() {
        val flags = compose.activity.applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
