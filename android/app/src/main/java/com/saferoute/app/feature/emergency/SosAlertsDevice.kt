// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import com.saferoute.app.R
import com.saferoute.app.core.data.ActiveSosContacts
import com.saferoute.app.core.emergency.ContactsFreshener
import com.saferoute.app.core.emergency.SmsComposer
import com.saferoute.app.core.emergency.SmsMode
import com.saferoute.app.core.emergency.SmsModeSource
import com.saferoute.app.core.emergency.SosAlertPolicy
import com.saferoute.app.core.emergency.SosLanguage
import com.saferoute.app.core.emergency.SosMessageSettings
import com.saferoute.app.core.session.AppLocale
import com.saferoute.app.core.session.LOCALE_BENGALI
import com.saferoute.app.feature.contacts.ContactsRepository
import com.saferoute.app.feature.contacts.ContactsResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/*
 * The phone's side of the SOS alerts (ADR 0027, note of P014b2): the gate, the way of
 * sending, the SMS app as the fallback, and the fresh look at the contacts.
 */

/**
 * The gate for SOS alerts. **Closed**: the notice that describes the alerts (version 2 of the
 * `sos_alerts` notice) does not exist yet, so no user can have agreed to it, and no alert may
 * be sent (ADR 0010). P014b3 replaces this class with one that reads the user's recorded
 * consent from the phone. Until then an SOS messages nobody, and its screens say so.
 */
class ClosedSosAlertPolicy @Inject constructor() : SosAlertPolicy {
    override suspend fun alertsAllowed(): Boolean = false
}

/**
 * The name and language of the messages. The app has no name for the user yet (the SOS setup
 * of P014c asks for one), so the message uses its wording for "no name". The language is the
 * one the app is shown in.
 */
class AppSosMessageSettings @Inject constructor(private val locale: AppLocale) : SosMessageSettings {
    override suspend fun name(): String? = null

    override suspend fun language(): SosLanguage =
        if (locale.current() == LOCALE_BENGALI) SosLanguage.BN else SosLanguage.EN
}

class AndroidSmsModeSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsModeSource {
    override fun mode(): SmsMode = if (canSendSmsAutomatically(context)) SmsMode.AUTOMATIC else SmsMode.COMPOSER
}

/**
 * Fetches the contact list once more and says who may still be alerted. Null when the fetch
 * failed: the phone's copy stays as it was and nobody is taken off. The caller limits the time.
 */
class RepositoryContactsFreshener @Inject constructor(
    private val repository: ContactsRepository,
    private val contacts: ActiveSosContacts,
) : ContactsFreshener {
    override suspend fun allowedContactIds(): Set<String>? =
        when (repository.refresh()) {
            is ContactsResult.Ok -> contacts.current().map { it.id }.toSet()
            is ContactsResult.Failed -> null
        }
}

internal const val SOS_COMPOSER_CHANNEL_ID = "sos_composer"
internal const val SOS_COMPOSER_NOTIFICATION_ID = 1122
private const val REQUEST_COMPOSER = 300

/**
 * The intent that opens the phone's SMS app with [text] ready for all of [phones]. Nothing is
 * sent: the user reads it there and presses Send. Several numbers are separated by `;`, the
 * form most SMS apps accept (some need a comma; what each phone does is a manual check).
 */
internal fun composerIntent(phones: List<String>, text: String): Intent =
    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phones.joinToString(";")))
        .putExtra("sms_body", text)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal fun ensureSosComposerChannel(context: Context) {
    val channel = NotificationChannel(
        SOS_COMPOSER_CHANNEL_ID,
        context.getString(R.string.sos_composer_channel),
        // High: it must be seen at once, it is the only way the alert gets sent.
        NotificationManager.IMPORTANCE_HIGH,
    ).apply { description = context.getString(R.string.sos_composer_channel_description) }
    context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
}

/**
 * "Send your SOS alert": a tap opens the SMS app with the message. The texts on it are fixed;
 * the numbers and the message travel inside the PendingIntent, which is not shown anywhere
 * and cannot be changed by whoever holds it. The second button is Call 112.
 */
internal fun buildSosComposerNotification(context: Context, phones: List<String>, text: String): Notification {
    val open = PendingIntent.getActivity(
        context,
        REQUEST_COMPOSER,
        composerIntent(phones, text),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Builder(context, SOS_COMPOSER_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_sos_tile)
        .setContentTitle(context.getString(R.string.sos_composer_title))
        .setContentText(context.getString(R.string.sos_composer_text))
        .setContentIntent(open)
        .setAutoCancel(true)
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
 * [SmsComposer] on a phone. It always posts the notification, and it opens the SMS app
 * directly when the app is in front (Android refuses that from the background, for example
 * while the phone is locked). With notifications switched off and the app in the background
 * nothing can be shown; the active screen then offers the same step as a button.
 */
class AndroidSmsComposer @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsComposer {

    private val manager get() = context.getSystemService(NotificationManager::class.java)

    override fun show(phones: List<String>, text: String) {
        if (phones.isEmpty()) return
        ensureSosComposerChannel(context)
        try {
            manager?.notify(SOS_COMPOSER_NOTIFICATION_ID, buildSosComposerNotification(context, phones, text))
        } catch (_: SecurityException) {
            // Notifications are not allowed.
        }
        if (appIsInFront()) {
            try {
                context.startActivity(composerIntent(phones, text))
            } catch (_: ActivityNotFoundException) {
                // No SMS app on this device. The notification and the screen stay.
            } catch (_: SecurityException) {
                // Android refused the start after all.
            }
        }
    }

    override fun dismiss() {
        manager?.cancel(SOS_COMPOSER_NOTIFICATION_ID)
    }

    private fun appIsInFront(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
