// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import java.util.Date

/** On the banner, so that layout tests can ask where it is. */
const val FollowBannerTag = "follow-banner"

/**
 * The card at the top of the map while a route is followed: how far and how long still, when
 * that is on the clock, and one line for whatever needs saying (no GPS, off the route, a new
 * route on its way, arrived). It states a distance and a time and nothing else: no label,
 * score or colour that could be read as a statement about safety or traffic.
 *
 * Lines that appear by themselves are live regions: TalkBack reads them when they appear.
 */
@Composable
fun FollowBanner(follow: FollowState, actions: DirectionsActions, modifier: Modifier = Modifier) {
    val spacing = SafeRouteTheme.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(FollowBannerTag),
        shape = MaterialTheme.shapes.medium,
        color = SafeRouteTheme.colors.mapOverlay,
        contentColor = SafeRouteTheme.colors.onMapOverlay,
        shadowElevation = SafeRouteTheme.elevation.raised,
    ) {
        // Scrolls: with large text and several lines to say, the card keeps the height the
        // screen gives it instead of growing down over the map controls.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (follow.arrived) {
                Announcement(text = stringResource(R.string.route_follow_arrived), style = MaterialTheme.typography.titleLarge)
                Button(onClick = actions.onClose) { Text(text = stringResource(R.string.route_follow_done)) }
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.route_follow_remaining,
                            distanceText(follow.remainingMeters),
                            durationText(follow.remainingSeconds),
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.route_follow_arrival, clockTime(follow.arrivalMillis)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SafeRouteTheme.colors.mapOverlayVariant,
                    )
                }
                OutlinedButton(onClick = actions.onEnd) { Text(text = stringResource(R.string.route_follow_end)) }
            }
            if (follow.gpsLost) Announcement(text = stringResource(R.string.route_follow_gps))
            if (follow.pausedNote) {
                Text(text = stringResource(R.string.route_follow_paused), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = actions.onPausedNoteDismiss) { Text(text = stringResource(R.string.route_follow_paused_ok)) }
            }
            if (follow.offRoute) OffRoute(follow.recalculation, actions.onRecalculate)
        }
    }
}

@Composable
private fun OffRoute(recalculation: Recalculation, onRecalculate: () -> Unit) {
    Announcement(text = stringResource(R.string.route_follow_off_route))
    when (recalculation) {
        Recalculation.Running -> Row(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            Text(text = stringResource(R.string.route_follow_recalculating))
        }
        else -> {
            // The same sentences as for a first request that fails.
            if (recalculation is Recalculation.Failed) Announcement(text = routeErrorText(recalculation.error))
            FilledTonalButton(onClick = onRecalculate) { Text(text = stringResource(R.string.route_follow_recalculate)) }
        }
    }
}

/** A line that TalkBack reads when it appears (polite: after what it is saying now). */
@Composable
private fun Announcement(text: String, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    Text(text = text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = style)
}

/** The time of day in the phone's own style: 12 or 24 hours, and the language's digits. */
@Composable
internal fun clockTime(millis: Long): String {
    // Read, so that the text follows a change of language or of the 24-hour setting.
    LocalConfiguration.current
    return DateFormat.getTimeFormat(LocalContext.current).format(Date(millis))
}

/** Back, or "End", while a route is followed. Ending by accident would lose the place on it. */
@Composable
fun EndNavigationDialog(onEnd: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = stringResource(R.string.route_follow_end_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = { Text(text = stringResource(R.string.route_follow_end_text)) },
        confirmButton = { Button(onClick = onEnd) { Text(text = stringResource(R.string.route_follow_end)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(text = stringResource(R.string.route_follow_end_cancel)) } },
    )
}

/**
 * Keeps the screen from switching itself off while [active], and only then.
 *
 * `keepScreenOn` is a property of the view the app draws in: Android honours it while that view
 * is visible. `DisposableEffect` undoes it the moment [active] turns false or this leaves the
 * screen for any reason, so no path out of following can leave the screen on.
 */
@Composable
fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        if (active) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@SafeRoutePreviews
@Composable
private fun FollowBannerPreview() {
    SafeRouteTheme {
        FollowBanner(
            follow = FollowState(remainingMeters = 1400, remainingSeconds = 1020, arrivalMillis = 0, offRoute = true),
            actions = DirectionsActions(),
        )
    }
}
