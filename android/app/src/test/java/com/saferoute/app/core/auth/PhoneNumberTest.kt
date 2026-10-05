// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Numbers here are made up; the 00000 block is not assigned to anyone. */
class PhoneNumberTest {

    @Test
    fun `ten digits starting with 6 to 9 become E164`() {
        for (first in 6..9) {
            assertEquals("+91${first}000000001", normaliseIndianMobile("${first}000000001"))
        }
    }

    @Test
    fun `spaces, dashes, dots and brackets are ignored`() {
        val expected = "+919000000001"
        val inputs = listOf(
            "90000 00001",
            "900-000-0001",
            "900.000.0001",
            "(900) 000 0001",
            "  9000000001  ",
            "90000 00001",
        )
        for (input in inputs) assertEquals(input, expected, normaliseIndianMobile(input))
    }

    @Test
    fun `the country code and the trunk zero are accepted and removed`() {
        val expected = "+919000000001"
        val inputs = listOf(
            "+919000000001",
            "+91 90000 00001",
            "+91-9000000001",
            "919000000001",
            "00919000000001",
            "09000000001",
            "0 90000 00001",
        )
        for (input in inputs) assertEquals(input, expected, normaliseIndianMobile(input))
    }

    @Test
    fun `a ten-digit number that itself starts with 91 is kept whole`() {
        assertEquals("+919100000001", normaliseIndianMobile("9100000001"))
    }

    @Test
    fun `everything else is rejected`() {
        val inputs = listOf(
            "",
            "   ",
            "5000000001", // starts with 5
            "0000000001",
            "1234567890",
            "900000000", // nine digits
            "90000000012", // eleven digits
            "+9190000000012",
            "+91900000000",
            "+19000000001", // another country
            "+4479000000001",
            "9000O00001", // letter O
            "nine000000001",
            "9000000001;drop",
            "+91+919000000001",
            "९०००००००००", // Devanagari digits
            "৯০০০০০০০০১", // Bengali digits
        )
        for (input in inputs) assertNull(input, normaliseIndianMobile(input))
    }
}
