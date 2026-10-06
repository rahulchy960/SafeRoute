// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import com.saferoute.app.core.auth.PhoneAuthGateway
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val STATUS_GRANTED = "granted"

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
 */
@Singleton
class SessionRepository @Inject constructor(
    private val gateway: PhoneAuthGateway,
    private val store: SessionStore,
    private val meApi: MeApi,
    private val appLocale: AppLocale,
) : Session {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)

    override val state: StateFlow<SessionState> = _state.asStateFlow()

    override val flags: Flow<SessionFlags> get() = store.flags

    /** One check at a time: a second caller waits and then works on the newer flags. */
    private val mutex = Mutex()

    override suspend fun refresh() = mutex.withLock { _state.value = resolve() }

    override suspend fun markWelcomeSeen() = mutex.withLock {
        store.update { it.copy(welcomeSeen = true) }
    }

    override suspend fun confirmAdult() = mutex.withLock {
        store.update { it.copy(ageConfirmed = true, under18 = false) }
        _state.value = resolve()
    }

    /**
     * There is deliberately no way back from this in the app (ADR 0010, addendum): only clearing
     * the app's data or reinstalling removes the flag. The screen asks for confirmation first.
     */
    override suspend fun declareUnder18() = mutex.withLock {
        store.update { it.copy(under18 = true, ageConfirmed = false) }
        _state.value = SessionState.Blocked(BlockReason.UNDER_18)
    }

    override suspend fun acceptNotice(noticeLocale: String) = mutex.withLock {
        store.update {
            it.copy(
                acceptedNoticeVersion = NOTICE_VERSION,
                acceptedNoticeLocale = noticeLocale.toSupportedLocale(),
            )
        }
        _state.value = resolve()
    }

    override suspend fun signOut() = mutex.withLock {
        gateway.signOut()
        store.clear()
        _state.value = resolve()
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
        return when (val me = apiCall { meApi.getMe() }) {
            is ApiResult.Success -> checkConsent(flags)
            is ApiResult.Failure -> when {
                me.failure.hasCode(ProblemCodes.BOOTSTRAP_REQUIRED) ->
                    onboardingStep(flags) ?: bootstrap(flags)
                else -> onFailure(me.failure, flags)
            }
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
            is ApiResult.Success -> ready()
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
