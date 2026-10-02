// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saferoute.app.BuildConfig
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

/**
 * The order in which TalkBack walks the Home screen: search, map controls, emergency, sheet.
 * Without it the order would follow where things happen to sit on screen.
 */
internal object HomeTraversal {
    const val Search = 0f
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
    val context = LocalContext.current

    HomeScreen(
        emergencyDialog = emergencyDialog,
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
 * - [map] is the slot for the map. P010 passes the MapLibre view here; nothing else changes.
 * - The search pill sits at the top, clear of the status bar.
 * - The map controls and the emergency button sit just above the sheet's peek area.
 * - The emergency button is drawn last, so it stays visible and tappable even when the sheet
 *   is pulled up over the map controls.
 *
 * @param showDebugDetails Whether the placeholder map shows its grid and label.
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
    sheetState: SafeRouteSheetState = rememberSafeRouteSheetState(),
    showDebugDetails: Boolean = BuildConfig.DEBUG,
    map: @Composable (Modifier) -> Unit = { mapModifier ->
        MapPlaceholder(showDebugDetails = showDebugDetails, modifier = mapModifier)
    },
) {
    val spacing = SafeRouteTheme.spacing
    // Keeps floating controls clear of display cut-outs and the gesture areas at the sides.
    val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    // Lifts floating controls above the part of the sheet that always shows.
    val abovePeek = SafeRouteSheetDefaults.PeekHeight + spacing.md

    Box(modifier = modifier.fillMaxSize()) {
        map(Modifier.fillMaxSize())

        SearchPill(
            hint = stringResource(R.string.search_hint),
            onClick = onSearchClick,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(horizontal = spacing.md, vertical = spacing.xs)
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
            // Disabled until P010. The description says so; the faded icon alone would be a
            // colour-only signal.
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
) {
    SafeRouteTheme {
        HomeScreen(
            emergencyDialog = dialog,
            onSearchClick = {},
            onSettingsClick = {},
            onEmergencyClick = {},
            onCallEmergency = {},
            onDismissEmergencyDialog = {},
            sheetState = rememberSafeRouteSheetState(detent),
            showDebugDetails = true,
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

@Preview(name = "Home, emergency dialog", showBackground = true)
@Composable
private fun HomeScreenDialogPreview() =
    HomePreviewContent(dialog = EmergencyDialogState.OfferDialer)
