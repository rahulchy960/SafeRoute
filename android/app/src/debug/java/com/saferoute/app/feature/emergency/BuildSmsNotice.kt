// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.emergency.SmsLimitNotice

const val BuildSmsNoticeTag = "build-sms-notice"

/** The sentence of the banner. A count only: never a name or a number. */
@Composable
internal fun debugSmsNoticeText(notice: SmsLimitNotice): String = when {
    !notice.automaticPossible -> stringResource(R.string.debug_sms_banner_no_permission)
    notice.automaticRecipients == 0 -> stringResource(R.string.debug_sms_banner_none)
    else -> stringResource(R.string.debug_sms_banner_sending, notice.automaticRecipients)
}

/**
 * DEBUG builds only: the banner on the arm step, the countdown and the active screen that
 * says what this build will really do with an alert. A debug build sends real SMS, and the
 * person holding the phone must not have to remember that. The release build has a function
 * of the same name in src/release that shows nothing; the strings exist only in debug builds.
 */
@Composable
fun BuildSmsNotice(notice: SmsLimitNotice?, modifier: Modifier = Modifier) {
    if (notice == null) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag(BuildSmsNoticeTag),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = debugSmsNoticeText(notice),
            modifier = Modifier.padding(SafeRouteTheme.spacing.sm),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
