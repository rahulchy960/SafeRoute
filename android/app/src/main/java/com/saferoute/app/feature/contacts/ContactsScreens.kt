// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saferoute.app.R
import com.saferoute.app.core.data.MAX_SOS_CONTACTS
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

/*
 * The emergency contacts screens that show what is stored: the list, one contact, and the card
 * on Home. Each screen is a stateless composable plus a Route that connects its ViewModel.
 *
 * None of them uses the emergency colour or covers the SOS control: these are ordinary
 * screens of their own, opened from Settings or from the card on Home.
 */

/** The words for an error. Calm, and never a reason to think an alert was or was not sent. */
@StringRes
internal fun ContactsError.message(): Int = when (this) {
    ContactsError.NoConnection -> R.string.contacts_error_offline
    ContactsError.Unavailable -> R.string.contacts_error_unavailable
    is ContactsError.RateLimited ->
        if (retryAfterSeconds == null) R.string.contacts_error_links_used else R.string.contacts_error_rate_limited
    ContactsError.AlreadyExists -> R.string.contacts_error_exists
    ContactsError.OptedOut -> R.string.contacts_error_opted_out
    ContactsError.OwnNumber -> R.string.contacts_error_own_number
    ContactsError.Invalid -> R.string.contacts_error_invalid
    ContactsError.LimitReached -> R.string.contacts_limit_reason
    ContactsError.NotFound -> R.string.contacts_error_not_found
    ContactsError.ConsentRequired, ContactsError.Unexpected -> R.string.contacts_error_unexpected
}

@StringRes
internal fun ContactStatus.label(): Int = when (this) {
    ContactStatus.NotInvited -> R.string.contacts_status_not_invited
    ContactStatus.Invited -> R.string.contacts_status_invited
    ContactStatus.OptedOut -> R.string.contacts_status_opted_out
}

/** The frame every contacts screen shares: a title bar with a back arrow, content that scrolls. */
@Composable
internal fun ContactsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
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
                        text = title,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = SafeRouteTheme.spacing.xs)
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    actions()
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Scrolls, so that large text can never push a button off the screen.
                .verticalScroll(rememberScrollState())
                .padding(SafeRouteTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.md),
            content = content,
        )
    }
}

/** A message that TalkBack reads out when it appears. */
@Composable
internal fun ContactsMessage(text: String, modifier: Modifier = Modifier, isError: Boolean = true) {
    Text(
        text = text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// The list --------------------------------------------------------------------------------

@Composable
fun ContactsRoute(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenContact: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ContactsScreen(
        state = state,
        onBack = onBack,
        onAdd = onAdd,
        onOpenContact = onOpenContact,
        onRefresh = viewModel::onRefresh,
        onStopConfirmed = viewModel::onStopConfirmed,
        modifier = modifier,
    )
}

/**
 * The list of emergency contacts. It shows the copy on the phone, so it opens with no
 * connection; in that case it says the list is read-only. "Add contact" is disabled, with the
 * reason in words, when the limit is reached.
 */
@Composable
fun ContactsScreen(
    state: ContactsUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenContact: (String) -> Unit,
    onRefresh: () -> Unit,
    onStopConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingStop by remember { mutableStateOf(false) }
    val contacts = state.contacts.orEmpty()

    ContactsScaffold(
        title = stringResource(R.string.contacts_title),
        onBack = onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.contacts_refresh),
                )
            }
        },
    ) {
        if (state.refreshing || state.stopping) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.contacts_intro, MAX_SOS_CONTACTS),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.offline) ContactsMessage(stringResource(R.string.contacts_offline), isError = false)
        state.error?.let {
            // A failed refresh by hand: say what is on screen. Anything else: the error itself.
            val refreshFailed = it == ContactsError.Unavailable || it == ContactsError.Unexpected
            ContactsMessage(stringResource(if (refreshFailed) R.string.contacts_refresh_failed else it.message()))
        }
        if (state.stopped) ContactsMessage(stringResource(R.string.contacts_stop_done), isError = false)

        if (state.contacts != null && contacts.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xxs)) {
                Text(
                    text = stringResource(R.string.contacts_empty_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = stringResource(R.string.contacts_empty_body))
            }
        }
        Column {
            contacts.forEach { contact ->
                ContactRow(contact = contact, onClick = { onOpenContact(contact.id) })
                HorizontalDivider()
            }
        }

        Button(onClick = onAdd, enabled = state.canAdd && !state.stopping, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.contacts_add))
        }
        if (!state.canAdd) {
            Text(
                text = stringResource(R.string.contacts_limit_reason, MAX_SOS_CONTACTS),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = { confirmingStop = true },
            enabled = !state.stopping,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.contacts_stop_button))
        }
    }

    if (confirmingStop) {
        ConfirmDialog(
            title = stringResource(R.string.contacts_stop_dialog_title),
            body = stringResource(R.string.contacts_stop_dialog_body),
            confirm = stringResource(R.string.contacts_stop_confirm),
            onConfirm = {
                confirmingStop = false
                onStopConfirmed()
            },
            onDismiss = { confirmingStop = false },
        )
    }
}

/** One contact: the name, the number and its status in words. The whole row is the button. */
@Composable
private fun ContactRow(contact: EmergencyContact, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text = contact.name) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xxs)) {
                Text(text = contact.phoneE164)
                StatusChip(status = contact.status)
            }
        },
    )
}

