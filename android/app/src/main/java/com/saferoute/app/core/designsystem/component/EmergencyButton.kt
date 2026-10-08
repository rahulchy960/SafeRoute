// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.MinTouchTarget
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

/** The letters stay this tall whatever the font setting: the control must stay 48 dp. */
private val SosLabelSize = 14.dp

/** Finds the control in layout tests, wherever on the screen it currently sits. */
const val SosControlTag = "sos-control"

/**
 * The always-reachable emergency control: a 48 dp red circle that says "SOS". One of the two
 * places allowed to use the `sos` red (the other is the emergency dialog).
 *
 * It is small on purpose and it is **part of a layout, never an overlay**: the screen gives it a
 * place of its own (the end of the map controls, the end of a sheet's header row, the end of a
 * top bar), so it can not cover a card, a result or a row (ADR 0008, note of 2026-10-08).
 *
 * The meaning does not rest on colour: the control shows the letters SOS, and TalkBack reads
 * "SOS and call 112". The letters do not grow with the font setting, because a control that
 * grows would again cover what is next to it; the spoken description is the accessible name.
 *
 * This component only reports the tap. What happens next belongs to the caller: today a
 * dialog that offers to open the phone dialer with 112; from P014 the real SOS flow (a tap or
 * a hold to arm). A Quick Settings tile is a separate entry point and does not use this.
 *
 * @param onClick Called when the control is tapped.
 * @param elevated A shadow, for when it floats on the map. Off inside a sheet or a bar.
 */
@Composable
fun SosControl(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = true,
) {
    val description = stringResource(R.string.sos_control_description)
    val clickLabel = stringResource(R.string.emergency_button_click_label)
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(MinTouchTarget)
            .testTag(SosControlTag)
            .semantics {
                role = Role.Button
                contentDescription = description
                // TalkBack then says "double tap to show emergency options" instead of the
                // generic "double tap to activate".
                onClick(label = clickLabel) {
                    onClick()
                    true
                }
            },
        shape = CircleShape,
        color = SafeRouteTheme.colors.sos,
        contentColor = SafeRouteTheme.colors.onSos,
        shadowElevation = if (elevated) SafeRouteTheme.elevation.raised else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.sos_control_label),
                // The description above is the name; "SOS" must not be read a second time.
                modifier = Modifier.clearAndSetSemantics { },
                // dp turned into sp at the current font scale: the result ignores the scale.
                fontSize = with(LocalDensity.current) { SosLabelSize.toSp() },
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@SafeRoutePreviews
@Composable
private fun SosControlPreview() {
    SafeRouteTheme {
        Row(
            modifier = Modifier.padding(SafeRouteTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.md),
        ) {
            SosControl(onClick = {})
            SosControl(onClick = {}, elevated = false)
        }
    }
}
