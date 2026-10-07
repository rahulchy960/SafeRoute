// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.component.EmergencyButton
import com.saferoute.app.core.designsystem.component.MapControlButton
import com.saferoute.app.core.designsystem.component.SafeRouteBottomSheet
import com.saferoute.app.core.designsystem.component.SafeRouteSheetDefaults
import com.saferoute.app.core.designsystem.component.SafeRouteSheetState
import com.saferoute.app.core.designsystem.component.SearchPill
import com.saferoute.app.core.designsystem.component.SheetDetent
import com.saferoute.app.core.designsystem.component.rememberSafeRouteSheetState
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.map.MapLoadState
import com.saferoute.app.core.map.MapPadding
import com.saferoute.app.core.map.MapStyleVariant
import com.saferoute.app.core.map.SelectedPlace
import com.saferoute.app.feature.directions.DirectionsActions
import com.saferoute.app.feature.directions.DirectionsSheet
import com.saferoute.app.feature.directions.DirectionsUiState
import com.saferoute.app.feature.directions.DirectionsViewModel
import com.saferoute.app.feature.directions.RouteIntroDialog
import kotlin.math.roundToInt

/**
 * The order in which TalkBack walks the Home screen: search, map controls, emergency, sheet.
 * Without it the order would follow where things happen to sit on screen.
 */
internal object HomeTraversal {
    const val Search = 0f
    const val MapStatus = 0.5f
    const val LocationNotice = 0.75f
    const val MapControls = 1f
    const val Emergency = 2f
    const val Sheet = 3f
}

/**
 * Home, connected to its [HomeViewModel]. This is the only part of the screen that knows about
 * Hilt, the ViewModel and the Android context; [HomeScreen] below is plain UI that takes values
 * and reports events, which makes it easy to preview and test.
 *
 * `collectAsStateWithLifecycle` reads the StateFlow only while the screen is visible.
 */
