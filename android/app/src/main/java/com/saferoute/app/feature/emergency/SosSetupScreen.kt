// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.emergency.AlertLanguageChoice
import com.saferoute.app.core.emergency.ContactsFreshness
import com.saferoute.app.core.emergency.MAX_NAME_LENGTH
import com.saferoute.app.core.emergency.ReadinessItem
import com.saferoute.app.core.emergency.ReadinessLocation
import com.saferoute.app.core.emergency.ReadinessStatus
import com.saferoute.app.feature.contacts.ContactsMessage
import com.saferoute.app.feature.contacts.ContactsScaffold

/** Test tags: the summary line, and one per row (`SosSetupRowTag + item.name`). */
const val SosSetupSummaryTag = "setup_summary"
const val SosSetupRowTag = "setup_row_"

/** What the buttons of the screen do. Each opens something; none changes a setting itself. */
class SosSetupActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    /** "Read how SOS alerts work": the notice. */
    val onReadNotice: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onOpenNotificationSettings: () -> Unit,
    val onOpenLocationSettings: () -> Unit,
    val onPractice: () -> Unit,
    /** The name for alerts was saved; null removes it. */
    val onNameSave: (String?) -> Unit = {},
    val onLanguageChange: (AlertLanguageChoice) -> Unit = {},
    val onCountdownSoundChange: (Boolean) -> Unit = {},
)

/**
 * "SOS setup": Settings → Emergency contacts → SOS setup. Shows, BEFORE an emergency, what
 * an SOS from this phone would and would not do.
 *
 * The state is read again every time the screen comes to the front ([LifecycleResumeEffect]
 * runs at each resume, also on the way back from the phone's settings).
 */
@Composable
fun SosSetupRoute(
    onBack: () -> Unit,
    onReadNotice: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SosSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    SosSetupScreen(
        state = state,
        actions = SosSetupActions(
            onBack = onBack,
            onRefresh = viewModel::onRefresh,
            onReadNotice = onReadNotice,
            onOpenAppSettings = { context.openSettings(appDetailsSettingsIntent(context)) },
            onOpenNotificationSettings = { context.openSettings(notificationSettingsIntent(context)) },
            onOpenLocationSettings = { context.openSettings(locationSettingsIntent()) },
            onPractice = { context.startActivity(EmergencyShortcut.modeIntent(context, SosOpenMode.PRACTICE)) },
            onNameSave = viewModel::onNameSave,
            onLanguageChange = viewModel::onLanguageChange,
            onCountdownSoundChange = viewModel::onCountdownSoundChange,
        ),
        modifier = modifier,
        // The one place that may ask for the SMS permission, with its own explanation first.
        smsCard = { SmsAlertModeCard() },
    )
}

/**
 * The screen as a picture of a state. Every row says its status in a WORD (not only a
 * colour) and, where the user can change something, has a button that leads there.
 *
 * It never says "safe": the summary is "Ready" or "Not ready: N things to fix", and under
 * it stands what "Ready" does not promise.
 */
