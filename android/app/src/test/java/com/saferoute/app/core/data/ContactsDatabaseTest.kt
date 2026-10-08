// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import com.saferoute.app.core.data.local.ContactEntity
import com.saferoute.app.core.data.local.SafeRouteDatabase
import com.saferoute.app.feature.contacts.inMemoryDatabase
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private fun row(n: Int, optedOut: Boolean = false, createdAt: Long = 1_000L * n) = ContactEntity(
    id = "id-$n",
    name = "Test Contact $n",
    phoneE164 = "+9190000100${n.toString().padStart(2, '0')}",
    createdAt = createdAt,
    invitedAt = null,
    optedOutAt = if (optedOut) 9_000L else null,
)

/** The `contacts` table and [ActiveSosContacts], on a real SQLite database in memory. */
@RunWith(AndroidJUnit4::class)
class ContactsDatabaseTest {

    private val database = inMemoryDatabase(ApplicationProvider.getApplicationContext())
    private val dao = database.contactDao()
    private val active = RoomActiveSosContacts(dao)

    @After
    fun tearDown() = database.close()

    @Test
    fun `rows come back oldest first, whatever order they were written in`() = runTest {
        dao.insertAll(listOf(row(3), row(1), row(2)))

        assertEquals(listOf("id-1", "id-2", "id-3"), dao.all().map { it.id })
    }

    @Test
    fun `replaceAll swaps the whole table and upsert replaces one row`() = runTest {
        dao.insertAll(listOf(row(1), row(2)))

        dao.replaceAll(listOf(row(2).copy(name = "Renamed"), row(3)))
        assertEquals(listOf("id-2" to "Renamed", "id-3" to "Test Contact 3"), dao.all().map { it.id to it.name })

        dao.upsert(row(3).copy(invitedAt = 5_000L))
        dao.upsert(row(4))
        assertEquals(listOf(null, 5_000L, null), dao.all().map { it.invitedAt })

        dao.delete("id-2")
        dao.delete("id-unknown")
        assertEquals(listOf("id-3", "id-4"), dao.all().map { it.id })

        dao.clear()
        assertEquals(emptyList<ContactEntity>(), dao.all())
    }

    @Test
    fun `observers get the new list when the table changes`() = runTest {
        dao.observeAll().test {
            assertEquals(emptyList<ContactEntity>(), awaitItem())
            dao.insertAll(listOf(row(1)))
            assertEquals(listOf("id-1"), awaitItem().map { it.id })
            dao.replaceAll(listOf(row(1), row(2)))
            assertEquals(listOf("id-1", "id-2"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sos contacts leave out everyone who opted out`() = runTest {
        dao.insertAll(listOf(row(1), row(2, optedOut = true), row(3)))

        assertEquals(
            listOf(SosContact("Test Contact 1", "+919000010001"), SosContact("Test Contact 3", "+919000010003")),
            active.current(),
        )
    }

    @Test
    fun `sos contacts are at most five, oldest first with the newest last`() = runTest {
        // More than five can only come from a bug elsewhere; SOS still gets five.
        dao.insertAll((7 downTo 1).map { row(it) })

        val names = active.current().map { it.name }
        assertEquals(MAX_SOS_CONTACTS, names.size)
        assertEquals((1..5).map { "Test Contact $it" }, names)
    }

    @Test
    fun `sos contacts are empty when there are none or all opted out`() = runTest {
        assertEquals(emptyList<SosContact>(), active.current())

        dao.insertAll(listOf(row(1, optedOut = true), row(2, optedOut = true)))
        assertEquals(emptyList<SosContact>(), active.current())
    }

    @Test
    fun `the types that hold a contact never print it`() {
        val printed = "${row(1)} ${SosContact("Test Contact 1", "+919000010001")}"

        assertFalse(printed.contains("Test Contact"))
        assertFalse(printed.contains("9000010001"))
    }
}

/**
 * The migration harness. `MigrationTestHelper` builds a database from an exported schema file
 * (`app/schemas`, which are test assets) and Room then checks that the tables it finds are the
 * ones the code expects. With one version there is nothing to migrate yet; the test proves the
 * harness works, so that version 2 only has to add its migration and one test here.
 */
@RunWith(AndroidJUnit4::class)
class SafeRouteDatabaseMigrationTest {

    // A file in the test's own folder, and the Android SQLite driver. (The helper's older
    // form, which takes a database NAME, compares paths by "/" and fails on Windows.)
    private val file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "migration-test.db")

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = file,
        driver = AndroidSQLiteDriver(),
        databaseClass = SafeRouteDatabase::class,
    )

    @Test
    fun `the exported schema of version 1 is what the code expects`() {
        helper.createDatabase(1).close()

        // No migrations yet. A mismatch between the schema file and the entities throws here.
        val connection = helper.runMigrationsAndValidate(SafeRouteDatabase.VERSION, emptyList())

        connection.prepare("SELECT id, name, phoneE164, createdAt, invitedAt, optedOutAt FROM contacts").use {
            assertFalse("a new database has no contacts", it.step())
        }
        connection.close()
    }

    @Test
    fun `every database version has a committed schema file`() {
        // Gradle runs unit tests with android/app as the working directory.
        val folder = File("schemas/${SafeRouteDatabase::class.java.name}")
        val versions = folder.listFiles().orEmpty().map { it.name }.sorted()

        assertEquals((1..SafeRouteDatabase.VERSION).map { "$it.json" }, versions)
        assertTrue(File(folder, "1.json").readText().contains("\"tableName\": \"contacts\""))
    }
}
