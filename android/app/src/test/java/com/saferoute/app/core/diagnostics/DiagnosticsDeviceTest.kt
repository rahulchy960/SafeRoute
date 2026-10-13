// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * What the crash-reporting library adds to the installed app, and the stored choice
 * (ADR 0028). Robolectric reads the merged manifest of the debug build, so the lists below
 * are what Android would see.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsDeviceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION")
    private fun packageInfo(flags: Int) = context.packageManager.getPackageInfo(context.packageName, flags)

    @Test
    fun `the merged manifest switches automatic collection off`() {
        @Suppress("DEPRECATION")
        val metaData = context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        assertTrue(metaData.containsKey("firebase_crashlytics_collection_enabled"))
        assertFalse(metaData.getBoolean("firebase_crashlytics_collection_enabled", true))
    }

    @Test
    fun `the library's services and receivers are the recorded ones`() {
        // Recorded in P014d1a. A change here means a library started to bring something
        // new: read what it is before changing the list.
        val services = packageInfo(PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS).services.orEmpty()
            .map { it.name }.filterNot { it.startsWith("com.saferoute.") || it.startsWith("androidx.") }
        assertEquals(
            setOf(
                "com.google.firebase.components.ComponentDiscoveryService",
                // Came with sign-in (P009b), not with crash reporting.
                "com.google.android.gms.auth.api.signin.RevocationBoundService",
                // Declared by the sessions library as switched off (enabled="false").
                "com.google.firebase.sessions.SessionLifecycleService",
                "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
                "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
            ),
            services.toSet(),
        )
        val receivers = packageInfo(PackageManager.GET_RECEIVERS).receivers.orEmpty()
            .map { it.name }.filterNot { it.startsWith("com.saferoute.") || it.startsWith("androidx.") }
        assertEquals(
            setOf(
                "com.google.android.datatransport.runtime.scheduling.jobscheduling." +
                    "AlarmManagerSchedulerBroadcastReceiver",
            ),
            receivers.toSet(),
        )
    }

    @Test
    fun `no analytics or advertising component is part of the app`() {
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS
        val info = packageInfo(flags)
        val names = listOf(info.services, info.receivers, info.providers, info.activities)
            .flatMap { it.orEmpty().toList() }.map { it.name } + info.requestedPermissions.orEmpty()
        assertTrue(names.size > 10)
        val banned = listOf("analytics", "measurement", "AD_ID", "ads.", "firebase.perf", "remoteconfig", "messaging")
        for (name in names) {
            assertTrue(name, banned.none { name.contains(it, ignoreCase = true) })
        }
    }

    @Test
    fun `the choice is off and not asked until it is made, and it is kept`() {
        val file = File(folder.root, "diagnostics.preferences_pb")

        fun <T> withStore(block: suspend (DataStoreDiagnosticsPreferences) -> T): T = runBlocking {
            val job = Job()
            val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + job),
                produceFile = { file },
            )
            try {
                block(DataStoreDiagnosticsPreferences(dataStore))
            } finally {
                job.cancelAndJoin()
            }
        }

        assertEquals(DiagnosticsSettings(crashReports = false, asked = false), withStore { it.read() })
        withStore { it.setAsked() }
        assertEquals(DiagnosticsSettings(crashReports = false, asked = true), withStore { it.read() })
        withStore { it.setCrashReports(true) }
        assertEquals(DiagnosticsSettings(crashReports = true, asked = true), withStore { it.read() })
        withStore { it.setCrashReports(false) }
        assertEquals(DiagnosticsSettings(crashReports = false, asked = true), withStore { it.read() })
    }
}
