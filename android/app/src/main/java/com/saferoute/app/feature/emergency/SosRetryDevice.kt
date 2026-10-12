// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.ContextCompat
import com.saferoute.app.R
import com.saferoute.app.core.emergency.RetrySignals
import com.saferoute.app.core.emergency.UnsentAlertNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/*
 * The phone's side of "keep trying while the SOS is active" (ADR 0027, note of P014b7): the
 * one notification about alerts that are still unsent, and the two moments at which the
 * phone itself says that another try is worth it. Nothing here logs, and nothing here holds
 * a number, a name or a text.
 */

internal const val SOS_UNSENT_CHANNEL_ID = "sos_unsent"
internal const val SOS_UNSENT_NOTIFICATION_ID = 1123
private const val REQUEST_UNSENT = 301

internal fun ensureSosUnsentChannel(context: Context) {
    val channel = NotificationChannel(
        SOS_UNSENT_CHANNEL_ID,
        context.getString(R.string.sos_unsent_channel),
        // High: after two hours the user must learn that nobody may have been told.
        NotificationManager.IMPORTANCE_HIGH,
    ).apply { description = context.getString(R.string.sos_unsent_channel_description) }
    context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
}

/**
 * "Some alerts have not been sent. Call 112 or send a text yourself." Fixed text only: it is
 * shown on the lock screen. A tap opens the emergency screen; the button opens the dialer
 * with 112 entered (the app never calls by itself).
 */
internal fun buildSosUnsentNotification(context: Context): Notification {
    val open = PendingIntent.getActivity(
        context,
        REQUEST_UNSENT,
        EmergencyShortcut.modeIntent(context, SosOpenMode.OPEN),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Builder(context, SOS_UNSENT_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_sos_tile)
        .setContentTitle(context.getString(R.string.sos_unsent_title))
        .setContentText(context.getString(R.string.sos_unsent_text))
        .setContentIntent(open)
        .setOnlyAlertOnce(true)
        .setLocalOnly(true)
        .setCategory(Notification.CATEGORY_ALARM)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_sos_tile),
                context.getString(R.string.emergency_notification_call),
                dialPendingIntent(context),
            ).build(),
        )
        .build()
}

/**
 * [UnsentAlertNotifier] on a phone. With notifications switched off nothing can be shown;
 * the active screen says the same sentence.
 */
class AndroidUnsentAlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : UnsentAlertNotifier {

    private val manager get() = context.getSystemService(NotificationManager::class.java)

    override fun show() {
        ensureSosUnsentChannel(context)
        try {
            manager?.notify(SOS_UNSENT_NOTIFICATION_ID, buildSosUnsentNotification(context))
        } catch (_: SecurityException) {
            // Notifications are not allowed.
        }
    }

    override fun dismiss() {
        manager?.cancel(SOS_UNSENT_NOTIFICATION_ID)
    }
}

/**
 * [RetrySignals] on a phone. Two signals, chosen because neither needs a permission the app
 * does not already have:
 *
 * - **Airplane mode switched off**: a broadcast only the system can send. A BROADCAST is a
 *   message Android sends to every app that registered for it; registering here, in code,
 *   lasts only while an SOS is sending.
 * - **A network became available**: a callback of ConnectivityManager. It needs
 *   ACCESS_NETWORK_STATE, a permission granted at install that is in the app's merged
 *   manifest through the sign-in libraries. Should it ever be missing, this signal is left
 *   out without an error.
 *
 * Not used: the state of the mobile network's own service ("no service" to "in service").
 * Its broadcast needs READ_PHONE_STATE, which this app must not ask for.
 *
 * A signal only asks for one more try. The regular retries do not depend on it.
 */
class AndroidRetrySignals @Inject constructor(
    @ApplicationContext private val context: Context,
) : RetrySignals {

    private var receiver: BroadcastReceiver? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)

    @Synchronized
    override fun start(onSignal: () -> Unit) {
        stop()
        val airplane = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                // "state" is true when airplane mode was switched ON: nothing to try then.
                if (!intent.getBooleanExtra("state", false)) onSignal()
            }
        }
        ContextCompat.registerReceiver(
            context,
            airplane,
            IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = airplane

        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val network = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onSignal()
        }
        try {
            connectivity?.registerDefaultNetworkCallback(network)
            callback = network
        } catch (_: SecurityException) {
            // The permission is gone after all: the signal is left out.
        } catch (_: RuntimeException) {
            // Android limits how many callbacks an app may hold; the regular retries go on.
        }
    }

    @Synchronized
    override fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        callback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        callback = null
    }
}
