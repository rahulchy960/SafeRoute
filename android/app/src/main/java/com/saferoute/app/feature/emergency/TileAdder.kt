// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import com.saferoute.app.R
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** What came of asking Android to add the SOS tile to Quick Settings. */
enum class TileAddResult {
    /** The user said yes in Android's dialog. */
    Added,

    /** The tile was there already. */
    AlreadyAdded,

    /**
     * Not added: the user said no, this Android version has no such request (before 13), or
     * the request failed. The app then shows how to add the tile by hand.
     */
    NotAdded,
}

/** Asks Android to offer the SOS tile. Tests use a fake: the real one needs a status bar. */
interface TileAdder {
    fun requestAdd(onResult: (TileAddResult) -> Unit)
}

/** `StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED` and `..._TILE_ADDED`. */
private const val RESULT_ALREADY_ADDED = 1
private const val RESULT_ADDED = 2

/**
 * Android's answer as a number, turned into ours. 0 is "not added"; everything else (1000 and
 * up) is an error: wrong user, no status bar, a request already open, the app not on screen.
 * All of those are treated as "not added". The numbers are written out because the constants
 * only exist from Android 13.
 */
internal fun tileAddResult(code: Int): TileAddResult = when (code) {
    RESULT_ADDED -> TileAddResult.Added
    RESULT_ALREADY_ADDED -> TileAddResult.AlreadyAdded
    else -> TileAddResult.NotAdded
}

/**
 * Uses `StatusBarManager.requestAddTileService`, which exists from Android 13. Android shows
 * its own dialog with the app's name, the tile's label and icon; the user decides. It may only
 * be called while the app is on screen, and Android may stop showing the dialog after the user
 * has said no several times. On older versions, and whenever anything goes wrong, the answer
 * is [TileAddResult.NotAdded] and the app explains the manual way.
 */
class AndroidTileAdder @Inject constructor(
    @ApplicationContext private val context: Context,
) : TileAdder {

    override fun requestAdd(onResult: (TileAddResult) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult(TileAddResult.NotAdded)
            return
        }
        val statusBar = context.getSystemService(StatusBarManager::class.java)
        if (statusBar == null) {
            onResult(TileAddResult.NotAdded)
            return
        }
        try {
            statusBar.requestAddTileService(
                ComponentName(context, SosTileService::class.java),
                context.getString(R.string.sos_tile_label),
                Icon.createWithResource(context, R.drawable.ic_sos_tile),
                context.mainExecutor,
            ) { code -> onResult(tileAddResult(code)) }
        } catch (_: RuntimeException) {
            // Some phones have no Quick Settings at all, or refuse the call.
            onResult(TileAddResult.NotAdded)
        }
    }
}

/** One module, so that tests replace it (`FakeEmergencyShortcutsModule`). */
@Module
@InstallIn(SingletonComponent::class)
interface EmergencyShortcutsModule {

    @Binds
    fun bindTileAdder(adder: AndroidTileAdder): TileAdder
}
