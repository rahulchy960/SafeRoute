// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.feature.developer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.diagnostics.DiagnosticsBuild
import com.saferoute.app.core.diagnostics.DiagnosticsPreferences
import com.saferoute.app.core.diagnostics.DiagnosticsReporter
import com.saferoute.app.core.diagnostics.ErrorCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Thrown by "Test crash". Its message is replaced like any other before it is reported. */
class DeveloperTestCrash : RuntimeException("Test crash from the Developer screen")

/** Why crash reports are or are not being collected right now, for the Developer screen. */
enum class CollectionState { ON, CHOICE_OFF, BUILD_OFF }

/**
 * The two test buttons of crash reporting (ADR 0028). Debug builds only. A report arrives
 * in the Firebase console only if "Send crash reports" is on AND this debug build was
 * built with `saferoute.debugCrashReports=true`; the line above the buttons says which.
 */
@HiltViewModel
class DiagnosticsDeveloperViewModel @Inject constructor(
    preferences: DiagnosticsPreferences,
    private val reporter: DiagnosticsReporter,
    build: DiagnosticsBuild,
) : ViewModel() {
    val collection: StateFlow<CollectionState> = preferences.settings
        .map {
            when {
                !build.reportsAllowed -> CollectionState.BUILD_OFF
                !it.crashReports -> CollectionState.CHOICE_OFF
                else -> CollectionState.ON
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CollectionState.CHOICE_OFF)

    /** A caught error, sent at the next start of the app. The app keeps running. */
    fun testNonFatal() {
        reporter.report(DeveloperTestCrash(), ErrorCategory.TEST)
    }

    /** Closes the app on purpose: nobody catches this, exactly like a real crash. */
    fun testCrash(): Nothing = throw DeveloperTestCrash()
}

@Composable
fun DiagnosticsDeveloperSection(viewModel: DiagnosticsDeveloperViewModel = hiltViewModel()) {
    val collection by viewModel.collection.collectAsStateWithLifecycle()
    DiagnosticsDeveloperButtons(
        collection = collection,
        onTestNonFatal = viewModel::testNonFatal,
        onTestCrash = { viewModel.testCrash() },
    )
}

@Composable
fun DiagnosticsDeveloperButtons(
    collection: CollectionState,
    onTestNonFatal: () -> Unit,
    onTestCrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ListItem(
            headlineContent = { Text(text = stringResource(R.string.developer_crash_reports)) },
            supportingContent = {
                Text(
                    text = stringResource(
                        when (collection) {
                            CollectionState.ON -> R.string.developer_crash_reports_on
                            CollectionState.CHOICE_OFF -> R.string.developer_crash_reports_choice_off
                            CollectionState.BUILD_OFF -> R.string.developer_crash_reports_build_off
                        },
                    ),
                )
            },
        )
        Button(onClick = onTestNonFatal, modifier = Modifier.padding(horizontal = SafeRouteTheme.spacing.md)) {
            Text(text = stringResource(R.string.developer_test_non_fatal))
        }
        Button(onClick = onTestCrash, modifier = Modifier.padding(SafeRouteTheme.spacing.md)) {
            Text(text = stringResource(R.string.developer_test_crash))
        }
    }
}
