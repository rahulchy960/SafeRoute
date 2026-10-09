// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Where an emergency shortcut outside the app was used. Not stored, not logged. */
enum class EmergencyEntry { QuickSettingsTile, Notification }

/** How a Quick Settings tile has to start an activity on this version of Android. */
sealed interface TileLaunch {
    /** Android 14 and newer: the system starts it from a PendingIntent. */
    data class WithPendingIntent(val pendingIntent: PendingIntent) : TileLaunch

    /** Up to Android 13: a plain Intent. */
    data class WithIntent(val intent: Intent) : TileLaunch
}

/**
 * The ONE destination of every emergency shortcut outside the app (ADR 0008, note of
 * 2026-10-08): the Quick Settings tile now, the pinned notification next.
 *
 * Today the destination is [EmergencyActivity], which shows the same dialog as the in-app SOS
 * control. P014 changes what that destination does (the countdown); the shortcuts keep
 * calling this object and do not change.
 */
object EmergencyShortcut {

    /** Android 14: the first version on which a tile must use a PendingIntent. */
    private const val PENDING_INTENT_REQUIRED_SDK = Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * NEW_TASK: the caller is a service or the system, not an activity. CLEAR_TOP: a second
     * tap shows the screen that is already there instead of stacking another.
     */
    fun intent(context: Context, entry: EmergencyEntry): Intent =
        Intent(context, EmergencyActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ENTRY, entry.name)

    /**
     * The same destination for the system to start later. IMMUTABLE: whoever holds it can
     * start exactly this and change nothing about it. One request code per entry, so the two
     * shortcuts never overwrite each other's PendingIntent.
     */
    fun pendingIntent(context: Context, entry: EmergencyEntry): PendingIntent =
        PendingIntent.getActivity(
            context,
            entry.ordinal,
            intent(context, entry),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun tileLaunch(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): TileLaunch =
        if (sdkInt >= PENDING_INTENT_REQUIRED_SDK) {
            TileLaunch.WithPendingIntent(pendingIntent(context, EmergencyEntry.QuickSettingsTile))
        } else {
            TileLaunch.WithIntent(intent(context, EmergencyEntry.QuickSettingsTile))
        }

    /**
     * The emergency screen opened for a reason of the app's own: the completed hold
     * ([SosOpenMode.START]), a practice run, or to show what is running. Only this app can
     * send it: the activity is not exported.
     */
    fun modeIntent(context: Context, mode: SosOpenMode): Intent =
        Intent(context, EmergencyActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_MODE, mode.name)

    /** "I'm safe" on the running-SOS notification: the emergency screen, asking to confirm. */
    fun safePendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_SAFE,
            modeIntent(context, SosOpenMode.SAFE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private const val REQUEST_SAFE = 200
    internal const val EXTRA_ENTRY = "com.saferoute.app.extra.EMERGENCY_ENTRY"
    internal const val EXTRA_MODE = "com.saferoute.app.extra.EMERGENCY_MODE"
}
