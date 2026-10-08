// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.session.AccountSummary
import com.saferoute.app.core.session.maskPhone
import com.saferoute.app.feature.emergency.NotificationBlock

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
    shortcutsViewModel: EmergencyShortcutsViewModel = hiltViewModel(),
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val tileNotice by shortcutsViewModel.tileNotice.collectAsStateWithLifecycle()
    val notification by shortcutsViewModel.notification.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val showRationale = {
        activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
    }

    // Android's permission dialog. It is launched only after the user turned the switch on
    // and chose "Continue" on the app's own explanation. Whatever the answer, the state is
    // read from Android again rather than taken from the answer.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { shortcutsViewModel.onPermissionResult(showRationale()) }
    LaunchedEffect(notification.requestPending) {
        if (notification.requestPending) {
            shortcutsViewModel.onRequestLaunched()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    // Back from system settings, the answer may be different.
    LifecycleResumeEffect(Unit) {
        shortcutsViewModel.refresh()
        onPauseOrDispose { }
    }

    SettingsScreen(
        versionName = versionName,
        versionCode = versionCode,
        onBack = onBack,
        modifier = modifier,
        developerEntry = developerEntry,
        account = account,
        onRetryAccount = viewModel::load,
        onSignOut = viewModel::signOut,
        tileNotice = tileNotice,
        onAddTile = shortcutsViewModel::onAddTileClick,
        onTileNoticeDismiss = shortcutsViewModel::onTileNoticeDismiss,
        notification = notification,
        notificationActions = NotificationShortcutActions(
            onToggle = shortcutsViewModel::onNotificationToggle,
            onExplanationContinue = shortcutsViewModel::onExplanationContinue,
            onExplanationDismiss = shortcutsViewModel::onExplanationDismiss,
            onNoticeDismiss = shortcutsViewModel::onNotificationNoticeDismiss,
            onOpenSystemSettings = {
                shortcutsViewModel.onNotificationNoticeDismiss()
                // The app's own notification page; a phone without it simply does nothing.
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        ),
    )
}

/** What the notification-shortcut part of Settings reports. Defaults do nothing. */
data class NotificationShortcutActions(
    val onToggle: (Boolean) -> Unit = {},
    val onExplanationContinue: () -> Unit = {},
    val onExplanationDismiss: () -> Unit = {},
    val onNoticeDismiss: () -> Unit = {},
    val onOpenSystemSettings: () -> Unit = {},
)

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
 * @param tileNotice What to say after "Add the SOS tile" was tapped; null says nothing.
 * @param onAddTile "Add the SOS tile" was tapped.
 * @param notification The pinned-notification shortcut: its switch, and what to say about it.
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
    tileNotice: TileNotice? = null,
    onAddTile: () -> Unit = {},
    onTileNoticeDismiss: () -> Unit = {},
    notification: NotificationShortcutUiState = NotificationShortcutUiState(),
    notificationActions: NotificationShortcutActions = NotificationShortcutActions(),
) {
    tileNotice?.let { TileNoticeDialog(notice = it, onDismiss = onTileNoticeDismiss) }
    if (notification.explaining) {
        NotificationExplanationDialog(
            onContinue = notificationActions.onExplanationContinue,
            onNotNow = notificationActions.onExplanationDismiss,
        )
    }
    notification.notice?.let { NotificationNoticeDialog(notice = it, actions = notificationActions) }

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

            EmergencyShortcutsSection(
                onAddTile = onAddTile,
                notification = notification,
                notificationActions = notificationActions,
            )

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
 * Emergency shortcuts outside the app. For now one: the Quick Settings tile. The row is a
 * button (a ListItem is far taller than 48 dp); the text under it says what the tile does and
 * what it does not do.
 */
@Composable
private fun EmergencyShortcutsSection(
    onAddTile: () -> Unit,
    notification: NotificationShortcutUiState,
    notificationActions: NotificationShortcutActions,
) {
    SectionTitle(text = stringResource(R.string.settings_shortcuts_title))
    ListItem(
        headlineContent = { Text(text = stringResource(R.string.settings_tile_add_title)) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onAddTile),
        supportingContent = { Text(text = stringResource(R.string.settings_tile_add_supporting)) },
        leadingContent = {
            Icon(painter = painterResource(R.drawable.ic_sos_tile), contentDescription = null)
        },
    )
    Text(
        text = stringResource(R.string.settings_tile_help),
        modifier = Modifier.padding(
            horizontal = SafeRouteTheme.spacing.md,
            vertical = SafeRouteTheme.spacing.xs,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NotificationShortcutRow(notification = notification, actions = notificationActions)
    HorizontalDivider()
}

/**
 * The switch for the pinned notification. The whole row is the switch (one TalkBack stop,
 * announced as a switch with its state); the small switch on the right is only its picture.
 * Under it: what state it is in, and the limits, said plainly.
 */
@Composable
private fun NotificationShortcutRow(
    notification: NotificationShortcutUiState,
    actions: NotificationShortcutActions,
) {
    val blocked = notification.enabled && notification.block != NotificationBlock.None
    ListItem(
        headlineContent = { Text(text = stringResource(R.string.settings_notification_title)) },
        modifier = Modifier.toggleable(
            value = notification.enabled,
            role = Role.Switch,
            onValueChange = actions.onToggle,
        ),
        supportingContent = {
            Text(
                text = stringResource(
                    when {
                        blocked -> R.string.settings_notification_on_blocked
                        notification.enabled -> R.string.settings_notification_on
                        else -> R.string.settings_notification_off
                    },
                ),
            )
        },
        // onCheckedChange = null: the row handles the tap; the switch only shows the state.
        trailingContent = { Switch(checked = notification.enabled, onCheckedChange = null) },
    )
    if (blocked) {
        TextButton(
            onClick = actions.onOpenSystemSettings,
            modifier = Modifier.padding(horizontal = SafeRouteTheme.spacing.xs),
        ) {
            Text(text = stringResource(R.string.settings_notification_open_settings))
        }
    }
    Text(
        text = stringResource(R.string.settings_notification_limits),
        modifier = Modifier.padding(
            horizontal = SafeRouteTheme.spacing.md,
            vertical = SafeRouteTheme.spacing.xs,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The app's own explanation, before Android is asked for anything. */
@Composable
private fun NotificationExplanationDialog(onContinue: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = {
            Text(
                text = stringResource(R.string.settings_notification_explain_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xs),
            ) {
                Text(text = stringResource(R.string.settings_notification_explain_what))
                Text(text = stringResource(R.string.settings_notification_explain_permission))
                Text(text = stringResource(R.string.settings_notification_limits))
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(text = stringResource(R.string.settings_notification_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) {
                Text(text = stringResource(R.string.settings_notification_not_now))
            }
        },
    )
}

/** Why the shortcut could not be turned on. System settings are offered where they can help. */
@Composable
private fun NotificationNoticeDialog(notice: NotificationNotice, actions: NotificationShortcutActions) {
    val body = when (notice) {
        NotificationNotice.DeniedOnce -> R.string.settings_notification_denied_once
        NotificationNotice.DeniedForGood -> R.string.settings_notification_denied_for_good
        NotificationNotice.AppBlocked -> R.string.settings_notification_app_blocked
        NotificationNotice.ChannelBlocked -> R.string.settings_notification_channel_blocked
    }
    AlertDialog(
        onDismissRequest = actions.onNoticeDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_notification_notice_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = stringResource(body))
            }
        },
        confirmButton = {
            // After one refusal Android asks again by itself; there is nothing to do in settings.
            if (notice != NotificationNotice.DeniedOnce) {
                TextButton(onClick = actions.onOpenSystemSettings) {
                    Text(text = stringResource(R.string.settings_notification_open_settings))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onNoticeDismiss) {
                Text(text = stringResource(R.string.settings_notification_close))
            }
        },
    )
}

/** The answer to "Add the SOS tile": done, there already, or how to do it by hand. */
@Composable
private fun TileNoticeDialog(notice: TileNotice, onDismiss: () -> Unit) {
    val title = when (notice) {
        TileNotice.Added -> R.string.settings_tile_added_title
        TileNotice.AlreadyAdded -> R.string.settings_tile_already_title
        TileNotice.ShowSteps -> R.string.settings_tile_steps_title
    }
    val lines = when (notice) {
        TileNotice.Added -> listOf(R.string.settings_tile_added_body)
        TileNotice.AlreadyAdded -> listOf(R.string.settings_tile_already_body)
        TileNotice.ShowSteps -> listOf(
            R.string.settings_tile_steps_1,
            R.string.settings_tile_steps_2,
            R.string.settings_tile_steps_3,
            R.string.settings_tile_steps_4,
            R.string.settings_tile_steps_note,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(title), modifier = Modifier.semantics { heading() }) },
        text = {
            // Scrolls, so that large text can never push the button off the screen.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xs),
            ) {
                lines.forEach { Text(text = stringResource(it)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.settings_tile_ok)) }
        },
    )
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
