// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Version 1 → 2 (P014a1): adds the three emergency tables. Nothing existing changes, so the
 * contacts stay as they are.
 *
 * A migration is the list of SQL statements that turns the tables of an old version into the
 * tables of the new one on a phone that already has the app. The statements must produce
 * exactly what `app/schemas/.../2.json` describes; `SafeRouteDatabaseMigrationTest` checks it.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `sos_records` (`clientSosId` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, `entryPoint` TEXT NOT NULL, `practice` INTEGER NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `countdownEndsAt` INTEGER NOT NULL, " +
                "`triggeredAt` INTEGER, `resolvedAt` INTEGER, " +
                "`syncState` TEXT NOT NULL DEFAULT 'NOT_SYNCED', PRIMARY KEY(`clientSosId`))",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `sos_actions` (`id` TEXT NOT NULL, " +
                "`clientSosId` TEXT NOT NULL, `contactLocalId` TEXT NOT NULL, " +
                "`phoneSnapshot` TEXT NOT NULL, `nameSnapshot` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, `attemptCount` INTEGER NOT NULL, `lastErrorCategory` TEXT, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`clientSosId`) REFERENCES `sos_records`(`clientSosId`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sos_actions_clientSosId` ON `sos_actions` (`clientSosId`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `sos_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`clientSosId` TEXT NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, " +
                "`accuracyM` REAL, `recordedAt` INTEGER NOT NULL, `mockFlag` INTEGER NOT NULL, " +
                "FOREIGN KEY(`clientSosId`) REFERENCES `sos_records`(`clientSosId`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sos_points_clientSosId` ON `sos_points` (`clientSosId`)",
        )
    }
}
