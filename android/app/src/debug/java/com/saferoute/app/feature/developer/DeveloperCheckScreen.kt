// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.developer

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

/*
 * Debug builds only (src/debug). A release build has neither this screen nor its strings.
 */

/** The row Settings shows in debug builds. */
@Composable
fun DeveloperSettingsRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        ListItem(
            headlineContent = { Text(text = stringResource(R.string.developer_settings_title)) },
            supportingContent = { Text(text = stringResource(R.string.developer_settings_supporting)) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .clickable(role = Role.Button, onClick = onClick),
        )
        HorizontalDivider()
    }
}

/** Connects [DeveloperCheckScreen] to its ViewModel. */
@Composable
fun DeveloperCheckRoute(onBack: () -> Unit, viewModel: DeveloperCheckViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DeveloperCheckScreen(
        state = state,
        onBack = onBack,
        onCheckAgain = viewModel::checkNow,
        onWhoAmI = viewModel::whoAmI,
    )
}

/**
 * Shows whether the app can reach the backend: is a server configured, does `/health` answer
 * (and with which version), does `/health/ready` answer, and the last request id, which can be
 * looked up in the backend's logs. It never shows the server address or a token.
 *
 * Since P009c it also shows the session state and "Who am I": the role and language the server
 * has for the signed-in account. Still no token, user id or phone number.
 */
@Composable
fun DeveloperCheckScreen(
    state: DeveloperCheckUiState,
    onBack: () -> Unit,
    onCheckAgain: () -> Unit,
    modifier: Modifier = Modifier,
    onWhoAmI: () -> Unit = {},
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
                        text = stringResource(R.string.developer_check_title),
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
            Line(
                title = stringResource(R.string.developer_check_server),
                value = stringResource(
                    if (state.serverConfigured) {
                        R.string.developer_check_configured
                    } else {
                        R.string.developer_check_not_configured
                    },
                ),
            )
            Line(
                title = stringResource(R.string.developer_check_health),
                value = probeText(state.health, okText = { version ->
                    stringResource(R.string.developer_check_health_ok, version.orEmpty())
                }),
            )
            Line(
                title = stringResource(R.string.developer_check_readiness),
                value = probeText(state.readiness, okText = {
                    stringResource(R.string.developer_check_ready)
                }),
            )
            Line(
                title = stringResource(R.string.developer_check_request_id),
                value = state.lastRequestId ?: stringResource(R.string.developer_check_request_id_none),
            )
            Button(
                onClick = onCheckAgain,
                enabled = state.canCheck,
                modifier = Modifier.padding(SafeRouteTheme.spacing.md),
            ) {
                Text(text = stringResource(R.string.developer_check_again))
            }
            Line(
                title = stringResource(R.string.developer_check_session),
                value = state.sessionState,
            )
            Line(
                title = stringResource(R.string.developer_check_location),
                value = state.location,
            )
            Line(
                title = stringResource(R.string.developer_check_who_am_i),
                value = when (val who = state.whoAmI) {
                    WhoAmIState.NotAsked -> stringResource(R.string.developer_check_not_checked)
                    WhoAmIState.Asking -> stringResource(R.string.developer_check_checking)
                    is WhoAmIState.Known ->
                        stringResource(R.string.developer_check_who_am_i_known, who.role, who.locale)
                    WhoAmIState.Unavailable -> stringResource(R.string.developer_check_who_am_i_unavailable)
                },
            )
            Button(
                onClick = onWhoAmI,
                enabled = state.whoAmI != WhoAmIState.Asking,
                modifier = Modifier.padding(SafeRouteTheme.spacing.md),
            ) {
                Text(text = stringResource(R.string.developer_check_who_am_i_ask))
            }
            Text(
                text = stringResource(R.string.developer_check_footer),
                modifier = Modifier.padding(horizontal = SafeRouteTheme.spacing.md),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Line(title: String, value: String) {
    ListItem(
        headlineContent = { Text(text = title) },
        supportingContent = { Text(text = value) },
        modifier = Modifier.semantics(mergeDescendants = true) {},
    )
    HorizontalDivider()
}

@Composable
private fun probeText(probe: ProbeState, okText: @Composable (String?) -> String): String = when (probe) {
    ProbeState.NotChecked -> stringResource(R.string.developer_check_not_checked)
    ProbeState.Checking -> stringResource(R.string.developer_check_checking)
    is ProbeState.Ok -> okText(probe.version)
    is ProbeState.Failed -> failureText(probe.reason)
}

@Composable
private fun failureText(reason: FailureReason): String = when (reason) {
    FailureReason.NoConnection -> stringResource(R.string.developer_check_no_connection)
    FailureReason.Unavailable -> stringResource(R.string.developer_check_unavailable)
    FailureReason.SignInNeeded -> stringResource(R.string.developer_check_sign_in)
    is FailureReason.ServerError ->
        stringResource(R.string.developer_check_server_error, reason.status, reason.code)
    is FailureReason.Unexpected -> reason.status
        ?.let { stringResource(R.string.developer_check_unexpected, it) }
        ?: stringResource(R.string.developer_check_unexpected_no_status)
}

@SafeRoutePreviews
@Composable
private fun DeveloperCheckScreenPreview() {
    SafeRouteTheme {
        DeveloperCheckScreen(
            state = DeveloperCheckUiState(
                serverConfigured = true,
                health = ProbeState.Ok("0.1.0"),
                readiness = ProbeState.Failed(FailureReason.Unavailable),
                lastRequestId = "3f2c1b9e-7a44-4c1e-9d2f-0b6a5e8c1d23",
            ),
            onBack = {},
            onCheckAgain = {},
        )
    }
}