/** The status as a small label. Words, not a colour alone, carry the meaning. */
@Composable
internal fun StatusChip(status: ContactStatus, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = stringResource(status.label()),
            modifier = Modifier.padding(
                horizontal = SafeRouteTheme.spacing.xs,
                vertical = SafeRouteTheme.spacing.xxs,
            ),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) { Text(text = body) }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(text = confirm) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.contacts_cancel)) }
        },
    )
}

// One contact -----------------------------------------------------------------------------

@Composable
fun ContactDetailRoute(
    onBack: () -> Unit,
    onInvite: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Removed here, or gone from the list (removed elsewhere): there is nothing left to show.
    val gone = state.removed || (!state.loading && state.contact == null)
    LaunchedEffect(gone) { if (gone) onBack() }
    ContactDetailScreen(
        state = state,
        onBack = onBack,
        onInvite = { state.contact?.let { onInvite(it.id) } },
        onRename = viewModel::onRename,
        onRemoveConfirmed = viewModel::onRemoveConfirmed,
        modifier = modifier,
    )
}

/**
 * One contact: rename, send the invite (again), remove. A contact who opted out cannot be
 * invited again, and the screen says why instead of offering the button.
 */
@Composable
fun ContactDetailScreen(
    state: ContactDetailUiState,
    onBack: () -> Unit,
    onInvite: () -> Unit,
    onRename: (String) -> Boolean,
    onRemoveConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmingRemove by remember { mutableStateOf(false) }
    val contact = state.contact

    ContactsScaffold(title = stringResource(R.string.contacts_detail_title), onBack = onBack, modifier = modifier) {
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (contact != null) {
            Column(verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xxs)) {
                Text(
                    text = contact.name,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(text = contact.phoneE164)
                StatusChip(status = contact.status)
            }
            state.error?.let { ContactsMessage(stringResource(it.message())) }

            if (renaming) {
                RenameEditor(
                    current = contact.name,
                    onSave = { name -> onRename(name).also { accepted -> if (accepted) renaming = false } },
                    onCancel = { renaming = false },
                )
                return@ContactsScaffold
            }
            if (contact.status == ContactStatus.OptedOut) {
                Text(text = stringResource(R.string.contacts_detail_opted_out))
            } else {
                Button(onClick = onInvite, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(
                            if (contact.status == ContactStatus.Invited) {
                                R.string.contacts_detail_invite_again
                            } else {
                                R.string.contacts_detail_invite
                            },
                        ),
                    )
                }
            }
            OutlinedButton(onClick = { renaming = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.contacts_detail_rename))
            }
            OutlinedButton(
                onClick = { confirmingRemove = true },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.contacts_detail_remove))
            }
        }
    }

    if (confirmingRemove) {
        ConfirmDialog(
            title = stringResource(R.string.contacts_remove_dialog_title),
            body = stringResource(R.string.contacts_remove_dialog_body),
            confirm = stringResource(R.string.contacts_remove_confirm),
            onConfirm = {
                confirmingRemove = false
                onRemoveConfirmed()
            },
            onDismiss = { confirmingRemove = false },
        )
    }
}

/**
 * The name field with Save and Cancel, shown in the screen in place of the buttons. Part of the
 * screen and not a dialog: a text field in a dialog would have to fit above the keyboard.
 */
@Composable
private fun RenameEditor(current: String, onSave: (String) -> Boolean, onCancel: () -> Unit) {
    // Kept in memory only (`remember`, not `rememberSaveable`): a name is not written to the
    // saved state of the screen.
    var name by remember { mutableStateOf(current) }
    var invalid by remember { mutableStateOf(false) }
    Text(
        text = stringResource(R.string.contacts_rename_title),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it
            invalid = false
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = stringResource(R.string.contacts_add_name_label)) },
        singleLine = true,
        isError = invalid,
        // Always there, as the rule; it turns into the error colour when broken.
        supportingText = { Text(text = stringResource(R.string.contacts_add_name_error)) },
    )
    Button(onClick = { invalid = !onSave(name) }, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.contacts_rename_save))
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.contacts_cancel))
    }
}

// The card on Home ------------------------------------------------------------------------

/**
 * "Add emergency contacts", for the Home sheet. Shown only while the phone knows of no
 * contact and "Not now" was not tapped in the last few days. It is a card in the sheet's
 * content, below the header row that holds the SOS control, so it can never cover it.
 */
@Composable
fun ContactsHomeCard(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactsCardViewModel = hiltViewModel(),
) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    if (visible) ContactsCard(onAdd = onAdd, onNotNow = viewModel::onNotNow, modifier = modifier)
}

@Composable
fun ContactsCard(onAdd: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SafeRouteTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.contacts_card_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = stringResource(R.string.contacts_card_body), style = MaterialTheme.typography.bodyMedium)
            // One under the other: side by side they would not fit at large font sizes.
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.contacts_card_action))
            }
            TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.contacts_card_not_now))
            }
        }
    }
}

@SafeRoutePreviews
@Composable
private fun ContactsScreenPreview() {
    SafeRouteTheme {
        ContactsScreen(
            state = ContactsUiState(contacts = emptyList()),
            onBack = {},
            onAdd = {},
            onOpenContact = {},
            onRefresh = {},
            onStopConfirmed = {},
        )
    }
}
