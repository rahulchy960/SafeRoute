// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.emergency.SosLocationReport
import kotlinx.coroutines.launch

/** How long the button must be held before the countdown starts (Plan v7 section 7.1). */
const val HOLD_TO_ARM_MILLIS = 2_000

/** The Cancel button of the countdown is at least this tall: it must be easy to hit. */
val SosCancelMinHeight: Dp = 72.dp

const val HoldToArmTag = "sos-hold-to-arm"
const val SosCancelTag = "sos-cancel"
const val SosCountdownNumberTag = "sos-countdown-number"

private val HoldButtonSize = 168.dp
private const val HAPTIC_STEPS = 4
private const val SECONDS_PER_MINUTE = 60

/**
 * "Hold to start SOS": a red circle that must be held for [holdMillis]. A ring fills while it
 * is held, with a light vibration at each quarter; lifting the finger early cancels and
 * empties the ring. Only a completed hold calls [onArmed].
 *
 * A person who cannot hold a finger down (TalkBack, a switch) starts the countdown with the
 * button's accessibility action instead; the 5-second countdown with its Cancel button is
 * then the safeguard, as it is for the shortcuts outside the app.
 */
@Composable
fun HoldToArmButton(
    onArmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = HOLD_TO_ARM_MILLIS,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val armed = rememberUpdatedState(onArmed)
    val label = stringResource(R.string.sos_arm_hold)
    val action = stringResource(R.string.sos_arm_hold_action)

    Box(
        modifier = modifier
            .size(HoldButtonSize)
            .testTag(HoldToArmTag)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick(label = action) {
                    armed.value()
                    true
                }
            }
            .pointerInput(holdMillis) {
                detectTapGestures(
                    onPress = {
                        val hold = scope.launch {
                            var step = 0
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            progress.animateTo(1f, tween(holdMillis, easing = LinearEasing)) {
                                val reached = (value * HAPTIC_STEPS).toInt()
                                if (reached > step) {
                                    step = reached
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                            armed.value()
                        }
                        // Returns when the finger is lifted or the touch is taken away.
                        tryAwaitRelease()
                        hold.cancel()
                        progress.snapTo(0f)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(SafeRouteTheme.spacing.xs),
            shape = CircleShape,
            color = SafeRouteTheme.colors.sos,
            contentColor = SafeRouteTheme.colors.onSos,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = label,
                    modifier = Modifier.padding(SafeRouteTheme.spacing.md).clearAndSetSemantics { },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        // The ring is progress, not decoration: it is the sign that the hold is being counted.
        CircularProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
            color = MaterialTheme.colorScheme.onSurface,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/**
 * The frame every SOS screen shares: an optional PRACTICE banner on top, the screen's own
 * content, and at the bottom the sentence "SafeRoute is not an emergency service" with a
 * Call 112 button. Scrolls, so nothing is cut off at 200% font size or in landscape.
 *
 * @param dialerMissing the phone has no app that can dial: the number is shown instead.
 */
@Composable
private fun SosFrame(
    practice: Boolean,
    dialerMissing: Boolean,
    onCall112: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(SafeRouteTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.md),
        ) {
            if (practice) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = stringResource(R.string.sos_practice_banner),
                        modifier = Modifier.padding(SafeRouteTheme.spacing.sm),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            content()
            Text(
                text = stringResource(R.string.sos_not_emergency_service_call),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (dialerMissing) {
                Text(
                    text = stringResource(R.string.emergency_dialog_no_dialer),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            } else {
                Button(
                    onClick = onCall112,
                    modifier = Modifier.fillMaxWidth().heightIn(min = SosCancelMinHeight),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SafeRouteTheme.colors.sos,
                        contentColor = SafeRouteTheme.colors.onSos,
                    ),
                ) {
                    Text(text = stringResource(R.string.emergency_dialog_call), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun SosTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}

/** The 5-second countdown: a big number, a big Cancel, Call 112. */
@Composable
fun SosCountdownScreen(
    secondsLeft: Int,
    practice: Boolean,
    dialerMissing: Boolean,
    onCancel: () -> Unit,
    onCall112: () -> Unit,
) {
    SosFrame(practice = practice, dialerMissing = dialerMissing, onCall112 = onCall112) {
        SosTitle(stringResource(R.string.sos_countdown_title))
        val spoken = stringResource(R.string.sos_countdown_seconds_description, secondsLeft)
        Text(
            text = secondsLeft.toString(),
            modifier = Modifier
                .testTag(SosCountdownNumberTag)
                // TalkBack reads each new number by itself.
                .semantics {
                    contentDescription = spoken
                    liveRegion = LiveRegionMode.Assertive
                },
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(text = stringResource(R.string.sos_countdown_explain), textAlign = TextAlign.Center)
        // Not red: red is the emergency action. Cancel is the calm, large one.
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().heightIn(min = SosCancelMinHeight).testTag(SosCancelTag),
        ) {
            Text(text = stringResource(R.string.sos_countdown_cancel), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun locationLine(report: SosLocationReport): String = when (report) {
    SosLocationReport.Precise -> stringResource(R.string.sos_location_precise)
    SosLocationReport.Approximate -> stringResource(R.string.sos_location_approximate)
    SosLocationReport.Searching -> stringResource(R.string.sos_location_searching)
    SosLocationReport.NoPermission -> stringResource(R.string.sos_location_no_permission)
    SosLocationReport.Unavailable -> stringResource(R.string.sos_location_unavailable)
    is SosLocationReport.Stale ->
        if (report.ageSeconds < SECONDS_PER_MINUTE) {
            stringResource(R.string.sos_location_stale_seconds, report.ageSeconds.toInt())
        } else {
            stringResource(R.string.sos_location_stale_minutes, (report.ageSeconds / SECONDS_PER_MINUTE).toInt())
        }
}

/**
 * The emergency is running. Every line is a status in words; none is a name, a number or a
 * place, so the screen reads the same on the lock screen ([locked]). What is locked is the
 * way out: "I'm safe" asks for the unlock first.
 */
@Composable
fun SosActiveScreen(
    state: SosUi.Active,
    locked: Boolean,
    dialerMissing: Boolean,
    onSafe: () -> Unit,
    onSafeConfirm: () -> Unit,
    onSafeDismiss: () -> Unit,
    onContinue: () -> Unit,
    onCall112: () -> Unit,
) {
    SosFrame(practice = state.practice, dialerMissing = dialerMissing, onCall112 = onCall112) {
        SosTitle(stringResource(R.string.sos_active_title))
        Text(text = stringResource(R.string.sos_active_contacts_none), textAlign = TextAlign.Center)
        if (!state.practice) {
            Text(text = locationLine(state.location), textAlign = TextAlign.Center)
            if (state.notificationsOff) {
                Text(text = stringResource(R.string.sos_active_notifications_off), textAlign = TextAlign.Center)
            }
        }
        OutlinedButton(
            onClick = onSafe,
            modifier = Modifier.fillMaxWidth().heightIn(min = SosCancelMinHeight),
        ) {
            Text(
                text = stringResource(if (locked) R.string.sos_active_safe_locked else R.string.sos_active_safe),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (state.confirmingSafe) {
        AlertDialog(
            onDismissRequest = onSafeDismiss,
            title = { Text(stringResource(R.string.sos_safe_confirm_title)) },
            text = { Text(stringResource(R.string.sos_safe_confirm_body)) },
            confirmButton = { Button(onClick = onSafeConfirm) { Text(stringResource(R.string.sos_safe_confirm_yes)) } },
            dismissButton = { TextButton(onClick = onSafeDismiss) { Text(stringResource(R.string.sos_safe_confirm_no)) } },
        )
    } else if (state.recovered) {
        AlertDialog(
            onDismissRequest = onContinue,
            title = { Text(stringResource(R.string.sos_recovered_title)) },
            text = { Text(stringResource(R.string.sos_recovered_body)) },
            confirmButton = { Button(onClick = onContinue) { Text(stringResource(R.string.sos_recovered_continue)) } },
            dismissButton = { TextButton(onClick = onSafe) { Text(stringResource(R.string.sos_active_safe)) } },
        )
    }
}

/** A countdown whose end passed while the app was closed: the user decides, nobody else. */
@Composable
fun SosAskScreen(
    dialerMissing: Boolean,
    onSendNow: () -> Unit,
    onCancel: () -> Unit,
    onCall112: () -> Unit,
) {
    SosFrame(practice = false, dialerMissing = dialerMissing, onCall112 = onCall112) {
        SosTitle(stringResource(R.string.sos_ask_title))
        Text(text = stringResource(R.string.sos_ask_body), textAlign = TextAlign.Center)
        Button(onClick = onSendNow, modifier = Modifier.fillMaxWidth().heightIn(min = SosCancelMinHeight)) {
            Text(text = stringResource(R.string.sos_ask_send), style = MaterialTheme.typography.titleLarge)
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().heightIn(min = SosCancelMinHeight).testTag(SosCancelTag),
        ) {
            Text(text = stringResource(R.string.sos_countdown_cancel), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun SosPracticeFinishedScreen(onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(SafeRouteTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SafeRouteTheme.spacing.md),
        ) {
            SosTitle(stringResource(R.string.sos_practice_finished_title))
            Text(text = stringResource(R.string.sos_practice_finished_body), textAlign = TextAlign.Center)
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = SosCancelMinHeight)) {
                Text(text = stringResource(R.string.sos_practice_close), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
