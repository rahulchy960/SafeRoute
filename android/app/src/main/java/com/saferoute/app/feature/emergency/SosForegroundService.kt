// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.saferoute.app.R
import com.saferoute.app.core.di.ApplicationScope
import com.saferoute.app.core.emergency.SosHost
import com.saferoute.app.core.emergency.SosRunState
import com.saferoute.app.core.emergency.SosRunner
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.location.LocationEnvironment
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * How a running emergency stays alive and visible (ADR 0027).
 *
 * A FOREGROUND SERVICE is a part of the app that Android keeps running while no screen is
 * open, on the condition that it shows a notification the whole time. This one has the type
 * "location": only with it does Android keep delivering positions while the app is not on
 * screen. It runs only during an emergency the user started, and ends with it.
 *
 * Two things Android does not allow, and what happens then:
 * - A "location" service cannot start without a location permission, or while the app is in
 *   the background. The emergency then runs WITHOUT the service: the same notification is
 *   posted as a plain one, and the trail gets positions only while the app is on screen.
 * - With notifications switched off the service still runs, but nothing shows in the
 *   notification drawer. The SOS screen itself must say that an emergency is active.
 *
 * The notification holds fixed text only: no name, number, place or count.
 */

internal const val SOS_RUNNING_CHANNEL_ID = "sos_running"

/** One id for the service's notification and the plain one, so there is never a second. */
internal const val SOS_RUNNING_NOTIFICATION_ID = 1121

internal fun ensureSosRunningChannel(context: Context) {
    val channel = NotificationChannel(
        SOS_RUNNING_CHANNEL_ID,
        context.getString(R.string.sos_running_channel),
        // Low: no sound and no pop-up. The countdown has its own vibration.
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.sos_running_channel_description)
        setShowBadge(false)
        enableVibration(false)
        enableLights(false)
        setSound(null, null)
    }
    context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
}

/**
 * "SOS countdown" or "SOS active". A tap opens the emergency screen. Buttons: Call 112 (the
 * dialer; nothing is dialled) and, once the SOS is active, "I'm safe", which opens the
 * emergency screen and asks for the unlock and a confirmation there. Public on the lock
 * screen: there is nothing private in it.
 */
internal fun buildSosRunningNotification(context: Context, active: Boolean): Notification {
    val icon = Icon.createWithResource(context, R.drawable.ic_sos_tile)
    val builder = Notification.Builder(context, SOS_RUNNING_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_sos_tile)
        .setContentTitle(
            context.getString(if (active) R.string.sos_running_title_active else R.string.sos_running_title_countdown),
        )
        .setContentText(context.getString(R.string.sos_running_text))
        .setContentIntent(EmergencyShortcut.pendingIntent(context, EmergencyEntry.Notification))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setLocalOnly(true)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .addAction(
            Notification.Action.Builder(
                icon,
                context.getString(R.string.emergency_notification_call),
                dialPendingIntent(context),
            ).build(),
        )
    if (active) {
        builder.addAction(
            Notification.Action.Builder(
                icon,
                context.getString(R.string.sos_active_safe),
                EmergencyShortcut.safePendingIntent(context),
            ).build(),
        )
    }
    return builder.build()
}

private fun postPlain(context: Context, active: Boolean) {
    ensureSosRunningChannel(context)
    try {
        context.getSystemService(NotificationManager::class.java)
            ?.notify(SOS_RUNNING_NOTIFICATION_ID, buildSosRunningNotification(context, active))
    } catch (_: SecurityException) {
        // Notifications are not allowed. The emergency does not depend on them.
    }
}

/** [SosHost] on a phone: the foreground service where Android allows it, else a notification. */
class AndroidSosHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val environment: LocationEnvironment,
    // Lazy: the runner needs a host, and the host only asks the runner what to show.
    private val runner: Lazy<SosRunner>,
) : SosHost {

    private val serviceIntent get() = Intent(context, SosForegroundService::class.java)

    override fun begin() {
        if (environment.granted() != GrantedLocation.None) {
            try {
                context.startForegroundService(serviceIntent)
                return
            } catch (_: IllegalStateException) {
                // Started from the background (ForegroundServiceStartNotAllowedException).
            } catch (_: SecurityException) {
                // The permission went away a moment ago.
            }
        }
        postPlain(context, active = runner.get().state.value is SosRunState.Active)
    }

    override fun end() {
        context.stopService(serviceIntent)
        context.getSystemService(NotificationManager::class.java)?.cancel(SOS_RUNNING_NOTIFICATION_ID)
    }
}

/**
 * The service itself is a shell: it holds the notification and follows [SosRunner]. The
 * countdown and every decision live in the runner, so they are the same with and without
 * the service.
 *
 * `START_NOT_STICKY`: if Android kills the process, the service is NOT restarted by the
 * system. A restarted "location" service is not promised positions, and a half-alive
 * emergency is worse than a clear question at the next app start (recovery, ADR 0027).
 */
@AndroidEntryPoint
class SosForegroundService : Service() {

    @Inject lateinit var runner: SosRunner

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = runner.state.value is SosRunState.Active
        ensureSosRunningChannel(this)
        try {
            // Must follow startForegroundService() within a few seconds, whatever happens next.
            ServiceCompat.startForeground(
                this,
                SOS_RUNNING_NOTIFICATION_ID,
                buildSosRunningNotification(this, active),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (_: IllegalStateException) {
            return runWithoutService(active, startId)
        } catch (_: SecurityException) {
            return runWithoutService(active, startId)
        }
        if (watcher == null) watcher = appScope.launch { runner.state.collect(::follow) }
        return START_NOT_STICKY
    }

    /** Android refused the foreground start: keep the notification, drop the service. */
    private fun runWithoutService(active: Boolean, startId: Int): Int {
        postPlain(this, active)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun follow(state: SosRunState) {
        if (state == SosRunState.Idle) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            postPlain(this, active = state is SosRunState.Active)
        }
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }
}
