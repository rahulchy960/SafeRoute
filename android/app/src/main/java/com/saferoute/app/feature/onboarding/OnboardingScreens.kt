// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.session.BlockReason

/** First screen of a fresh install: what the app is, and what it is not. */
@Composable
fun WelcomeScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingLayout(
        title = stringResource(R.string.welcome_title),
        modifier = modifier,
        actions = {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.onboarding_continue))
            }
        },
    ) {
        OnboardingText(text = stringResource(R.string.welcome_body))
        Text(
            text = stringResource(R.string.not_emergency_service) + " " +
                stringResource(R.string.onboarding_emergency_note),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        OnboardingText(text = stringResource(R.string.welcome_safety_context))
    }
}

/**
 * The age gate (ADR 0010): a yes/no question, never a date of birth.
 *
 * "I am under 18" cannot be undone inside the app, so it first opens a dialog that says so.
 * Only "Yes, I am under 18" in that dialog calls [onUnder18Confirmed]; "Go back", the back
 * gesture and a tap outside all close the dialog and record nothing.
 *
 * `rememberSaveable` keeps the dialog open across a rotation.
 */
@Composable
fun AgeGateScreen(
    onAdult: () -> Unit,
    onUnder18Confirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    OnboardingLayout(
        title = stringResource(R.string.age_title),
        modifier = modifier,
        actions = {
            Button(onClick = onAdult, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.age_adult))
            }
            OutlinedButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.age_minor))
            }
        },
    ) {
        OnboardingText(text = stringResource(R.string.age_body))
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(text = stringResource(R.string.age_minor_dialog_title)) },
            text = { Text(text = stringResource(R.string.age_minor_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onUnder18Confirmed()
                    },
                ) {
                    Text(text = stringResource(R.string.age_minor_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(text = stringResource(R.string.age_minor_dialog_cancel))
                }
            },
        )
    }
}

/**
 * The end of the road: the app cannot be used. It has no button on purpose. For
 * [BlockReason.UNDER_18] it thanks the person, says that nothing left the phone, repeats the
 * 112 note and gives a contact for people who answered by mistake.
 */
@Composable
fun BlockedScreen(reason: BlockReason, modifier: Modifier = Modifier) {
    when (reason) {
        BlockReason.UNDER_18 -> OnboardingLayout(
            title = stringResource(R.string.blocked_minor_title),
            modifier = modifier,
        ) {
            OnboardingText(text = stringResource(R.string.blocked_minor_body))
            Text(
                text = stringResource(R.string.onboarding_emergency_note),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            OnboardingText(text = stringResource(R.string.blocked_minor_mistake))
        }

        BlockReason.ACCOUNT_DELETED -> OnboardingLayout(
            title = stringResource(R.string.blocked_deleted_title),
            modifier = modifier,
        ) {
            OnboardingText(text = stringResource(R.string.blocked_deleted_body))
            Text(
                text = stringResource(R.string.onboarding_emergency_note),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** A spinner with a sentence, while the session is checked or the account is created. */
@Composable
fun WorkingScreen(creatingAccount: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(SafeRouteTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(
            SafeRouteTheme.spacing.md,
            Alignment.CenterVertically,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(
                if (creatingAccount) R.string.onboarding_creating_account else R.string.onboarding_working,
            ),
            // TalkBack announces the change from "One moment" to "Setting up your account".
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The session check failed. [retryable] chooses the explanation (no connection or server
 * trouble, against anything else); "Try again" is offered either way.
 */
@Composable
fun ProblemScreen(retryable: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingLayout(
        title = stringResource(R.string.onboarding_problem_title),
        modifier = modifier,
        actions = {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.onboarding_try_again))
            }
        },
    ) {
        OnboardingText(
            text = stringResource(
                if (retryable) R.string.onboarding_problem_offline else R.string.onboarding_problem_generic,
            ),
        )
        OnboardingText(text = stringResource(R.string.onboarding_emergency_note))
    }
}

@SafeRoutePreviews
@Composable
private fun WelcomeScreenPreview() {
    SafeRouteTheme { WelcomeScreen(onContinue = {}) }
}

@SafeRoutePreviews
@Composable
private fun AgeGateScreenPreview() {
    SafeRouteTheme { AgeGateScreen(onAdult = {}, onUnder18Confirmed = {}) }
}

@SafeRoutePreviews
@Composable
private fun BlockedScreenPreview() {
    SafeRouteTheme { BlockedScreen(reason = BlockReason.UNDER_18) }
}

@SafeRoutePreviews
@Composable
private fun ProblemScreenPreview() {
    SafeRouteTheme { ProblemScreen(retryable = true, onRetry = {}) }
}
