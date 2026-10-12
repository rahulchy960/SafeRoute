// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import com.saferoute.app.core.emergency.SmsRecipientLimit
import com.saferoute.app.core.emergency.UnlimitedSmsRecipients
import javax.inject.Inject

/*
 * Release builds set no limit on who is texted: every emergency contact of the user gets the
 * alert. This stands in for the debug version in src/debug/.../BuildSmsRecipientLimit.kt,
 * which allows only a list of test numbers. The Gradle property saferoute.debugSmsAllowlist
 * is not read by a release build, and a release build has no BuildConfig field for it.
 */

/** The limit of this build: none. */
class BuildSmsRecipientLimit @Inject constructor() : SmsRecipientLimit by UnlimitedSmsRecipients
