// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * The app's local SQLite database, managed by Room. Only `core/data` uses Room; the rest of the
 * app sees interfaces such as `ActiveSosContacts`.
 *
 * [VERSION] must go up by one whenever a table changes, together with a `Migration` and a row
 * in `SafeRouteDatabaseMigrationTest`. Each version's layout is exported to `app/schemas` and
 * committed. Never use `fallbackToDestructiveMigration`: it would silently delete the contacts.
 */
@Database(
    entities = [ContactEntity::class, SosRecordEntity::class, SosActionEntity::class, SosPointEntity::class],
    version = SafeRouteDatabase.VERSION,
    exportSchema = true,
)
abstract class SafeRouteDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao

    abstract fun sosDao(): SosDao

    companion object {
        const val VERSION = 2
        const val FILE_NAME = "saferoute.db"

        /** Every step from an older version to the current one, in order. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
