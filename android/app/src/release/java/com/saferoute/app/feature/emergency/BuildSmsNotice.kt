// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.saferoute.app.core.emergency.SmsLimitNotice

/*
 * Release builds show no banner about who is texted: they text every emergency contact, as
 * the notice the user agreed to says. This stands in for the debug version in
 * src/debug/.../BuildSmsNotice.kt and shows nothing, so the banner and its strings are not
 * compiled into a release build at all.
 */

@Suppress("unused", "UNUSED_PARAMETER")
@Composable
fun BuildSmsNotice(notice: SmsLimitNotice?, modifier: Modifier = Modifier) = Unit
