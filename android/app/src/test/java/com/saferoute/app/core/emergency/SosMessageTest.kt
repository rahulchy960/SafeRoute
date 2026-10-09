// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import java.time.Instant
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 08:35 UTC is 14:05 in India.
private val NOW: Instant = Instant.parse("2026-10-09T08:35:00Z")

// Round fixture values; not a place.
private fun point(ageSeconds: Long = 0, accuracy: Float? = 12f) = SosPoint(
    latitude = 10.0,
    longitude = 20.5,
    accuracyMeters = accuracy,
    recordedAt = NOW.minusSeconds(ageSeconds),
    mock = false,
)

private fun input(
    name: String? = "Test User",
    language: SosLanguage = SosLanguage.EN,
    point: SosPoint? = point(),
    battery: Int? = 40,
) = SosMessageInput(name, language, point, NOW, battery)

/** How many SMS parts a text needs. */
class SmsSegmentsTest {

    @Test
    fun `plain text fits 160 characters in one part and 153 per part after that`() {
        assertEquals(0, smsSegments(""))
        assertEquals(1, smsSegments("a".repeat(160)))
        assertEquals(2, smsSegments("a".repeat(161)))
        assertEquals(2, smsSegments("a".repeat(306)))
        assertEquals(3, smsSegments("a".repeat(307)))
        assertEquals(3, smsSegments("a".repeat(459)))
        assertEquals(4, smsSegments("a".repeat(460)))
    }

    @Test
    fun `the characters of the SMS alphabet that cost two places are counted twice`() {
        // 80 brackets are 160 places; one more does not fit.
        assertEquals(1, smsSegments("[".repeat(80)))
        assertEquals(2, smsSegments("[".repeat(81)))
        assertEquals(1, smsSegments("a".repeat(158) + "€"))
        assertEquals(2, smsSegments("a".repeat(159) + "€"))
    }

    @Test
    fun `one letter from outside the SMS alphabet makes the whole message 70 per part`() {
        assertEquals(1, smsSegments("ক".repeat(70)))
        assertEquals(2, smsSegments("ক".repeat(71)))
        assertEquals(2, smsSegments("ক".repeat(134)))
        assertEquals(3, smsSegments("ক".repeat(135)))
        // 100 plain letters would be one part; with one Bengali letter they are two.
        assertEquals(1, smsSegments("a".repeat(100)))
        assertEquals(2, smsSegments("a".repeat(100) + "ক"))
    }

    @Test
    fun `punctuation used by the messages is inside the SMS alphabet`() {
        assertEquals(1, smsSegments("Location: https://maps.google.com/?q=10.00000,20.50000 (about 12 m, at 14:05 IST). 40%."))
    }
}

/** The text of the alert and of its follow-ups. */
class SosMessageTest {

    private val defaultLocale: Locale = Locale.getDefault()

    @After
    fun tearDown() = Locale.setDefault(defaultLocale)

    @Test
    fun `the English alert says who, where, how exact, when, the battery, the sender and 112`() {
        assertEquals(
            "Test User needs help. Location: https://maps.google.com/?q=10.00000,20.50000 " +
                "(about 12 m, at 14:05 IST). Battery 40%. Sent by the SafeRoute app. " +
                "If you think they are in danger, call 112.",
            buildAlertMessage(input()),
        )
        assertEquals(2, smsSegments(buildAlertMessage(input())))
    }

    @Test
    fun `an old position carries its age in minutes and the clock time it was taken`() {
        val text = buildAlertMessage(input(point = point(ageSeconds = 7 * 60 + 30)))

        assertTrue(text, text.contains("(about 12 m, at 13:57 IST, 7 min old)."))
        // Under a minute is not called old.
        assertFalse(buildAlertMessage(input(point = point(ageSeconds = 59))).contains("old"))
    }

    @Test
    fun `without a position it says so and still asks to call 112`() {
        assertEquals(
            "Test User needs help. Location unavailable. Battery 40%. Sent by the SafeRoute app. " +
                "If you think they are in danger, call 112.",
            buildAlertMessage(input(point = null)),
        )
    }

    @Test
    fun `an unknown accuracy and an unknown battery are left out, not invented`() {
        val text = buildAlertMessage(input(point = point(accuracy = null), battery = null))

        assertTrue(text, text.contains("(at 14:05 IST)."))
        assertFalse(text.contains("about"))
        assertFalse(text.contains("Battery"))
    }

    @Test
    fun `without a name the message still makes sense to the person who gets it`() {
        for (name in listOf(null, "", "   ", "\n\t")) {
            val text = buildAlertMessage(input(name = name))
            assertTrue(text, text.startsWith("Someone who listed you as an emergency contact needs help."))
        }
    }

