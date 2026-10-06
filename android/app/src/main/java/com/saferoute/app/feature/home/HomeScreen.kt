// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import com.saferoute.app.core.map.MapLoadState
import com.saferoute.app.core.map.MapPadding
import com.saferoute.app.core.map.MapStyleVariant
import kotlin.math.roundToInt

/**
 * The order in which TalkBack walks the Home screen: search, map controls, emergency, sheet.
 * Without it the order would follow where things happen to sit on screen.
 */
internal object HomeTraversal {
    const val Search = 0f
    const val MapStatus = 0.5f
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
) {
    val emergencyDialog by viewModel.emergencyDialog.collectAsStateWithLifecycle()
    val mapState by viewModel.map.loadState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

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
) {
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
            // Layers has no prompt yet; my location arrives with P010b. The descriptions say
            // so; the faded icon alone would be a colour-only signal.
            MapControlButton(
                painter = painterResource(R.drawable.ic_layers),
                contentDescription = stringResource(R.string.map_control_layers_unavailable),
                onClick = {},
                enabled = false,
            )
            MapControlButton(
                painter = painterResource(R.drawable.ic_my_location),
                contentDescription = stringResource(R.string.map_control_my_location_unavailable),
                onClick = {},
                enabled = false,
            )
        }

        SafeRouteBottomSheet(
            modifier = Modifier.semantics {
                isTraversalGroup = true
                traversalIndex = HomeTraversal.Sheet
            },
            state = sheetState,
        ) {
            HomeSheetContent()
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
