// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.onboarding

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.preview.SafeRoutePreviews
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.session.LOCALE_BENGALI
import com.saferoute.app.core.session.LOCALE_ENGLISH
import com.saferoute.app.core.session.NOTICE_VERSION
import com.saferoute.app.core.session.toSupportedLocale
import java.util.Locale

/** Test tag of the scrolling notice text. */
const val NOTICE_SCROLL_TAG = "notice-scroll"

/** One section of the notice: a heading and a paragraph. */
data class NoticeSection(@param:StringRes val title: Int, @param:StringRes val body: Int)

/**
 * The consent notice for `account_core`, in order. The same list is used for both languages,
 * so English and Bengali always have the same sections; `docs/legal/consent-notice-v1.md`
 * mirrors the text. Changing the text means changing [NOTICE_VERSION] too.
 */
val NOTICE_SECTIONS: List<NoticeSection> = listOf(
    NoticeSection(R.string.notice_s1_title, R.string.notice_s1_body),
    NoticeSection(R.string.notice_s2_title, R.string.notice_s2_body),
    NoticeSection(R.string.notice_s3_title, R.string.notice_s3_body),
    NoticeSection(R.string.notice_s4_title, R.string.notice_s4_body),
    NoticeSection(R.string.notice_s5_title, R.string.notice_s5_body),
    NoticeSection(R.string.notice_s6_title, R.string.notice_s6_body),
    NoticeSection(R.string.notice_s7_title, R.string.notice_s7_body),
    NoticeSection(R.string.notice_s8_title, R.string.notice_s8_body),
)

/**
 * The consent notice (ADR 0010). It comes before the phone number is asked for.
 *
 * - Nothing is pre-selected. "I agree" is the only way forward.
 * - **"I agree" is enabled only after the notice has been scrolled to its end** (or when it
 *   fits on the screen without scrolling). A short hint says so until then.
 * - "I don't agree" shows an explanation and does nothing else: nothing is stored or sent.
 * - The language switch changes this screen only. It does not change the app's language.
 *
 * How the switch works: [localizedContext] builds a copy of the context whose resources are in
 * the chosen language, and the `CompositionLocalProvider` below hands that copy to everything
 * inside, so `stringResource` reads from it.
 *
 * @param showDraftMarker shows "DRAFT" above the text; on in debug builds while the text awaits
 * a lawyer's review.
 * @param onAgree called with the language the notice was shown in (`en` or `bn`).
 */
@Composable
fun ConsentNoticeScreen(
    showDraftMarker: Boolean,
    onAgree: (noticeLocale: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseContext = LocalContext.current
    val appLocale = LocalConfiguration.current.locales[0].language.toSupportedLocale()
    var noticeLocale by rememberSaveable { mutableStateOf(appLocale) }
    var declined by rememberSaveable { mutableStateOf(false) }
    var reachedEnd by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // snapshotFlow watches the scroll position from a coroutine, so the screen is not redrawn
    // for every pixel scrolled. maxValue is Int.MAX_VALUE until the text has been measured.
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue != Int.MAX_VALUE && scrollState.value >= scrollState.maxValue }
            .collect { atEnd -> if (atEnd) reachedEnd = true }
    }

    val localized = remember(baseContext, noticeLocale) { localizedContext(baseContext, noticeLocale) }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalResources provides localized.resources,
        LocalConfiguration provides localized.resources.configuration,
    ) {
        val spacing = SafeRouteTheme.spacing
        Column(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag(NOTICE_SCROLL_TAG)
                    .verticalScroll(scrollState)
                    .padding(horizontal = spacing.lg, vertical = spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.notice_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (showDraftMarker) {
                    Text(
                        text = stringResource(R.string.notice_draft_marker),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                LanguageSwitch(selected = noticeLocale, onSelect = { noticeLocale = it })
                Text(
                    text = stringResource(R.string.notice_intro),
                    style = MaterialTheme.typography.bodyLarge,
                )
                NOTICE_SECTIONS.forEach { section ->
                    Text(
                        text = stringResource(section.title),
                        modifier = Modifier
                            .padding(top = spacing.xs)
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(section.body),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    text = stringResource(R.string.notice_version, NOTICE_VERSION),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg, vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                if (declined) {
                    OnboardingMessage(text = stringResource(R.string.notice_declined))
                } else if (!reachedEnd) {
                    Text(
                        text = stringResource(R.string.notice_scroll_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Button(
                    onClick = { onAgree(noticeLocale) },
                    enabled = reachedEnd,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.notice_agree))
                }
                OutlinedButton(onClick = { declined = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.notice_decline))
                }
            }
        }
    }
}

/**
 * Two chips, each labelled in its own language. A selected chip shows a tick as well as its
 * colour, and TalkBack says "selected".
 */
@Composable
private fun LanguageSwitch(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xxs)) {
        Text(
            text = stringResource(R.string.notice_language_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.xs)) {
            FilterChip(
                selected = selected == LOCALE_ENGLISH,
                onClick = { onSelect(LOCALE_ENGLISH) },
                label = { Text(text = stringResource(R.string.notice_language_english)) },
            )
            FilterChip(
                selected = selected == LOCALE_BENGALI,
                onClick = { onSelect(LOCALE_BENGALI) },
                label = { Text(text = stringResource(R.string.notice_language_bengali)) },
            )
        }
    }
}

/** A copy of [base] whose resources are in [language]; the app's own language is untouched. */
internal fun localizedContext(base: Context, language: String): Context {
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(language))
    return base.createConfigurationContext(configuration)
}

@SafeRoutePreviews
@Composable
private fun ConsentNoticeScreenPreview() {
    SafeRouteTheme { ConsentNoticeScreen(showDraftMarker = true, onAgree = {}) }
}
