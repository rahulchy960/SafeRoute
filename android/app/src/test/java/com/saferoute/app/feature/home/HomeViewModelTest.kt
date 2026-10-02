// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The emergency dialog's state machine. Plain JVM test: a ViewModel is an ordinary class.
 *
 * Turbine's `test { }` collects the StateFlow and `awaitItem()` returns each value in the order
 * it was emitted, so the test reads like the sequence of events.
 */
class HomeViewModelTest {

    private val viewModel = HomeViewModel()

    @Test
    fun `starts with no dialog`() = runTest {
        viewModel.emergencyDialog.test {
            assertEquals(EmergencyDialogState.Hidden, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `emergency tap offers the dialer and cancel hides it`() = runTest {
        viewModel.emergencyDialog.test {
            assertEquals(EmergencyDialogState.Hidden, awaitItem())

            viewModel.onEmergencyClick()
            assertEquals(EmergencyDialogState.OfferDialer, awaitItem())

            viewModel.onEmergencyDialogDismiss()
            assertEquals(EmergencyDialogState.Hidden, awaitItem())
        }
    }

    @Test
    fun `opening the dialer closes the dialog`() = runTest {
        viewModel.emergencyDialog.test {
            assertEquals(EmergencyDialogState.Hidden, awaitItem())

            viewModel.onEmergencyClick()
            assertEquals(EmergencyDialogState.OfferDialer, awaitItem())

            viewModel.onDialerOpened()
            assertEquals(EmergencyDialogState.Hidden, awaitItem())
        }
    }

    @Test
    fun `a missing dialer keeps the dialog open until it is closed`() = runTest {
        viewModel.emergencyDialog.test {
            assertEquals(EmergencyDialogState.Hidden, awaitItem())

            viewModel.onEmergencyClick()
            assertEquals(EmergencyDialogState.OfferDialer, awaitItem())

            viewModel.onDialerUnavailable()
            assertEquals(EmergencyDialogState.DialerUnavailable, awaitItem())

            viewModel.onEmergencyDialogDismiss()
            assertEquals(EmergencyDialogState.Hidden, awaitItem())
        }
    }

    @Test
    fun `a second emergency tap does not emit again`() = runTest {
        viewModel.emergencyDialog.test {
            assertEquals(EmergencyDialogState.Hidden, awaitItem())

            viewModel.onEmergencyClick()
            assertEquals(EmergencyDialogState.OfferDialer, awaitItem())

            // A StateFlow only reports changes; the same value twice is one dialog.
            viewModel.onEmergencyClick()
            expectNoEvents()
        }
    }
}
