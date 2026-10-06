// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import com.saferoute.app.core.auth.PhoneAuthGateway
import com.saferoute.app.core.di.ApplicationScope
import com.saferoute.app.core.network.errors.ApiFailure
import com.saferoute.app.core.network.errors.ApiResult
import com.saferoute.app.core.network.errors.ProblemCodes
import com.saferoute.app.core.network.errors.apiCall
import com.saferoute.app.core.network.errors.isRetryable
import com.saferoute.app.core.network.generated.api.MeApi
import com.saferoute.app.core.network.generated.model.BootstrapConsent
import com.saferoute.app.core.network.generated.model.BootstrapMeRequest
import com.saferoute.app.core.network.generated.model.Consent
import com.saferoute.app.core.network.generated.model.SetConsentRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val STATUS_GRANTED = "granted"

/**
 * The longest one session check may take before the app stops waiting and shows the retry
 * screen. A single request already gives up after 45 seconds (the HTTP client's call timeout);
 * a check is up to four requests in a row.
 */
const val SESSION_CHECK_TIMEOUT_MILLIS = 60_000L

/**
 * Decides the [SessionState]: a small state machine fed by three sources.
 *
 * - the flags on the phone ([SessionStore]): age declared, notice accepted, "was ready once";
 * - Firebase ([PhoneAuthGateway]): is somebody signed in;
 * - the SafeRoute API: does the account exist, and is the consent on record current.
 *
 * Order of onboarding (CLAUDE.md "Android rules"): age → consent → phone. Nothing is sent to any
 * server before the first two are done, and the under-18 path makes no network call at all.
 *
 * Offline rule (device-first, Plan v7 §7): once the app has been [SessionState.Ready] with this
 * sign-in, it opens again without a connection. Only a definite answer from the server (401,
 * account deleted) takes a signed-in user out of the app.
 *
 * Every public function ends by publishing a new [state]. None of them throws for an expected
 * failure, and none logs.
 *
 * **Where the work runs (P009d).** Every change of state runs in [appScope], which lives as long
 * as the app, and never in the scope of whoever asked for it. The caller only waits for the
 * result. This matters because a state change replaces the screen on show: if the work ran in
 * that screen's `viewModelScope`, replacing the screen would cancel the work half-way (it did:
 * the first sign-in stopped after `GET /v1/me` and never created the account).
 *
 * **Always an answer.** A check that takes longer than [checkTimeoutMillis], or fails in a way
 * nobody expected, ends in the retry screen ([SessionState.Error]), or stays on Home if the app
 * was ready before. It never leaves the app on a spinner.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val gateway: PhoneAuthGateway,
    private val store: SessionStore,
    private val meApi: MeApi,
    private val appLocale: AppLocale,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : Session {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)

    override val state: StateFlow<SessionState> = _state.asStateFlow()

    override val flags: Flow<SessionFlags> get() = store.flags

    /** One check at a time: a second caller waits and then works on the newer flags. */
    private val mutex = Mutex()

    /** See [SESSION_CHECK_TIMEOUT_MILLIS]. Tests shorten it. */
    internal var checkTimeoutMillis: Long = SESSION_CHECK_TIMEOUT_MILLIS

    init {
        // Sign-in can finish a moment after the code was accepted, or without any code at all
        // (Android read the SMS). Whoever is waiting on the phone screen then must not depend on
        // a screen to notice: when Firebase reports a user while the session still says
        // "signed out", run the check. A repeated report changes nothing, because by then the
        // state is no longer SignedOut.
        appScope.launch {
            try {
                gateway.authState.collect { user ->
                    if (user != null && _state.value == SessionState.SignedOut) onSignedIn()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Without the listener the app still works: the screens ask for a refresh.
            }
        }
    }

    /**
     * Runs [block] in [appScope], one at a time, and publishes the state it returns. The caller
     * waits for it; if the caller is cancelled (its screen went away), the work carries on.
     */
    private suspend fun change(block: suspend () -> SessionState) {
        appScope.async {
            mutex.withLock {
                _state.value = try {
                    withTimeoutOrNull(checkTimeoutMillis) { block() } ?: stalled(retryable = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stalled(retryable = false)
                }
            }
        }.await()
    }

    /**
     * The check did not finish. If Home is already on show (the app was ready before), it
     * stays; otherwise the person gets the retry screen instead of a spinner.
     */
    private fun stalled(retryable: Boolean): SessionState =
        if (_state.value == SessionState.Ready) SessionState.Ready else SessionState.Error(retryable)

    override suspend fun refresh() = change { resolve() }

    /**
     * Two things report a sign-in: the sign-in screen and Firebase's own listener (see `init`).
     * The state is looked at again here, inside the one-at-a-time section, so whichever comes
     * second finds the work done and adds nothing.
     */
    override suspend fun onSignedIn() = change {
        if (_state.value == SessionState.SignedOut) resolve() else _state.value
    }

    override suspend fun markWelcomeSeen() = change {
        store.update { it.copy(welcomeSeen = true) }
        _state.value
    }

    override suspend fun confirmAdult() = change {
        store.update { it.copy(ageConfirmed = true, under18 = false) }
        resolve()
    }

    /**
     * There is deliberately no way back from this in the app (ADR 0010, addendum): only clearing
     * the app's data or reinstalling removes the flag. The screen asks for confirmation first.
     */
    override suspend fun declareUnder18() = change {
        store.update { it.copy(under18 = true, ageConfirmed = false) }
        SessionState.Blocked(BlockReason.UNDER_18)
    }

    override suspend fun acceptNotice(noticeLocale: String) = change {
        store.update {
            it.copy(
                acceptedNoticeVersion = NOTICE_VERSION,
                acceptedNoticeLocale = noticeLocale.toSupportedLocale(),
            )
        }
        resolve()
    }

    override suspend fun signOut() = change {
        gateway.signOut()
        store.clear()
        resolve()
    }

    /**
     * Two reads: the account and its consents. It does not change [state]; a failure here only
     * means Settings cannot show the details right now.
     */
    override suspend fun account(): AccountResult {
        if (gateway.currentUser == null) return AccountResult.Unavailable(retryable = false)
        val me = when (val result = apiCall { meApi.getMe() }) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> return AccountResult.Unavailable(isRetryable(result.failure))
        }
        val consents = when (val result = apiCall { meApi.getMyConsents() }) {
            is ApiResult.Success -> result.value.items
            is ApiResult.Failure -> return AccountResult.Unavailable(isRetryable(result.failure))
        }
        return AccountResult.Loaded(
            AccountSummary(
                maskedPhone = me.phoneE164?.let(::maskPhone),
                role = me.role,
                locale = me.locale,
                grantedPurposes = consents.filter { it.status == STATUS_GRANTED }.map { it.purpose },
            ),
        )
    }

    private suspend fun resolve(): SessionState {
        val flags = store.read()
        if (flags.under18) return SessionState.Blocked(BlockReason.UNDER_18)

        if (gateway.currentUser == null) return onboardingStep(flags) ?: SessionState.SignedOut

        // Was ready before with this sign-in: show Home at once and check in the background, so
        // the app opens without waiting for the network (and without one at all).
        _state.value = if (flags.readyOnce) SessionState.Ready else SessionState.Loading
        return checkAccount(flags, mayBootstrap = true)
    }

    /**
     * Does the account exist? If not, create it (once), then look again: after a bootstrap the
     * account and its consent are read back from the server before the app says "ready".
     */
    private suspend fun checkAccount(flags: SessionFlags, mayBootstrap: Boolean): SessionState =
        when (val me = apiCall { meApi.getMe() }) {
            is ApiResult.Success -> checkConsent(flags)
            is ApiResult.Failure -> when {
                mayBootstrap && me.failure.hasCode(ProblemCodes.BOOTSTRAP_REQUIRED) ->
                    onboardingStep(flags) ?: bootstrap(flags)
                else -> onFailure(me.failure, flags)
            }
        }

    /** The onboarding step still missing on this phone, or null when age and consent are done. */
    private fun onboardingStep(flags: SessionFlags): SessionState? = when {
        !flags.ageConfirmed -> SessionState.NeedsAge
        flags.acceptedNoticeVersion != NOTICE_VERSION -> SessionState.NeedsConsent
        else -> null
    }

    /** The account exists. Is `account_core` on record for the notice version shown today? */
    private suspend fun checkConsent(flags: SessionFlags): SessionState =
        when (val consents = apiCall { meApi.getMyConsents() }) {
            is ApiResult.Failure -> onFailure(consents.failure, flags)
            is ApiResult.Success -> when {
                consents.value.items.any { it.isCurrentAccountCore() } -> ready()
                // The user already accepted the current notice on this phone (for example the
                // app was killed before the answer arrived): send it now.
                flags.acceptedNoticeVersion == NOTICE_VERSION -> sendConsent(flags)
                else -> SessionState.NeedsConsent
            }
        }

    private suspend fun sendConsent(flags: SessionFlags): SessionState {
        val request = SetConsentRequest(
            status = SetConsentRequest.Status.granted,
            noticeVersion = NOTICE_VERSION,
            noticeLocale = when (flags.acceptedNoticeLocale) {
                LOCALE_BENGALI -> SetConsentRequest.NoticeLocale.bn
                else -> SetConsentRequest.NoticeLocale.en
            },
        )
        return when (val result = apiCall { meApi.setMyConsent(PURPOSE_ACCOUNT_CORE, request) }) {
            is ApiResult.Success -> ready()
            is ApiResult.Failure -> onFailure(result.failure, flags)
        }
    }

    /**
     * Creates the account with the age declaration and the consent (ADR 0010). The request never
     * contains the phone number: the server takes it from the verified ID token.
     */
    private suspend fun bootstrap(flags: SessionFlags): SessionState {
        _state.value = SessionState.NeedsBootstrap
        val request = BootstrapMeRequest(
            locale = when (appLocale.current()) {
                LOCALE_BENGALI -> BootstrapMeRequest.Locale.bn
                else -> BootstrapMeRequest.Locale.en
            },
            consent = BootstrapConsent(
                ageConfirmed = true,
                noticeVersion = NOTICE_VERSION,
                noticeLocale = when (flags.acceptedNoticeLocale) {
                    LOCALE_BENGALI -> BootstrapConsent.NoticeLocale.bn
                    else -> BootstrapConsent.NoticeLocale.en
                },
                purposes = listOf(PURPOSE_ACCOUNT_CORE),
            ),
        )
        return when (val result = apiCall { meApi.bootstrapMe(request) }) {
            // mayBootstrap = false: a second "no account" answer is an error, not a loop.
            is ApiResult.Success -> checkAccount(flags, mayBootstrap = false)
            is ApiResult.Failure -> onFailure(result.failure, flags)
        }
    }

    private suspend fun ready(): SessionState {
        store.update { it.copy(readyOnce = true) }
        return SessionState.Ready
    }

    private suspend fun onFailure(failure: ApiFailure, flags: SessionFlags): SessionState = when {
        // After the network layer's one token refresh and retry: the sign-in is no longer valid.
        // Age and consent stay, so the user only verifies the phone number again.
        failure is ApiFailure.Unauthorized -> {
            gateway.signOut()
            store.update { it.copy(readyOnce = false) }
            onboardingStep(flags) ?: SessionState.SignedOut
        }

        failure.hasCode(ProblemCodes.ACCOUNT_DELETED) -> {
            gateway.signOut()
            store.update { it.copy(readyOnce = false) }
            SessionState.Blocked(BlockReason.ACCOUNT_DELETED)
        }

        // Was ready before: only the definite answers above take the user out of the app.
        flags.readyOnce -> SessionState.Ready

        // No connection, 503, auth_unavailable: not the user's fault and not a sign-out.
        isRetryable(failure) -> SessionState.Error(retryable = true)

        // Anything else, including codes this version does not know.
        else -> SessionState.Error(retryable = false)
    }
}

private fun ApiFailure.hasCode(code: String): Boolean = this is ApiFailure.Problem && this.code == code

private fun Consent.isCurrentAccountCore(): Boolean =
    purpose == PURPOSE_ACCOUNT_CORE && status == STATUS_GRANTED && noticeVersion == NOTICE_VERSION
