// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

const val LOCALE_ENGLISH = "en"
const val LOCALE_BENGALI = "bn"

/** `bn` stays `bn`; everything else is `en`, the app's fallback language. */
fun String.toSupportedLocale(): String = if (this == LOCALE_BENGALI) LOCALE_BENGALI else LOCALE_ENGLISH

/** The language the app's screens are shown in right now: `en` or `bn`. */
fun interface AppLocale {
    fun current(): String
}

/**
 * Reads the language from the app's resources, which follow the per-app language chosen in
 * Android's settings (Android 13+) or the system language.
 */
class ResourcesAppLocale @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppLocale {
    override fun current(): String =
        context.resources.configuration.locales[0].language.toSupportedLocale()
}
