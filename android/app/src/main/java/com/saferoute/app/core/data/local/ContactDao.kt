// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * How the app reads and writes the `contacts` table. A DAO ("data access object") is an
 * interface; Room writes the code behind it at build time and checks every SQL statement
 * against the table while doing so.
 *
 * `suspend` functions run off the main thread. A function that returns a `Flow` keeps
 * emitting: whoever collects it gets the new list every time the table changes.
 */
@Dao
interface ContactDao {

    /** Oldest first, so the newest contact is last. */
    @Query("SELECT * FROM contacts ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts ORDER BY createdAt ASC, id ASC")
    suspend fun all(): List<ContactEntity>

    /** The contacts that may be alerted: everyone who has not opted out. */
    @Query("SELECT * FROM contacts WHERE optedOutAt IS NULL ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun notOptedOut(limit: Int): List<ContactEntity>

    @Insert
    suspend fun insertAll(contacts: List<ContactEntity>)

    /** Inserts the row, or replaces the one with the same id. */
    @Upsert
    suspend fun upsert(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM contacts")
    suspend fun clear()

    /**
     * Swaps the whole table for [contacts]. `@Transaction`: both steps happen or neither, so a
     * reader never sees an empty list in between and a crash cannot leave one behind.
     */
    @Transaction
    suspend fun replaceAll(contacts: List<ContactEntity>) {
        clear()
        insertAll(contacts)
    }
}
