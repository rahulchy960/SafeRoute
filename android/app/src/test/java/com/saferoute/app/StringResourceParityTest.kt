// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * English and Bengali must stay in step: the same keys, the same plural quantities and the
 * same format placeholders (`%1$s`, `%2$d`, ...). A key missing from Bengali would silently
 * show English; a mismatched placeholder would crash at runtime.
 *
 * Plain JVM test that reads the XML files. Gradle runs unit tests with the module folder
 * (`android/app`) as the working directory.
 */
class StringResourceParityTest {

    private val english = parse("src/main/res/values/strings.xml")
    private val bengali = parse("src/main/res/values-bn/strings.xml")

    // Debug builds only: the developer server check (P008b).
    private val debugEnglish = parse("src/debug/res/values/strings.xml")
    private val debugBengali = parse("src/debug/res/values-bn/strings.xml")

    @Test
    fun `debug-only strings have the same keys and placeholders in both languages`() {
        assertEquals(emptySet<String>(), debugEnglish.keys - debugBengali.keys)
        assertEquals(emptySet<String>(), debugBengali.keys - debugEnglish.keys)
        val mismatches = debugEnglish.keys.filter { key ->
            placeholders(debugEnglish.getValue(key)) != placeholders(debugBengali.getValue(key))
        }
        assertEquals(emptyList<String>(), mismatches)
        assertEquals(emptySet<String>(), debugBengali.filterValues { it.isBlank() }.keys)
    }

    @Test
    fun `debug-only strings don't replace app strings`() {
        // A debug key with the same name as a main key would silently change the app's text.
        assertEquals(emptySet<String>(), debugEnglish.keys.intersect(english.keys))
    }

    @Test
    fun `both files have strings`() {
        assertTrue("English strings.xml is empty", english.isNotEmpty())
        assertTrue("Bengali strings.xml is empty", bengali.isNotEmpty())
    }

    @Test
    fun `bengali has exactly the same keys as english`() {
        assertEquals(
            "Missing from values-bn/strings.xml",
            emptySet<String>(),
            english.keys - bengali.keys,
        )
        assertEquals(
            "In values-bn/strings.xml but not in values/strings.xml",
            emptySet<String>(),
            bengali.keys - english.keys,
        )
    }

    @Test
    fun `format placeholders match`() {
        val mismatches = english.keys.intersect(bengali.keys).filter { key ->
            placeholders(english.getValue(key)) != placeholders(bengali.getValue(key))
        }
        assertEquals("Placeholders differ between languages", emptyList<String>(), mismatches)
    }

    @Test
    fun `no bengali string is blank`() {
        val blank = bengali.filterValues { it.isBlank() }.keys
        assertEquals("Blank Bengali strings", emptySet<String>(), blank)
    }

    @Test
    fun `the emergency number is written as 112 in both languages`() {
        // Latin digits on purpose: that is what people know and what the dialer shows.
        listOf(english, bengali).forEach { strings ->
            assertTrue(strings.getValue("string:sos_control_description").contains("112"))
            assertTrue(strings.getValue("string:emergency_dialog_call").contains("112"))
        }
    }

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sdf]""").findAll(text).map { it.value }.sorted().toList()

    /**
     * Returns "string:name" -> text, and for plurals "plurals:name:quantity" -> text, so that
     * a missing quantity (one, other, ...) shows up as a missing key.
     */
    private fun parse(path: String): Map<String, String> {
        val file = File(path)
        assertTrue("Not found: ${file.absolutePath}", file.isFile)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val result = linkedMapOf<String, String>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val element = children.item(i) as? Element ?: continue
            val name = element.getAttribute("name")
            when (element.tagName) {
                "string" -> result["string:$name"] = element.textContent
                "plurals" -> {
                    val items = element.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        result["plurals:$name:${item.getAttribute("quantity")}"] = item.textContent
                    }
                }
            }
        }
        return result
    }
}
