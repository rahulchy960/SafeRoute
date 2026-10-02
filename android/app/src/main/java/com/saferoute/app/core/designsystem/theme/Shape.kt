// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Material's five shape sizes: 16 dp for cards (`medium`), 28 dp for sheets (`extraLarge`). */
internal val SafeRouteShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Shapes that Material's scale has no slot for. */
object SafeRouteShapeTokens {
    /** Fully rounded ends: the search pill and the emergency button. */
    val Pill: Shape = CircleShape

    /** A bottom sheet: rounded at the top, square where it meets the screen edge. */
    val SheetTop: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}
