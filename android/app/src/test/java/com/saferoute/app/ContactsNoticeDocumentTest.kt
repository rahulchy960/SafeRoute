// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The `sos_alerts` notice and the invite SMS exist twice: as string resources in the app and
 * as a document a lawyer can read (`docs/legal/sos-alerts-notice-v1.md`). They must say the
 * same thing, word for word, and the document must name the notice version the code sends.
 *
 * Plain JVM test that reads the files. Gradle runs unit tests with `android/app` as the
 * working directory.
 */
class ContactsNoticeDocumentTest {

    private val document = File("../../docs/legal/sos-alerts-notice-v2.md")
    private val versionSource = File("src/main/java/com/saferoute/app/feature/contacts/ContactsRepository.kt")
    private val screenSource = File("src/main/java/com/saferoute/app/feature/contacts/AddContactScreens.kt")

    private val noticeKeys = listOf("contacts_notice_title") + (1..7).map { "contacts_notice_p$it" } +
        listOf("contacts_notice_agree", "contacts_notice_not_now")

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

    /** A resource writes `\'` and `\"`; the document writes the plain characters. */
    private fun plain(resource: String) = resource.replace("\\'", "'").replace("\\\"", "\"")

    @Test
    fun `the document exists and names the notice version the app sends`() {
        assertTrue("Not found: ${document.path}", document.isFile)
        val version = Regex("""const val SOS_ALERTS_NOTICE_VERSION = "([^"]+)"""")
            .find(versionSource.readText())?.groupValues?.get(1)
        assertTrue("SOS_ALERTS_NOTICE_VERSION not found in the code", !version.isNullOrBlank())
        assertTrue("The document must mention $version", document.readText().contains("`$version`"))
    }

    @Test
    fun `every sentence of the English and Bengali notice is in the document, word for word`() {
        val text = document.readText()
        val missing = mutableListOf<String>()
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-bn/strings.xml")) {
            val strings = strings(path)
            for (key in noticeKeys) {
                val expected = plain(strings[key] ?: error("$key is missing from $path"))
                if (!text.contains(expected)) missing += "$path $key: $expected"
            }
        }
        assertEquals("The document and the app's notice differ", emptyList<String>(), missing)
    }

    @Test
    fun `the invite text in both languages is in the document, word for word`() {
        val text = document.readText()
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-bn/strings.xml")) {
            val message = plain(strings(path)["contacts_invite_message"] ?: error("invite text missing from $path"))
            assertTrue("$path: the invite text must end with the link", message.endsWith("%1\$s"))
            assertTrue("$path: the invite text differs", text.contains(message.replace("%1\$s", "`<link>`")))
        }
    }

    @Test
    fun `the screen shows exactly the seven paragraphs the document lists, in order`() {
        val shown = Regex("""R\.string\.(contacts_notice_p\d)""").findAll(screenSource.readText())
            .map { it.groupValues[1] }.toList()

        assertEquals((1..7).map { "contacts_notice_p$it" }, shown)
    }

    @Test
    fun `the document is marked as a draft for a lawyer`() {
        val text = document.readText()
        assertTrue(text.contains("DRAFT"))
        assertTrue(text.contains("lawyer"))
        assertTrue(text.contains("native speaker"))
    }
}
