// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.saferoute.app.core.designsystem.theme.MinTouchTarget
import org.junit.Assert.assertTrue

/**
 * Asserts that the area that reacts to a finger is at least 48 dp in both directions.
 *
 * This measures the *touch* bounds, not the drawn size: a Material icon button draws 40 dp but
 * accepts touches on 48 dp, and that is the size accessibility guidance is about.
 */
fun SemanticsNodeInteraction.assertMinTouchTarget(): SemanticsNodeInteraction {
    val node = fetchSemanticsNode()
    val minimum = with(node.layoutInfo.density) { MinTouchTarget.toPx() } - 0.5f
    val touch = node.touchBoundsInRoot
    assertTrue(
        "Touch target is ${touch.width} x ${touch.height} px, needs $minimum px each way",
        touch.width >= minimum && touch.height >= minimum,
    )
    return this
}
