// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.component.MapControlButton
import com.saferoute.app.core.designsystem.theme.MinTouchTarget
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.location.LocationFix
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.map.CameraState
import com.saferoute.app.core.map.MapOverlay
import com.saferoute.app.core.map.MarkerStyle
import kotlin.math.abs

/** What the "my location" button shows. It is never disabled: it is the way in. */
enum class MyLocationControl {
    /** No permission yet (or location not started): a tap starts the permission flow. */
    Off,

    /** Permission granted, waiting for the first position. */
    Searching,

    /** A position is on the map: a tap centres the map on it. */
    Located,

    /** The map moves with the position: a tap stops that. */
    Following,

    /** No position can be had: a tap says why. */
    Unavailable,
}

/** Zoom used when the map first centres on the user: a few streets around. */
internal const val LOCATE_ZOOM = 16.0

/** Street level: where the map opens when it knows where the user is. */
internal const val INITIAL_LOCATE_ZOOM = 15.0

/** A position older than this does not open the map; a new one is waited for instead. */
internal const val INITIAL_FIX_MAX_AGE_SECONDS = 600L

/** How long the map waits for a position when it opens, before it stays on the overview. */
internal const val INITIAL_FIX_WAIT_MILLIS = 8_000L

/**
 * Zoomed out further than this, the middle of the map is not "the area the user is looking
 * at" any more (it shows several districts), so a search prefers no area at all.
 */
internal const val SEARCH_AREA_MIN_ZOOM = 9.0

/** Two positions closer than this (in degrees, about 10 m) count as the same place. */
private const val SAME_PLACE_DEGREES = 0.0001

internal fun CameraState.isCentredOn(fix: LocationFix): Boolean =
    abs(target.latitude - fix.position.latitude) < SAME_PLACE_DEGREES &&
        abs(target.longitude - fix.position.longitude) < SAME_PLACE_DEGREES

internal fun LocationState.fixOrNull(): LocationFix? = when (this) {
    is LocationState.Fix -> fix
    is LocationState.Stale -> lastFix
    else -> null
}

/**
 * What to draw for a location state: the accuracy circle under the dot. Each of the three looks
 * differs in shape or colour **and** is named by the button's description, so colour is never
 * the only signal:
 * - precise: a filled dot, an arrowhead while moving, a circle as wide as the accuracy;
 * - approximate: a ring instead of a dot, and a wide circle;
 * - stale: the last position in grey, no arrowhead.
 */
internal fun locationOverlays(state: LocationState): List<MapOverlay> {
    val fix = state.fixOrNull() ?: return emptyList()
    val style = when {
        state is LocationState.Stale -> MarkerStyle.Stale
        fix.isApproximate -> MarkerStyle.Approximate
        else -> MarkerStyle.Default
    }
    return listOf(
        MapOverlay.AccuracyCircle(
            id = "location-accuracy",
            center = fix.position,
            radiusMeters = fix.accuracyMeters.toDouble(),
            style = style,
        ),
        MapOverlay.Marker(
            id = "location",
            position = fix.position,
            headingDegrees = fix.headingDegrees?.toDouble(),
            style = style,
        ),
    )
}

internal fun myLocationControl(active: Boolean, state: LocationState, following: Boolean) = when {
    !active -> MyLocationControl.Off
    state is LocationState.NoPermission -> MyLocationControl.Off
    state is LocationState.Searching -> MyLocationControl.Searching
    state is LocationState.Unavailable -> MyLocationControl.Unavailable
    following -> MyLocationControl.Following
    else -> MyLocationControl.Located
}

@StringRes
private fun MyLocationControl.description(stale: Boolean, approximate: Boolean): Int = when (this) {
    MyLocationControl.Off -> R.string.my_location_off
    MyLocationControl.Searching -> R.string.my_location_searching
    MyLocationControl.Unavailable -> R.string.my_location_unavailable
    MyLocationControl.Following -> R.string.my_location_following
    MyLocationControl.Located -> when {
        stale -> R.string.my_location_located_stale
        approximate -> R.string.my_location_located_approximate
        else -> R.string.my_location_located
    }
}