@Composable
fun HomeRoute(
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    permissionViewModel: LocationPermissionViewModel = hiltViewModel(),
    directionsViewModel: DirectionsViewModel = hiltViewModel(),
) {
    val directions by directionsViewModel.state.collectAsStateWithLifecycle()
    val emergencyDialog by viewModel.emergencyDialog.collectAsStateWithLifecycle()
    val mapState by viewModel.map.loadState.collectAsStateWithLifecycle()
    val myLocation by viewModel.myLocation.collectAsStateWithLifecycle()
    val locationState by viewModel.locationState.collectAsStateWithLifecycle()
    val permission by permissionViewModel.state.collectAsStateWithLifecycle()
    val selectedPlace by viewModel.selectedPlace.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // "Should the app explain itself before asking again?" Android answers per activity.
    val activity = LocalActivity.current
    val showRationale = {
        activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    // The system permission dialog. `launch` shows it; the lambda receives the answer. The
    // state is then read from Android again rather than from the answer itself.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { answers ->
        // No answers at all: Android dropped the request (another one was already open).
        // That is not a refusal.
        if (answers.isEmpty()) {
            permissionViewModel.onPermissionRequestCancelled()
        } else {
            permissionViewModel.onPermissionResult(showRationale())
        }
    }
    LaunchedEffect(permission.requestPending) {
        if (permission.requestPending) {
            permissionViewModel.onRequestLaunched()
            // Both together: Android then offers "precise" and "approximate" in one dialog.
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    // Every time Home comes back to the front the permission is read again: the user may have
    // changed it in system Settings in the meantime.
    LifecycleResumeEffect(Unit) {
        permissionViewModel.refresh(showRationale())
        onPauseOrDispose { }
    }

    // Location runs only while Home is visible AND permitted, and stops the moment Home is
    // not visible (onStop). Nothing keeps it alive in the background.
    val granted = permission.permission as? LocationPermissionState.Granted
    LifecycleStartEffect(granted) {
        if (granted != null) {
            viewModel.onLocationAvailable(userAsked = permissionViewModel.consumeUserAsked())
        } else {
            viewModel.onLocationUnavailable(permissionLost = true)
        }
        onStopOrDispose { viewModel.onLocationUnavailable(permissionLost = false) }
    }

    // The map follows the app theme, which follows the phone's dark-mode setting.
    val darkTheme = isSystemInDarkTheme()
    LaunchedEffect(darkTheme) {
        viewModel.map.setStyleVariant(if (darkTheme) MapStyleVariant.Dark else MapStyleVariant.Light)
    }

    HomeScreen(
        emergencyDialog = emergencyDialog,
        mapState = mapState,
        onMapRetry = viewModel.map::retry,
        onMapPaddingChange = viewModel.map::setPadding,
        // A phone without a browser: the link simply does not open.
        onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
        map = { mapModifier -> viewModel.mapEngine.Map(viewModel.map, mapModifier) },
        myLocation = myLocation,
        locationStale = locationState is LocationState.Stale,
        locationApproximate = locationState.fixOrNull()?.isApproximate == true,
        onMyLocationClick = {
            // The only place the permission flow can start.
            if (permissionViewModel.onMyLocationClick() && !viewModel.onMyLocationClick()) {
                permissionViewModel.onNoFix()
            }
        },
        showLocationDisclosure = permission.permission == LocationPermissionState.DisclosureShown,
        onLocationDisclosureContinue = permissionViewModel::onDisclosureContinue,
        onLocationDisclosureNotNow = permissionViewModel::onDisclosureNotNow,
        locationNotice = permission.notice,
        onLocationNoticeAction = { notice ->
            when (notice) {
                LocationNotice.DeniedPermanently -> context.openSettings(appSettingsIntent(context))
                LocationNotice.ServicesOff ->
                    context.openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                LocationNotice.Approximate -> permissionViewModel.onUsePreciseClick()
                else -> Unit
            }
        },
        onLocationNoticeDismiss = permissionViewModel::onNoticeDismiss,
        selectedPlace = selectedPlace,
        onPlaceDismiss = viewModel::onPlaceDismiss,
        directions = directions,
        directionsActions = DirectionsActions(
            onOpen = directionsViewModel::onDirectionsClick,
            onIntroContinue = directionsViewModel::onIntroContinue,
            onModeChange = directionsViewModel::onModeChange,
            onRouteSelect = directionsViewModel::onRouteSelect,
            onRetry = directionsViewModel::onRetry,
            onClose = directionsViewModel::onClose,
        ),
        onSearchClick = onOpenSearch,
        onSettingsClick = onOpenSettings,
        onEmergencyClick = viewModel::onEmergencyClick,
        onCallEmergency = {
            if (openEmergencyDialer(context)) {
                viewModel.onDialerOpened()
            } else {
                viewModel.onDialerUnavailable()
            }
        },
        onDismissEmergencyDialog = viewModel::onEmergencyDialogDismiss,
        modifier = modifier,
    )
}

/** The app's own page in system Settings, where a permission denied for good can be allowed. */
private fun appSettingsIntent(context: Context) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", context.packageName, null),
)

/** Opens a system Settings page; a phone without that page simply does nothing. */
private fun Context.openSettings(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * The maps-style Home layout (ADR 0008): the map fills the screen, and everything else floats
 * on it.
 *
 * - [map] is the slot for the map. The route passes the real map; previews and tests pass
 *   nothing and get the neutral backdrop. It is not composed at all without a map key.
 * - [mapState] decides the small status card in the map area. The map can fail in several
 *   ways; none of them touches the search pill, the controls, the sheet or the emergency
 *   button, which are separate elements drawn on top of it.
 * - The search pill sits at the top, clear of the status bar, with the map credit below it:
 *   the one place the sheet never covers while any map is visible.
 * - The map controls and the emergency button sit just above the sheet's peek area.
 * - The emergency button is drawn last, so it stays visible and tappable even when the sheet
 *   is pulled up over the map controls.
 *
 * @param onMapPaddingChange Told how much of the map the pill (top) and the sheet (bottom)
 * cover, in pixels, whenever that changes, so the map keeps its focus in the visible part.
 */
@Composable
fun HomeScreen(
    emergencyDialog: EmergencyDialogState,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEmergencyClick: () -> Unit,
    onCallEmergency: () -> Unit,
    onDismissEmergencyDialog: () -> Unit,
    modifier: Modifier = Modifier,
    mapState: MapLoadState = MapLoadState.Ready,
    onMapRetry: () -> Unit = {},
    onMapPaddingChange: (MapPadding) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    sheetState: SafeRouteSheetState = rememberSafeRouteSheetState(),
    map: @Composable (Modifier) -> Unit = {},
    myLocation: MyLocationControl = MyLocationControl.Off,
    locationStale: Boolean = false,
    locationApproximate: Boolean = false,
    onMyLocationClick: () -> Unit = {},
    showLocationDisclosure: Boolean = false,
    onLocationDisclosureContinue: () -> Unit = {},
    onLocationDisclosureNotNow: () -> Unit = {},
    locationNotice: LocationNotice? = null,
    onLocationNoticeAction: (LocationNotice) -> Unit = {},
    onLocationNoticeDismiss: () -> Unit = {},
    selectedPlace: SelectedPlace? = null,
    onPlaceDismiss: () -> Unit = {},
    directions: DirectionsUiState = DirectionsUiState.Closed,
    directionsActions: DirectionsActions = DirectionsActions(),
) {
    // Back with a place on the map takes the place away first; the next back leaves the app as
    // before. BackHandler is only active while there is a place, so normal back is untouched.
    BackHandler(enabled = selectedPlace != null, onBack = onPlaceDismiss)
    // Declared after it, so it wins while directions show: back closes the routes first and
    // leaves the place card.
    BackHandler(enabled = directions != DirectionsUiState.Closed, onBack = directionsActions.onClose)

    // Directions need room: a sheet that only peeks is raised to half once when they open. The
    // user can still pull it down again; it is not forced back up.
    val directionsOpen = directions is DirectionsUiState.Open
    LaunchedEffect(directionsOpen) {
        if (directionsOpen && sheetState.currentDetent == SheetDetent.Peek) sheetState.animateTo(SheetDetent.Half)
    }

    val spacing = SafeRouteTheme.spacing
    // Keeps floating controls clear of display cut-outs and the gesture areas at the sides.
    val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    // Lifts floating controls above the part of the sheet that always shows.
    val abovePeek = SafeRouteSheetDefaults.PeekHeight + spacing.md

    var showMapCredits by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Where the pill and the credit end, measured after layout.
        var topCoveredPx by remember { mutableIntStateOf(0) }
        val mapPadding = MapPadding(
            top = topCoveredPx,
            bottom = mapBottomPaddingPx(
                detent = sheetState.currentDetent,
                containerHeightPx = constraints.maxHeight,
                peekHeightPx = with(density) { SafeRouteSheetDefaults.PeekHeight.roundToPx() },
                navigationBarPx = WindowInsets.navigationBars.getBottom(density),
            ),
        )
        // Runs again only when the padding really changes (the sheet settled somewhere else).
        LaunchedEffect(mapPadding) { onMapPaddingChange(mapPadding) }

        val mapDescription = stringResource(R.string.map_content_description)
        Box(
            modifier = Modifier
                .fillMaxSize()
                // What shows before the first tiles arrive and when there is no map at all.
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .semantics { contentDescription = mapDescription },
        ) {
            if (mapState != MapLoadState.NotConfigured) map(Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(horizontal = spacing.md, vertical = spacing.xs)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Column(
                // Home fills the window edge to edge, so "in root" is "in the map view".
                modifier = Modifier.onGloballyPositioned {
                    topCoveredPx = (it.positionInRoot().y + it.size.height).roundToInt()
                },
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                SearchPill(
                    hint = stringResource(R.string.search_hint),
                    onClick = onSearchClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            isTraversalGroup = true
                            traversalIndex = HomeTraversal.Search
                        },
                    trailingContent = {
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.settings_title),
                                tint = SafeRouteTheme.colors.mapOverlayVariant,
                            )
                        }
                    },
                )
                // No map, no map data on screen, nothing to credit.
                if (mapState != MapLoadState.NotConfigured) {
                    MapAttributionChip(onClick = { showMapCredits = true })
                }
            }
            MapStatusCard(
                state = mapState,
                onRetry = onMapRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        isTraversalGroup = true
                        traversalIndex = HomeTraversal.MapStatus
                    },
            )
            locationNotice?.let { notice ->
                LocationNoticeCard(
                    notice = notice,
                    onAction = onLocationNoticeAction,
                    onDismiss = onLocationNoticeDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            isTraversalGroup = true
                            traversalIndex = HomeTraversal.LocationNotice
                        },
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(sideInsets)
                .navigationBarsPadding()
                // One emergency button's height higher, so the two never overlap.
                .padding(end = spacing.md, bottom = abovePeek + EmergencyRowHeight)
                .semantics {
                    isTraversalGroup = true
                    traversalIndex = HomeTraversal.MapControls
                },
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            // Layers has no prompt yet. Its description says so; the faded icon alone would
            // be a colour-only signal.
            MapControlButton(
                painter = painterResource(R.drawable.ic_layers),
                contentDescription = stringResource(R.string.map_control_layers_unavailable),
                onClick = {},
                enabled = false,
            )
            MyLocationButton(
                control = myLocation,
                onClick = onMyLocationClick,
                stale = locationStale,
                approximate = locationApproximate,
            )
        }

        SafeRouteBottomSheet(
            modifier = Modifier.semantics {
                isTraversalGroup = true
                traversalIndex = HomeTraversal.Sheet
            },
            state = sheetState,
        ) {
            when {
                directions is DirectionsUiState.Open -> DirectionsSheet(
                    state = directions,
                    actions = directionsActions,
                    // "Use my location" is the same button as on the map: one permission flow.
                    onUseMyLocation = onMyLocationClick,
                )
                selectedPlace != null -> PlaceCard(
                    place = selectedPlace,
                    onClose = onPlaceDismiss,
                    onDirections = directionsActions.onOpen,
                )
                else -> HomeSheetContent()
            }
        }

        EmergencyButton(
            onClick = onEmergencyClick,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(sideInsets)
                .navigationBarsPadding()
                .padding(start = spacing.md, end = spacing.md, bottom = abovePeek)
                .semantics {
                    isTraversalGroup = true
                    traversalIndex = HomeTraversal.Emergency
                },
        )
    }

    if (showLocationDisclosure) {
        LocationDisclosureDialog(
            onContinue = onLocationDisclosureContinue,
            onNotNow = onLocationDisclosureNotNow,
        )
    }

    if (directions is DirectionsUiState.Intro) {
        RouteIntroDialog(onContinue = directionsActions.onIntroContinue, onNotNow = directionsActions.onClose)
    }

    if (showMapCredits) {
        MapAttributionDialog(onOpenLink = onOpenLink, onDismiss = { showMapCredits = false })
    }

    // Independent of the map on purpose: this depends on [emergencyDialog] alone.
    if (emergencyDialog != EmergencyDialogState.Hidden) {
        EmergencyDialog(
            state = emergencyDialog,
            onCallEmergency = onCallEmergency,
            onDismiss = onDismissEmergencyDialog,
        )
    }
}

