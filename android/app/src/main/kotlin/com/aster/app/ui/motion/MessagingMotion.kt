package com.aster.app.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aster.app.domain.model.MessageStatus
import com.aster.app.ui.theme.AsterBorder
import com.aster.app.ui.theme.AsterCrimson
import com.aster.app.ui.theme.AsterMuted
import com.aster.app.ui.theme.AsterOnline
import com.aster.app.ui.theme.AsterSurfaceElevated
import com.aster.app.ui.theme.AsterViolet
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Aster Messaging Motion & Interaction Primitives
// Authoritative source: PHASE_8_ROADMAP.md (Phase 8.3 Messaging Motion)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Design tokens specific to messaging interaction and physics.
 */
object AsterMessagingTokens {
    /** Horizontal swipe distance (64dp) required to commit a reply. */
    val SwipeThresholdDp: Dp = 64.dp

    /** Maximum horizontal displacement allowed during drag before strong resistance stops it. */
    val SwipeMaxDistanceDp: Dp = 96.dp

    /** Vertical translation offset (+12dp) applied during organic message arrival. */
    val ArrivalOffsetDp: Dp = 12.dp

    /** Initial scale factor (0.97f) applied during organic message arrival. */
    const val ArrivalScale: Float = 0.97f

    /** Duration for status checkmark and color transitions (250ms). */
    const val StatusTransitionDuration: Int = AsterMotionTokens.Duration.Short
}

/**
 * Modifier that orchestrates organic entrance motion for newly arrived messages.
 *
 * Incoming or newly sent messages emerge with:
 * - Subtle vertical translation (+12dp -> 0dp) using Settle spring physics
 * - Subtle scale expansion (0.97f -> 1.0f) using Settle spring physics
 * - Smooth decelerating alpha fade-in
 *
 * When [isNewArrival] is false (e.g. restored from database) or [LocalReducedMotion] is true,
 * transitions collapse to immediate static display without replaying.
 */
fun Modifier.asterMessageArrival(
    isNewArrival: Boolean
): Modifier = composed {
    val isReduced = LocalReducedMotion.current

    if (!isNewArrival || isReduced) {
        return@composed this
    }

    var isSettled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isSettled = true
    }

    val targetScale = if (isSettled) AsterMotionTokens.Scale.Normal else AsterMessagingTokens.ArrivalScale
    val targetAlpha = if (isSettled) AsterMotionTokens.Alpha.Opaque else AsterMotionTokens.Alpha.Ghost
    val targetOffsetY = if (isSettled) 0f else AsterMessagingTokens.ArrivalOffsetDp.value

    val springSpec = AsterMotion.spring<Float>(
        spec = AsterMotionTokens.SpringPhysics.Settle,
        reducedMotion = isReduced
    )
    val tweenSpec = AsterMotion.tween<Float>(
        durationMillis = AsterMotionTokens.Duration.Standard,
        easing = AsterMotionTokens.EasingCurve.StandardDecelerate,
        reducedMotion = isReduced
    )

    val scale by animateFloatAsState(targetValue = targetScale, animationSpec = springSpec, label = "MsgArrivalScale")
    val alpha by animateFloatAsState(targetValue = targetAlpha, animationSpec = tweenSpec, label = "MsgArrivalAlpha")
    val offsetY by animateFloatAsState(targetValue = targetOffsetY, animationSpec = springSpec, label = "MsgArrivalOffset")

    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
        translationY = offsetY * density
    }
}

/**
 * Container supporting physical swipe-to-reply gestures.
 *
 * Features:
 * - Movement follows finger with non-linear resistance past [thresholdDp].
 * - Circular reply affordance badge emerges proportionally on the left.
 * - Crossing [thresholdDp] provides a tactile click signal.
 * - Releasing beyond threshold triggers [onReply].
 * - In all release cases, springs smoothly back to resting coordinate (0dp).
 * - Coexists with LazyColumn vertical scrolling without intercepting vertical deltas.
 */
