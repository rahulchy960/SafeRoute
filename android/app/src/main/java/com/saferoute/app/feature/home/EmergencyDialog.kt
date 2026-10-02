// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

/**
 * The dialog behind the emergency button until the SOS flow exists (P014).
 *
 * It says plainly that SOS is not available, that SafeRoute is not an emergency service, and
 * that "Call 112" only opens the phone app: the person starts the call. Nothing here may
 * suggest that the app calls for help by itself.
 *
 * With [EmergencyDialogState.DialerUnavailable] it shows the number instead of the call button.
 *
 * @param state Must not be [EmergencyDialogState.Hidden]; the caller decides whether to show it.
 * @param onCallEmergency "Call 112" was tapped.
 * @param onDismiss Cancel, Close, the back gesture or a tap outside.
 */
@Composable
fun EmergencyDialog(
    state: EmergencyDialogState,
    onCallEmergency: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dialerAvailable = state != EmergencyDialogState.DialerUnavailable
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = SafeRouteTheme.colors.sos,
            )
        },
        title = { Text(text = stringResource(R.string.emergency_dialog_title)) },
        text = {
            // Scrolls, so that nothing is cut off at 200% font size on a small screen.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.sm),
            ) {
                Text(text = stringResource(R.string.emergency_dialog_body))
                Text(
                    text = stringResource(R.string.not_emergency_service),
                    fontWeight = FontWeight.SemiBold,
                )
                if (dialerAvailable) {
                    Text(
                        text = stringResource(R.string.emergency_dialog_dialer_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.emergency_dialog_no_dialer),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = {
            if (dialerAvailable) {
                Button(
                    onClick = onCallEmergency,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SafeRouteTheme.colors.sos,
                        contentColor = SafeRouteTheme.colors.onSos,
                    ),
                ) {
                    Text(text = stringResource(R.string.emergency_dialog_call))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(
                        if (dialerAvailable) {
                            R.string.emergency_dialog_cancel
                        } else {
                            R.string.emergency_dialog_close
                        },
                    ),
                )
            }
        },
    )
}

@SafeRoutePreviews
@Composable
private fun EmergencyDialogPreview() {
    SafeRouteTheme {
        EmergencyDialog(
            state = EmergencyDialogState.OfferDialer,
            onCallEmergency = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "No dialer on the device", showBackground = true)
@Composable
private fun EmergencyDialogNoDialerPreview() {
    SafeRouteTheme {
        EmergencyDialog(
            state = EmergencyDialogState.DialerUnavailable,
            onCallEmergency = {},
            onDismiss = {},
        )
    }
}
