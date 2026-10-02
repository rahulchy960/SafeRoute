// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.MinTouchTarget
import com.saferoute.app.core.designsystem.theme.SafeRouteShapeTokens
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

private val SearchPillMinHeight = 56.dp

/**
 * The search entry point that floats over the map: a pill with a search icon and a hint.
 *
 * It is a button, not a text field. Tapping it opens the search screen, where the real field
 * lives; that keeps the map screen free of the keyboard.
 *
 * The pill grows taller instead of cutting the hint when the user's font size is large.
 *
 * @param hint The text shown in the pill; it is also what TalkBack reads.
 * @param onClick Called when the pill is tapped.
 * @param trailingContent Optional slot at the end, for one control such as a settings or
 *   profile button. Give that control its own content description and a 48 dp touch target.
 */
@Composable
fun SearchPill(
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val spacing = SafeRouteTheme.spacing
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = SearchPillMinHeight)
            .semantics { role = Role.Button },
        shape = SafeRouteShapeTokens.Pill,
        color = SafeRouteTheme.colors.mapOverlay,
        contentColor = SafeRouteTheme.colors.onMapOverlay,
        shadowElevation = SafeRouteTheme.elevation.floating,
    ) {
        Row(
            modifier = Modifier.padding(
                start = spacing.md,
                end = if (trailingContent == null) spacing.md else spacing.xxs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                // Decorative: the hint text already says what this is.
                contentDescription = null,
                tint = SafeRouteTheme.colors.mapOverlayVariant,
            )
            Spacer(Modifier.width(spacing.sm))
            Text(
                text = hint,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = spacing.xs),
                style = MaterialTheme.typography.bodyLarge,
                color = SafeRouteTheme.colors.mapOverlayVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}

@SafeRoutePreviews
@Composable
private fun SearchPillPreview() {
    SafeRouteTheme {
        Box(Modifier.padding(SafeRouteTheme.spacing.md)) {
            SearchPill(
                hint = stringResource(R.string.search_hint),
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                trailingContent = {
                    Box(Modifier.size(MinTouchTarget), contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = null)
                    }
                },
            )
        }
    }
}
