// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A place the user picked to look at on the map (a search result, for now).
 *
 * It says where the user means to go, so it is treated like a position: `toString()` hides it,
 * and it is never logged, sent or written to a file.
 */
data class SelectedPlace(val name: String, val label: String, val position: LatLng) {
    override fun toString(): String = "SelectedPlace(hidden)"
}

/**
 * What the map screen and the screens around it share: the place to show, and where the map is
 * looking.
 *
 * Search and Home are separate screens with separate ViewModels. Search must tell Home "show
 * this place", and must know roughly which area the map shows so that results nearby come
 * first. This small object carries both.
 *
 * `@ActivityRetainedScoped`: one instance for as long as the activity lives, rotations included.
 * It is created with the activity's ViewModels and thrown away with them. It lives in memory
 * only.
 */
@ActivityRetainedScoped
class MapSelection @Inject constructor() {

    private val _selected = MutableStateFlow<SelectedPlace?>(null)

    /** The place to show on the map, or null. */
    val selected: StateFlow<SelectedPlace?> = _selected.asStateFlow()

    /**
     * Where the map was looking when it last came to rest; null before the map has reported
     * and while the map shows the whole region. A search prefers this area when it can not use
     * the user's recent position (see `SearchAreaProvider`, P011e2).
     */
    @Volatile
    var viewCentre: LatLng? = null

    fun select(place: SelectedPlace) {
        _selected.value = place
    }

    fun clear() {
        _selected.value = null
    }
}
