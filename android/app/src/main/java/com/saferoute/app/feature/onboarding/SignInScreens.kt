// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saferoute.app.R
import com.saferoute.app.core.auth.PhoneAuthError
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme

/**
 * Phone number, then SMS code, connected to [SignInViewModel].
 *
 * `LocalActivity` is the activity this screen runs in; Firebase needs it for its app check.
 * `BackHandler` makes the system back gesture on the code screen return to the phone screen
 * instead of leaving the app.
 */
@Composable
fun SignInRoute(modifier: Modifier = Modifier, viewModel: SignInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    BackHandler(enabled = state.step == SignInStep.CODE) { viewModel.changeNumber() }

    when (state.step) {
        SignInStep.PHONE -> PhoneEntryScreen(
            state = state,
            onPhoneChange = viewModel::onPhoneChange,
            onSendCode = { activity?.let(viewModel::submitPhone) },
            modifier = modifier,
        )

        SignInStep.CODE -> CodeEntryScreen(
            state = state,
            onCodeChange = viewModel::onCodeChange,
            onVerify = viewModel::submitCode,
            onResend = { activity?.let(viewModel::resend) },
            onChangeNumber = viewModel::changeNumber,
            modifier = modifier,
        )
    }
}

/**
 * Asks for an Indian mobile number. `+91` is fixed and shown next to the field.
 *
 * - `KeyboardType.Phone` opens the number pad.
 * - `ContentType.PhoneNumberNational` lets the phone's autofill offer the user's own number.
 * - An invalid number is marked with `error(...)` so that TalkBack reads the reason, and the
 *   field shows an error icon and text, not only a red outline.
 */
@Composable
fun PhoneEntryScreen(
    state: SignInUiState,
    onPhoneChange: (String) -> Unit,
    onSendCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val invalidText = stringResource(R.string.phone_error_invalid)
    val countryCodeDescription = stringResource(R.string.phone_country_code_description)

    OnboardingLayout(
        title = stringResource(R.string.phone_title),
        modifier = modifier,
        actions = {
            Button(onClick = onSendCode, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.phone_send_code))
            }
            if (state.busy) BusyText(text = stringResource(R.string.phone_sending))
        },
    ) {
        OnboardingText(text = stringResource(R.string.phone_body))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.phone_country_code),
                modifier = Modifier.semantics { contentDescription = countryCodeDescription },
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = state.phoneInput,
                onValueChange = onPhoneChange,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentType = ContentType.PhoneNumberNational
                        if (state.phoneInvalid) error(invalidText)
                    },
                enabled = !state.busy,
                label = { Text(text = stringResource(R.string.phone_field_label)) },
                isError = state.phoneInvalid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSendCode() }),
            )
        }
        if (state.phoneInvalid) OnboardingMessage(text = invalidText)
        state.error?.let { OnboardingMessage(text = stringResource(signInErrorText(it))) }
        Text(
            text = stringResource(R.string.phone_india_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Asks for the 6-digit SMS code.
 *
 * - `KeyboardType.NumberPassword` opens a digits-only pad on every keyboard.
 * - `ContentType.SmsOtpCode` lets the keyboard offer the code from the SMS.
 * - "Send a new code" stays disabled, with the seconds left written out, until the wait is
 *   over.
 */
@Composable
fun CodeEntryScreen(
    state: SignInUiState,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onChangeNumber: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorText = state.error?.let { stringResource(signInErrorText(it)) }

    OnboardingLayout(
        title = stringResource(R.string.code_title),
        modifier = modifier,
        actions = {
            Button(onClick = onVerify, enabled = state.canSubmitCode, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.code_verify))
            }
            if (state.busy) BusyText(text = stringResource(R.string.code_verifying))
            OutlinedButton(onClick = onResend, enabled = state.canResend, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.code_resend))
            }
            if (state.resendInSeconds > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.code_resend_wait,
                        state.resendInSeconds,
                        state.resendInSeconds,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onChangeNumber, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.code_change_number))
            }
        },
    ) {
        OnboardingText(text = stringResource(R.string.code_body))
        OutlinedTextField(
            value = state.codeInput,
            onValueChange = onCodeChange,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentType = ContentType.SmsOtpCode
                    if (errorText != null) error(errorText)
                },
            enabled = !state.busy,
            label = { Text(text = stringResource(R.string.code_field_label)) },
            isError = errorText != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (state.canSubmitCode) onVerify() }),
        )
        if (errorText != null) OnboardingMessage(text = errorText)
    }
}

/** "Sending…" / "Checking…": announced by TalkBack when it appears. */
@Composable
private fun BusyText(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(vertical = SafeRouteTheme.spacing.xxs)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** The sentence for each sign-in failure. None of them repeats the number or the code. */
@StringRes
fun signInErrorText(error: PhoneAuthError): Int = when (error) {
    PhoneAuthError.INVALID_PHONE -> R.string.signin_error_invalid_phone
    PhoneAuthError.WRONG_CODE -> R.string.signin_error_wrong_code
    PhoneAuthError.CODE_EXPIRED -> R.string.signin_error_code_expired
    PhoneAuthError.TOO_MANY_REQUESTS -> R.string.signin_error_too_many
    PhoneAuthError.QUOTA_OR_BLOCKED -> R.string.signin_error_blocked
    PhoneAuthError.NETWORK -> R.string.signin_error_network
    PhoneAuthError.APP_NOT_VERIFIED -> R.string.signin_error_app_not_verified
    PhoneAuthError.UNKNOWN -> R.string.signin_error_unknown
}

@SafeRoutePreviews
@Composable
private fun PhoneEntryScreenPreview() {
    SafeRouteTheme {
        PhoneEntryScreen(
            state = SignInUiState(phoneInput = "90000 0000", phoneInvalid = true),
            onPhoneChange = {},
            onSendCode = {},
        )
    }
}

@SafeRoutePreviews
@Composable
private fun CodeEntryScreenPreview() {
    SafeRouteTheme {
        CodeEntryScreen(
            state = SignInUiState(
                step = SignInStep.CODE,
                error = PhoneAuthError.WRONG_CODE,
                resendInSeconds = 42,
            ),
            onCodeChange = {},
            onVerify = {},
            onResend = {},
            onChangeNumber = {},
        )
    }
}
