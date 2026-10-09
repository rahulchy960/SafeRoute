// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.semantics.role
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
import com.saferoute.app.feature.emergency.EmergencyShortcut
import com.saferoute.app.feature.emergency.SosArmViewModel
import com.saferoute.app.feature.emergency.SosOpenMode
import com.saferoute.app.feature.home.EmergencyArm
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
    const val Chips = "search-chips"
    const val Within = "search-within"
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
    armViewModel: SosArmViewModel = hiltViewModel(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val category by viewModel.category.collectAsStateWithLifecycle()
    LaunchedEffect(query) { viewModel.onQueryChange(query) }
    // Set when the phone has no app that can open a web page; shown under the link.
    var browserMissing by rememberSaveable { mutableStateOf(false) }
    // The same dialog as on Home. It is this screen's own state: nothing about a search can
    // reach it, and it survives a rotation.
    var emergencyDialog by rememberSaveable { mutableStateOf(EmergencyDialogState.Hidden) }
    val alertsEnabled by armViewModel.alertsEnabled.collectAsStateWithLifecycle()
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
        category = category,
        onCategoryClick = {
            // A chip searches a kind of place, not the text: the field is emptied first.
            query = ""
            viewModel.onCategoryClick(it)
        },
        onOpenMapSite = { browserMissing = !openMapSite(context) },
        browserMissing = browserMissing,
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
            arm = EmergencyArm(
                onArmed = {
                    emergencyDialog = EmergencyDialogState.Hidden
                    context.startActivity(EmergencyShortcut.modeIntent(context, SosOpenMode.START))
                }.takeIf { alertsEnabled },
                onPractice = {
                    emergencyDialog = EmergencyDialogState.Hidden
                    context.startActivity(EmergencyShortcut.modeIntent(context, SosOpenMode.PRACTICE))
                },
            ),
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
    category: SearchCategory? = null,
    onCategoryClick: (SearchCategory) -> Unit = {},
    onOpenMapSite: () -> Unit = {},
    browserMissing: Boolean = false,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Keeps the content above the keyboard.
                .imePadding(),
        ) {
            // The chips are the screen's starting point: shown while the field is empty, which
            // includes the whole of a chip's own search. Typing makes room for the results.
            if (query.isEmpty()) {
                CategoryChips(
                    selected = category,
                    onClick = {
                        keyboard?.hide()
                        onCategoryClick(it)
                    },
                )
            }
            val bodyModifier = Modifier
                .fillMaxWidth()
                .weight(1f)
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
                is SearchUiState.Empty -> {
                    val circle = state.circle
                    if (circle == null) {
                        SearchMessage(
                            title = stringResource(R.string.search_no_results_title),
                            body = stringResource(R.string.search_no_results_body),
                            modifier = bodyModifier,
                        )
                    } else {
                        // Says what was looked at and nothing more: an empty circle on the map is
                        // not a statement about the place itself.
                        SearchMessage(
                            title = stringResource(R.string.search_nothing_within, wholeNumber(circle.radiusKm)),
                            body = stringResource(R.string.search_missing_from_map),
                            modifier = bodyModifier,
                            action = stringResource(R.string.search_open_osm) to onOpenMapSite,
                            footnote = stringResource(R.string.search_no_browser).takeIf { browserMissing },
                        )
                    }
                }
                is SearchUiState.Error -> SearchMessage(
                    title = searchErrorText(state.error),
                    modifier = bodyModifier,
                    action = stringResource(R.string.search_retry) to onSearch,
                )
            }
        }
    }
}

/** The app's words for each quick-search chip. */
private fun SearchCategory.labelRes(): Int = when (this) {
    SearchCategory.Bank -> R.string.search_chip_bank
    SearchCategory.Atm -> R.string.search_chip_atm
    SearchCategory.Pharmacy -> R.string.search_chip_pharmacy
    SearchCategory.Hospital -> R.string.search_chip_hospital
    SearchCategory.Fuel -> R.string.search_chip_fuel
    SearchCategory.Food -> R.string.search_chip_food
    SearchCategory.Grocery -> R.string.search_chip_grocery
    SearchCategory.Transit -> R.string.search_chip_transit
}

