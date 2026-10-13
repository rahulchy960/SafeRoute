// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What may and may not travel with a crash report (ADR 0028). Every value below is made up:
 * the numbers are the test range of the contacts rules, the coordinates are round figures.
 */
class DiagnosticsSanitizerTest {

    private fun assertPersonal(vararg values: String) {
        for (value in values) assertTrue("should be refused: $value", DiagnosticsSanitizer.looksPersonal(value))
    }

    @Test
    fun `phone numbers are refused in every way of writing them`() {
        assertPersonal(
            "9000010001",
            "+910000100001",
            "+91 00001 00001",
            "(000) 010-0001",
            "000.010.0001",
            "call 0000100001",
            "0091 90000 10001",
        )
    }

    @Test
    fun `coordinates are refused, as a pair and alone`() {
        assertPersonal("22.5, 88.25", "22.50,88.25", "-22.5 88.25", "22,5; 88,25", "22.5000", "at 88.123456")
    }

    @Test
    fun `addresses are refused, with or without a query`() {
        assertPersonal(
            "https://example.invalid/v1/x?q=a",
            "https://example.invalid/",
            "www.example.invalid",
            "a?b=c",
            "someone@example.invalid",
        )
    }

    @Test
    fun `tokens and ids are refused`() {
        assertPersonal(
            "eyJhbGciOi",
            "FAKEtoken1234567890abcdef",
            "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b",
            "AbCdEfGhIj0123456789",
        )
    }

    @Test
    fun `free text is refused by its length or its number of words`() {
        assertPersonal(
            "x".repeat(DiagnosticsSanitizer.MAX_LENGTH + 1),
            "this is a sentence someone typed",
            "near the old bridge",
        )
    }

    @Test
    fun `short technical words pass the filter`() {
        for (value in listOf("1.0", "36", "debug", "samsung", "bn", "en-IN", "wifi", "sms_app", "not_declared")) {
            assertFalse("should pass: $value", DiagnosticsSanitizer.looksPersonal(value))
        }
    }

    @Test
    fun `a name passes the filter but no key accepts it`() {
        // A filter cannot know that a word is a name. The list of accepted words per key can.
        val names = listOf("Test Contact 1", "Asha", "asha", "Test")
        assertFalse(DiagnosticsSanitizer.looksPersonal("Asha"))
        for (key in DiagnosticKey.entries) {
            for (name in names) assertNull("${key.key} accepted $name", key.clean(name))
        }
    }

    @Test
    fun `no key accepts a number, a place, an address or a token`() {
        val bad = listOf(
            "+910000100001", "9000010001", "22.5, 88.25", "https://example.invalid/?a=b",
            "FAKEtoken1234567890abcdef", "some longer free text here", "", " ",
        )
        for (key in DiagnosticKey.entries) {
            for (value in bad) assertNull("${key.key} accepted '$value'", key.clean(value))
        }
    }

    @Test
    fun `each key accepts its own values and only those`() {
        val good = mapOf(
            DiagnosticKey.APP_VERSION to listOf("1.0", "1.2.3", "12"),
            DiagnosticKey.BUILD_TYPE to listOf("debug", "release"),
            DiagnosticKey.ANDROID_API to listOf("26", "36"),
            DiagnosticKey.OEM_FAMILY to listOf("samsung", "other"),
            DiagnosticKey.LOCALE to listOf("en", "bn", "en-IN"),
            DiagnosticKey.NETWORK_TYPE to listOf("wifi", "cellular", "other", "none"),
            DiagnosticKey.SMS_MODE to listOf("automatic", "sms_app", "not_set_up"),
            DiagnosticKey.LOCATION_PERMISSION to listOf("precise", "approximate", "denied"),
            DiagnosticKey.SMS_PERMISSION to listOf("granted", "denied", "not_declared"),
            DiagnosticKey.NOTIFICATION_PERMISSION to listOf("granted", "denied", "not_declared"),
        )
        assertEquals("a key without a test", DiagnosticKey.entries.toSet(), good.keys)
        for ((key, values) in good) {
            for (value in values) assertEquals(value, key.clean(value))
        }
        assertNull(DiagnosticKey.BUILD_TYPE.clean("staging"))
        assertNull(DiagnosticKey.ANDROID_API.clean("7"))
        assertNull(DiagnosticKey.APP_VERSION.clean("2026.10.13.1"))
        assertNull(DiagnosticKey.OEM_FAMILY.clean("SM-G990E"))
        assertNull(DiagnosticKey.NETWORK_TYPE.clean("HomeWifi"))
    }

    @Test
    fun `the keys are the agreed list and nothing names a person`() {
        assertEquals(
            setOf(
                "appVersion", "buildType", "androidApi", "oemFamily", "locale", "networkType",
                "smsMode", "locationPermission", "smsPermission", "notificationPermission",
            ),
            DiagnosticKey.entries.map { it.key }.toSet(),
        )
    }
}
