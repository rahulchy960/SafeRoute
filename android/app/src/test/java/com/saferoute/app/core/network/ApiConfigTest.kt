// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The base URL rule (the same one `app/build.gradle.kts` applies when the build starts) and
 * the two files that keep traffic encrypted. Unit tests run with the module folder (`app/`) as
 * the working directory, so the source files can be read directly.
 */
class ApiConfigTest {

    @Test
    fun `a well-formed https URL with a trailing slash is accepted`() {
        assertNull(apiBaseUrlProblem("https://api.invalid/"))
        assertNull(apiBaseUrlProblem("https://example.invalid:8443/api/"))
    }

    @Test
    fun `http, a missing trailing slash and unsafe characters are refused`() {
        assertNotNull(apiBaseUrlProblem("http://example.invalid/"))
        assertNotNull(apiBaseUrlProblem("example.invalid/"))
        assertNotNull(apiBaseUrlProblem(""))
        assertNotNull(apiBaseUrlProblem("https://example.invalid"))
        assertNotNull(apiBaseUrlProblem("https://example.invalid/api"))
        assertNotNull(apiBaseUrlProblem("https://exa mple.invalid/"))
        assertNotNull(apiBaseUrlProblem("https://example.invalid/\"+x+\"/"))
        assertNotNull(apiBaseUrlProblem("https://example.invalid\\/"))
    }

    @Test
    fun `the message never repeats the value`() {
        val value = "http://private-host.invalid/secret-path"

        val message = apiBaseUrlProblem(value).orEmpty()

        assertFalse(message.contains("private-host"))
        assertFalse(message.contains("secret-path"))
    }

    @Test
    fun `apiConfigFromBuild refuses a cleartext URL`() {
        assertThrows(IllegalStateException::class.java) {
            apiConfigFromBuild("http://example.invalid/", true, "1.0", debug = false)
        }
    }

    @Test
    fun `apiConfigFromBuild carries the build's values and hides the URL in toString`() {
        val config = apiConfigFromBuild("https://example.invalid/", false, "1.0", debug = true)

        assertEquals("example.invalid", config.baseUrl.host)
        assertFalse(config.isConfigured)
        assertTrue(config.debugLogging)
        assertFalse(config.toString().contains("example"))
    }

    @Test
    fun `only the same scheme, host and port count as the API`() {
        val config = apiConfigFromBuild("https://example.invalid/", true, "1.0", debug = false)

        assertTrue(config.isApiRequest("https://example.invalid/v1/me".toHttpUrl()))
        assertFalse(config.isApiRequest("http://example.invalid/v1/me".toHttpUrl()))
        assertFalse(config.isApiRequest("https://example.invalid:8443/v1/me".toHttpUrl()))
        assertFalse(config.isApiRequest("https://tiles.example.invalid/v1/me".toHttpUrl()))
        assertFalse(config.isApiRequest("https://example.invalid.evil.invalid/".toHttpUrl()))
    }

    private fun xml(path: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(File(path)).documentElement
    }

    private fun Element.children(name: String): List<Element> {
        val nodes = getElementsByTagName(name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `the network security config refuses cleartext for every host`() {
        val config = xml("src/main/res/xml/network_security_config.xml")

        val base = config.children("base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        // No host is an exception, and debug builds get no extra trust.
        assertEquals(0, config.children("domain-config").size)
        assertEquals(0, config.children("debug-overrides").size)
        assertEquals(listOf("system"), config.children("certificates").map { it.getAttribute("src") })
    }

    @Test
    fun `the manifest uses the network security config and does not allow cleartext`() {
        val android = "http://schemas.android.com/apk/res/android"
        val application = xml("src/main/AndroidManifest.xml").children("application").single()

        assertEquals(
            "@xml/network_security_config",
            application.getAttributeNS(android, "networkSecurityConfig"),
        )
        assertFalse(application.getAttributeNS(android, "usesCleartextTraffic") == "true")
    }
}
