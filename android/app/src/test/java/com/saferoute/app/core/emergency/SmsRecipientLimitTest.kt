// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import com.saferoute.app.core.data.SosContact
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Obviously fake people: "Test Contact N" and numbers from +91 90000 100NN. Nobody real. */
private fun contact(n: Int) = SosContact("id-$n", "Test Contact $n", "+9190000100${n.toString().padStart(2, '0')}")

/** The limit on who a build may text by itself, and what the screens are told about it (P014b6). */
class SmsRecipientLimitTest {

    private val contacts = FakeActiveSosContacts(listOf(contact(1), contact(2), contact(3)))
    private val mode = FakeSmsModeSource(SmsMode.AUTOMATIC)

    @Test
    fun `no limit means every number, and nothing to tell the screens`() = runTest {
        assertFalse(UnlimitedSmsRecipients.limited)
        assertTrue(UnlimitedSmsRecipients.allows(contact(1).phoneE164))
        assertTrue(UnlimitedSmsRecipients.allows("anything at all"))

        assertNull(SmsLimitNotices(UnlimitedSmsRecipients, contacts, mode).current())
    }

    @Test
    fun `a limited build is told how many of its contacts would be texted`() = runTest {
        val limit = FakeSmsRecipientLimit(allowed = setOf(contact(1).phoneE164, contact(3).phoneE164, contact(9).phoneE164))

        val notice = SmsLimitNotices(limit, contacts, mode).current()

        // Two of the three contacts are on the list; the third number on it is nobody's.
        assertEquals(SmsLimitNotice(automaticPossible = true, automaticRecipients = 2), notice)
    }

    @Test
    fun `an empty list, or one that matches no contact, counts nobody`() = runTest {
        assertEquals(
            SmsLimitNotice(automaticPossible = true, automaticRecipients = 0),
            SmsLimitNotices(FakeSmsRecipientLimit(allowed = emptySet()), contacts, mode).current(),
        )
        assertEquals(
            SmsLimitNotice(automaticPossible = true, automaticRecipients = 0),
            SmsLimitNotices(FakeSmsRecipientLimit(allowed = setOf(contact(9).phoneE164)), contacts, mode).current(),
        )
    }

    @Test
    fun `when the app cannot send by itself nobody is counted, whatever the list holds`() = runTest {
        mode.mode = SmsMode.COMPOSER
        val limit = FakeSmsRecipientLimit(allowed = setOf(contact(1).phoneE164))

        assertEquals(
            SmsLimitNotice(automaticPossible = false, automaticRecipients = 0),
            SmsLimitNotices(limit, contacts, mode).current(),
        )
    }

    @Test
    fun `the notice holds counts only`() {
        val text = SmsLimitNotice(automaticPossible = true, automaticRecipients = 2).toString()

        assertFalse(text, text.contains("+91"))
        assertFalse(text, text.contains("Test Contact"))
    }
}
