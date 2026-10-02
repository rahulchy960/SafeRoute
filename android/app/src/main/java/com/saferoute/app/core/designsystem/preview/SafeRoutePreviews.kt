// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.preview

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/**
 * Put this on a preview function instead of `@Preview` to get the four renderings every
 * SafeRoute screen and component must survive: light, dark, Bengali, and 200% font size.
 *
 * A preview is a composable that Android Studio draws in the editor (Split or Design view)
 * without running the app. An annotation that carries several `@Preview`s is called a
 * multipreview.
 */
@Preview(name = "1 Light", showBackground = true)
@Preview(name = "2 Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "3 Bengali", showBackground = true, locale = "bn")
@Preview(name = "4 Font 200%", showBackground = true, fontScale = 2f)
annotation class SafeRoutePreviews