/** Room reserved for the emergency button below the map controls (its height plus a gap). */
private val EmergencyRowHeight = 72.dp

/**
 * How much of the bottom of the map the sheet covers, in pixels.
 *
 * At [SheetDetent.Full] the sheet hides the whole map, so there is no "visible part" to centre
 * in: the half-height value is kept, and the map does not jump while the sheet is pulled up.
 */
internal fun mapBottomPaddingPx(
    detent: SheetDetent,
    containerHeightPx: Int,
    peekHeightPx: Int,
    navigationBarPx: Int,
): Int = when (detent) {
    SheetDetent.Peek -> (peekHeightPx + navigationBarPx).coerceAtMost(containerHeightPx)
    SheetDetent.Half, SheetDetent.Full ->
        (containerHeightPx * SafeRouteSheetDefaults.HalfFraction).roundToInt()
}

/**
 * What the sheet shows for now: the question and two rows that will list places later. The
 * rows are not buttons; each says in words that it is not available yet.
 */
@Composable
private fun HomeSheetContent() {
    val spacing = SafeRouteTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = stringResource(R.string.home_sheet_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        PlaceholderRow(icon = Icons.Filled.Star, title = stringResource(R.string.home_saved_places))
        PlaceholderRow(icon = Icons.Filled.Place, title = stringResource(R.string.home_recent))
    }
}

