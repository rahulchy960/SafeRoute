// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saferoute.app.core.session.AccountResult
import com.saferoute.app.core.session.AccountSummary
import com.saferoute.app.core.session.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Account and Privacy sections of Settings show. */
sealed interface AccountUiState {
    data object Loading : AccountUiState

    data class Loaded(val account: AccountSummary) : AccountUiState

    /** The details could not be read (offline, server trouble). Sign out still works. */
    data object Unavailable : AccountUiState
}

/**
 * Loads the account for Settings and signs out.
 *
 * The phone number arrives already masked ([AccountSummary]); this class never sees the full
 * number.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val session: Session,
) : ViewModel() {

    private val _account = MutableStateFlow<AccountUiState>(AccountUiState.Loading)
    val account: StateFlow<AccountUiState> = _account.asStateFlow()

    init {
        load()
    }

    /** Also the "Try again" button. */
    fun load() {
        _account.value = AccountUiState.Loading
        viewModelScope.launch {
            _account.value = when (val result = session.account()) {
                is AccountResult.Loaded -> AccountUiState.Loaded(result.account)
                is AccountResult.Unavailable -> AccountUiState.Unavailable
            }
        }
    }

    /**
     * Called after the confirmation dialog. The session state changes, and the app's root
     * replaces Settings with the onboarding screens.
     */
    fun signOut() {
        viewModelScope.launch { session.signOut() }
    }
}
