// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A [Session] for UI and ViewModel tests: no Firebase, no server, no file.
 *
 * It applies the same simple rules as the real one for the steps before sign-in, records every
 * call in [calls], and lets a test set the state directly ([setState]) or say what `refresh`
 * leads to ([afterRefresh]).
 *
 * A Hilt test installs it with:
 * ```
 * @UninstallModules(SessionBindingModule::class)
 * @BindValue @JvmField val session: Session = FakeSession(SessionState.Ready)
 * ```
 */
class FakeSession(
    initial: SessionState = SessionState.Loading,
    flags: SessionFlags = SessionFlags(),
) : Session {

    private val _state = MutableStateFlow(initial)
    private val _flags = MutableStateFlow(flags)

    /** One entry per call, for example `refresh` or `acceptNotice(bn)`. */
    val calls = CopyOnWriteArrayList<String>()

    /** What `refresh` and `onSignedIn` set the state to; null leaves it as it is. */
    var afterRefresh: SessionState? = null

    var accountResult: AccountResult = AccountResult.Loaded(
        AccountSummary(
            maskedPhone = "+91 ••••• ••123",
            role = "user",
            locale = "en",
            grantedPurposes = listOf(PURPOSE_ACCOUNT_CORE),
        ),
    )

    override val state: StateFlow<SessionState> = _state

    override val flags: Flow<SessionFlags> = _flags

    val currentFlags: SessionFlags get() = _flags.value

    fun setState(state: SessionState) {
        _state.value = state
    }

    override suspend fun refresh() {
        calls += "refresh"
        afterRefresh?.let { _state.value = it }
    }

    override suspend fun onSignedIn() {
        calls += "onSignedIn"
        afterRefresh?.let { _state.value = it }
    }

    override suspend fun markWelcomeSeen() {
        calls += "markWelcomeSeen"
        _flags.value = _flags.value.copy(welcomeSeen = true)
    }

    override suspend fun confirmAdult() {
        calls += "confirmAdult"
        _flags.value = _flags.value.copy(ageConfirmed = true)
        _state.value = SessionState.NeedsConsent
    }

    override suspend fun declareUnder18() {
        calls += "declareUnder18"
        _flags.value = _flags.value.copy(under18 = true)
        _state.value = SessionState.Blocked(BlockReason.UNDER_18)
    }

    override suspend fun acceptNotice(noticeLocale: String) {
        calls += "acceptNotice($noticeLocale)"
        _flags.value = _flags.value.copy(
            acceptedNoticeVersion = NOTICE_VERSION,
            acceptedNoticeLocale = noticeLocale,
        )
        _state.value = SessionState.SignedOut
    }

    override suspend fun signOut() {
        calls += "signOut"
        _flags.value = SessionFlags()
        _state.value = SessionState.NeedsAge
    }

    override suspend fun account(): AccountResult {
        calls += "account"
        return accountResult
    }
}
