// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.MinTouchTarget
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.SelectedPlace
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** On every route card, so that layout tests can ask where the cards are. */
const val RouteCardTag = "route-card"

/** What the directions UI reports. Defaults do nothing, for previews and tests. */
data class DirectionsActions(
    val onOpen: () -> Unit = {},
    val onIntroContinue: () -> Unit = {},
    val onModeChange: (TravelMode) -> Unit = {},
    val onRouteSelect: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * The directions panel in Home's bottom sheet: where to, how (walking or driving), and then
 * either the routes or one calm sentence about why there are none.
 *
 * A route card shows a duration, a distance and, on the first card, "Fastest". Nothing else:
 * no label, score or colour that could be read as a statement about safety or traffic.
 */
@Composable
fun DirectionsSheet(
    state: DirectionsUiState.Open,
    actions: DirectionsActions,
    onUseMyLocation: () -> Unit,
    modifier: Modifier = Modifier,
    headerEnd: @Composable () -> Unit = {},
) {
    val spacing = SafeRouteTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        // The header does not scroll: the close button and whatever [headerEnd] holds (the SOS
        // control) stay in reach however long the list below is.
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(
                    text = stringResource(R.string.route_title_to, state.destination.name),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.route_from_my_location),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // IconButton is 48 dp, the minimum touch target.
            IconButton(onClick = actions.onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.route_close))
            }
            headerEnd()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            ModeToggle(mode = state.mode, onModeChange = actions.onModeChange)
            when (val status = state.status) {
                is DirectionsStatus.Loading -> Waiting(
                    text = stringResource(if (status.starting) R.string.route_starting else R.string.route_loading),
                )
                is DirectionsStatus.Results -> RouteList(status, actions.onRouteSelect)
                is DirectionsStatus.NeedsOrigin -> NeedsOrigin(status.problem, onUseMyLocation)
                is DirectionsStatus.Failed -> Failure(status, actions.onRetry)
            }
        }
    }
}

/** Two choices, one always selected: a radio group, which TalkBack announces as such. */
@Composable
private fun ModeToggle(mode: TravelMode, onModeChange: (TravelMode) -> Unit) {
    Row(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.sm),
    ) {
        TravelMode.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .defaultMinSize(minHeight = MinTouchTarget)
                    .selectable(
                        selected = option == mode,
                        onClick = { onModeChange(option) },
                        role = Role.RadioButton,
                    )
                    .padding(end = SafeRouteTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // onClick = null: the whole row is the control, not just the small circle.
                RadioButton(selected = option == mode, onClick = null)
                Text(
                    text = stringResource(
                        if (option == TravelMode.Walking) R.string.route_mode_walking else R.string.route_mode_driving,
                    ),
                    modifier = Modifier.padding(start = SafeRouteTheme.spacing.xs),
                )
            }
        }
    }
}