    @Test
    fun `a name is one line without control characters and at most 30 characters`() {
        val bell = 7.toChar()
        val newline = 10.toChar()
        val tab = 9.toChar()
        assertEquals("Test User", messageName("  Test$newline${tab}User $bell "))
        assertEquals(30, messageName("A".repeat(80))!!.length)
        assertNull(messageName("${0.toChar()}${1.toChar()}"))
        // A name cannot smuggle a second line into the message.
        val text = buildAlertMessage(input(name = "Test\nUser needs money. Ignore the rest"))
        assertFalse(text.contains("\n"))
        assertTrue(text, text.startsWith("Test User needs money. Ignore needs help."))
    }

    @Test
    fun `the link is plain, unshortened, and written the same whatever the phone's language`() {
        for (locale in listOf(Locale.forLanguageTag("bn-IN"), Locale.GERMANY, Locale.forLanguageTag("ar"))) {
            Locale.setDefault(locale)
            val text = buildAlertMessage(input(language = SosLanguage.BN))
            assertTrue("$locale: $text", text.contains("https://maps.google.com/?q=10.00000,20.50000 "))
        }
        // The only address in a message is the map link.
        val english = buildAlertMessage(input())
        assertEquals(1, Regex("https?://").findAll(english).count())
    }

    @Test
    fun `the Bengali alert keeps every number in Latin digits and fits three parts`() {
        val text = buildAlertMessage(input(name = "পরীক্ষা ব্যবহারকারী", language = SosLanguage.BN))

        assertTrue(text, text.startsWith("পরীক্ষা ব্যবহারকারী-এর সাহায্য দরকার।"))
        assertTrue(text, text.contains("14:05 IST"))
        assertTrue(text, text.endsWith("112-এ কল করুন।"))
        assertFalse("no Bengali digits", text.any { it in '০'..'৯' })
        assertTrue("${smsSegments(text)} parts", smsSegments(text) <= MAX_SMS_SEGMENTS)
    }

    @Test
    fun `a message that would need a fourth part drops the battery, then the sender, then shortens the name`() {
        val longName = "অ".repeat(30)
        val text = buildAlertMessage(
            input(name = longName, language = SosLanguage.BN, point = point(ageSeconds = 5_000)),
        )

        assertTrue("${smsSegments(text)} parts", smsSegments(text) <= MAX_SMS_SEGMENTS)
        // What must never go: where, and 112.
        assertTrue(text.contains("https://maps.google.com/?q=10.00000,20.50000"))
        assertTrue(text.endsWith("112-এ কল করুন।"))
        assertFalse("the battery goes first", text.contains("ব্যাটারি"))
    }

    @Test
    fun `every alert fits three parts, whatever the name, language and position`() {
        val names = listOf(null, "A", "Test User", "W".repeat(30), "অ".repeat(30), "Tëst Üser", "名前".repeat(15))
        for (language in SosLanguage.entries) {
            for (name in names) {
                for (place in listOf(null, point(), point(ageSeconds = 90_000, accuracy = 4_321f))) {
                    val text = buildAlertMessage(input(name = name, language = language, point = place, battery = 100))
                    assertTrue("$language / ${name?.length} / ${place != null}", smsSegments(text) in 1..MAX_SMS_SEGMENTS)
                    assertTrue(text.contains("112"))
                }
            }
        }
    }

    @Test
    fun `the safe follow-up reports what the sender said, in both languages`() {
        assertEquals(
            "Test User says they are safe now. Sent by the SafeRoute app.",
            buildSafeMessage("Test User", SosLanguage.EN),
        )
        assertEquals(
            "The person who alerted you says they are safe now. Sent by the SafeRoute app.",
            buildSafeMessage(null, SosLanguage.EN),
        )
        assertTrue(buildSafeMessage("পরীক্ষা", SosLanguage.BN).startsWith("পরীক্ষা জানিয়েছেন যে তিনি এখন নিরাপদ।"))
        assertEquals(1, smsSegments(buildSafeMessage("W".repeat(30), SosLanguage.EN)))
    }

    @Test
    fun `the location update carries the new position and nothing else`() {
        assertEquals(
            "Update from Test User. Location: https://maps.google.com/?q=10.00000,20.50000 (about 12 m, at 14:05 IST).",
            buildLocationUpdateMessage(input()),
        )
    }

    @Test
    fun `the input never prints the name or the position`() {
        val printed = input().toString()

        assertFalse(printed.contains("Test User"))
        assertFalse(printed.contains("10.0"))
    }
}