@Composable
fun AsterSwipeToReplyBox(
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thresholdDp: Dp = AsterMessagingTokens.SwipeThresholdDp,
    maxDragDp: Dp = AsterMessagingTokens.SwipeMaxDistanceDp,
    content: @Composable () -> Unit
) {
    val isReduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val thresholdPx = with(density) { thresholdDp.toPx() }
    val maxDragPx = with(density) { maxDragDp.toPx() }
    val haptics = LocalAsterHaptics.current
    val coroutineScope = rememberCoroutineScope()

    val offsetX = remember { Animatable(0f) }
    var rawDragX by remember { mutableStateOf(0f) }
    var hasFiredHaptic by remember { mutableStateOf(false) }

    val dragModifier = if (!enabled) {
        Modifier
    } else {
        Modifier.pointerInput(enabled) {
            detectHorizontalDragGestures(
                onDragStart = {
                    hasFiredHaptic = false
                    rawDragX = 0f
                },
                onHorizontalDrag = { change, dragAmount ->
                    // Only process rightward drag (positive X)
                    if (dragAmount > 0f || rawDragX > 0f) {
                        change.consume()
                        rawDragX = (rawDragX + dragAmount).coerceAtLeast(0f)

                        // Resistance curve past threshold: delta dampened by 75%
                        val computedX = if (rawDragX <= thresholdPx) {
                            rawDragX
                        } else {
                            thresholdPx + (rawDragX - thresholdPx) * 0.25f
                        }.coerceAtMost(maxDragPx)

                        if (computedX >= thresholdPx && !hasFiredHaptic) {
                            hasFiredHaptic = true
                            haptics.perform(AsterHapticEvent.Threshold)
                        } else if (computedX < thresholdPx && hasFiredHaptic) {
                            hasFiredHaptic = false
                        }

                        coroutineScope.launch {
                            offsetX.snapTo(computedX)
                        }
                    }
                },
                onDragEnd = {
                    val triggered = offsetX.value >= thresholdPx
                    if (triggered) {
                        haptics.perform(AsterHapticEvent.Success)
                        onReply()
                    }
                    coroutineScope.launch {
                        if (isReduced) {
                            offsetX.snapTo(0f)
                        } else {
                            offsetX.animateTo(
                                0f,
                                animationSpec = AsterMotion.spring(
                                    AsterMotionTokens.SpringPhysics.Settle,
                                    reducedMotion = isReduced
                                )
                            )
                        }
                        rawDragX = 0f
                        hasFiredHaptic = false
                    }
                },
                onDragCancel = {
                    coroutineScope.launch {
                        if (isReduced) {
                            offsetX.snapTo(0f)
                        } else {
                            offsetX.animateTo(
                                0f,
                                animationSpec = AsterMotion.spring(
                                    AsterMotionTokens.SpringPhysics.Settle,
                                    reducedMotion = isReduced
                                )
                            )
                        }
                        rawDragX = 0f
                        hasFiredHaptic = false
                    }
                }
            )
        }
    }

    Box(modifier = modifier) {
        // Reply Affordance Indicator (revealed as message slides right)
        if (offsetX.value > 2f) {
            val progress = (offsetX.value / thresholdPx).coerceIn(0f, 1f)
            val iconScale = 0.6f + 0.4f * progress
            val isActivated = progress >= 1.0f

            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .size(34.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        alpha = progress
                    }
                    .clip(CircleShape)
                    .background(if (isActivated) AsterViolet.copy(alpha = 0.22f) else AsterSurfaceElevated)
                    .border(
                        width = 1.dp,
                        color = if (isActivated) AsterViolet else AsterBorder.copy(alpha = 0.6f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↩",
                    color = if (isActivated) AsterViolet else AsterMuted,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Message Bubble Content
        Box(
            modifier = Modifier
                .then(dragModifier)
                .graphicsLayer {
                    translationX = offsetX.value
                }
        ) {
            content()
        }
    }
}

/**
 * Animated delivery and read status indicator.
 *
 * Smoothly morphs through actual server/protocol states:
 * - SENDING: subtle dot pulsing
 * - SENT: single checkmark
 * - DELIVERED: double checkmark
 * - READ: double checkmark with smooth color transition to [AsterOnline] (Nebula Teal)
 * - FAILED: exclamation mark
 * - UNSENT: subtle return arrow
 */
@Composable
fun AsterMessageStatusTransition(
    status: MessageStatus,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isReduced = LocalReducedMotion.current

    val targetColor = when (status) {
        MessageStatus.SENDING -> AsterMuted.copy(alpha = 0.6f)
        MessageStatus.SENT -> AsterMuted
        MessageStatus.DELIVERED -> AsterMuted
        MessageStatus.READ -> AsterOnline
        MessageStatus.FAILED -> AsterCrimson
        MessageStatus.UNSENT -> AsterMuted
    }

    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = AsterMotion.tween(
            durationMillis = AsterMessagingTokens.StatusTransitionDuration,
            easing = AsterMotionTokens.EasingCurve.Standard,
            reducedMotion = isReduced
        ),
        label = "StatusIndicatorColor"
    )

    AnimatedContent(
        targetState = status,
        transitionSpec = {
            if (isReduced) {
                fadeIn(animationSpec = AsterMotion.tween(0, reducedMotion = true)) togetherWith
                    fadeOut(animationSpec = AsterMotion.tween(0, reducedMotion = true))
            } else {
                (fadeIn(
                    animationSpec = AsterMotion.tween(
                        durationMillis = AsterMessagingTokens.StatusTransitionDuration,
                        easing = AsterMotionTokens.EasingCurve.StandardDecelerate
                    )
                ) + scaleIn(
                    initialScale = 0.85f,
                    animationSpec = AsterMotion.spring(AsterMotionTokens.SpringPhysics.Release)
                )) togetherWith
                (fadeOut(
                    animationSpec = AsterMotion.tween(
                        durationMillis = AsterMessagingTokens.StatusTransitionDuration,
                        easing = AsterMotionTokens.EasingCurve.StandardAccelerate
                    )
                ) + scaleOut(
                    targetScale = 0.85f,
                    animationSpec = AsterMotion.spring(AsterMotionTokens.SpringPhysics.Release)
                ))
            }
        },
        modifier = modifier,
        label = "MessageStatusContent"
    ) { currentStatus ->
        when (currentStatus) {
            MessageStatus.SENDING -> {
                Text(
                    text = "·",
                    color = animatedColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { contentDescription = "Message sending" }
                )
            }
            MessageStatus.SENT -> {
                Text(
                    text = "✓",
                    color = animatedColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { contentDescription = "Message sent" }
                )
            }
            MessageStatus.DELIVERED -> {
                Text(
                    text = "✓✓",
                    color = animatedColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { contentDescription = "Message delivered" }
                )
            }
            MessageStatus.READ -> {
                Text(
                    text = "✓✓",
                    color = animatedColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { contentDescription = "Message read" }
                )
            }
            MessageStatus.FAILED -> {
                Text(
                    text = "!",
                    color = animatedColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = (if (onRetry != null) Modifier.clickable(onClick = onRetry) else Modifier)
                        .semantics { contentDescription = "Message failed" }
                )
            }
            MessageStatus.UNSENT -> {
                Text(
                    text = "↩",
                    color = animatedColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { contentDescription = "Message unsent" }
                )
            }
        }
    }
}
