// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
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
 * The real DataStore on a temporary file. Opening the same file a second time is what the app
 * sees after Android killed its process ("process death").
 *
 * Runs under Robolectric so that DataStore sees a modern Android version and replaces its file
 * the way it does on a phone; on a plain JVM it falls back to a rename that Windows refuses.
 */
@RunWith(AndroidJUnit4::class)
class DataStoreSessionStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "session.preferences_pb") }

    /** Runs [block] with a store on [file], then closes it so the file can be opened again. */
    private fun <T> withStore(block: suspend (DataStoreSessionStore) -> T): T = runBlocking {
        val job = Job()
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        try {
            block(DataStoreSessionStore(dataStore))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `a fresh install has no flags`() {
        assertEquals(SessionFlags(), withStore { it.read() })
    }

    @Test
    fun `every flag survives a restart of the process`() {
        val written = SessionFlags(
            welcomeSeen = true,
            ageConfirmed = true,
            under18 = false,
            acceptedNoticeVersion = NOTICE_VERSION,
            acceptedNoticeLocale = LOCALE_BENGALI,
            readyOnce = true,
        )
        withStore { store -> store.update { written } }

        assertEquals(written, withStore { it.read() })
    }

    @Test
    fun `updates build on the stored value`() {
        withStore { store ->
            store.update { it.copy(welcomeSeen = true) }
            store.update { it.copy(ageConfirmed = true) }
        }
        val flags = withStore { it.read() }
        assertTrue(flags.welcomeSeen)
        assertTrue(flags.ageConfirmed)
        assertFalse(flags.readyOnce)
    }

    @Test
    fun `a notice version can be removed again`() {
        withStore { store ->
            store.update { it.copy(acceptedNoticeVersion = NOTICE_VERSION, acceptedNoticeLocale = LOCALE_ENGLISH) }
            store.update { it.copy(acceptedNoticeVersion = null, acceptedNoticeLocale = null) }
        }
        assertEquals(SessionFlags(), withStore { it.read() })
    }

    @Test
    fun `clear goes back to a fresh install`() {
        withStore { store ->
            store.update { ONBOARDED.copy(readyOnce = true) }
            store.clear()
        }
        assertEquals(SessionFlags(), withStore { it.read() })
    }

    @Test
    fun `the flow emits the current flags and then every change`() = withStore { store ->
        store.flags.test {
            assertEquals(SessionFlags(), awaitItem())
            store.update { it.copy(under18 = true) }
            assertEquals(SessionFlags(under18 = true), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an unreadable file is treated as a fresh install instead of crashing`() {
        file.writeBytes(byteArrayOf(0x13, 0x37, 0x00, 0x7f, 0x01))
        assertEquals(SessionFlags(), withStore { it.read() })
    }

    @Test
    fun `the file holds flags and a notice version only`() {
        withStore { store -> store.update { ONBOARDED.copy(readyOnce = true) } }

        val content = file.readBytes().toString(Charsets.ISO_8859_1)
        val keys = listOf(
            "welcome_seen",
            "age_confirmed",
            "under_18",
            "accepted_notice_version",
            "accepted_notice_locale",
            "ready_once",
        )
        for (key in keys) assertTrue(key, content.contains(key))
        // Nothing that could be a phone number, a token or an SMS code.
        for (forbidden in listOf("phone", "token", "otp", "code", "+91", "uid")) {
            assertFalse(forbidden, content.contains(forbidden, ignoreCase = true))
        }
    }
}
