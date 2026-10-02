// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The one real behaviour of the shell: opening the dialer with 112, and never calling. */
@RunWith(AndroidJUnit4::class)
class EmergencyDialerTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the intent only opens the dialer with 112`() {
        val intent = emergencyDialIntent()

        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertNotEquals(Intent.ACTION_CALL, intent.action)
        assertEquals("tel:112", intent.dataString)
        assertEquals("112", EmergencyNumber)
    }

    @Test
    fun `reports success when an activity was started`() {
        var started: Intent? = null
        val context = object : ContextWrapper(application) {
            override fun startActivity(intent: Intent) {
                started = intent
            }
        }

        assertTrue(openEmergencyDialer(context))
        assertEquals(Intent.ACTION_DIAL, started?.action)
        assertEquals("tel:112", started?.dataString)
    }

    @Test
    fun `reports failure instead of crashing when the device has no dialer`() {
        val context = object : ContextWrapper(application) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException("no dialer")
            }
        }

        assertFalse(openEmergencyDialer(context))
    }
}
