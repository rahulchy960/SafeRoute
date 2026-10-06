// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import com.saferoute.app.core.network.apiConfigFromBuild
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two walls around the map (ADR 0015).
 *
 * 1. Portability: the map library is used in ONE file. Screens, ViewModels and tests see
 *    `MapEngine`, `MapController` and plain data classes, so the library can be replaced
 *    without touching them.
 * 2. Privacy: map traffic and API traffic never share an HTTP client, so the sign-in token
 *    cannot reach the tile provider.
 *
 * Plain JVM test that reads the source files; Gradle runs unit tests with `android/app` as the
 * working directory.
 */
class MapLibreBoundaryTest {

    private val mapLibreFile = "src/main/java/com/saferoute/app/core/map/MapLibreEngine.kt"
    private val libraryReference = Regex("""org\.maplibre""")

    private fun kotlinFiles(vararg roots: String): List<File> = roots.flatMap { root ->
        File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun File.codeLines(): List<String> =
        readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }

    @Test
    fun `only one file refers to the map library`() {
        val all = kotlinFiles("src/main", "src/debug", "src/release", "src/test", "src/testDebug")
        assertTrue("sources not found", all.size > 50)

        val users = all
            .filter { file -> file.readLines().any(libraryReference::containsMatchIn) }
            .map { it.invariantSeparatorsPath }
            // This test names the package in order to look for it.
            .filterNot { it.endsWith("MapLibreBoundaryTest.kt") }

        assertEquals(listOf(mapLibreFile), users)
    }

    @Test
    fun `the map vocabulary used by screens has no library or android view types`() {
        val types = File("src/main/java/com/saferoute/app/core/map/MapTypes.kt").readText()

        assertFalse(types.contains("import android."))
        assertFalse(types.contains("import org."))
    }

    @Test
    fun `the map package never touches the api client or its http stack`() {
        val offenders = kotlinFiles("src/main/java/com/saferoute/app/core/map")
            .flatMap { file -> file.codeLines().map { file.name to it } }
            .filter { (_, line) ->
                line.contains("core.network") ||
                    line.contains("okhttp3") ||
                    line.contains("retrofit2") ||
                    // MapLibre's hook for swapping in another HTTP client.
                    line.contains("setOkHttpClient")
            }

        assertEquals(emptyList<Pair<String, String>>(), offenders)
    }

    @Test
    fun `the api client is not handed to anything outside core network`() {
        // Whoever asks Hilt for the OkHttpClient gets the one with the token interceptor.
        val users = kotlinFiles("src/main", "src/debug", "src/release")
            .filter { file -> file.codeLines().any { it.contains("OkHttpClient") } }
            .map { it.invariantSeparatorsPath }
            .filterNot { it.contains("/core/network/") }

        assertEquals(emptyList<String>(), users)
    }

    @Test
    fun `the tile provider is not an api host, so the token interceptor skips it`() {
        val api = apiConfigFromBuild(
            baseUrl = "https://example.invalid/",
            isConfigured = true,
            versionName = "test",
            debug = false,
        )
        val style = MapProviderConfig(MapKey(FAKE_MAP_KEY)).styleUrl(MapStyleVariant.Light)!!

        assertFalse(api.isApiRequest(style.reveal().toHttpUrl()))
    }
}
