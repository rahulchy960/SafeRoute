// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.diagnostics.DiagnosticsBuild
import com.saferoute.app.core.diagnostics.DiagnosticsPreferences
import com.saferoute.app.core.diagnostics.DiagnosticsSettings
import com.saferoute.app.feature.onboarding.OnboardingLayout
import com.saferoute.app.feature.onboarding.OnboardingText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val CrashReportsHomeCardTag = "crash_reports_home_card"
const val CrashReportsSwitchTag = "crash_reports_switch"

/**
 * The choice "Send crash reports" for Settings and for the Home card (ADR 0028). The
 * choice is two flags on this phone; changing it here is all it takes, because
 * `CrashReporting` listens to the same flags and switches collection on or off.
 */
@HiltViewModel
class CrashReportsViewModel @Inject constructor(
    private val preferences: DiagnosticsPreferences,
    build: DiagnosticsBuild,
) : ViewModel() {
    /** Null until the stored flags have been read, so nothing flashes on screen. */
    val settings: StateFlow<DiagnosticsSettings?> = preferences.settings
        .map<_, DiagnosticsSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** False in a debug build without its flag: the switch is shown but sends nothing. */
    val buildSendsReports: Boolean = build.reportsAllowed

    fun choose(enabled: Boolean) {
        viewModelScope.launch { preferences.setCrashReports(enabled) }
    }
}

/** What is sent, what is not, where it goes and how to stop it: the same text everywhere. */
@Composable
fun CrashReportsNotice(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.sm)) {
        for ((title, body) in listOf(
            R.string.diagnostics_sent_title to R.string.diagnostics_sent_body,
            R.string.diagnostics_not_sent_title to R.string.diagnostics_not_sent_body,
            R.string.diagnostics_where_title to R.string.diagnostics_where_body,
            R.string.diagnostics_off_title to R.string.diagnostics_off_body,
        )) {
            Text(
                text = stringResource(title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = stringResource(body), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * The onboarding step after the account notice and before sign-in: an optional choice,
 * separate from every other one, and not ticked. "Continue" works either way and records
 * what the box says. Nothing is sent from this screen.
 */
@Composable
fun CrashReportsChoiceScreen(onContinue: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    // A yes or no on the way to the button; kept across a rotation, never ticked by the app.
    var ticked by rememberSaveable { mutableStateOf(false) }
    OnboardingLayout(
        title = stringResource(R.string.diagnostics_title),
        modifier = modifier,
        actions = {
            Button(onClick = { onContinue(ticked) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.onboarding_continue))
            }
        },
    ) {
        OnboardingText(text = stringResource(R.string.diagnostics_intro))
        CrashReportsNotice()
        ListItem(
            headlineContent = { Text(text = stringResource(R.string.diagnostics_switch)) },
            modifier = Modifier
                .testTag(CrashReportsSwitchTag)
                .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { ticked = it }),
            // onCheckedChange = null: the row handles the tap; the box only shows the state.
            leadingContent = { Checkbox(checked = ticked, onCheckedChange = null) },
        )
    }
}

/** The section in Settings: the switch, and under it the same notice as in onboarding. */
@Composable
fun CrashReportsSettingsSection(modifier: Modifier = Modifier, viewModel: CrashReportsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    CrashReportsSettings(
        enabled = settings?.crashReports ?: false,
        buildSendsReports = viewModel.buildSendsReports,
        onToggle = viewModel::choose,
        modifier = modifier,
    )
}

@Composable
fun CrashReportsSettings(
    enabled: Boolean,
    buildSendsReports: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ListItem(
            headlineContent = { Text(text = stringResource(R.string.diagnostics_switch)) },
            modifier = Modifier
                .testTag(CrashReportsSwitchTag)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle),
            supportingContent = { Text(text = stringResource(R.string.diagnostics_intro)) },
            trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
        )
        if (!buildSendsReports) {
            Text(
                text = stringResource(R.string.diagnostics_debug_off),
                modifier = Modifier.padding(horizontal = SafeRouteTheme.spacing.md),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        CrashReportsNotice(
            modifier = Modifier.padding(horizontal = SafeRouteTheme.spacing.md, vertical = SafeRouteTheme.spacing.sm),
        )
    }
}

/**
 * For a person who finished onboarding before this choice existed: a card in the Home
 * sheet, once. It is a card, never a dialog, so it blocks nothing and never covers the SOS
 * control. Either button answers the question for good; Settings can change it later.
 */
@Composable
fun CrashReportsHomeCard(modifier: Modifier = Modifier, viewModel: CrashReportsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    if (settings?.asked != false) return
    Card(modifier = modifier.fillMaxWidth().testTag(CrashReportsHomeCardTag)) {
        Column(
            modifier = Modifier.padding(SafeRouteTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_home_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = stringResource(R.string.diagnostics_home_body), style = MaterialTheme.typography.bodyMedium)
            // Two buttons of the same weight: neither answer is the "main" one.
            TextButton(onClick = { viewModel.choose(true) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.diagnostics_switch))
            }
            TextButton(onClick = { viewModel.choose(false) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.diagnostics_home_no))
            }
        }
    }
}
