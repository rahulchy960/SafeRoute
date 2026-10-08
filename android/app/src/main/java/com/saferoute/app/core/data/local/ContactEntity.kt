// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row of the phone's copy of the user's emergency contacts (ADR 0024). The server holds the
 * list; this table is what the app reads when there is no network, and what SOS will read.
 *
 * `@Entity` tells Room to create a table with one column per property.
 *
 * PERSONAL DATA of people who are not users: [name] and [phoneE164]. The table is in the app's
 * private storage and is never backed up (`allowBackup=false`). It is emptied on sign-out.
 * Times are milliseconds since 1970 in UTC.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    /** The server's id of the contact (a UUID). */
    @PrimaryKey val id: String,
    val name: String,
    val phoneE164: String,
    val createdAt: Long,
    /** When the user said the invite SMS was sent; null until then. */
    val invitedAt: Long?,
    /** When the contact opted out; such a contact must never be alerted. */
    val optedOutAt: Long?,
) {
    // The generated toString() would print a name and a phone number.
    override fun toString(): String = "ContactEntity(hidden)"
}
