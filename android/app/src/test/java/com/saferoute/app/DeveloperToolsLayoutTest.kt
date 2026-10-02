// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The developer tools exist only in debug builds. Gradle compiles `src/main` and `src/release`
 * into a release build, never `src/debug`, so this test checks that nothing outside `src/debug`
 * refers to them. CI also scans the release APK itself (android-ci.yml).
 */
class DeveloperToolsLayoutTest {

    private val developerNames = Regex("""feature\.developer|DeveloperCheck|ServerCheck|developer_check|developer_settings""")

    private fun files(root: String): List<File> {
        val dir = File(root)
        assertTrue("Not found: ${dir.absolutePath}", dir.isDirectory)
        return dir.walkTopDown().filter { it.isFile }.toList()
    }

    @Test
    fun `the screen, its view model and its strings are in the debug source set`() {
        val debug = files("src/debug").map { it.invariantSeparatorsPath }
        listOf(
            "java/com/saferoute/app/feature/developer/DeveloperCheckScreen.kt",
            "java/com/saferoute/app/feature/developer/DeveloperCheckViewModel.kt",
            "java/com/saferoute/app/core/network/ServerCheck.kt",
            "res/values/strings.xml",
            "res/values-bn/strings.xml",
        ).forEach { path -> assertTrue("missing src/debug/$path", debug.any { it.endsWith(path) }) }
    }

    @Test
    fun `nothing compiled into a release build refers to them`() {
        val offenders = (files("src/main") + files("src/release"))
            .filter { it.extension in setOf("kt", "xml") }
            .filter { file -> file.useLines { lines -> lines.any(developerNames::containsMatchIn) } }
            .map { it.invariantSeparatorsPath }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the release hooks add no destination and no row`() {
        val release = File("src/release/java/com/saferoute/app/navigation/DeveloperNavigation.kt").readText()

        assertFalse(release.contains("composable<"))
        assertFalse(release.contains("navigate("))
        assertTrue(release.contains("fun NavGraphBuilder.developerDestinations"))
        assertTrue(release.contains("fun DeveloperSettingsEntry"))
    }
}
