// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.component.SosControl
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.feature.home.EmergencyDialog
import com.saferoute.app.feature.home.EmergencyDialogState
import com.saferoute.app.feature.home.openEmergencyDialer
import java.text.NumberFormat
import java.util.Locale

/** Test tags of the search screen. */
internal object SearchTags {
    const val Field = "search-field"
    const val Results = "search-results"
    const val Row = "search-result-row"
    const val DistanceNote = "search-distance-note"
}

/**
 * Search, connected to its [SearchViewModel].
 *
 * The typed text is plain UI state: `rememberSaveable` keeps it across a rotation, and it is
 * never written to a file or a log. Every change is handed to the ViewModel, which decides
 * when to search.
 *
 * @param onPlaceChosen called after a result was tapped and handed to the map; navigation then
 * returns to Home.
 */
@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onPlaceChosen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(query) { viewModel.onQueryChange(query) }
    // The same dialog as on Home. It is this screen's own state: nothing about a search can
    // reach it, and it survives a rotation.
    var emergencyDialog by rememberSaveable { mutableStateOf(EmergencyDialogState.Hidden) }
    val context = LocalContext.current

    SearchScreen(
        query = query,
        state = state,
        onQueryChange = { query = it },
        onSearch = viewModel::onSearchNow,
        onPlaceClick = {
            viewModel.onPlaceChosen(it)
            onPlaceChosen()
        },
        onBack = onBack,
        modifier = modifier,
        onEmergencyClick = { emergencyDialog = EmergencyDialogState.OfferDialer },
        choosingStart = viewModel.choosingStart,
    )

    if (emergencyDialog != EmergencyDialogState.Hidden) {
        EmergencyDialog(
            state = emergencyDialog,
            onCallEmergency = {
                emergencyDialog = if (openEmergencyDialer(context)) {
                    EmergencyDialogState.Hidden
                } else {
                    EmergencyDialogState.DialerUnavailable
                }
            },
            onDismiss = { emergencyDialog = EmergencyDialogState.Hidden },
        )
    }
}

/**
 * The search screen: a back button, a text field that takes the keyboard focus as the screen
 * opens, and below it whatever [state] says: a hint, a spinner, results or a calm message.
 *
 * Stateless: it draws what it is given and reports events, so it can be previewed and tested
 * without a ViewModel or a server.
 */
@Composable
fun SearchScreen(
    query: String,
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPlaceClick: (FoundPlace) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onEmergencyClick: () -> Unit = {},
    choosingStart: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        modifier = modifier,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(SafeRouteTheme.spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            // AutoMirrored: the arrow points the other way in right-to-left
                            // languages.
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                    TextField(
                        value = query,
                        // At most 100 characters, counted as code points so that a Bengali
                        // letter or an emoji is never cut in half.
                        onValueChange = { onQueryChange(limitCodePoints(it)) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .testTag(SearchTags.Field),
                        placeholder = {
                            // The field says what the choice is for.
                            Text(text = stringResource(if (choosingStart) R.string.route_start_search_hint else R.string.search_hint))
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = stringResource(R.string.search_clear),
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                onSearch()
                                keyboard?.hide()
                            },
                        ),
                    )
                    // A top-bar action: in the bar's own row, so it can not cover a result.
                    SosControl(
                        onClick = onEmergencyClick,
                        modifier = Modifier.padding(horizontal = SafeRouteTheme.spacing.xs),
                        elevated = false,
                    )
                }
            }
        },
    ) { innerPadding ->
        val bodyModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            // Keeps the content above the keyboard.
            .imePadding()
        when (state) {
            is SearchUiState.Results -> SearchResults(
                results = state,
                onPlaceClick = onPlaceClick,
                modifier = bodyModifier,
            )
            SearchUiState.Idle -> SearchMessage(
                title = stringResource(R.string.search_empty_title),
                body = stringResource(R.string.search_empty_body),
                modifier = bodyModifier,
                announce = false,
            )
            SearchUiState.TooShort -> SearchMessage(
                title = stringResource(R.string.search_too_short),
                modifier = bodyModifier,
            )
            SearchUiState.Loading -> SearchLoading(bodyModifier)
            SearchUiState.Empty -> SearchMessage(
                title = stringResource(R.string.search_no_results_title),
                body = stringResource(R.string.search_no_results_body),
                modifier = bodyModifier,
            )
            is SearchUiState.Error -> SearchMessage(
                title = searchErrorText(state.error),
                modifier = bodyModifier,
                onRetry = onSearch,
            )
        }
    }
}

/** A calm sentence for every way a search can fail. None of them blames the user. */
@Composable
private fun searchErrorText(error: SearchError): String = when (error) {
    SearchError.NoConnection -> stringResource(R.string.search_error_offline)
    is SearchError.RateLimited -> {
        val seconds = error.retryAfterSeconds
        if (seconds == null) {
            stringResource(R.string.search_error_rate_limited)
        } else {
            pluralStringResource(R.plurals.search_error_rate_limited_seconds, seconds, seconds)
        }
    }
    SearchError.Unavailable -> stringResource(R.string.search_error_unavailable)
    SearchError.Unexpected -> stringResource(R.string.search_error_unexpected)
}

