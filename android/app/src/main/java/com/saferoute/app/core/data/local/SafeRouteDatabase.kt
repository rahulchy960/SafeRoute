// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The app's local SQLite database, managed by Room. Only `core/data` uses Room; the rest of the
 * app sees interfaces such as `ActiveSosContacts`.
 *
 * [VERSION] must go up by one whenever a table changes, together with a `Migration` and a row
 * in `SafeRouteDatabaseMigrationTest`. Each version's layout is exported to `app/schemas` and
 * committed. Never use `fallbackToDestructiveMigration`: it would silently delete the contacts.
 */
@Database(entities = [ContactEntity::class], version = SafeRouteDatabase.VERSION, exportSchema = true)
abstract class SafeRouteDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "saferoute.db"
    }
}
