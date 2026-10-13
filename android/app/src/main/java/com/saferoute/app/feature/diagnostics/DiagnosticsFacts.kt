// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.feature.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.saferoute.app.BuildConfig
import com.saferoute.app.core.diagnostics.DiagnosticKey
import com.saferoute.app.core.diagnostics.DiagnosticsFactsSource
import com.saferoute.app.core.emergency.SmsMode
import com.saferoute.app.core.emergency.SmsModeSource
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.location.LocationEnvironment
import com.saferoute.app.feature.emergency.NotificationBlock
import com.saferoute.app.feature.emergency.NotificationGate
import com.saferoute.app.feature.emergency.canSendSmsAutomatically
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

/** The phone makers the app tells apart; everything else is "other". Never the model. */
internal fun oemFamily(manufacturer: String): String = when (manufacturer.trim().lowercase(Locale.ROOT)) {
    "samsung" -> "samsung"
    "xiaomi", "redmi", "poco" -> "xiaomi"
    "oppo" -> "oppo"
    "vivo", "iqoo" -> "vivo"
    "realme" -> "realme"
    "oneplus" -> "oneplus"
    "google" -> "google"
    "motorola" -> "motorola"
    "hmd global", "nokia" -> "nokia"
    "huawei" -> "huawei"
    "honor" -> "honor"
    else -> "other"
}

/**
 * Reads the allowlisted facts about this phone (ADR 0028): words for states, never a
 * value. It asks for no permission, starts nothing and keeps nothing; each word still has
 * to pass its key's list before it goes anywhere.
 */
class AndroidDiagnosticsFacts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val location: LocationEnvironment,
    private val smsMode: SmsModeSource,
    private val notifications: NotificationGate,
) : DiagnosticsFactsSource {

    override suspend fun read(): Map<DiagnosticKey, String> = mapOf(
        DiagnosticKey.OEM_FAMILY to oemFamily(Build.MANUFACTURER.orEmpty()),
        DiagnosticKey.LOCALE to locale(),
        DiagnosticKey.NETWORK_TYPE to networkType(),
        DiagnosticKey.SMS_MODE to if (smsMode.mode() == SmsMode.AUTOMATIC) "automatic" else "composer",
        DiagnosticKey.LOCATION_PERMISSION to when (location.granted()) {
            GrantedLocation.None -> "denied"
            GrantedLocation.Approximate -> "approximate"
            else -> "precise"
        },
        DiagnosticKey.SMS_PERMISSION to when {
            !BuildConfig.SEND_SMS_DECLARED -> "not_declared"
            canSendSmsAutomatically(context) -> "granted"
            else -> "denied"
        },
        DiagnosticKey.NOTIFICATION_PERMISSION to
            if (notifications.block() == NotificationBlock.None) "granted" else "denied",
    )

    /** The app's language and, if set, the country: "bn" or "en-IN". */
    private fun locale(): String {
        val current = context.resources.configuration.locales[0]
        return listOf(current.language, current.country).filter { it.isNotEmpty() }.joinToString("-")
    }

    /**
     * The kind of connection, not its name. Reading it needs `ACCESS_NETWORK_STATE`, a
     * permission without a dialog that the libraries already bring into the manifest; if
     * it were ever missing, the answer is "other" rather than a crash.
     */
    private fun networkType(): String = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
        when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    } catch (_: SecurityException) {
        "other"
    }
}
