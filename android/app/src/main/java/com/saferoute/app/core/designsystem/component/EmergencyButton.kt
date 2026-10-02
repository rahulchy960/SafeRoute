// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteShapeTokens
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

private val EmergencyButtonMinHeight = 56.dp

/**
 * The always-visible emergency button. One of the two places allowed to use the `sos` red
 * (the other is the emergency dialog).
 *
 * The meaning does not rest on colour: the button also carries a warning icon and a label
 * that includes the number 112.
 *
 * This component only reports the tap. What happens next belongs to the caller: in P007 a
 * dialog that offers to open the phone dialer with 112; from P014 the real SOS flow.
 *
 * @param onClick Called when the button is tapped.
 * @param label Visible text. The default is the localised "Emergency 112".
 */
@Composable
fun EmergencyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.emergency_button_label),
) {
    val spacing = SafeRouteTheme.spacing
    val clickLabel = stringResource(R.string.emergency_button_click_label)
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = EmergencyButtonMinHeight)
            .semantics {
                role = Role.Button
                // TalkBack then says "double tap to show emergency options" instead of the
                // generic "double tap to activate".
                onClick(label = clickLabel) {
                    onClick()
                    true
                }
            },
        shape = SafeRouteShapeTokens.Pill,
        color = SafeRouteTheme.colors.sos,
        contentColor = SafeRouteTheme.colors.onSos,
        shadowElevation = SafeRouteTheme.elevation.floating,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Filled.Warning, contentDescription = null)
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@SafeRoutePreviews
@Composable
private fun EmergencyButtonPreview() {
    SafeRouteTheme {
        Box(Modifier.padding(SafeRouteTheme.spacing.md)) {
            EmergencyButton(onClick = {})
        }
    }
}
