// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.feature.diagnostics

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.diagnostics.DiagnosticsBuild
import com.saferoute.app.core.diagnostics.DiagnosticsFactsSource
import com.saferoute.app.core.diagnostics.DiagnosticsLog
import com.saferoute.app.core.diagnostics.ProblemReportInput
import com.saferoute.app.core.diagnostics.problemReportText
import com.saferoute.app.feature.emergency.SosReadinessReader
import com.saferoute.app.feature.onboarding.OnboardingLayout
import com.saferoute.app.feature.onboarding.OnboardingText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val ProblemReportTextTag = "problem_report_text"
const val ProblemReportNoteTag = "problem_report_note"

/** Where "Send by email" is addressed; empty when the build has no address. */
data class SupportAddress(val email: String)

/**
 * Builds the report when the screen comes to the front (ADR 0028). It reads the phone and
 * the in-memory list of categories; it stores nothing and sends nothing.
 */
@HiltViewModel
class ProblemReportViewModel @Inject constructor(
    private val facts: DiagnosticsFactsSource,
    private val readiness: SosReadinessReader,
    private val log: DiagnosticsLog,
    private val build: DiagnosticsBuild,
    private val clock: Clock,
    support: SupportAddress,
) : ViewModel() {
    val supportEmail: String = support.email

    private val _report = MutableStateFlow<ProblemReportInput?>(null)
    val report: StateFlow<ProblemReportInput?> = _report.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val reading = readiness.read().readiness
            _report.value = ProblemReportInput(
                appVersion = build.versionName,
                buildType = build.buildType,
                make = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                androidVersion = "${Build.VERSION.RELEASE.orEmpty()} (API ${build.androidApi})",
                facts = facts.read().mapNotNull { (key, value) -> key.clean(value)?.let { key to it } }.toMap(),
                // Status words of the checklist; it holds no count of contacts and no time.
                readiness = reading.items.map { (item, status) ->
                    item.name.lowercase(Locale.ROOT) to status.name.lowercase(Locale.ROOT)
                },
                toFix = reading.toFix,
                events = log.entries(),
                now = clock.instant(),
            )
        }
    }
}

/** The e-mail app, addressed and filled in. The person still has to press send there. */
fun emailReportIntent(address: String, subject: String, text: String): Intent =
    Intent(Intent.ACTION_SENDTO, "mailto:$address".toUri())
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)

/** Android's share sheet with the text; the person picks the app. */
fun shareReportIntent(text: String): Intent = Intent.createChooser(
    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
    null,
)

/** False when no app can take it (for example no e-mail app on the phone). */
private fun Context.open(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

@Composable
fun ProblemReportRoute(onBack: () -> Unit, viewModel: ProblemReportViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val subject = stringResource(R.string.report_subject)
    var noEmailApp by remember { mutableStateOf(false) }
    // Read again each time the screen comes to the front: a permission may have changed.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    ProblemReportScreen(
        report = report,
        emailAvailable = viewModel.supportEmail.isNotEmpty(),
        noEmailApp = noEmailApp,
        onBack = onBack,
        onEmail = { text -> noEmailApp = !context.open(emailReportIntent(viewModel.supportEmail, subject, text)) },
        onShare = { text -> context.open(shareReportIntent(text)) },
    )
}

/**
 * Shows the report as the plain text that would be shared, then offers to share it. The
 * app sends nothing: both buttons hand the text to another app, where the person sends it.
 */
@Composable
fun ProblemReportScreen(
    report: ProblemReportInput?,
    emailAvailable: Boolean,
    noEmailApp: Boolean,
    onBack: () -> Unit,
    onEmail: (String) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // What the person typed stays in memory: not saved across a restart, never stored.
    var note by remember { mutableStateOf("") }
    val text = report?.let { problemReportText(it, note) }
    OnboardingLayout(
        title = stringResource(R.string.report_title),
        modifier = modifier,
        actions = {
            if (emailAvailable) {
                Button(onClick = { text?.let(onEmail) }, enabled = text != null, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.report_email))
                }
            }
            OutlinedButton(
                onClick = { text?.let(onShare) },
                enabled = text != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.report_share))
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.navigate_back))
            }
        },
    ) {
        OnboardingText(text = stringResource(R.string.report_intro))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    text = text.orEmpty(),
                    modifier = Modifier.padding(SafeRouteTheme.spacing.md).testTag(ProblemReportTextTag),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth().testTag(ProblemReportNoteTag),
            label = { Text(text = stringResource(R.string.report_note_label)) },
            supportingText = { Text(text = stringResource(R.string.report_note_hint)) },
            minLines = 2,
        )
        if (noEmailApp) {
            Text(
                text = stringResource(R.string.report_no_email_app),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
