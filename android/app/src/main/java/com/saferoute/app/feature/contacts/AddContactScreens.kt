// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.contacts

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

/*
 * Adding a contact and inviting them. Three steps on two screens:
 *
 * 1. the `sos_alerts` notice, before the first contact only;
 * 2. the form: pick one number from the phone's contacts, or type one;
 * 3. the invite: the phone's SMS app opens with a message, and the USER sends it.
 */

@Composable
fun AddContactRoute(
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddContactViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The system's contact picker. It returns a link to the ONE entry the user chose, with a
    // right to read it that lasts for this moment only. No permission is requested.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            readPickedContact(context.contentResolver, uri)
                ?.let(viewModel::onPicked)
                ?: viewModel.onPickProblem(PickProblem.Unreadable)
        }
        // Anything else: the user closed the picker. Nothing happens.
    }

    (state as? AddContactUiState.Saved)?.let { saved ->
        LaunchedEffect(saved.contactId) { onSaved(saved.contactId) }
    }

    AddContactScreen(
        state = state,
        onBack = onBack,
        onRetryCheck = viewModel::check,
        onAgree = viewModel::onAgree,
        onPickFromPhone = {
            try {
                picker.launch(pickPhoneNumberIntent())
            } catch (e: ActivityNotFoundException) {
                viewModel.onPickProblem(PickProblem.NoContactsApp)
            }
        },
        onNameChange = viewModel::onNameChange,
        onPhoneChange = viewModel::onPhoneChange,
        onSave = viewModel::onSave,
        modifier = modifier,
    )
}

@Composable
fun AddContactScreen(
    state: AddContactUiState,
    onBack: () -> Unit,
    onRetryCheck: () -> Unit,
    onAgree: () -> Unit,
    onPickFromPhone: () -> Unit,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(
        if (state is AddContactUiState.Notice) R.string.contacts_notice_title else R.string.contacts_add_title,
    )
    ContactsScaffold(title = title, onBack = onBack, modifier = modifier) {
        when (state) {
            AddContactUiState.Checking, is AddContactUiState.Saved -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(text = stringResource(R.string.contacts_checking))
            }

            is AddContactUiState.CheckFailed -> {
                ContactsMessage(stringResource(state.error.message()))
                Button(onClick = onRetryCheck, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.contacts_try_again))
                }
            }

            is AddContactUiState.Notice -> AlertsNotice(state = state, onAgree = onAgree, onNotNow = onBack)

            is AddContactUiState.Form -> AddContactForm(
                state = state,
                onPickFromPhone = onPickFromPhone,
                onNameChange = onNameChange,
                onPhoneChange = onPhoneChange,
                onSave = onSave,
            )
        }
    }
}

/** The paragraphs of the `sos_alerts` notice, in order (`ContactsNoticeDocumentTest`). */
internal val ALERTS_NOTICE_PARAGRAPHS = listOf(
    R.string.contacts_notice_p1,
    R.string.contacts_notice_p2,
    R.string.contacts_notice_p3,
    R.string.contacts_notice_p4,
    R.string.contacts_notice_p5,
    R.string.contacts_notice_p6,
    R.string.contacts_notice_p7,
)

/**
 * The plain-language notice for the `sos_alerts` consent (ADR 0010): shown just in time, with
 * nothing pre-selected. "I agree" records the consent on the server; "Not now" goes back, and
 * nothing was stored or sent.
 */
@Composable
private fun AlertsNotice(state: AddContactUiState.Notice, onAgree: () -> Unit, onNotNow: () -> Unit) {
    Text(
        text = stringResource(R.string.notice_draft_marker),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.sm)) {
        ALERTS_NOTICE_PARAGRAPHS.forEach { Text(text = stringResource(it)) }
    }
    Text(
        text = stringResource(R.string.notice_version, SOS_ALERTS_NOTICE_VERSION),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.sending) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    state.error?.let { ContactsMessage(stringResource(it.message())) }
    Button(onClick = onAgree, enabled = !state.sending, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.contacts_notice_agree))
    }
    OutlinedButton(onClick = onNotNow, enabled = !state.sending, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.contacts_notice_not_now))
    }
}

