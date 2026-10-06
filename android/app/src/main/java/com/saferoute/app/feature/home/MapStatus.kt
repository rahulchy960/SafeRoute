// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.MinTouchTarget
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.map.MapLoadState
import com.saferoute.app.core.map.MapProviderConfig

private data class MapStatusText(
    @StringRes val title: Int,
    @StringRes val body: Int? = null,
    val canRetry: Boolean = false,
)

private fun MapLoadState.statusText(): MapStatusText? = when (this) {
    MapLoadState.Ready -> null
    MapLoadState.Loading -> MapStatusText(R.string.map_status_loading)
    MapLoadState.Error ->
        MapStatusText(R.string.map_status_error_title, R.string.map_status_error_body, true)
    MapLoadState.Offline ->
        MapStatusText(R.string.map_status_offline_title, R.string.map_status_offline_body, true)
    MapLoadState.RateLimited -> MapStatusText(
        R.string.map_status_rate_limited_title,
        R.string.map_status_rate_limited_body,
        true,
    )
    MapLoadState.NotConfigured -> MapStatusText(
        R.string.map_status_not_configured_title,
        R.string.map_status_not_configured_body,
    )
}

/**
 * Says, calmly, why the map is not showing: a small card in the map area, never a full-screen
 * error. Nothing is drawn while the map is [MapLoadState.Ready].
 *
 * It only ever covers part of the map. The search pill, the map controls, the sheet and the
 * emergency button are separate elements of the Home screen and keep working in every state.
 *
 * `liveRegion` makes TalkBack read the card out when the state changes, without the user
 * having to find it.
 */
@Composable
fun MapStatusCard(state: MapLoadState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val text = state.statusText() ?: return
    val spacing = SafeRouteTheme.spacing
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = SafeRouteTheme.colors.mapOverlay,
        contentColor = SafeRouteTheme.colors.onMapOverlay,
        shadowElevation = SafeRouteTheme.elevation.raised,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(text = stringResource(text.title), style = MaterialTheme.typography.titleMedium)
            text.body?.let {
                Text(text = stringResource(it), style = MaterialTheme.typography.bodyMedium)
            }
            if (state != MapLoadState.Loading) {
                Text(
                    text = stringResource(R.string.map_status_rest_works),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SafeRouteTheme.colors.mapOverlayVariant,
                )
            }
            if (text.canRetry) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget),
                ) {
                    Text(text = stringResource(R.string.map_status_retry))
                }
            }
        }
    }
}

/**
 * The credit line both the tile provider and OpenStreetMap require to be visible on the map.
 * Tapping it opens [MapAttributionDialog]. It is the app's own element rather than the map
 * library's, so it is translated, large enough to tap, and placed where the sheet never
 * covers it (ADR 0015, "Attribution").
 */
@Composable
fun MapAttributionChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = MinTouchTarget),
        shape = MaterialTheme.shapes.small,
        color = SafeRouteTheme.colors.mapOverlay,
        contentColor = SafeRouteTheme.colors.mapOverlayVariant,
    ) {
        Text(
            text = stringResource(R.string.map_attribution),
            modifier = Modifier
                .padding(horizontal = SafeRouteTheme.spacing.xs)
                .wrapContentHeight(Alignment.CenterVertically),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Who made the map, with links to each licence page. */
@Composable
fun MapAttributionDialog(onOpenLink: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.map_attribution_dialog_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.map_attribution_dialog_body))
                TextButton(onClick = { onOpenLink(MapProviderConfig.PROVIDER_COPYRIGHT_URL) }) {
                    Text(text = stringResource(R.string.map_attribution_provider_link))
                }
                TextButton(onClick = { onOpenLink(MapProviderConfig.DATA_COPYRIGHT_URL) }) {
                    Text(text = stringResource(R.string.map_attribution_data_link))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.map_attribution_close))
            }
        },
    )
}

@Preview(name = "Map status: offline", showBackground = true)
@Composable
private fun MapStatusOfflinePreview() {
    SafeRouteTheme { MapStatusCard(state = MapLoadState.Offline, onRetry = {}) }
}

@Preview(name = "Map status: not configured, Bengali 200%", locale = "bn", fontScale = 2f)
@Composable
private fun MapStatusNotConfiguredPreview() {
    SafeRouteTheme { MapStatusCard(state = MapLoadState.NotConfigured, onRetry = {}) }
}