@Composable
fun SosSetupScreen(
    state: SosSetupUiState?,
    actions: SosSetupActions,
    modifier: Modifier = Modifier,
    smsCard: @Composable () -> Unit = {},
) {
    ContactsScaffold(title = stringResource(R.string.sos_setup_title), onBack = actions.onBack, modifier = modifier) {
        if (state == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            return@ContactsScaffold
        }
        val readiness = state.readiness
        Summary(toFix = readiness.toFix)

        // 1. The consent: without it no SOS can be started at all.
        CheckRow(
            item = ReadinessItem.ALERTS,
            status = readiness.status(ReadinessItem.ALERTS),
            title = R.string.sos_setup_alerts_title,
        ) {
            if (readiness.status(ReadinessItem.ALERTS) == ReadinessStatus.OK) {
                Text(stringResource(R.string.sos_setup_alerts_ok))
            } else {
                Text(stringResource(R.string.sos_setup_alerts_fix))
                Fix(R.string.contacts_alerts_read_notice, actions.onReadNotice)
            }
        }

        // 2. Who would be texted, and how old the copy on this phone is.
        CheckRow(
            item = ReadinessItem.CONTACTS,
            status = readiness.status(ReadinessItem.CONTACTS),
            title = R.string.sos_setup_contacts_title,
        ) {
            Text(
                text = when {
                    readiness.status(ReadinessItem.CONTACTS) == ReadinessStatus.CANNOT_KNOW ->
                        stringResource(R.string.sos_setup_contacts_unknown)
                    state.activeContacts == 0 -> stringResource(R.string.sos_setup_contacts_none)
                    state.activeContacts == 1 -> stringResource(R.string.sos_setup_contacts_one)
                    else -> stringResource(R.string.sos_setup_contacts_many, state.activeContacts)
                },
            )
            Text(text = readiness.freshness.text(), fontWeight = FontWeight.SemiBold)
            Text(text = stringResource(R.string.sos_setup_contacts_explain), style = MaterialTheme.typography.bodySmall)
            if (state.refreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (state.refreshFailed) ContactsMessage(stringResource(R.string.sos_setup_refresh_failed))
            OutlinedButton(onClick = actions.onRefresh, enabled = !state.refreshing) {
                Text(stringResource(R.string.sos_setup_refresh))
            }
        }

        // 3. How the SMS leaves the phone.
        CheckRow(
            item = ReadinessItem.SMS,
            status = readiness.status(ReadinessItem.SMS),
            title = R.string.sos_setup_sms_title,
        ) {
            when (readiness.status(ReadinessItem.SMS)) {
                ReadinessStatus.OK -> Text(stringResource(R.string.sos_setup_sms_automatic))
                ReadinessStatus.INFO -> Text(stringResource(R.string.sos_setup_sms_app_only))
                else -> {
                    Text(stringResource(R.string.sos_setup_sms_app))
                    smsCard()
                }
            }
        }

        // 4. Location.
        CheckRow(
            item = ReadinessItem.LOCATION,
            status = readiness.status(ReadinessItem.LOCATION),
            title = R.string.sos_setup_location_title,
        ) {
            Text(
                stringResource(
                    when (state.location) {
                        ReadinessLocation.PRECISE -> R.string.sos_setup_location_precise
                        ReadinessLocation.APPROXIMATE -> R.string.sos_setup_location_approximate
                        ReadinessLocation.NOT_ALLOWED -> R.string.sos_setup_location_not_allowed
                        ReadinessLocation.SWITCHED_OFF -> R.string.sos_setup_location_off
                    },
                ),
            )
            when (state.location) {
                ReadinessLocation.PRECISE -> Unit
                ReadinessLocation.SWITCHED_OFF ->
                    Fix(R.string.sos_setup_open_location_settings, actions.onOpenLocationSettings)
                ReadinessLocation.APPROXIMATE, ReadinessLocation.NOT_ALLOWED -> {
                    Text(stringResource(R.string.sos_setup_location_how), style = MaterialTheme.typography.bodySmall)
                    Fix(R.string.sos_setup_open_app_settings, actions.onOpenAppSettings)
                }
            }
        }

        // 5. Notifications.
        CheckRow(
            item = ReadinessItem.NOTIFICATIONS,
            status = readiness.status(ReadinessItem.NOTIFICATIONS),
            title = R.string.sos_setup_notifications_title,
        ) {
            if (readiness.status(ReadinessItem.NOTIFICATIONS) == ReadinessStatus.OK) {
                Text(stringResource(R.string.sos_setup_notifications_ok))
            } else {
                Text(stringResource(R.string.sos_setup_notifications_fix))
                Fix(R.string.sos_setup_open_notification_settings, actions.onOpenNotificationSettings)
            }
        }

        // 6. Battery: no app can see what a phone maker's battery manager does.
        CheckRow(
            item = ReadinessItem.BATTERY,
            status = readiness.status(ReadinessItem.BATTERY),
            title = R.string.sos_setup_battery_title,
        ) {
            Text(stringResource(R.string.sos_setup_battery_explain))
            for (maker in MAKER_HINTS) Text(stringResource(maker), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.sos_setup_battery_note), style = MaterialTheme.typography.bodySmall)
            Fix(R.string.sos_setup_open_app_settings, actions.onOpenAppSettings)
        }

        // 7. What the user chose for their alerts. None of it is needed for an SOS to work.
        AlertSettings(state = state, actions = actions)

        // 8. Practice.
        Card(title = R.string.sos_arm_practice) {
            Text(stringResource(R.string.sos_setup_practice_explain))
            OutlinedButton(onClick = actions.onPractice) { Text(stringResource(R.string.sos_arm_practice)) }
        }
    }
}

/** Test tags of the alert settings. */
const val SosSetupNameFieldTag = "setup_name_field"
const val SosSetupSoundSwitchTag = "setup_sound_switch"

/**
 * The name in the alert, its language and the countdown sound (P014c2b). Optional, all
 * three: they change what an alert says or how the countdown is felt, never whether an SOS
 * can start, so they are not counted in the sum above.
 */