/** A whole number in the app language's digits: "10", or "১০" in Bengali. */
@Composable
private fun wholeNumber(value: Int): String {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    return NumberFormat.getIntegerInstance(locale).format(value)
}

/**
 * One row of quick searches. It scrolls sideways, so every chip keeps its full size at any
 * font scale. Each chip is a button for TalkBack ("Banks, button"); the chosen one is also
 * announced as selected. A chip is at least 48 dp tall to the touch (Material's default).
 */
@Composable
private fun CategoryChips(
    selected: SearchCategory?,
    onClick: (SearchCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = SafeRouteTheme.spacing
    val label = stringResource(R.string.search_chips_label)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = spacing.md, vertical = spacing.xxs)
            .testTag(SearchTags.Chips)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchCategory.entries.forEach { category ->
            FilterChip(
                selected = category == selected,
                onClick = { onClick(category) },
                label = { Text(text = stringResource(category.labelRes())) },
                modifier = Modifier.semantics { role = Role.Button },
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
    action: Pair<String, () -> Unit>? = null,
    footnote: String? = null,
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
        if (action != null) {
            OutlinedButton(onClick = action.second) { Text(text = action.first) }
        }
        if (footnote != null) {
            Text(
                text = footnote,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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
        // A search by kind or brand: how far around what the server looked. Read out too.
        results.circle?.let { circle ->
            item(key = "within") {
                Text(
                    text = stringResource(withinRes(circle, results.distancesFrom), wholeNumber(circle.radiusKm)) +
                        " " + stringResource(R.string.search_distance_note),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md)
                        .testTag(SearchTags.Within)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Said once, in words, for everyone: what the distances are measured from.
        if (results.circle == null && results.distancesFrom != null && results.places.any { it.distanceMeters != null }) {
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
            PlaceRow(
                place = place,
                // A distance is shown only when the app knows it belongs to this answer: the
                // search was sent with an area, or the server says it searched a circle.
                showDistance = results.distancesFrom != null || results.circle != null,
                spokenDistanceRes = spokenDistanceRes(results.circle, results.distancesFrom),
                onClick = { onPlaceClick(place) },
            )
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

/** "Within N km of …": of what depends on what the circle was drawn around. */
private fun withinRes(circle: SearchedCircle, sent: NearSource?): Int = when (circle.centre) {
    CircleCentre.TypedPlace -> R.string.search_within_place
    CircleCentre.Unknown -> R.string.search_within
    CircleCentre.SentArea -> when (sent) {
        NearSource.Position -> R.string.search_within_position
        NearSource.MapCentre -> R.string.search_within_map
        null -> R.string.search_within
    }
}

/**
 * The sentence TalkBack reads for a row's distance, or null when the app can not say what the
 * distance is measured from (it then reads the bare figure).
 */
private fun spokenDistanceRes(circle: SearchedCircle?, sent: NearSource?): Int? = when (circle?.centre) {
    CircleCentre.TypedPlace -> R.string.search_distance_from_place
    CircleCentre.Unknown -> null
    CircleCentre.SentArea, null -> when (sent) {
        NearSource.Position -> R.string.search_distance_from_position
        NearSource.MapCentre -> R.string.search_distance_from_map
        null -> null
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
private fun PlaceRow(place: FoundPlace, showDistance: Boolean, spokenDistanceRes: Int?, onClick: () -> Unit) {
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
        if (place.distanceMeters != null && showDistance) {
            val distance = searchDistanceText(place.distanceMeters, locale)
            // What TalkBack reads instead of the bare figure: "2.3 km from your location".
            val spoken = spokenDistanceRes?.let { stringResource(it, distance) } ?: distance
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
