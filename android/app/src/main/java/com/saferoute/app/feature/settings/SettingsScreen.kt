// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

/** Material's opacity for content that can't be used yet. */
private const val DisabledAlpha = 0.38f

/**
 * Settings: a language row that does nothing yet, an About section and the standing safety
 * notice. Nothing here reads or stores data, and the repository address is plain text, not a
 * link (the app has no network access).
 *
 * @param versionName The app's version as shown to people, from `BuildConfig.VERSION_NAME`.
 * @param versionCode The internal build number, from `BuildConfig.VERSION_CODE`.
 * @param onBack Called by the back arrow.
 */
@Composable
fun SettingsScreen(
    versionName: String,
    versionCode: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_title),
                        modifier = Modifier
                            .padding(horizontal = SafeRouteTheme.spacing.xs)
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Not a button yet. Faded, marked disabled for TalkBack, and it says "coming soon"
            // in words. Until then the language follows the phone (or, on Android 13+, the
            // per-app language in the system settings).
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.settings_language_title)) },
                modifier = Modifier
                    .alpha(DisabledAlpha)
                    .semantics(mergeDescendants = true) { disabled() },
                supportingContent = {
                    Text(text = stringResource(R.string.settings_language_supporting))
                },
            )
            HorizontalDivider()

            SectionTitle(text = stringResource(R.string.settings_about_title))
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.app_name)) },
                modifier = Modifier.semantics(mergeDescendants = true) {},
                supportingContent = {
                    Column {
                        Text(
                            text = stringResource(
                                R.string.settings_version,
                                versionName,
                                versionCode,
                            ),
                        )
                        Text(text = stringResource(R.string.settings_licence))
                        Text(text = stringResource(R.string.settings_source_code))
                    }
                },
                leadingContent = { Icon(imageVector = Icons.Filled.Info, contentDescription = null) },
            )
            HorizontalDivider()

            SectionTitle(text = stringResource(R.string.settings_safety_notice_title))
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.not_emergency_service)) },
                modifier = Modifier.semantics(mergeDescendants = true) {},
                supportingContent = {
                    Text(text = stringResource(R.string.settings_safety_notice_body))
                },
                leadingContent = {
                    Icon(imageVector = Icons.Filled.Warning, contentDescription = null)
                },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(
                start = SafeRouteTheme.spacing.md,
                end = SafeRouteTheme.spacing.md,
                top = SafeRouteTheme.spacing.md,
            )
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@SafeRoutePreviews
@Composable
private fun SettingsScreenPreview() {
    SafeRouteTheme {
        SettingsScreen(versionName = "1.0", versionCode = 1, onBack = {})
    }
}
