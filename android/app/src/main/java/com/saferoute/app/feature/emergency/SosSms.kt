// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.saferoute.app.BuildConfig
import com.saferoute.app.core.emergency.SmsGateway
import com.saferoute.app.core.emergency.SmsOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The one place that sends an SMS (ADR 0027, note of P014b1). It is used only for the alerts
 * of an SOS the user started. It sends from the phone's own SIM, to the user's own emergency
 * contacts, and the user's plan may charge for each message.
 *
 * SEND_SMS is a permission Google Play restricts. It is in the manifest only of builds made
 * with it (the Gradle property saferoute.sendSmsEnabled; see android/README.md), and even
 * then the user has to grant it. Without it this gateway refuses, and the caller falls back
 * to the phone's SMS app, where the user presses Send.
 *
 * Nothing here logs: not a number, not a text, not a result.
 */

/** How long the phone gets to say what happened to a message before it counts as failed. */
private const val SENT_TIMEOUT_MILLIS = 60_000L

/** The codes Android reports for a sent message that are not worth another try. */
private val FINAL_RESULTS = setOf(
    SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE,
    SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
    SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED,
    SmsManager.RESULT_INVALID_ARGUMENTS,
    SmsManager.RESULT_INVALID_SMS_FORMAT,
    SmsManager.RESULT_ENCODING_ERROR,
    SmsManager.RESULT_REQUEST_NOT_SUPPORTED,
    SmsManager.RESULT_USER_NOT_ALLOWED,
    SmsManager.RESULT_RIL_INVALID_ARGUMENTS,
    SmsManager.RESULT_RIL_INVALID_SMS_FORMAT,
    SmsManager.RESULT_RIL_ENCODING_ERR,
    SmsManager.RESULT_RIL_REQUEST_NOT_SUPPORTED,
)

/**
 * What a result code of the "sent" broadcast means for the alert. `RESULT_OK` means the
 * phone handed the message to the network, not that it was read or even delivered.
 * Everything that may pass (no signal, radio off, a busy network, an absent SIM that may be
 * put back) is worth another try; the category is a short word for the record.
 */
internal fun smsOutcome(resultCode: Int): SmsOutcome = when (resultCode) {
    Activity.RESULT_OK -> SmsOutcome.Sent
    in FINAL_RESULTS -> SmsOutcome.Final("rejected")
    SmsManager.RESULT_ERROR_RADIO_OFF,
    SmsManager.RESULT_RADIO_NOT_AVAILABLE,
    SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE,
    -> SmsOutcome.Retryable("radio_off")
    SmsManager.RESULT_ERROR_NO_SERVICE,
    SmsManager.RESULT_RIL_NETWORK_NOT_READY,
    -> SmsOutcome.Retryable("no_service")
    SmsManager.RESULT_ERROR_LIMIT_EXCEEDED,
    SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED,
    -> SmsOutcome.Retryable("limit")
    SmsManager.RESULT_RIL_SIM_ABSENT -> SmsOutcome.Retryable("sim_absent")
    else -> SmsOutcome.Retryable("generic")
}

/** True when this build declares SEND_SMS and the user has granted it. */
internal fun canSendSmsAutomatically(context: Context): Boolean =
    BuildConfig.SEND_SMS_DECLARED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

class AndroidSmsGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsGateway {

    /**
     * The SMS service for the SIM the user chose for messages (Settings of the phone), or
     * the only SIM. The app does not list SIMs: that would need the phone-state permission.
     */
    private fun smsManager(): SmsManager? {
        val subscription = try {
            SmsManager.getDefaultSmsSubscriptionId()
        } catch (_: UnsupportedOperationException) {
            return null
        }
        val valid = subscription != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(SmsManager::class.java) ?: return null
            if (valid) manager.createForSubscriptionId(subscription) else manager
        } else {
            @Suppress("DEPRECATION")
            if (valid) SmsManager.getSmsManagerForSubscriptionId(subscription) else SmsManager.getDefault()
        }
    }

    /**
     * Sends [text] as one message (the phone splits a long one into parts and the receiver's
     * phone joins them) and waits until the phone has reported on every part.
     */
    override suspend fun send(phoneE164: String, text: String): SmsOutcome {
        if (!canSendSmsAutomatically(context)) return SmsOutcome.Final("no_permission")
        val manager = smsManager() ?: return SmsOutcome.Retryable("no_sms_service")
        val parts = try {
            manager.divideMessage(text)
        } catch (_: UnsupportedOperationException) {
            return SmsOutcome.Final("no_telephony")
        }
        if (parts.isEmpty()) return SmsOutcome.Final("empty")
        return sendParts(manager, phoneE164, parts)
    }

    /** Hands the [parts] of one message to [manager] and waits for the phone's answers. */
    internal suspend fun sendParts(manager: SmsManager, phoneE164: String, parts: ArrayList<String>): SmsOutcome {
        // An action nobody else knows, used for this one message only.
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        return withTimeoutOrNull(SENT_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                var waitingFor = parts.size
                var worst = Activity.RESULT_OK
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        if (resultCode != Activity.RESULT_OK) worst = resultCode
                        waitingFor--
                        if (waitingFor == 0) {
                            context.unregisterReceiver(this)
                            if (continuation.isActive) continuation.resume(smsOutcome(worst))
                        }
                    }
                }
                // NOT_EXPORTED: only the system and this app can deliver to it.
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(action),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                val sentIntents = ArrayList(
                    parts.indices.map { part ->
                        PendingIntent.getBroadcast(
                            context,
                            part,
                            Intent(action).setPackage(context.packageName),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                        )
                    },
                )
                val refused = try {
                    manager.sendMultipartTextMessage(phoneE164, null, parts, sentIntents, null)
                    null
                } catch (_: SecurityException) {
                    SmsOutcome.Final("no_permission")
                } catch (_: IllegalArgumentException) {
                    SmsOutcome.Final("rejected")
                } catch (_: UnsupportedOperationException) {
                    SmsOutcome.Final("no_telephony")
                }
                if (refused != null) {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (continuation.isActive) continuation.resume(refused)
                }
            }
        } ?: SmsOutcome.Retryable("timeout")
    }
}
