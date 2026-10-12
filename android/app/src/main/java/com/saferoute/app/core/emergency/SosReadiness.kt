// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import java.time.Duration
import java.time.Instant

/** What the "SOS setup" screen checks, in the order it shows them. */
enum class ReadinessItem { ALERTS, CONTACTS, SMS, LOCATION, NOTIFICATIONS, BATTERY }

/** What a check found. Shown as a word on the screen, never as a colour alone. */
enum class ReadinessStatus {
    OK,

    /** Something the user can change, and should, before an emergency. */
    NEEDS_ACTION,

    /** The app cannot tell. */
    CANNOT_KNOW,

    /** A fact about how alerts work on this phone or in this build; nothing to do. */
    INFO,
}

/** How current the copy of the contact list on this phone is. */
sealed interface ContactsFreshness {
    /** The list was never fetched on this phone (or the copy was emptied since). */
    data object Never : ContactsFreshness

    data object Today : ContactsFreshness

    data object Yesterday : ContactsFreshness

    data class DaysAgo(val days: Int) : ContactsFreshness
}

/** What the phone allows for location, as far as an SOS cares. */
enum class ReadinessLocation { PRECISE, APPROXIMATE, NOT_ALLOWED, SWITCHED_OFF }

/**
 * Everything the screen looks at, read from the phone at one moment. It holds counts and
 * yes-or-no answers: no name, no number, no place.
 */
data class ReadinessFacts(
    /** The user has agreed to the alerts notice the app shows today (the consent gate). */
    val alertsSetUp: Boolean,
    /** Contacts on THIS phone who have not opted out: the people an SOS would text. */
    val activeContacts: Int,
    /** When the contact list was last fetched from the server; null when never. */
    val contactsSyncedAt: Instant?,
    /** The app sends the SMS by itself (the permission is granted). */
    val smsAutomatic: Boolean,
    /** This build could send by itself if the user allowed it. False in a release build today. */
    val smsCanBeAutomatic: Boolean,
    val location: ReadinessLocation,
    val notificationsAllowed: Boolean,
)

/** The checklist and its sum. */
data class SosReadiness(
    val items: Map<ReadinessItem, ReadinessStatus>,
    val freshness: ContactsFreshness,
    /** How many rows the user can and should fix. */
    val toFix: Int,
) {
    /** "Ready" means: nothing left that the app can see. It promises nothing about an alert. */
    val ready: Boolean get() = toFix == 0

    fun status(item: ReadinessItem): ReadinessStatus = items.getValue(item)
}

private const val HOURS_PER_DAY = 24L

/** [syncedAt] in words, as seen at [now]. A time in the future (a changed clock) is "today". */
fun contactsFreshness(syncedAt: Instant?, now: Instant): ContactsFreshness {
    if (syncedAt == null) return ContactsFreshness.Never
    val days = (Duration.between(syncedAt, now).toHours() / HOURS_PER_DAY).toInt()
    return when {
        days <= 0 -> ContactsFreshness.Today
        days == 1 -> ContactsFreshness.Yesterday
        else -> ContactsFreshness.DaysAgo(days)
    }
}

/**
 * The checklist for [facts] at [now]. A pure function: it reads nothing and decides nothing
 * about an emergency. An SOS never asks it; it exists so that the user learns BEFORE an
 * emergency what would not work (failure matrix, rows A6 and A7).
 *
 * What counts as "to fix":
 * - no agreement to the current notice: no SOS can be started at all;
 * - no contact on this phone. If the list was never fetched the app cannot know whether
 *   there are none or whether they never arrived ([ReadinessStatus.CANNOT_KNOW]); that
 *   counts too, because "Refresh" settles it;
 * - the SMS app instead of automatic sending, but only in a build that could send by
 *   itself: otherwise it is how this build works ([ReadinessStatus.INFO]);
 * - anything less than precise location; notifications not allowed.
 *
 * The battery row never counts: no app can see what a phone maker's battery manager will
 * do, so it is always "cannot know", with guidance.
 */
fun sosReadiness(facts: ReadinessFacts, now: Instant): SosReadiness {
    val items = mapOf(
        ReadinessItem.ALERTS to if (facts.alertsSetUp) ReadinessStatus.OK else ReadinessStatus.NEEDS_ACTION,
        ReadinessItem.CONTACTS to when {
            facts.activeContacts > 0 -> ReadinessStatus.OK
            facts.contactsSyncedAt == null -> ReadinessStatus.CANNOT_KNOW
            else -> ReadinessStatus.NEEDS_ACTION
        },
        ReadinessItem.SMS to when {
            facts.smsAutomatic -> ReadinessStatus.OK
            facts.smsCanBeAutomatic -> ReadinessStatus.NEEDS_ACTION
            else -> ReadinessStatus.INFO
        },
        ReadinessItem.LOCATION to
            if (facts.location == ReadinessLocation.PRECISE) ReadinessStatus.OK else ReadinessStatus.NEEDS_ACTION,
        ReadinessItem.NOTIFICATIONS to
            if (facts.notificationsAllowed) ReadinessStatus.OK else ReadinessStatus.NEEDS_ACTION,
        ReadinessItem.BATTERY to ReadinessStatus.CANNOT_KNOW,
    )
    val toFix = items.count { (item, status) ->
        status == ReadinessStatus.NEEDS_ACTION ||
            (item == ReadinessItem.CONTACTS && status == ReadinessStatus.CANNOT_KNOW)
    }
    return SosReadiness(items, contactsFreshness(facts.contactsSyncedAt, now), toFix)
}
