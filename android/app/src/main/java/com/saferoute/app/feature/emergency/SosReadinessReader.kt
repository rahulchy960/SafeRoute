// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import com.saferoute.app.BuildConfig
import com.saferoute.app.core.data.ActiveSosContacts
import com.saferoute.app.core.emergency.ReadinessFacts
import com.saferoute.app.core.emergency.ReadinessLocation
import com.saferoute.app.core.emergency.SmsMode
import com.saferoute.app.core.emergency.SmsModeSource
import com.saferoute.app.core.emergency.SosAlertPolicy
import com.saferoute.app.core.emergency.SosReadiness
import com.saferoute.app.core.emergency.sosReadiness
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.location.LocationEnvironment
import com.saferoute.app.feature.contacts.ContactsPreferences
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** One look at the phone: the facts, and the checklist they give. No name, number or place. */
data class ReadinessReading(
    val facts: ReadinessFacts,
    val readiness: SosReadiness,
)

/**
 * Reads, at this moment, everything that decides whether an SOS from this phone would reach
 * anyone. The "SOS setup" screen and the card on Home both use it, so that they cannot
 * disagree about what "not ready" means.
 *
 * It only reads the phone: the consent flag, the copy of the contacts, what Android says
 * about permissions and switches. It asks for no permission, starts no location updates,
 * touches no network, keeps nothing and logs nothing. Each call is a fresh look; nothing
 * runs between calls.
 */
class SosReadinessReader @Inject constructor(
    private val policy: SosAlertPolicy,
    private val contacts: ActiveSosContacts,
    private val preferences: ContactsPreferences,
    private val smsMode: SmsModeSource,
    private val environment: LocationEnvironment,
    private val notifications: NotificationGate,
    private val clock: Clock,
) {

    /** Whether this build could send SMS by itself. A release build cannot today (ADR 0027). */
    internal var smsDeclared: Boolean = BuildConfig.SEND_SMS_DECLARED

    suspend fun read(): ReadinessReading {
        val facts = ReadinessFacts(
            alertsSetUp = policy.alertsAllowed(),
            activeContacts = contacts.current().size,
            contactsSyncedAt = preferences.contactsSyncedAt.first().takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            smsAutomatic = smsMode.mode() == SmsMode.AUTOMATIC,
            smsCanBeAutomatic = smsDeclared,
            location = location(),
            // A single category switched off is not "notifications off": the others still show.
            notificationsAllowed = notifications.block().let {
                it == NotificationBlock.None || it == NotificationBlock.ChannelBlocked
            },
        )
        return ReadinessReading(facts, sosReadiness(facts, clock.instant()))
    }

    private fun location(): ReadinessLocation = when {
        environment.granted() == GrantedLocation.None -> ReadinessLocation.NOT_ALLOWED
        !environment.isLocationEnabled() -> ReadinessLocation.SWITCHED_OFF
        environment.granted() == GrantedLocation.Approximate -> ReadinessLocation.APPROXIMATE
        else -> ReadinessLocation.PRECISE
    }
}
