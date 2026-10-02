// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * What the network layer needs to know about this build.
 *
 * @property baseUrl where the SafeRoute API lives. Never shown in the UI and never logged.
 * @property isConfigured false when the build had no `saferoute.apiBaseUrl` and [baseUrl] is the
 * placeholder that resolves nowhere. The UI may show this as "no server configured".
 * @property versionName the app version, sent in the `User-Agent` header.
 * @property debugLogging true in debug builds only: one safe line per request in Logcat.
 */
data class ApiConfig(
    val baseUrl: HttpUrl,
    val isConfigured: Boolean,
    val versionName: String,
    val debugLogging: Boolean,
) {
    /**
     * True when [url] has the same scheme, host and port as the API. Tokens are attached only
     * to such requests: any other server (a map tile provider, say) must never see one.
     */
    fun isApiRequest(url: HttpUrl): Boolean =
        url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port

    // The generated toString() would print the URL.
    override fun toString(): String = "ApiConfig(isConfigured=$isConfigured)"
}

private val apiBaseUrlPattern = Regex("""https://[^\s"\\]+/""")

/**
 * Explains what is wrong with a base URL, or returns null when it is fine. The rule (HTTPS, a
 * trailing slash, no spaces, quotes or backslashes) is the same one `app/build.gradle.kts`
 * applies when the build starts. The message never contains the value.
 */
fun apiBaseUrlProblem(value: String): String? = when {
    !value.startsWith("https://") -> "The API base URL must start with https://."
    !value.endsWith("/") -> "The API base URL must end with /."
    !apiBaseUrlPattern.matches(value) ->
        "The API base URL must not contain spaces, quotes or backslashes."
    else -> null
}

/** Builds the [ApiConfig] from `BuildConfig` values, refusing a base URL that breaks the rule. */
fun apiConfigFromBuild(
    baseUrl: String,
    isConfigured: Boolean,
    versionName: String,
    debug: Boolean,
): ApiConfig {
    apiBaseUrlProblem(baseUrl)?.let { error(it) }
    return ApiConfig(baseUrl.toHttpUrl(), isConfigured, versionName, debugLogging = debug)
}