/**
 * A centred message. [announce] makes it a live region: TalkBack reads it out when it appears
 * or changes, without the user having to find it (errors, "no results", "too short").
 */
@Composable
private fun SearchMessage(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    announce: Boolean = true,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(SafeRouteTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(
            SafeRouteTheme.spacing.sm,
            Alignment.CenterVertically,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            modifier = Modifier.semantics {
                heading()
                if (announce) liveRegion = LiveRegionMode.Polite
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry) { Text(text = stringResource(R.string.search_retry)) }
        }
    }
}

@Composable
private fun SearchLoading(modifier: Modifier = Modifier) {
    val searching = stringResource(R.string.search_loading)
    Column(
        modifier = modifier.padding(SafeRouteTheme.spacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The spinner alone says nothing to a screen reader; the description does.
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = searching })
    }
}

@Composable
private fun SearchResults(
    results: SearchUiState.Results,
    onPlaceClick: (FoundPlace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = SafeRouteTheme.spacing
    val count = results.places.size
    LazyColumn(
        modifier = modifier.testTag(SearchTags.Results),
        contentPadding = PaddingValues(vertical = spacing.xs),
    ) {
        item(key = "count") {
            // Read out by TalkBack when results arrive: "6 places found".
            Text(
                text = pluralStringResource(R.plurals.search_result_count, count, count),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Said once, in words, for everyone: what the distances are measured from.
        if (results.distancesFrom != null && results.places.any { it.distanceMeters != null }) {
            item(key = "distance-note") {
                Text(
                    text = stringResource(
                        when (results.distancesFrom) {
                            NearSource.Position -> R.string.search_distance_note_position
                            NearSource.MapCentre -> R.string.search_distance_note_map
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md)
                        .testTag(SearchTags.DistanceNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(items = results.places, key = { it.id }) { place ->
            PlaceRow(place = place, distancesFrom = results.distancesFrom, onClick = { onPlaceClick(place) })
        }
        results.attribution?.let { attribution ->
            item(key = "attribution") {
                // The credit the geocoding provider and the map data ask for.
                Text(
                    text = attribution,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A distance for a row: "under 1 km", "2.3 km", "12 km", in the phone's number style.
 *
 * Deliberately rough. The server measures from a point rounded to about 1 km, so anything
 * below a kilometre is only "under 1 km", one decimal is shown up to 10 km, and whole
 * kilometres from there.
 */
@Composable
internal fun searchDistanceText(distanceMeters: Int, locale: Locale): String = when {
    distanceMeters < METERS_PER_KM -> stringResource(R.string.search_distance_under_km)
    else -> stringResource(R.string.search_distance_km, searchKilometres(distanceMeters, locale))
}

private const val METERS_PER_KM = 1000
private const val WHOLE_KM_FROM_METERS = 10_000

/** "2.3" below 10 km, "12" from there; digits and decimal mark follow [locale]. */
internal fun searchKilometres(distanceMeters: Int, locale: Locale): String {
    val decimals = if (distanceMeters < WHOLE_KM_FROM_METERS) 1 else 0
    return NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }.format(distanceMeters / METERS_PER_KM.toDouble())
}

@Composable
private fun PlaceRow(place: FoundPlace, distancesFrom: NearSource?, onClick: () -> Unit) {
    val spacing = SafeRouteTheme.spacing
    // Read from the configuration, so the numbers change with the app language at once.
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SearchTags.Row)
            // One TalkBack stop per result, announced as a button: "Main Station, Station Road".
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = spacing.md, vertical = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // weight(1f): the name and the label take what the distance leaves, and wrap.
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                text = place.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (place.label.isNotBlank()) {
                Text(
                    text = place.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (place.distanceMeters != null && distancesFrom != null) {
            val distance = searchDistanceText(place.distanceMeters, locale)
            // What TalkBack reads instead of the bare figure: "2.3 km from your location".
            val spoken = stringResource(
                when (distancesFrom) {
                    NearSource.Position -> R.string.search_distance_from_position
                    NearSource.MapCentre -> R.string.search_distance_from_map
                },
                distance,
            )
            Text(
                text = distance,
                modifier = Modifier.semantics { contentDescription = spoken },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@SafeRoutePreviews
@Composable
private fun SearchScreenPreview() {
    SafeRouteTheme {
        SearchScreen(
            query = "station",
            state = SearchUiState.Results(
                places = listOf(
                    FoundPlace("1", "Main Station", "Station Road, Example District", LatLng(10.0, 20.0), "station"),
                    FoundPlace("2", "Station Market", "Example Town", LatLng(10.1, 20.1), "market"),
                ),
                attribution = "© OpenStreetMap contributors",
            ),
            onQueryChange = {},
            onSearch = {},
            onPlaceClick = {},
            onBack = {},
        )
    }
}
