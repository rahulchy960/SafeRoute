// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.emergency

import java.io.File
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alert texts are mirrored in `docs/legal/sos-sms-text-v1.md` for the lawyer and the
 * Bengali reviewer. This test builds the messages and fails when a fixed sentence of the code
 * is not in the document, so the two cannot drift apart unnoticed.
 */
class SosSmsTextDocumentTest {

    // Gradle runs unit tests with android/app as the working directory.
    private val document = File("../../docs/legal/sos-sms-text-v1.md").readText()
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .lines()
        .joinToString(" ") { it.trim().removePrefix(">").trim() }
        .replace(Regex(" +"), " ")

    private val now: Instant = Instant.parse("2026-10-09T08:35:00Z")

    /** The sentences of a message that hold no filled-in value. */
    private fun fixedSentences(text: String): List<String> =
        text.split(Regex("(?<=[.।]) "))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.none(Char::isDigit) || it.contains("112") }

    @Test
    fun `every fixed sentence of the messages is in the document, in both languages`() {
        val missing = mutableListOf<String>()
        for (language in SosLanguage.entries) {
            val texts = listOf(
                buildAlertMessage(SosMessageInput(null, language, null, now, null)),
                buildSafeMessage(null, language),
                buildLocationUpdateMessage(SosMessageInput(null, language, null, now, null)),
            )
            for (sentence in texts.flatMap(::fixedSentences)) {
                if (!document.contains(sentence)) missing += sentence
            }
        }

        assertTrue("Not in docs/legal/sos-sms-text-v1.md: $missing", missing.isEmpty())
    }

    @Test
    fun `the sentences that carry a name are in the document with a placeholder`() {
        val name = "<Name>"
        val expected = listOf(
            "$name needs help.",
            "$name-এর সাহায্য দরকার।",
            "$name says they are safe now.",
            "$name জানিয়েছেন যে তিনি এখন নিরাপদ।",
            "Update from $name.",
            "$name-এর নতুন তথ্য।",
        )

        assertTrue(expected.filterNot(document::contains).toString(), expected.all(document::contains))
        // And the code really writes them that way.
        assertTrue(buildAlertMessage(SosMessageInput("X", SosLanguage.EN, null, now, null)).startsWith("X needs help."))
        assertTrue(buildAlertMessage(SosMessageInput("X", SosLanguage.BN, null, now, null)).startsWith("X-এর সাহায্য দরকার।"))
        assertTrue(buildSafeMessage("X", SosLanguage.BN).startsWith("X জানিয়েছেন যে তিনি এখন নিরাপদ।"))
        assertTrue(buildLocationUpdateMessage(SosMessageInput("X", SosLanguage.BN, null, now, null)).startsWith("X-এর নতুন তথ্য।"))
    }

    @Test
    fun `the document says that it is a draft and that nothing is sent yet`() {
        assertTrue(document.contains("draft, to be verified by a lawyer"))
        assertTrue(document.contains("No message is sent by any build yet"))
    }
}