/** A spinner is never shown alone: the sentence next to it says what is happening. */
@Composable
private fun Waiting(text: String) {
    Row(
        // Polite: TalkBack reads the change when it has finished what it was saying.
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RouteList(results: DirectionsStatus.Results, onSelect: (String) -> Unit) {
    val spacing = SafeRouteTheme.spacing
    // Read from the configuration, so the numbers change with the app language at once.
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        results.routes.forEachIndexed { index, route ->
            val selected = route.id == results.selectedId
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = MinTouchTarget)
                    .testTag(RouteCardTag)
                    .selectable(selected = selected, onClick = { onSelect(route.id) }, role = Role.RadioButton),
                shape = MaterialTheme.shapes.medium,
                // The chosen card differs in its border and its radio mark, not in colour alone.
                border = BorderStroke(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = durationText(route.durationSeconds),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = distanceText(route.distanceMeters, locale),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The server lists the fastest route first.
                    if (index == 0) {
                        Text(
                            text = stringResource(R.string.route_fastest),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
        // The credit for the map data the routes were computed from. Always shown with them.
        Text(
            text = results.attribution,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NeedsOrigin(problem: OriginProblem, onUseMyLocation: () -> Unit) {
    if (problem == OriginProblem.Searching) {
        Waiting(text = stringResource(R.string.route_origin_searching))
        return
    }
    Message(
        text = stringResource(
            if (problem == OriginProblem.NoPermission) {
                R.string.route_origin_no_permission
            } else {
                R.string.route_origin_unavailable
            },
        ),
        action = stringResource(R.string.route_use_my_location),
        onAction = onUseMyLocation,
    )
}

@Composable
private fun Failure(status: DirectionsStatus.Failed, onRetry: () -> Unit) {
    val text = when (val error = status.error) {
        RouteError.OutsideCovered -> stringResource(R.string.route_error_outside)
        RouteError.NoRoute -> stringResource(R.string.route_error_no_route)
        RouteError.NotRoutable -> stringResource(R.string.route_error_not_routable)
        RouteError.TooLong -> stringResource(R.string.route_error_too_long)
        is RouteError.RateLimited -> error.retryAfterSeconds?.let {
            pluralStringResource(R.plurals.route_error_rate_limited_seconds, it, it)
        } ?: stringResource(R.string.route_error_rate_limited)
        is RouteError.Starting -> stringResource(R.string.route_error_still_starting)
        RouteError.Unavailable -> stringResource(R.string.route_error_unavailable)
        RouteError.Offline -> stringResource(R.string.route_error_offline)
    }
    // Asking again cannot change an answer about the places themselves.
    val canRetry = when (status.error) {
        RouteError.OutsideCovered, RouteError.NoRoute, RouteError.NotRoutable, RouteError.TooLong -> false
        else -> true
    }
    Message(
        text = text,
        action = stringResource(R.string.route_try_again).takeIf { canRetry },
        onAction = onRetry,
    )
}

@Composable
private fun Message(text: String, action: String?, onAction: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.sm)) {
        Text(
            text = text,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (action != null) {
            FilledTonalButton(onClick = onAction) { Text(text = action) }
        }
    }
}

/**
 * Shown once, before the first route request ever leaves the phone: what is sent and that it
 * is not kept. "Not now" sends nothing. The wording is a draft until a lawyer has reviewed it.
 */
@Composable
fun RouteIntroDialog(onContinue: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = {
            Text(
                text = stringResource(R.string.route_intro_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            // Scrolls, so that large text can never push the buttons off the screen.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.sm),
            ) {
                Text(text = stringResource(R.string.route_intro_sent))
                Text(text = stringResource(R.string.route_intro_not_stored))
                Text(text = stringResource(R.string.route_intro_estimate))
            }
        },
        confirmButton = {
            Button(onClick = onContinue) { Text(text = stringResource(R.string.route_intro_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) { Text(text = stringResource(R.string.route_intro_not_now)) }
        },
    )
}

/** Whole minutes, rounded up, at least one: "4 min" never turns out to be five. */
internal fun routeMinutes(durationSeconds: Int): Int = ceil(durationSeconds / 60.0).toInt().coerceAtLeast(1)

@Composable
private fun durationText(durationSeconds: Int): String {
    val minutes = routeMinutes(durationSeconds)
    return if (minutes < 60) {
        stringResource(R.string.route_duration_minutes, minutes)
    } else {
        stringResource(R.string.route_duration_hours_minutes, minutes / 60, minutes % 60)
    }
}

/** Kilometres with one decimal in the phone's number style ("2.1" or "২.১"), from 1 km on. */
internal fun routeKilometres(distanceMeters: Int, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(distanceMeters / 1000.0)

/** Metres rounded to ten: nobody needs "437 m". */
internal fun routeRoundedMeters(distanceMeters: Int): Int = ((distanceMeters / 10.0).roundToInt() * 10).coerceAtLeast(10)

@Composable
private fun distanceText(distanceMeters: Int, locale: Locale): String =
    if (distanceMeters < 1000) {
        stringResource(R.string.route_distance_meters, routeRoundedMeters(distanceMeters))
    } else {
        stringResource(R.string.route_distance_km, routeKilometres(distanceMeters, locale))
    }

@SafeRoutePreviews
@Composable
private fun DirectionsSheetPreview() {
    SafeRouteTheme {
        DirectionsSheet(
            state = DirectionsUiState.Open(
                destination = SelectedPlace("Main Station", "Station Road, Example District", LatLng(10.0, 20.0)),
                mode = TravelMode.Walking,
                status = DirectionsStatus.Results(
                    routes = listOf(
                        RouteOption("a", 2100, 1560, emptyList()),
                        RouteOption("b", 2400, 1740, emptyList()),
                    ),
                    selectedId = "a",
                    attribution = "© OpenStreetMap contributors",
                ),
            ),
            actions = DirectionsActions(),
            onUseMyLocation = {},
        )
    }
}
