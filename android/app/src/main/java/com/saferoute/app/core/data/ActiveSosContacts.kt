// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.data

import com.saferoute.app.core.data.local.ContactDao
import javax.inject.Inject

/** The most contacts a user can have, and so the most an SOS can reach (ADR 0024). */
const val MAX_SOS_CONTACTS = 5

/** A person an SOS may text. */
data class SosContact(val name: String, val phoneE164: String) {
    override fun toString(): String = "SosContact(hidden)"
}

/**
 * Who an SOS may alert, read from the phone only. It never touches the network, so it answers
 * the same with no signal, no data and no server (Plan v7 section 7: device-first SOS).
 *
 * The list leaves out everyone who opted out: **an opted-out contact must never be alerted**
 * (failure matrix). It can be empty: no contacts yet, all opted out, or the list was never
 * fetched on this phone. The caller must handle that, and 112 stays the first answer.
 */
interface ActiveSosContacts {

    /** At most [MAX_SOS_CONTACTS], oldest first (the newest contact is last). */
    suspend fun current(): List<SosContact>
}

class RoomActiveSosContacts @Inject constructor(private val dao: ContactDao) : ActiveSosContacts {

    override suspend fun current(): List<SosContact> =
        dao.notOptedOut(MAX_SOS_CONTACTS).map { SosContact(it.name, it.phoneE164) }
}
