// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search

import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.location.LocationEnvironment
import com.saferoute.app.core.location.LocationRepository
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapSelection
import java.time.Clock
import javax.inject.Inject

/** What the area of a search, and so the distances in its answer, are measured from. */
enum class NearSource {
    /** The user's own position, as the phone last knew it. */
    Position,

    /** The middle of what the map shows. */
    MapCentre,
}

/** The area a search prefers. It can be the user's position: `toString()` hides it. */
data class SearchArea(val point: LatLng, val source: NearSource) {
    override fun toString(): String = "SearchArea(hidden)"
}

/** A position older than this says where the user was, not where they are. */
internal const val SEARCH_POSITION_MAX_AGE_MILLIS = 5 * 60_000L

/**
 * Decides where a search looks first (ADR 0018, "Local ranking" and its note of 2026-10-08).
 *
 * 1. **The user's position**, when the location permission is granted right now and the phone's
 *    position is at most five minutes old. "bank" then means a bank near the user, wherever the
 *    map happens to look.
 * 2. Otherwise **the middle of the map**, if the map is zoomed in on an area.
 * 3. Otherwise nothing: the server uses its default.
 *
 * It only READS what is already there. It never starts location updates, never asks for the
 * permission and stores nothing: a search works exactly as before for someone who never allowed
 * location. The point leaves the phone rounded to two decimals, about 1 km
 * (`ApiSearchRepository`), and only as part of the search request to SafeRoute's own API.
 */
class SearchAreaProvider @Inject constructor(
    private val location: LocationRepository,
    private val environment: LocationEnvironment,
    private val selection: MapSelection,
    private val clock: Clock,
) {

    fun current(): SearchArea? = recentPosition()?.let { SearchArea(it, NearSource.Position) }
        ?: selection.viewCentre?.let { SearchArea(it, NearSource.MapCentre) }

    private fun recentPosition(): LatLng? {
        // Asked again for every search: the permission can be taken away while the app is open,
        // and a position the app may no longer read must not be used.
        if (environment.granted() == GrantedLocation.None) return null
        val fix = when (val state = location.state.value) {
            is LocationState.Fix -> state.fix
            is LocationState.Stale -> state.lastFix
            else -> return null
        }
        // Age by the clock, not by the state's own label: updates stop while Home is not on
        // screen, so a "current" position can have grown old without anyone noticing.
        val age = clock.millis() - fix.timeMillis
        return fix.position.takeIf { age in 0..SEARCH_POSITION_MAX_AGE_MILLIS }
    }
}