@Composable
private fun PlaceholderRow(icon: ImageVector, title: String) {
    val spacing = SafeRouteTheme.spacing
    Row(
        // One TalkBack stop per row: "Saved places, coming in a later version".
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.coming_later),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A grey bar where a place name will be: a visual hint only.
            Box(
                Modifier
                    .fillMaxWidth(fraction = 0.6f)
                    .height(spacing.xs)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.extraSmall,
                    ),
            )
        }
    }
}

@Composable
private fun HomePreviewContent(
    dialog: EmergencyDialogState = EmergencyDialogState.Hidden,
    detent: SheetDetent = SheetDetent.Peek,
    mapState: MapLoadState = MapLoadState.Ready,
) {
    SafeRouteTheme {
        HomeScreen(
            emergencyDialog = dialog,
            onSearchClick = {},
            onSettingsClick = {},
            onEmergencyClick = {},
            onCallEmergency = {},
            onDismissEmergencyDialog = {},
            mapState = mapState,
            sheetState = rememberSafeRouteSheetState(detent),
        )
    }
}

@SafeRoutePreviews
@Composable
private fun HomeScreenPreview() = HomePreviewContent()

@Preview(name = "Home, sheet half open", showBackground = true)
@Composable
private fun HomeScreenHalfSheetPreview() = HomePreviewContent(detent = SheetDetent.Half)

@Preview(name = "Home, sheet fully open", showBackground = true)
@Composable
private fun HomeScreenFullSheetPreview() = HomePreviewContent(detent = SheetDetent.Full)

@Preview(name = "Home, map offline", showBackground = true)
@Composable
private fun HomeScreenMapOfflinePreview() = HomePreviewContent(mapState = MapLoadState.Offline)

@Preview(name = "Home, emergency dialog", showBackground = true)
@Composable
private fun HomeScreenDialogPreview() =
    HomePreviewContent(dialog = EmergencyDialogState.OfferDialer)