@Composable
private fun AlertSettings(state: SosSetupUiState, actions: SosSetupActions) {
    // In memory only, like everything a person types here: not saved with the screen.
    var editingName by remember { mutableStateOf(false) }
    val settings = state.settings

    Card(title = R.string.sos_setup_settings_title) {
        Text(
            text = stringResource(R.string.sos_setup_name_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = settings.name?.let { stringResource(R.string.sos_setup_name_set, it) }
                ?: stringResource(R.string.sos_setup_name_none),
        )
        Text(stringResource(R.string.sos_setup_name_explain), style = MaterialTheme.typography.bodySmall)
        if (editingName) {
            NameEditor(
                current = settings.name,
                onSave = {
                    editingName = false
                    actions.onNameSave(it)
                },
                onCancel = { editingName = false },
            )
        } else {
            OutlinedButton(onClick = { editingName = true }) {
                Text(
                    stringResource(
                        if (settings.name == null) R.string.sos_setup_name_edit else R.string.sos_setup_name_change,
                    ),
                )
            }
        }

        Text(
            text = stringResource(R.string.sos_setup_language_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(stringResource(R.string.sos_setup_language_explain), style = MaterialTheme.typography.bodySmall)
        Column(modifier = Modifier.selectableGroup()) {
            for (choice in AlertLanguageChoice.entries) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = settings.language == choice,
                            role = Role.RadioButton,
                            onClick = { actions.onLanguageChange(choice) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The row is the button; the circle only shows the state.
                    RadioButton(selected = settings.language == choice, onClick = null)
                    Text(
                        text = when (choice) {
                            AlertLanguageChoice.APP -> stringResource(
                                if (state.bengali) R.string.sos_setup_language_app_bn else R.string.sos_setup_language_app_en,
                            )
                            AlertLanguageChoice.EN -> stringResource(R.string.sos_setup_language_en)
                            AlertLanguageChoice.BN -> stringResource(R.string.sos_setup_language_bn)
                        },
                        modifier = Modifier.padding(start = SafeRouteTheme.spacing.xs),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(SosSetupSoundSwitchTag)
                .toggleable(
                    value = settings.countdownSound,
                    role = Role.Switch,
                    onValueChange = actions.onCountdownSoundChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.sos_setup_sound_title),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
            )
            Switch(checked = settings.countdownSound, onCheckedChange = null)
        }
        Text(
            stringResource(if (settings.countdownSound) R.string.sos_setup_sound_on else R.string.sos_setup_sound_off),
        )
        Text(stringResource(R.string.sos_setup_sound_warning), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The field for the name, inside the card (the same way a contact is renamed). What is
 * typed stays in memory until "Save": it is not written to the saved state of the screen.
 */
@Composable
private fun NameEditor(current: String?, onSave: (String?) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(current.orEmpty()) }
    OutlinedTextField(
        value = text,
        // No longer than a message can carry.
        onValueChange = { text = it.take(MAX_NAME_LENGTH) },
        modifier = Modifier.fillMaxWidth().testTag(SosSetupNameFieldTag),
        label = { Text(stringResource(R.string.sos_setup_name_field, MAX_NAME_LENGTH)) },
        singleLine = true,
    )
    Button(onClick = { onSave(text) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sos_setup_name_save))
    }
    if (current != null) {
        OutlinedButton(onClick = { onSave(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sos_setup_name_remove))
        }
    }
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.emergency_dialog_cancel))
    }
}

/** The battery hints, one per group of phone makers. Drafts: not checked on those phones. */
private val MAKER_HINTS = listOf(
    R.string.sos_setup_battery_samsung,
    R.string.sos_setup_battery_xiaomi,
    R.string.sos_setup_battery_vivo,
    R.string.sos_setup_battery_oppo,
)

@Composable
private fun ContactsFreshness.text(): String = when (this) {
    ContactsFreshness.Never -> stringResource(R.string.sos_setup_updated_never)
    ContactsFreshness.Today -> stringResource(R.string.sos_setup_updated_today)
    ContactsFreshness.Yesterday -> stringResource(R.string.sos_setup_updated_yesterday)
    is ContactsFreshness.DaysAgo -> stringResource(R.string.sos_setup_updated_days, days)
}

@Composable
private fun ReadinessStatus.word(): String = stringResource(
    when (this) {
        ReadinessStatus.OK -> R.string.sos_setup_status_ok
        ReadinessStatus.NEEDS_ACTION -> R.string.sos_setup_status_fix
        ReadinessStatus.CANNOT_KNOW -> R.string.sos_setup_status_unknown
        ReadinessStatus.INFO -> R.string.sos_setup_status_info
    },
)

/** The sum, read out by TalkBack when it changes, and what "Ready" does not promise. */
@Composable
private fun Summary(toFix: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xs)) {
        Text(
            text = when (toFix) {
                0 -> stringResource(R.string.sos_setup_ready)
                1 -> stringResource(R.string.sos_setup_not_ready_one)
                else -> stringResource(R.string.sos_setup_not_ready_many, toFix)
            },
            modifier = Modifier
                .testTag(SosSetupSummaryTag)
                .semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(text = stringResource(R.string.sos_setup_caveat), style = MaterialTheme.typography.bodyMedium)
    }
}

/** One check: its name, its status in a word, and what [content] says about it. */
@Composable
private fun CheckRow(
    item: ReadinessItem,
    status: ReadinessStatus,
    title: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(title = title, status = status, modifier = Modifier.testTag(SosSetupRowTag + item.name), content = content)
}

@Composable
private fun Card(
    title: Int,
    modifier: Modifier = Modifier,
    status: ReadinessStatus? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(SafeRouteTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xs),
        ) {
            Text(
                text = stringResource(title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            if (status != null) {
                Text(
                    text = status.word(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (status == ReadinessStatus.NEEDS_ACTION) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            content()
        }
    }
}

/** A button that leads to where the row can be fixed. At least 48 dp high (Material default). */
@Composable
private fun Fix(label: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(stringResource(label)) }
}
