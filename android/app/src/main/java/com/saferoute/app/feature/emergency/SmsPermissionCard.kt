// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.saferoute.app.BuildConfig
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

/*
 * "Send alerts automatically" (ADR 0027, note of P014b3): where the user allows the app to
 * send the SOS alerts by SMS itself, or learns that it will open the SMS app instead.
 *
 * The order is fixed: the app's own explanation first (what is sent, to whom, from which
 * SIM, that it may cost, that nothing is sent unless an SOS is started), then Android's
 * dialog. The permission is never asked for during an SOS, at app start or in onboarding.
 */

const val SmsAlertModeCardTag = "sms-alert-mode-card"

/** How SOS alerts leave this phone, as the card shows it. */
enum class SmsAlertMode {
    /** This build was made without the SMS permission: always the SMS app. */
    NOT_IN_BUILD,

    /** The permission is not granted: the SMS app, and an offer to allow sending. */
    OFF,

    /** Granted: the app sends the alerts itself. */
    ON,

    /** Refused in a way that Android no longer asks: only system Settings can change it. */
    BLOCKED,
}

/** @param blocked Android refused without showing its dialog, or the user chose "don't ask". */
fun smsAlertMode(declared: Boolean, granted: Boolean, blocked: Boolean): SmsAlertMode = when {
    !declared -> SmsAlertMode.NOT_IN_BUILD
    granted -> SmsAlertMode.ON
    blocked -> SmsAlertMode.BLOCKED
    else -> SmsAlertMode.OFF
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * The card with its real connections to Android. The state is read again every time the
 * screen comes back: the user may have changed the permission in system Settings meanwhile.
 */
@Composable
fun SmsAlertModeCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val declared = BuildConfig.SEND_SMS_DECLARED
    // Not saved across process death on purpose: "blocked" is found out again by asking.
    var blocked by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(canSendSmsAutomatically(context)) }
    var disclosureOpen by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        granted = canSendSmsAutomatically(context)
        if (granted) blocked = false
        onPauseOrDispose { }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { nowGranted ->
        granted = nowGranted
        // Refused, and Android would not explain again: it will not show its dialog any more.
        blocked = !nowGranted &&
            context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS) == false
    }

    SmsAlertModeContent(
        mode = smsAlertMode(declared, granted, blocked),
        disclosureOpen = disclosureOpen,
        onAllowClick = { disclosureOpen = true },
        onDisclosureContinue = {
            disclosureOpen = false
            request.launch(Manifest.permission.SEND_SMS)
        },
        onDisclosureDismiss = { disclosureOpen = false },
        onOpenSettings = {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        modifier = modifier,
    )
}

/** The card as a picture of a state. */
@Composable
fun SmsAlertModeContent(
    mode: SmsAlertMode,
    disclosureOpen: Boolean,
    onAllowClick: () -> Unit,
    onDisclosureContinue: () -> Unit,
    onDisclosureDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(SmsAlertModeCardTag),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(SafeRouteTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.sms_mode_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    when (mode) {
                        SmsAlertMode.ON -> R.string.sms_mode_on
                        SmsAlertMode.OFF -> R.string.sms_mode_off
                        SmsAlertMode.BLOCKED -> R.string.sms_mode_blocked
                        SmsAlertMode.NOT_IN_BUILD -> R.string.sms_mode_not_in_build
                    },
                ),
            )
            when (mode) {
                SmsAlertMode.OFF -> Button(onClick = onAllowClick) {
                    Text(text = stringResource(R.string.sms_mode_allow))
                }
                SmsAlertMode.BLOCKED -> OutlinedButton(onClick = onOpenSettings) {
                    Text(text = stringResource(R.string.sms_mode_open_settings))
                }
                SmsAlertMode.ON -> Text(
                    text = stringResource(R.string.sms_mode_on_how_to_stop),
                    style = MaterialTheme.typography.bodySmall,
                )
                SmsAlertMode.NOT_IN_BUILD -> Unit
            }
        }
    }
    if (disclosureOpen) {
        AlertDialog(
            onDismissRequest = onDisclosureDismiss,
            title = { Text(stringResource(R.string.sms_disclosure_title)) },
            text = {
                // Scrolls, so that nothing is cut off at 200% font size.
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.sm),
                ) {
                    Text(stringResource(R.string.sms_disclosure_p1))
                    Text(stringResource(R.string.sms_disclosure_p2))
                    Text(stringResource(R.string.sms_disclosure_p3))
                    Text(stringResource(R.string.sms_disclosure_p4))
                }
            },
            confirmButton = {
                Button(onClick = onDisclosureContinue) { Text(stringResource(R.string.sms_disclosure_continue)) }
            },
            dismissButton = {
                TextButton(onClick = onDisclosureDismiss) { Text(stringResource(R.string.sms_disclosure_not_now)) }
            },
        )
    }
}
