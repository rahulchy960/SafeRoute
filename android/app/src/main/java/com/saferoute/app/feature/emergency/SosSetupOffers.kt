// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.feature.contacts.CARD_SNOOZE_DAYS
import com.saferoute.app.feature.contacts.ContactsPreferences
import com.saferoute.app.feature.contacts.ContactsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * The two places that lead a user to "SOS setup" without their looking for it (P014c2c):
 * once on the Emergency contacts screen after the first contact exists, and on Home for as
 * long as something is to fix. Neither runs anything in the background: the first follows
 * the contact list while its screen is shown, the second looks at the phone when Home comes
 * to the front. Both show fixed text and a count; no name, number or place.
 */

const val SosSetupOfferTag = "setup_offer"
const val SosSetupHomeCardTag = "setup_home_card"

private const val STOP_TIMEOUT_MILLIS = 5_000L

// Once, after the first contact ---------------------------------------------------------

/**
 * Whether the Emergency contacts screen offers "SOS setup": when the phone knows of at
 * least one contact and the offer was not made before. Made once; either answer, or opening
 * the checklist any other way, ends it for good.
 */
@HiltViewModel
class SosSetupOfferViewModel @Inject constructor(
    repository: ContactsRepository,
    private val preferences: ContactsPreferences,
) : ViewModel() {

    val visible: StateFlow<Boolean> = combine(
        repository.contacts,
        preferences.setupOfferShown,
    ) { contacts, shown -> contacts.isNotEmpty() && !shown }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    /** "Not now", "Open SOS setup", or the checklist was opened by its own button. */
    fun onAnswered() {
        viewModelScope.launch { preferences.setSetupOfferShown() }
    }
}

/** The one-time offer on the Emergency contacts screen. Nothing when it is not due. */
@Composable
fun SosSetupOfferCard(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SosSetupOfferViewModel = hiltViewModel(),
) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    if (!visible) return
    SetupCard(
        title = stringResource(R.string.sos_setup_offer_title),
        body = stringResource(R.string.sos_setup_offer_body),
        onOpen = {
            viewModel.onAnswered()
            onOpen()
        },
        onNotNow = viewModel::onAnswered,
        modifier = modifier.testTag(SosSetupOfferTag),
    )
}

// On Home, while not ready --------------------------------------------------------------

/**
 * Whether Home shows "Not ready: N things to fix", and N.
 *
 * Shown when all of this holds: the user has begun to set SOS up (agreed to the notice, or
 * has a contact on this phone; before that the card "Add emergency contacts" is the right
 * one); the checklist counts something to fix; and "Not now" was not tapped in the last
 * [CARD_SNOOZE_DAYS] days.
 *
 * It looks at the phone only in [onResume], which Home calls each time it comes to the
 * front. Nothing is watched while Home is not shown.
 */
@HiltViewModel
class SosSetupCardViewModel @Inject constructor(
    private val reader: SosReadinessReader,
    private val preferences: ContactsPreferences,
    private val clock: Clock,
) : ViewModel() {

    private val _toFix = MutableStateFlow(0)

    /** How many things are to fix; 0 means: show nothing. */
    val toFix: StateFlow<Int> = _toFix.asStateFlow()

    fun onResume() {
        viewModelScope.launch {
            val reading = reader.read()
            val begun = reading.facts.alertsSetUp || reading.facts.activeContacts > 0
            val snoozed = clock.millis() < preferences.setupCardSnoozedUntil.first()
            _toFix.value = if (begun && !snoozed) reading.readiness.toFix else 0
        }
    }

    fun onNotNow() {
        _toFix.value = 0
        viewModelScope.launch {
            preferences.snoozeSetupCardUntil(clock.millis() + Duration.ofDays(CARD_SNOOZE_DAYS).toMillis())
        }
    }
}

/**
 * The card for the Home sheet. It sits in the sheet's content, below the header row that
 * holds the SOS control, so it can never cover it. Nothing while there is nothing to fix.
 */
@Composable
fun SosSetupHomeCard(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SosSetupCardViewModel = hiltViewModel(),
) {
    val toFix by viewModel.toFix.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    if (toFix <= 0) return
    SetupCard(
        title = if (toFix == 1) {
            stringResource(R.string.sos_setup_not_ready_one)
        } else {
            stringResource(R.string.sos_setup_not_ready_many, toFix)
        },
        body = stringResource(R.string.sos_setup_home_body),
        onOpen = onOpen,
        onNotNow = viewModel::onNotNow,
        modifier = modifier.testTag(SosSetupHomeCardTag),
    )
}

/** A title, a sentence, and two buttons one under the other (they fit at any font size). */
@Composable
fun SetupCard(title: String, body: String, onOpen: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SafeRouteTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xs),
        ) {
            Text(text = title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.sos_setup_open))
            }
            TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.contacts_card_not_now))
            }
        }
    }
}
