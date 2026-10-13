// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walls around crash reporting (ADR 0028).
 *
 * Plain JVM test that reads the source files; Gradle runs unit tests with `android/app` as
 * the working directory.
 */
class DiagnosticsBoundaryTest {
    private val backendFile = "src/main/java/com/saferoute/app/core/diagnostics/CrashlyticsBackend.kt"
    private val diagnostics = "src/main/java/com/saferoute/app/core/diagnostics"

    private fun kotlinFiles(vararg roots: String): List<File> = roots.flatMap { root ->
        File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun File.codeLines(): List<String> =
        readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }

    private fun File.code(): String = codeLines().joinToString("\n")

    @Test
    fun `only one file uses the crash reporting library`() {
        val production = kotlinFiles("src/main", "src/debug", "src/release")
        assertTrue("sources not found", production.size > 50)
        val users = production
            .filter { file -> file.codeLines().any { it.contains("com.google.firebase.crashlytics") } }
            .map { it.invariantSeparatorsPath }
        assertEquals(listOf(backendFile), users)
    }

    @Test
    fun `the one file names nobody, sends no text and forces no upload`() {
        val code = File(backendFile).code()
        for (call in listOf("setUserId", ".log(", "sendUnsentReports", "setCustomKeys", "checkForUnsentReports")) {
            assertFalse("the backend calls $call", code.contains(call))
        }
        // Everything it records has been through the redaction first.
        assertTrue(code.contains("recordNonFatal(error: RedactedError)"))
        assertFalse(code.contains("Throwable"))
    }

    @Test
    fun `no analytics, performance or remote configuration library is used or declared`() {
        val banned = listOf(
            "firebase.analytics", "firebase-analytics", "firebase.perf", "firebase-perf",
            "firebase.remoteconfig", "firebase-config", "play-services-measurement", "firebase-messaging",
        )
        val sources = kotlinFiles("src/main", "src/debug", "src/release").map { it.readText() }
        val build = listOf("build.gradle.kts", "../build.gradle.kts", "../gradle/libs.versions.toml")
            .map { File(it).readLines().filterNot { line -> line.trimStart().startsWith("//") || line.trimStart().startsWith("#") } }
            .map { it.joinToString("\n") }
        for (name in banned) {
            assertTrue("$name is used", (sources + build).none { it.contains(name) })
        }
    }

    @Test
    fun `mapping upload is off for both build types and the manifest switches collection off`() {
        val build = File("build.gradle.kts").readText()
        assertEquals(2, Regex("""mappingFileUploadEnabled = false""").findAll(build).count())
        assertFalse(build.contains("mappingFileUploadEnabled = true"))
        val manifest = File("src/main/AndroidManifest.xml").readText().replace(Regex("""\s+"""), " ")
        assertTrue(
            manifest.contains(
                """<meta-data android:name="firebase_crashlytics_collection_enabled" android:value="false" />""",
            ),
        )
    }

    @Test
    fun `diagnostics code never logs, opens a connection or writes a file`() {
        val files = kotlinFiles(diagnostics)
        assertTrue(files.size >= 7)
        val banned = listOf(
            "android.util.Log", "println(", "okhttp3", "retrofit2", "java.net.", "java.io.File",
            "SharedPreferences", "androidx.room", "FileOutputStream",
        )
        for (file in files) {
            val code = file.code()
            for (name in banned) assertFalse("${file.name} uses $name", code.contains(name))
        }
    }

    @Test
    fun `the reporter offers no way to send free text or to name a user`() {
        val reporter = File("$diagnostics/DiagnosticsReporter.kt").code()
        val declared = Regex("""fun (\w+)\(([^)]*)\)""").findAll(reporter.substringBefore("interface CrashBackend"))
            .map { "${it.groupValues[1]}(${it.groupValues[2]})" }.toList()
        assertEquals(
            listOf(
                "setCollecting(enabled: Boolean)",
                "setKey(key: DiagnosticKey, value: String)",
                "report(event: DiagnosticEvent)",
                "report(error: Throwable, category: ErrorCategory)",
            ),
            declared,
        )
        assertFalse(reporter.contains("userId", ignoreCase = true))
    }
}
