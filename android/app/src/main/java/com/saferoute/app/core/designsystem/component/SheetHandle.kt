// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.MinTouchTarget
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

private val HandleTouchWidth = 72.dp
private val HandleBarWidth = 32.dp
private val HandleBarHeight = 4.dp

/**
 * The small bar at the top of a bottom sheet.
 *
 * Dragging a sheet is impossible for many TalkBack and Switch Access users, so the handle
 * also offers the same result without a gesture:
 * - tap (or double tap in TalkBack) moves the sheet to its next size;
 * - the accessibility actions "expand" and "collapse" appear in the TalkBack actions menu;
 * - [stateDescription] tells the user which size the sheet has now.
 *
 * @param stateDescription The sheet's current size in words, e.g. "Half open".
 * @param onClick Called on tap; the sheet should move to its next size.
 * @param onExpand Makes the sheet one step taller, or `null` when it is already at its tallest.
 * @param onCollapse Makes the sheet one step shorter, or `null` when it is at its shortest.
 */
@Composable
fun SheetHandle(
    stateDescription: String,
    onClick: () -> Unit,
    onExpand: (() -> Unit)?,
    onCollapse: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.sheet_handle_description)
    val cycleLabel = stringResource(R.string.sheet_action_cycle)
    val expandLabel = stringResource(R.string.sheet_action_expand)
    val collapseLabel = stringResource(R.string.sheet_action_collapse)

    Box(
        modifier = modifier
            .sizeIn(minWidth = HandleTouchWidth, minHeight = MinTouchTarget)
            .clip(CircleShape)
            .clickable(onClickLabel = cycleLabel, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                this.stateDescription = stateDescription
                if (onExpand != null) {
                    expand(label = expandLabel) {
                        onExpand()
                        true
                    }
                }
                if (onCollapse != null) {
                    collapse(label = collapseLabel) {
                        onCollapse()
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = HandleBarWidth, height = HandleBarHeight)
                // `outline` keeps the bar at 3:1 or better against the sheet in both themes.
                .background(color = MaterialTheme.colorScheme.outline, shape = CircleShape),
        )
    }
}

@SafeRoutePreviews
@Composable
private fun SheetHandlePreview() {
    SafeRouteTheme {
        Box(Modifier.padding(SafeRouteTheme.spacing.md)) {
            SheetHandle(
                stateDescription = stringResource(R.string.sheet_state_half),
                onClick = {},
                onExpand = {},
                onCollapse = {},
            )
        }
    }
}
