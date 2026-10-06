// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

/**
 * The frame every onboarding screen shares: a heading, content that scrolls, and the buttons
 * underneath.
 *
 * Everything is inside one scrolling column, so at 200% font size or on a small phone nothing
 * is cut off: the person scrolls to the buttons. `safeDrawing` keeps content clear of the
 * status bar, the navigation bar and display cut-outs; `imePadding` keeps it above the keyboard.
 * TalkBack reads the screen top to bottom in the order written here.
 */
@Composable
fun OnboardingLayout(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = SafeRouteTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
        )
        content()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            content = actions,
        )
    }
}

/** Body text of an onboarding screen. */
@Composable
fun OnboardingText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, style = MaterialTheme.typography.bodyLarge)
}

/**
 * An error or warning line. It has an icon as well as its colour (colour is never the only
 * signal), and `liveRegion` makes TalkBack read it out when it appears.
 */
@Composable
fun OnboardingMessage(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