@Composable
private fun AddContactForm(
    state: AddContactUiState.Form,
    onPickFromPhone: () -> Unit,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    OutlinedButton(onClick = onPickFromPhone, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.contacts_add_from_phone))
    }
    Text(
        text = stringResource(R.string.contacts_add_from_phone_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.pickProblem?.let {
        ContactsMessage(
            stringResource(
                when (it) {
                    PickProblem.Unreadable -> R.string.contacts_pick_failed
                    PickProblem.NoContactsApp -> R.string.contacts_pick_unavailable
                },
            ),
        )
    }

    Text(
        text = stringResource(R.string.contacts_add_type_heading),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
    OutlinedTextField(
        value = state.name,
        onValueChange = onNameChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.saving,
        label = { Text(text = stringResource(R.string.contacts_add_name_label)) },
        singleLine = true,
        isError = state.nameInvalid,
        supportingText = {
            if (state.nameInvalid) Text(text = stringResource(R.string.contacts_add_name_error))
        },
    )
    OutlinedTextField(
        value = state.phone,
        onValueChange = onPhoneChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.saving,
        label = { Text(text = stringResource(R.string.contacts_add_phone_label)) },
        singleLine = true,
        isError = state.phoneInvalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        supportingText = {
            Text(
                text = stringResource(
                    if (state.phoneInvalid) R.string.contacts_add_phone_error else R.string.contacts_add_phone_help,
                ),
            )
        },
    )
    if (state.saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    state.error?.let { ContactsMessage(stringResource(it.message())) }
    Button(onClick = onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(if (state.saving) R.string.contacts_add_saving else R.string.contacts_add_save))
    }
}

// The invite ------------------------------------------------------------------------------

@Composable
fun InviteRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InviteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The invite text up to the place of the link. The link is the last thing in the text in
    // every language (ContactsNoticeDocumentTest), so it is simply added at the end.
    val messageStart = stringResource(R.string.contacts_invite_message, "")

    // The one moment the link is used: it is put into the message for the SMS app, and the
    // ViewModel drops it as soon as this reports back.
    state.smsToOpen?.let { sms ->
        LaunchedEffect(sms) {
            viewModel.onSmsOpened(openInviteSms(context, sms.phoneE164, messageStart + sms.link.url))
        }
    }
    val finished = state.step == InviteStep.Done || state.missing
    LaunchedEffect(finished) { if (finished) onDone() }

    InviteScreen(
        state = state,
        onWrite = viewModel::onWrite,
        onSent = viewModel::onSent,
        onLater = viewModel::onLater,
        modifier = modifier,
    )
}

/**
 * The invite step. Before: what will happen. After the SMS app was opened: "Did you send the
 * invite?". "Yes" records that the user SAID so; the app cannot see the SMS.
 */
@Composable
fun InviteScreen(
    state: InviteUiState,
    onWrite: () -> Unit,
    onSent: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ContactsScaffold(title = stringResource(R.string.contacts_invite_title), onBack = onLater, modifier = modifier) {
        when (state.step) {
            InviteStep.Intro, InviteStep.Preparing -> {
                val preparing = state.step == InviteStep.Preparing
                state.contactName?.let { Text(text = stringResource(R.string.contacts_invite_body, it)) }
                if (preparing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    ContactsMessage(stringResource(R.string.contacts_invite_preparing), isError = false)
                }
                state.error?.let { ContactsMessage(stringResource(it.message())) }
                Button(
                    onClick = onWrite,
                    enabled = !preparing && state.contactName != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.contacts_invite_open))
                }
                TextButton(onClick = onLater, enabled = !preparing, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.contacts_invite_later))
                }
            }

            InviteStep.AskSent -> {
                Text(
                    text = stringResource(R.string.contacts_invite_sent_question),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.confirming) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.error?.let { ContactsMessage(stringResource(it.message())) }
                Button(onClick = onSent, enabled = !state.confirming, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.contacts_invite_sent_yes))
                }
                OutlinedButton(onClick = onLater, enabled = !state.confirming, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.contacts_invite_later))
                }
            }

            InviteStep.NoSmsApp -> {
                ContactsMessage(stringResource(R.string.contacts_invite_no_sms_app))
                Button(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.contacts_done))
                }
            }

            InviteStep.Done -> Unit
        }
    }
}
