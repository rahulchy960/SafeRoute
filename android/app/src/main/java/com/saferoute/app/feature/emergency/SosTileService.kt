// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.saferoute.app.R

/**
 * The "SafeRoute SOS" tile in Quick Settings (the panel that opens when the status bar is
 * pulled down): the dependable emergency shortcut outside the app.
 *
 * A TileService is not a service that keeps running. Android binds it for a moment when the
 * tile is added, shown or tapped, and lets it go again. It is declared as an "active" tile in
 * the manifest: Android then does not wake it every time the panel opens, only for a tap.
 *
 * A tap opens [EmergencyActivity] through [EmergencyShortcut], also from the lock screen (that
 * activity is safe there; see its notes). Nothing is logged and nothing is stored.
 */
class SosTileService : TileService() {

    /** The panel is showing the tile: make sure it reads as something that can be tapped. */
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.sos_tile_label)
        tile.contentDescription = getString(R.string.sos_control_description)
        // An action, not a switch: "inactive" is the look of a tile that is tappable and not
        // "on". (STATE_UNAVAILABLE would grey it out and refuse taps.)
        tile.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(R.string.sos_tile_subtitle)
        }
        tile.updateTile()
    }

    // The Intent form is the only one that exists before Android 14, and from Android 14 on it
    // throws: EmergencyShortcut.tileLaunch picks the right one for this phone.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        when (val launch = EmergencyShortcut.tileLaunch(this)) {
            is TileLaunch.WithPendingIntent ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(launch.pendingIntent)
                }
            is TileLaunch.WithIntent -> {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launch.intent)
            }
        }
    }
}
