// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import com.saferoute.app.R
import com.saferoute.app.core.emergency.SosEngine
import com.saferoute.app.core.emergency.SosState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/*
 * The notice after a restart (P014c3, ADR 0027).
 *
 * An SOS that was active when the phone restarted (or when the app was updated) is still
 * written down on the phone, but nothing is running it: no location is recorded and no
 * unsent alert is retried. Until this prompt the user learned of it only by opening the app.
 * Now the receiver that Android calls after a restart looks at the record and, if an alert
 * had gone out, posts ONE notification that says so.
 *
 * It does nothing else. It does not continue the SOS, it starts no service (Android forbids
 * starting a location service from a restart, and the project forbids resuming an emergency
 * in the background), it reads no contact and no position, and it sends nothing. The user
 * decides: Continue, I'm safe, or Call 112.
 */

internal const val SOS_RESTART_CHANNEL_ID = "sos_after_restart"
internal const val SOS_RESTART_NOTIFICATION_ID = 1124

/** Why the app's process was started without the user: decides the notification's title. */
enum class RestartReason { PHONE_RESTARTED, APP_UPDATED }

/** Shows and removes the notice. Tests use a fake. */
interface RestartNotifier {
    fun show(reason: RestartReason)

    fun dismiss()
}

/**
 * Looks at the phone's record once and says whether the user must be told. Only an
 * emergency whose alert went out counts: a countdown that never finished sent nothing, and
 * the app asks about it when it is next opened (ADR 0027, decision 7).
 */
class SosRestartNotice @Inject constructor(
    private val engine: SosEngine,
    private val notifier: RestartNotifier,
) {
    /** True when the notice was posted. It changes nothing about the record. */
    suspend fun check(reason: RestartReason): Boolean {
        val active = when (engine.current()?.state) {
            SosState.TRIGGERED_LOCAL, SosState.SYNCING, SosState.ACTIVE -> true
            null, SosState.IDLE, SosState.ARMING, SosState.COUNTDOWN, SosState.RESOLVED -> false
        }
        if (active) notifier.show(reason)
        return active
    }
}

/**
 * High importance: unlike the silent notification of a running SOS, this one must be
 * noticed, because nothing is running. The user can still switch the category off.
 */
internal fun ensureSosRestartChannel(context: Context) {
    val channel = NotificationChannel(
        SOS_RESTART_CHANNEL_ID,
        context.getString(R.string.sos_restart_channel),
        NotificationManager.IMPORTANCE_HIGH,
    ).apply { description = context.getString(R.string.sos_restart_channel_description) }
    context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
}

/**
 * Fixed text only: it is shown on the lock screen. Three ways on, each an immutable activity
 * PendingIntent:
 * - a tap, and "Continue": the emergency screen, which says "SOS is still active" and picks
 *   the SOS up again while it is visible;
 * - "I'm safe": the same screen, which asks for the unlock and then for the confirmation;
 * - "Call 112": the dialer with 112 entered. Nothing is dialled by the app.
 */
internal fun buildSosRestartNotification(context: Context, reason: RestartReason): Notification {
    val icon = Icon.createWithResource(context, R.drawable.ic_sos_tile)
    val open = EmergencyShortcut.openPendingIntent(context)
    val text = context.getString(R.string.sos_restart_text)
    return Notification.Builder(context, SOS_RESTART_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_sos_tile)
        .setContentTitle(
            context.getString(
                when (reason) {
                    RestartReason.PHONE_RESTARTED -> R.string.sos_restart_title_phone
                    RestartReason.APP_UPDATED -> R.string.sos_restart_title_update
                },
            ),
        )
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(open)
        .setOnlyAlertOnce(true)
        .setLocalOnly(true)
        .setCategory(Notification.CATEGORY_ALARM)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .addAction(Notification.Action.Builder(icon, context.getString(R.string.sos_recovered_continue), open).build())
        .addAction(
            Notification.Action.Builder(
                icon,
                context.getString(R.string.sos_active_safe),
                EmergencyShortcut.safePendingIntent(context),
            ).build(),
        )
        .addAction(
            Notification.Action.Builder(
                icon,
                context.getString(R.string.emergency_notification_call),
                dialPendingIntent(context),
            ).build(),
        )
        .build()
}

/** [RestartNotifier] on a phone. With notifications switched off nothing can be shown. */
class AndroidRestartNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : RestartNotifier {

    private val manager get() = context.getSystemService(NotificationManager::class.java)

    override fun show(reason: RestartReason) {
        ensureSosRestartChannel(context)
        try {
            manager?.notify(SOS_RESTART_NOTIFICATION_ID, buildSosRestartNotification(context, reason))
        } catch (_: SecurityException) {
            // Notifications are not allowed. Opening the app shows the SOS, as before.
        }
    }

    override fun dismiss() {
        manager?.cancel(SOS_RESTART_NOTIFICATION_ID)
    }
}
