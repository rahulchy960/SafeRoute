// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import com.saferoute.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/*
 * The pinned emergency notification (P012f2, ADR 0008): an OPTIONAL shortcut the user switches
 * on. It shows fixed text and two buttons. It carries nothing about the user: no name, no
 * number, no position, no state.
 *
 * It is an ordinary notification. There is no foreground service behind it, so nothing of the
 * app keeps running for it. The price: the user can swipe it away (Android 14 and newer), and
 * the system or a phone maker's battery manager can remove it. The app puts it back when it is
 * opened, after a restart and after an update. The Quick Settings tile is the dependable
 * shortcut.
 */

/** The channel's id. Channels are what the user sees and switches under Settings > Notifications. */
internal const val EMERGENCY_CHANNEL_ID = "emergency_shortcut"

/** One notification, one id: posting again replaces it instead of adding a second one. */
internal const val EMERGENCY_NOTIFICATION_ID = 1120

/** Request codes of the notification's own PendingIntents (the tile uses the entry's ordinal). */
private const val REQUEST_DIAL = 100

/** Why the notification can not be shown right now, if anything stops it. */
enum class NotificationBlock {
    None,

    /** Android 13 and newer: the user has not allowed notifications for the app. */
    PermissionMissing,

    /** Notifications are switched off for the whole app in system settings. */
    AppBlocked,

    /** The app may notify, but the user switched this one category off. */
    ChannelBlocked,
}

/** What Android says about posting notifications. Tests use a fake. */
interface NotificationGate {
    fun block(): NotificationBlock
}

/** Posts and removes the notification. Tests use a fake. */
interface EmergencyNotifier {
    fun post()

    fun cancel()
}

/**
 * Opens the dialer with 112 straight from the notification's button. An activity
 * PendingIntent, not a broadcast: since Android 12 an app may not start an activity from a
 * receiver or service in answer to a notification tap.
 */
internal fun dialPendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        REQUEST_DIAL,
        // ACTION_DIAL shows the dialer; it never calls. Written out here (and not taken from the
        // home feature) so that this file has no dependency on a screen.
        Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", "112", null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

/**
 * The channel: low importance, so the notification sits in the shade without sound, vibration
 * or a pop-up. Creating a channel that exists already changes nothing, and never overrides
 * what the user chose for it.
 */
internal fun ensureEmergencyChannel(context: Context) {
    val channel = NotificationChannel(
        EMERGENCY_CHANNEL_ID,
        context.getString(R.string.emergency_notification_channel),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.emergency_notification_channel_description)
        setShowBadge(false)
        enableVibration(false)
        enableLights(false)
        setSound(null, null)
    }
    context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
}

/**
 * The notification itself. Fixed text only.
 *
 * - ongoing: asks Android to keep it; the user can still swipe it away on Android 14+.
 * - public: shown in full on the lock screen; there is nothing private in it.
 * - a tap, and "Open SOS": the same emergency screen as the tile ([EmergencyShortcut]).
 * - "Call 112": the dialer with 112 entered. Nothing is dialled automatically.
 */
internal fun buildEmergencyNotification(context: Context): Notification {
    val open = EmergencyShortcut.pendingIntent(context, EmergencyEntry.Notification)
    val icon = Icon.createWithResource(context, R.drawable.ic_sos_tile)
    return Notification.Builder(context, EMERGENCY_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_sos_tile)
        .setContentTitle(context.getString(R.string.emergency_notification_title))
        .setContentText(context.getString(R.string.emergency_notification_text))
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setLocalOnly(true)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .addAction(
            Notification.Action.Builder(
                icon,
                context.getString(R.string.emergency_notification_call),
                dialPendingIntent(context),
            ).build(),
        )
        .addAction(
            Notification.Action.Builder(
                icon,
                context.getString(R.string.emergency_notification_open),
                open,
            ).build(),
        )
        .build()
}

class AndroidEmergencyNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : EmergencyNotifier {

    private val manager get() = context.getSystemService(NotificationManager::class.java)

    override fun post() {
        ensureEmergencyChannel(context)
        try {
            manager?.notify(EMERGENCY_NOTIFICATION_ID, buildEmergencyNotification(context))
        } catch (_: SecurityException) {
            // The permission went away between the check and the call: nothing is shown, and
            // the next check says why.
        }
    }

    override fun cancel() {
        manager?.cancel(EMERGENCY_NOTIFICATION_ID)
    }
}

class AndroidNotificationGate @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationGate {

    override fun block(): NotificationBlock {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return NotificationBlock.AppBlocked
        // The runtime permission exists from Android 13; before that, installing is allowing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return NotificationBlock.PermissionMissing
        }
        if (!manager.areNotificationsEnabled()) return NotificationBlock.AppBlocked
        // A channel the user switched off has importance "none". No channel yet: not blocked.
        val importance = manager.getNotificationChannel(EMERGENCY_CHANNEL_ID)?.importance
        return if (importance == NotificationManager.IMPORTANCE_NONE) {
            NotificationBlock.ChannelBlocked
        } else {
            NotificationBlock.None
        }
    }
}
