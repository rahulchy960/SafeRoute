// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.MinTouchTarget
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

private val MapControlIconSize = 24.dp

/** Material's opacity for the content of a disabled control. */
private const val DisabledContentAlpha = 0.38f

/**
 * A round button that floats on the map, for actions such as "my location" or "layers".
 *
 * It has no text, so [contentDescription] is required: it is the only thing TalkBack can
 * read. When the action can't be used, pass `enabled = false` **and** say so in the
 * description; the faded icon alone is a colour-only signal.
 *
 * @param painter The icon, e.g. `painterResource(R.drawable.ic_my_location)`.
 * @param contentDescription What the button does, read by TalkBack.
 * @param onClick Called on tap. Not called while disabled.
 * @param busy Shows a spinner instead of the icon (looking for a position, say). The button
 * keeps its description and stays tappable.
 * @param selected Draws the icon in the accent colour: the mode this button switches is on.
 * Say so in [contentDescription] too.
 */
@Composable
fun MapControlButton(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    selected: Boolean = false,
) {
    val colors = SafeRouteTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(MinTouchTarget)
            .semantics {
                role = Role.Button
                // The spinner has no icon to carry the description, so the button does.
                if (busy) this.contentDescription = contentDescription
            },
        enabled = enabled,
        shape = CircleShape,
        color = colors.mapOverlay,
        contentColor = when {
            !enabled -> colors.onMapOverlay.copy(alpha = DisabledContentAlpha)
            selected -> MaterialTheme.colorScheme.primary
            else -> colors.onMapOverlay
        },
        shadowElevation = SafeRouteTheme.elevation.raised,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(MapControlIconSize)
                        .clearAndSetSemantics { },
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painter = painter,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(MapControlIconSize),
                )
            }
        }
    }
}

@SafeRoutePreviews
@Composable
private fun MapControlButtonPreview() {
    SafeRouteTheme {
        Column(
            modifier = Modifier.padding(SafeRouteTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.sm),
        ) {
            MapControlButton(
                painter = painterResource(R.drawable.ic_my_location),
                contentDescription = stringResource(R.string.my_location_off),
                onClick = {},
            )
            MapControlButton(
                painter = painterResource(R.drawable.ic_layers),
                contentDescription = stringResource(R.string.map_control_layers_unavailable),
                onClick = {},
                enabled = false,
            )
        }
    }
}