/** The "my location" button in its five states, each with its own TalkBack description. */
@Composable
fun MyLocationButton(
    control: MyLocationControl,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stale: Boolean = false,
    approximate: Boolean = false,
) {
    MapControlButton(
        painter = painterResource(
            when (control) {
                MyLocationControl.Following -> R.drawable.ic_location_following
                MyLocationControl.Unavailable -> R.drawable.ic_location_unavailable
                else -> R.drawable.ic_my_location
            },
        ),
        contentDescription = stringResource(control.description(stale, approximate)),
        onClick = onClick,
        modifier = modifier,
        busy = control == MyLocationControl.Searching,
        selected = control == MyLocationControl.Following,
    )
}

/**
 * The app's own explanation, shown BEFORE Android's permission dialog (Plan v7 §12.3, and a
 * Google Play requirement, "prominent disclosure"). It says what is collected, why, what is not
 * done, and how to stop. Nothing is pre-selected; "Not now" leaves the map as it was.
 *
 * The wording is a draft until a lawyer has reviewed it (and the Bengali a native speaker).
 */
@Composable
fun LocationDisclosureDialog(onContinue: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = {
            Text(
                text = stringResource(R.string.location_disclosure_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            // Scrolls, so that large text can never push the buttons off the screen.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.sm),
            ) {
                Text(text = stringResource(R.string.location_disclosure_what))
                Text(text = stringResource(R.string.location_disclosure_why))
                Text(text = stringResource(R.string.location_disclosure_not))
                Text(text = stringResource(R.string.location_disclosure_stop))
            }
        },
        confirmButton = {
            Button(onClick = onContinue) {
                Text(text = stringResource(R.string.location_disclosure_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) {
                Text(text = stringResource(R.string.location_disclosure_not_now))
            }
        },
    )
}

private data class NoticeText(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val action: Int? = null,
)

private fun LocationNotice.text(): NoticeText = when (this) {
    LocationNotice.DeniedOnce ->
        NoticeText(R.string.location_notice_denied_title, R.string.location_notice_denied_body)
    LocationNotice.DeniedPermanently -> NoticeText(
        R.string.location_notice_denied_title,
        R.string.location_notice_settings_body,
        R.string.location_notice_open_settings,
    )
    LocationNotice.ServicesOff -> NoticeText(
        R.string.location_notice_off_title,
        R.string.location_notice_off_body,
        R.string.location_notice_turn_on,
    )
    LocationNotice.PlayServicesUnavailable ->
        NoticeText(R.string.location_notice_no_play_title, R.string.location_notice_no_play_body)
    LocationNotice.Approximate -> NoticeText(
        R.string.location_notice_approximate_title,
        R.string.location_notice_approximate_body,
        R.string.location_notice_use_precise,
    )
    LocationNotice.ApproximateOnly -> NoticeText(
        R.string.location_notice_approximate_title,
        R.string.location_notice_approximate_body,
    )
    LocationNotice.NoFix ->
        NoticeText(R.string.location_notice_no_fix_title, R.string.location_notice_no_fix_body)
}

/**
 * A short, closable message about location in the map area. It appears only as the answer to a
 * tap, and at most one action is offered: the app never asks twice in a row.
 */
@Composable
fun LocationNoticeCard(
    notice: LocationNotice,
    onAction: (LocationNotice) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = notice.text()
    val spacing = SafeRouteTheme.spacing
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = SafeRouteTheme.colors.mapOverlay,
        contentColor = SafeRouteTheme.colors.onMapOverlay,
        shadowElevation = SafeRouteTheme.elevation.raised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(text = stringResource(text.title), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(text.body), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                text.action?.let { action ->
                    OutlinedButton(
                        onClick = { onAction(notice) },
                        modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget),
                    ) {
                        Text(text = stringResource(action))
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget),
                ) {
                    Text(text = stringResource(R.string.location_notice_close))
                }
            }
        }
    }
}

@Preview(name = "Location disclosure", showBackground = true)
@Composable
private fun LocationDisclosurePreview() {
    SafeRouteTheme { LocationDisclosureDialog(onContinue = {}, onNotNow = {}) }
}

@Preview(name = "Location notice: approximate, Bengali 200%", locale = "bn", fontScale = 2f)
@Composable
private fun LocationNoticePreview() {
    SafeRouteTheme {
        LocationNoticeCard(notice = LocationNotice.Approximate, onAction = {}, onDismiss = {})
    }
}
