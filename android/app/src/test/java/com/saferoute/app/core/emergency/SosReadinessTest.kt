// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checklist of "SOS setup" as arithmetic: no phone, no clock of its own (P014c2a). */
class SosReadinessTest {

    private val now: Instant = Instant.parse("2026-10-13T12:00:00Z")

    /** Everything in order, in a build that can send by itself. */
    private val allGood = ReadinessFacts(
        alertsSetUp = true,
        activeContacts = 2,
        contactsSyncedAt = now.minus(Duration.ofHours(3)),
        smsAutomatic = true,
        smsCanBeAutomatic = true,
        location = ReadinessLocation.PRECISE,
        notificationsAllowed = true,
    )

    private fun check(facts: ReadinessFacts) = sosReadiness(facts, now)

    @Test
    fun `with everything in order it is ready, and only the battery cannot be known`() {
        val readiness = check(allGood)

        assertTrue(readiness.ready)
        assertEquals(0, readiness.toFix)
        assertEquals(ReadinessStatus.CANNOT_KNOW, readiness.status(ReadinessItem.BATTERY))
        for (item in ReadinessItem.entries - ReadinessItem.BATTERY) {
            assertEquals("$item", ReadinessStatus.OK, readiness.status(item))
        }
        assertEquals(ContactsFreshness.Today, readiness.freshness)
    }

    @Test
    fun `without agreement to the notice the alerts row is to fix`() {
        val readiness = check(allGood.copy(alertsSetUp = false))

        assertEquals(ReadinessStatus.NEEDS_ACTION, readiness.status(ReadinessItem.ALERTS))
        assertEquals(1, readiness.toFix)
        assertFalse(readiness.ready)
    }

    @Test
    fun `no contact on the phone is to fix, and a list that was never fetched cannot be known`() {
        val none = check(allGood.copy(activeContacts = 0))
        assertEquals(ReadinessStatus.NEEDS_ACTION, none.status(ReadinessItem.CONTACTS))
        assertEquals(1, none.toFix)

        // Never fetched: the app cannot tell "no contacts" from "they never arrived". It still
        // counts, because Refresh settles it.
        val never = check(allGood.copy(activeContacts = 0, contactsSyncedAt = null))
        assertEquals(ReadinessStatus.CANNOT_KNOW, never.status(ReadinessItem.CONTACTS))
        assertEquals(ContactsFreshness.Never, never.freshness)
        assertEquals(1, never.toFix)

        // A contact on the phone is a contact an SOS would text, however it got there.
        val present = check(allGood.copy(activeContacts = 1, contactsSyncedAt = null))
        assertEquals(ReadinessStatus.OK, present.status(ReadinessItem.CONTACTS))
        assertEquals(ContactsFreshness.Never, present.freshness)
    }

    @Test
    fun `the SMS app is to fix only in a build that could send by itself`() {
        val couldSend = check(allGood.copy(smsAutomatic = false, smsCanBeAutomatic = true))
        assertEquals(ReadinessStatus.NEEDS_ACTION, couldSend.status(ReadinessItem.SMS))
        assertEquals(1, couldSend.toFix)

        // A build without the permission (a release build today): it is how alerts work
        // there, not something the user can change.
        val cannotSend = check(allGood.copy(smsAutomatic = false, smsCanBeAutomatic = false))
        assertEquals(ReadinessStatus.INFO, cannotSend.status(ReadinessItem.SMS))
        assertEquals(0, cannotSend.toFix)
        assertTrue(cannotSend.ready)
    }

    @Test
    fun `anything less than precise location is to fix`() {
        for (location in ReadinessLocation.entries - ReadinessLocation.PRECISE) {
            val readiness = check(allGood.copy(location = location))
            assertEquals("$location", ReadinessStatus.NEEDS_ACTION, readiness.status(ReadinessItem.LOCATION))
            assertEquals("$location", 1, readiness.toFix)
        }
    }

    @Test
    fun `notifications not allowed are to fix`() {
        val readiness = check(allGood.copy(notificationsAllowed = false))

        assertEquals(ReadinessStatus.NEEDS_ACTION, readiness.status(ReadinessItem.NOTIFICATIONS))
        assertEquals(1, readiness.toFix)
    }

    @Test
    fun `the sum counts every row to fix, and never the battery`() {
        val worst = check(
            ReadinessFacts(
                alertsSetUp = false,
                activeContacts = 0,
                contactsSyncedAt = null,
                smsAutomatic = false,
                smsCanBeAutomatic = true,
                location = ReadinessLocation.NOT_ALLOWED,
                notificationsAllowed = false,
            ),
        )

        assertEquals("alerts, contacts, SMS, location, notifications", 5, worst.toFix)
        assertEquals(ReadinessStatus.CANNOT_KNOW, worst.status(ReadinessItem.BATTERY))
        assertEquals(ReadinessItem.entries.toSet(), worst.items.keys)
    }

    @Test
    fun `how old the list is, in words, at several ages`() {
        fun at(age: Duration) = contactsFreshness(now.minus(age), now)

        assertEquals(ContactsFreshness.Never, contactsFreshness(null, now))
        assertEquals(ContactsFreshness.Today, at(Duration.ZERO))
        assertEquals(ContactsFreshness.Today, at(Duration.ofHours(23)))
        assertEquals(ContactsFreshness.Yesterday, at(Duration.ofHours(24)))
        assertEquals(ContactsFreshness.Yesterday, at(Duration.ofHours(47)))
        assertEquals(ContactsFreshness.DaysAgo(2), at(Duration.ofHours(48)))
        assertEquals(ContactsFreshness.DaysAgo(12), at(Duration.ofDays(12)))
        assertEquals(ContactsFreshness.DaysAgo(400), at(Duration.ofDays(400)))
        // A time in the future means the phone's clock was changed: not a negative age.
        assertEquals(ContactsFreshness.Today, contactsFreshness(now.plus(Duration.ofDays(3)), now))
    }

    @Test
    fun `an old list is said in words but is not by itself a thing to fix`() {
        val old = check(allGood.copy(contactsSyncedAt = now.minus(Duration.ofDays(90))))

        assertEquals(ContactsFreshness.DaysAgo(90), old.freshness)
        assertEquals(ReadinessStatus.OK, old.status(ReadinessItem.CONTACTS))
        assertTrue(old.ready)
    }
}
