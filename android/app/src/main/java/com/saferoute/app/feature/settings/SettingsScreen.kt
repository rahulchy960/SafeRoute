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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.session.AccountSummary
import com.saferoute.app.core.session.maskPhone

/** Material's opacity for content that can't be used yet. */
private const val DisabledAlpha = 0.38f

/** Settings, connected to [AccountViewModel] for the Account and Privacy sections. */
@Composable
fun SettingsRoute(
    versionName: String,
    versionCode: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    developerEntry: @Composable () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    SettingsScreen(
        versionName = versionName,
        versionCode = versionCode,
        onBack = onBack,
        modifier = modifier,
        developerEntry = developerEntry,
        account = account,
        onRetryAccount = viewModel::load,
        onSignOut = viewModel::signOut,
    )
}

/**
 * Settings: the account (masked phone number, sign out), the consents on record, a language
 * row that does nothing yet, an About section and the standing safety notice. The repository
 * address is plain text, not a link (no browser intent yet).
 *
 * @param versionName The app's version as shown to people, from `BuildConfig.VERSION_NAME`.
 * @param versionCode The internal build number, from `BuildConfig.VERSION_CODE`.
 * @param onBack Called by the back arrow.
 * @param developerEntry A slot under the language row. Debug builds put the "Developer" row
 * here; release builds leave it empty (see navigation/DeveloperNavigation.kt).
 * @param account What the Account and Privacy sections show; null leaves both out.
 * @param onRetryAccount "Try again" when the account could not be loaded.
 * @param onSignOut Called after the person confirmed "Sign out" in the dialog.
 */
@Composable
fun SettingsScreen(
    versionName: String,
    versionCode: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    developerEntry: @Composable () -> Unit = {},
    account: AccountUiState? = null,
    onRetryAccount: () -> Unit = {},
    onSignOut: () -> Unit = {},
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
            if (account != null) {
                AccountSection(account = account, onRetry = onRetryAccount, onSignOut = onSignOut)
            }

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
            developerEntry()

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

/**
 * Account: the masked phone number and "Sign out". Privacy: the consents on record, read-only.
 *
 * "Sign out" asks first, because coming back means going through the first steps again.
 * It stays available when the details can't be loaded.
 */
@Composable
private fun AccountSection(account: AccountUiState, onRetry: () -> Unit, onSignOut: () -> Unit) {
    var confirmingSignOut by rememberSaveable { mutableStateOf(false) }

    SectionTitle(text = stringResource(R.string.settings_account_title))
    when (account) {
        AccountUiState.Loading -> ListItem(
            headlineContent = { Text(text = stringResource(R.string.settings_account_loading)) },
        )

        AccountUiState.Unavailable -> ListItem(
            headlineContent = { Text(text = stringResource(R.string.settings_account_unavailable)) },
            supportingContent = {
                TextButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.settings_account_retry))
                }
            },
        )

        is AccountUiState.Loaded -> ListItem(
            headlineContent = { Text(text = stringResource(R.string.settings_account_phone)) },
            modifier = Modifier.semantics(mergeDescendants = true) {},
            supportingContent = {
                Text(
                    text = account.account.maskedPhone
                        ?: stringResource(R.string.settings_account_no_phone),
                )
            },
        )
    }
    OutlinedButton(
        onClick = { confirmingSignOut = true },
        modifier = Modifier.padding(horizontal = SafeRouteTheme.spacing.md),
    ) {
        Text(text = stringResource(R.string.settings_sign_out))
    }
    HorizontalDivider(modifier = Modifier.padding(top = SafeRouteTheme.spacing.md))

    if (account is AccountUiState.Loaded) {
        SectionTitle(text = stringResource(R.string.settings_privacy_title))
        ListItem(
            headlineContent = {
                Text(
                    text = stringResource(
                        if (account.account.grantedPurposes.isEmpty()) {
                            R.string.settings_privacy_none
                        } else {
                            R.string.settings_privacy_granted
                        },
                    ),
                )
            },
            modifier = Modifier.semantics(mergeDescendants = true) {},
            supportingContent = {
                Column {
                    account.account.grantedPurposes.forEach { purpose ->
                        Text(text = "• " + purposeLabel(purpose))
                    }
                }
            },
        )
        HorizontalDivider()
    }

    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text(text = stringResource(R.string.settings_sign_out_dialog_title)) },
            text = { Text(text = stringResource(R.string.settings_sign_out_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingSignOut = false
                        onSignOut()
                    },
                ) {
                    Text(text = stringResource(R.string.settings_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOut = false }) {
                    Text(text = stringResource(R.string.settings_sign_out_cancel))
                }
            },
        )
    }
}

/** A purpose in words. One the app does not know yet is shown as the server sent it. */
@Composable
private fun purposeLabel(purpose: String): String = when (purpose) {
    "account_core" -> stringResource(R.string.consent_purpose_account_core)
    "sos_alerts" -> stringResource(R.string.consent_purpose_sos_alerts)
    "live_sharing" -> stringResource(R.string.consent_purpose_live_sharing)
    "safety_reports" -> stringResource(R.string.consent_purpose_safety_reports)
    else -> purpose
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
        SettingsScreen(
            versionName = "1.0",
            versionCode = 1,
            onBack = {},
            account = AccountUiState.Loaded(
                AccountSummary(
                    maskedPhone = maskPhone("+910000000123"),
                    role = "user",
                    locale = "en",
                    grantedPurposes = listOf("account_core"),
                ),
            ),
        )
    }
}
