// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.saferoute.app.core.emergency.SosEntryPoint

/** How a Quick Settings tile has to start an activity on this version of Android. */
sealed interface TileLaunch {
    /** Android 14 and newer: the system starts it from a PendingIntent. */
    data class WithPendingIntent(val pendingIntent: PendingIntent) : TileLaunch

    /** Up to Android 13: a plain Intent. */
    data class WithIntent(val intent: Intent) : TileLaunch
}

/**
 * The ONE destination of everything that leads to an emergency: [EmergencyActivity]. This
 * object builds the ways there, so that the hold in the app, the Quick Settings tile, the
 * home-screen widget and the notifications cannot differ in what they carry.
 *
 * An intent here says two things and nothing about the user: why the screen opens
 * ([SosOpenMode]) and, for a start, what was touched ([SosEntryPoint]). The screen hands a
 * start to the `StartSos` use case, which asks the consent gate; an intent alone starts
 * nothing.
 *
 * A PENDING INTENT is a permission slip: the app hands it to the system (for a tile, a
 * widget or a notification), and the system later starts exactly what it describes, as if
 * the app had. All of them here are IMMUTABLE (the holder can change nothing) and start the
 * activity directly: no receiver or service in between, which Android has not allowed for a
 * notification tap since version 12.
 */
object EmergencyShortcut {

    /** Android 14: the first version on which a tile must use a PendingIntent. */
    private const val PENDING_INTENT_REQUIRED_SDK = Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * The emergency screen opened for a reason of the app's own: to show what is running or
     * the emergency options ([SosOpenMode.OPEN]), to ask about "I'm safe", or for a practice
     * run. Only this app can send it: the activity is not exported.
     *
     * NEW_TASK: the caller may be a service or the system, not an activity. CLEAR_TOP: a
     * second tap shows a new screen in place of the old one instead of stacking another.
     */
    fun modeIntent(context: Context, mode: SosOpenMode): Intent =
        Intent(context, EmergencyActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_MODE, mode.name)

    /** "Start an SOS", with what the user touched. The consent gate is asked by the screen. */
    fun startIntent(context: Context, entryPoint: SosEntryPoint): Intent =
        modeIntent(context, SosOpenMode.START).putExtra(EXTRA_ENTRY, entryPoint.name)

    /**
     * [startIntent] for the system to start later. One request code per entry point: Android
     * tells PendingIntents apart by that number, not by their extras, so without it the
     * tile's and the widget's would be one and the same.
     */
    fun startPendingIntent(context: Context, entryPoint: SosEntryPoint): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_START + entryPoint.ordinal,
            startIntent(context, entryPoint),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** A tap on a notification's body: the emergency screen, showing what is going on. */
    fun openPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            modeIntent(context, SosOpenMode.OPEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun tileLaunch(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): TileLaunch =
        if (sdkInt >= PENDING_INTENT_REQUIRED_SDK) {
            TileLaunch.WithPendingIntent(startPendingIntent(context, SosEntryPoint.TILE))
        } else {
            TileLaunch.WithIntent(startIntent(context, SosEntryPoint.TILE))
        }

    /** "I'm safe" on the running-SOS notification: the emergency screen, asking to confirm. */
    fun safePendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_SAFE,
            modeIntent(context, SosOpenMode.SAFE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** What the intent says was touched. Anything missing or unknown counts as the app. */
    fun entryPointOf(intent: Intent?): SosEntryPoint =
        intent?.getStringExtra(EXTRA_ENTRY)
            ?.let { name -> SosEntryPoint.entries.firstOrNull { it.name == name } }
            ?: SosEntryPoint.IN_APP

    private const val REQUEST_OPEN = 10
    private const val REQUEST_START = 20
    private const val REQUEST_SAFE = 200
    internal const val EXTRA_ENTRY = "com.saferoute.app.extra.EMERGENCY_ENTRY"
    internal const val EXTRA_MODE = "com.saferoute.app.extra.EMERGENCY_MODE"
}
