// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The consent notice exists twice: as string resources (what the app shows) and as
 * `docs/legal/consent-notice-v1.md` (what a lawyer and a translator review). This test keeps
 * them identical: every notice string, in both languages, must appear word for word in the
 * document, and the document must name the notice version the code sends.
 *
 * Plain JVM test that reads the files. Gradle runs unit tests with `android/app` as the
 * working directory.
 */
class ConsentNoticeDocumentTest {

    private val document = File("../../docs/legal/consent-notice-v1.md")
    private val versionSource = File("src/main/java/com/saferoute/app/core/session/SessionState.kt")

    /** The notice's own strings, in order. Language names and button labels are not part of it. */
    private val noticeKeys = listOf("notice_title", "notice_intro") +
        (1..8).flatMap { listOf("notice_s${it}_title", "notice_s${it}_body") }

    private fun strings(path: String): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement
        val result = linkedMapOf<String, String>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val element = children.item(i) as? Element ?: continue
            if (element.tagName == "string") result[element.getAttribute("name")] = element.textContent
        }
        return result
    }

    /** One paragraph per line of the string; `\n` in a resource is a line break. */
    private fun paragraphs(text: String): List<String> = text.split("\\n").map { it.trim() }

    @Test
    fun `the document exists and names the notice version the app sends`() {
        assertTrue("Not found: ${document.path}", document.isFile)
        val version = Regex("""const val NOTICE_VERSION = "([^"]+)"""")
            .find(versionSource.readText())?.groupValues?.get(1)
        assertTrue("NOTICE_VERSION not found in the code", !version.isNullOrBlank())
        assertTrue("The document must mention $version", document.readText().contains("`$version`"))
    }

    @Test
    fun `every sentence of the English and Bengali notice is in the document, word for word`() {
        val text = document.readText()
        val missing = mutableListOf<String>()
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-bn/strings.xml")) {
            val strings = strings(path)
            for (key in noticeKeys) {
                val value = strings[key] ?: error("$key is missing from $path")
                for (paragraph in paragraphs(value)) {
                    // Bullets are written as a Markdown list in the document.
                    val expected = paragraph.removePrefix("• ")
                    if (!text.contains(expected)) missing += "$path $key: $expected"
                }
            }
        }
        assertEquals("The document and the app's notice differ", emptyList<String>(), missing)
    }

    @Test
    fun `the document is marked as a draft and keeps both placeholders`() {
        val text = document.readText()
        assertTrue(text.contains("DRAFT"))
        assertTrue(text.contains("[operator name]"))
        assertTrue(text.contains("[grievance contact]"))
        assertTrue(text.contains("lawyer"))
    }
}
