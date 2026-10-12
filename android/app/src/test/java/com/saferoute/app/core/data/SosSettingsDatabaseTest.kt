// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.saferoute.app.core.data.local.SafeRouteDatabase
import com.saferoute.app.core.data.local.SosSettingsEntity
import com.saferoute.app.core.emergency.AlertLanguageChoice
import com.saferoute.app.core.emergency.MAX_NAME_LENGTH
import com.saferoute.app.core.emergency.SosSettings
import com.saferoute.app.feature.contacts.inMemoryDatabase
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the user chose for their alerts, on a real SQLite database in memory (P014c2b). */
@RunWith(AndroidJUnit4::class)
class RoomSosSettingsStoreTest {

    private val database = inMemoryDatabase(ApplicationProvider.getApplicationContext())
    private val store = RoomSosSettingsStore(database.sosSettingsDao())

    @After
    fun tearDown() = database.close()

    @Test
    fun `with nothing chosen there is no name, the app's language and no sound`() = runTest {
        assertEquals(SosSettings(name = null, language = AlertLanguageChoice.APP, countdownSound = false), store.current())
        assertEquals(SosSettings(), store.settings.first())
    }

    @Test
    fun `a name is stored as one trimmed line of at most thirty characters, and blank removes it`() = runTest {
        store.setName("  Test \n  User\t")
        assertEquals("Test User", store.current().name)

        store.setName("x".repeat(80))
        assertEquals(MAX_NAME_LENGTH, store.current().name?.length)

        store.setName("   ")
        assertNull(store.current().name)
        store.setName("Test User")
        store.setName(null)
        assertNull(store.current().name)
    }

    @Test
    fun `each choice is kept when another one changes`() = runTest {
        store.setName("Test User")
        store.setLanguage(AlertLanguageChoice.BN)
        store.setCountdownSound(true)

        assertEquals(SosSettings("Test User", AlertLanguageChoice.BN, countdownSound = true), store.current())
        assertEquals(store.current(), store.settings.first())

        store.setCountdownSound(false)
        assertEquals(SosSettings("Test User", AlertLanguageChoice.BN, countdownSound = false), store.current())
    }

    @Test
    fun `clear removes the name and puts the choices back`() = runTest {
        store.setName("Test User")
        store.setLanguage(AlertLanguageChoice.EN)
        store.setCountdownSound(true)

        store.clear()

        assertEquals(SosSettings(), store.current())
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sos_settings").use {
            assertTrue(it.moveToFirst())
            assertEquals("no row is left on the phone", 0, it.getInt(0))
        }
    }

    @Test
    fun `the table never holds more than one row`() = runTest {
        repeat(5) { store.setName("Test User $it") }
        store.setLanguage(AlertLanguageChoice.BN)

        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sos_settings").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }

    @Test
    fun `the types that hold the name never print it`() {
        val name = "Test User"
        assertFalse(SosSettings(name = name).toString().contains(name))
        val entity = SosSettingsEntity(alertName = name, alertLanguage = AlertLanguageChoice.APP, countdownSound = false)
        assertFalse(entity.toString().contains(name))
    }

    @Test
    fun `the store never logs, prints or touches the network`() {
        for (path in listOf("core/data/RoomSosSettingsStore.kt", "core/data/local/SosSettingsEntity.kt", "core/emergency/SosSettings.kt")) {
            val code = File("src/main/java/com/saferoute/app/$path").readLines()
                .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }
                .joinToString("\n")
            for (forbidden in listOf("Log.", "println", "Timber", "okhttp", "Retrofit", "DataStore")) {
                assertFalse("$path must not use $forbidden", code.contains(forbidden))
            }
        }
    }
}

/** Version 2 to 3: the settings table is added and nothing else changes. */
@RunWith(AndroidJUnit4::class)
class SosSettingsMigrationTest {

    private val file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "settings-migration-test.db")

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = file,
        driver = AndroidSQLiteDriver(),
        databaseClass = SafeRouteDatabase::class,
    )

    @Test
    fun `version 2 to 3 keeps the contacts and the emergency records and adds the empty settings table`() {
        helper.createDatabase(2).use { old ->
            old.execSQL(
                "INSERT INTO contacts (id, name, phoneE164, createdAt, invitedAt, optedOutAt) " +
                    "VALUES ('id-1', 'Test Contact 1', '+919000010001', 1000, NULL, NULL)",
            )
            old.execSQL(
                "INSERT INTO sos_records (clientSosId, state, entryPoint, practice, startedAt, countdownEndsAt) " +
                    "VALUES ('rec-1', 'ACTIVE', 'WIDGET', 0, 1, 2)",
            )
        }

        // Throws if the table the migration made differs from what the entity describes.
        val connection = helper.runMigrationsAndValidate(3, SafeRouteDatabase.MIGRATIONS.toList())

        connection.prepare("SELECT name FROM contacts").use {
            assertTrue(it.step())
            assertEquals("Test Contact 1", it.getText(0))
        }
        connection.prepare("SELECT state, entryPoint FROM sos_records").use {
            assertTrue("an SOS that was active survives the update of the app", it.step())
            assertEquals("ACTIVE", it.getText(0))
            assertEquals("WIDGET", it.getText(1))
        }
        connection.prepare("SELECT COUNT(*) FROM sos_settings").use {
            assertTrue(it.step())
            assertEquals(0L, it.getLong(0))
        }
        connection.close()
    }

    @Test
    fun `from version 1 both steps run in order`() {
        helper.createDatabase(1).close()

        val connection = helper.runMigrationsAndValidate(3, SafeRouteDatabase.MIGRATIONS.toList())

        connection.prepare("SELECT COUNT(*) FROM sos_settings").use { assertTrue(it.step()) }
        connection.close()
        assertEquals(3, SafeRouteDatabase.VERSION)
        assertTrue(File("schemas/${SafeRouteDatabase::class.java.name}/3.json").readText().contains("\"tableName\": \"sos_settings\""))
    }
}
