// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.settings

import androidx.lifecycle.ViewModel
import com.saferoute.app.feature.emergency.TileAddResult
import com.saferoute.app.feature.emergency.TileAdder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What Settings says after "Add the SOS tile" was tapped. */
enum class TileNotice {
    Added,
    AlreadyAdded,

    /** Android did not add it (or can not ask on this version): show how to do it by hand. */
    ShowSteps,
}

/**
 * The "Emergency shortcuts" section of Settings. For now: adding the Quick Settings tile.
 *
 * Nothing is stored: whether the tile is in Quick Settings is Android's knowledge, not the
 * app's, and the app does not ask for it.
 */
@HiltViewModel
class EmergencyShortcutsViewModel @Inject constructor(
    private val tileAdder: TileAdder,
) : ViewModel() {

    private val _tileNotice = MutableStateFlow<TileNotice?>(null)
    val tileNotice: StateFlow<TileNotice?> = _tileNotice.asStateFlow()

    /** Only ever called from a tap: Android wants the request made in context. */
    fun onAddTileClick() {
        tileAdder.requestAdd { result ->
            _tileNotice.value = when (result) {
                TileAddResult.Added -> TileNotice.Added
                TileAddResult.AlreadyAdded -> TileNotice.AlreadyAdded
                TileAddResult.NotAdded -> TileNotice.ShowSteps
            }
        }
    }

    fun onTileNoticeDismiss() {
        _tileNotice.value = null
    }
}
