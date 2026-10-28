package com.aster.app.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.aster.app.ui.theme.AsterNight

// ─────────────────────────────────────────────────────────────────────────────
// Aster Interaction Motion Primitives
// Authoritative source: PHASE_8_ROADMAP.md (Phase 8.1 Motion Foundation)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Reusable press interaction scale modifier.
 *
 * Provides subtle tactile compression (`0.96f`) on press, cleanly recovering to (`1.0f`)
 * on release using Aster spring physics.
 *
 * Automatically respects [LocalReducedMotion] by remaining static when reduced motion is preferred.
 */
fun Modifier.asterPressable(
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    pressedScale: Float = AsterMotionTokens.Scale.Pressed,
    hapticEvent: AsterHapticEvent? = AsterHapticEvent.Press,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val isReduced = LocalReducedMotion.current
    val haptics = LocalAsterHaptics.current
    val effectiveSource = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by effectiveSource.collectIsPressedAsState()

    val targetScale = if (enabled && isPressed && !isReduced) pressedScale else AsterMotionTokens.Scale.Normal
    val springSpec = if (isPressed) {
        AsterMotion.spring<Float>(AsterMotionTokens.SpringPhysics.Press, reducedMotion = isReduced)
    } else {
        AsterMotion.spring<Float>(AsterMotionTokens.SpringPhysics.Release, reducedMotion = isReduced)
    }

    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = springSpec,
        label = "AsterPressableScale"
    )

    this
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = effectiveSource,
                    indication = null,
                    enabled = enabled,
                    onClick = {
                        if (hapticEvent != null) {
                            haptics.perform(hapticEvent)
                        }
                        onClick()
                    }
                )
            } else {
                Modifier
            }
        )
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
        }
}

/**
 * Remembers a press scale state for custom canvas or composable drawing.
 */
@Composable
fun rememberAsterPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = AsterMotionTokens.Scale.Pressed
): State<Float> {
    val isReduced = LocalReducedMotion.current
    val isPressed by interactionSource.collectIsPressedAsState()
    val targetScale = if (isPressed && !isReduced) pressedScale else AsterMotionTokens.Scale.Normal
    val springSpec = if (isPressed) {
        AsterMotion.spring<Float>(AsterMotionTokens.SpringPhysics.Press, reducedMotion = isReduced)
    } else {
        AsterMotion.spring<Float>(AsterMotionTokens.SpringPhysics.Release, reducedMotion = isReduced)
    }

    return animateFloatAsState(
        targetValue = targetScale,
        animationSpec = springSpec,
        label = "AsterCanvasPressScale"
    )
}

/**
 * Output properties for focus and elevation transitions.
 */
data class AsterFocusProperties(
    val scale: Float,
    val alpha: Float,
    val elevationDp: Float,
    val glowIntensity: Float
)

/**
 * Reusable motion state for `normal → focused → normal` transitions.
 *
 * Will be utilized by future phases for:
 * - Message context elevation
 * - Constellation node selection
 * - Highlight emphasis
 */
@Composable
fun rememberAsterFocusMotion(
    isFocused: Boolean,
    focusedScale: Float = AsterMotionTokens.Scale.Focused,
    focusedElevationDp: Float = 8f,
    focusedGlowIntensity: Float = 1.0f
): AsterFocusProperties {
    val isReduced = LocalReducedMotion.current

    val targetScale = if (isFocused && !isReduced) focusedScale else AsterMotionTokens.Scale.Normal
    val targetAlpha = if (isFocused) AsterMotionTokens.Alpha.Opaque else AsterMotionTokens.Alpha.Subtle
    val targetElevation = if (isFocused && !isReduced) focusedElevationDp else 0f
    val targetGlow = if (isFocused && !isReduced) focusedGlowIntensity else 0f

    val springSpec = AsterMotion.spring<Float>(AsterMotionTokens.SpringPhysics.Expansion, reducedMotion = isReduced)
    val tweenSpec = AsterMotion.tween<Float>(AsterMotionTokens.Duration.Standard, reducedMotion = isReduced)

    val scale by animateFloatAsState(targetValue = targetScale, animationSpec = springSpec, label = "FocusScale")
    val alpha by animateFloatAsState(targetValue = targetAlpha, animationSpec = tweenSpec, label = "FocusAlpha")
    val elevation by animateFloatAsState(targetValue = targetElevation, animationSpec = springSpec, label = "FocusElevation")
    val glow by animateFloatAsState(targetValue = targetGlow, animationSpec = tweenSpec, label = "FocusGlow")

    return remember(scale, alpha, elevation, glow) {
        AsterFocusProperties(
            scale = scale,
            alpha = alpha,
            elevationDp = elevation,
            glowIntensity = glow
        )
    }
}

/**
 * State holder for `idle → dragging → released → settle` interaction physics.
 *
 * Reusable foundation for:
 * - Future Swipe-to-reply (Phase 8.3)
 * - Constellation Map node repositioning (Phase 8.6)
 * - Drag-to-dismiss surfaces
 */
@Stable
class AsterDragSettleState(
    initialX: Float = 0f,
    initialY: Float = 0f
) {
    private val animatableOffset = Animatable(Offset(initialX, initialY), Offset.VectorConverter)

    var isDragging: Boolean by mutableStateOf(false)
        private set

    val offsetX: Float
        get() = animatableOffset.value.x

    val offsetY: Float
        get() = animatableOffset.value.y

    /**
     * Updates offset immediately during direct finger tracking.
     */
    suspend fun dragBy(deltaX: Float, deltaY: Float) {
        isDragging = true
        animatableOffset.snapTo(Offset(animatableOffset.value.x + deltaX, animatableOffset.value.y + deltaY))
    }

    /**
     * Sets offset immediately without animation.
     */
    suspend fun snapTo(targetX: Float, targetY: Float) {
        isDragging = false
        animatableOffset.snapTo(Offset(targetX, targetY))
    }

    /**
     * Smoothly settles the object back to the target coordinates using Aster's settle spring physics.
     */
    suspend fun settleTo(
        targetX: Float = 0f,
        targetY: Float = 0f,
        reducedMotion: Boolean = false
    ) {
        isDragging = false
        val springSpec = AsterMotion.spring<Offset>(
            AsterMotionTokens.SpringPhysics.Settle,
            reducedMotion = reducedMotion
        )
        try {
            animatableOffset.animateTo(
                targetValue = Offset(targetX, targetY),
                animationSpec = springSpec
            )
        } catch (_: IllegalStateException) {
            // Defensively fallback to instant snap if running in a context without a MonotonicFrameClock
            animatableOffset.snapTo(Offset(targetX, targetY))
        }
    }
}

/**
 * Remembers an [AsterDragSettleState] instance across recompositions.
 */
@Composable
fun rememberAsterDragSettleState(
    initialX: Float = 0f,
    initialY: Float = 0f
): AsterDragSettleState {
    return remember { AsterDragSettleState(initialX, initialY) }
}

/**
 * Reusable animated scrim overlay primitive.
 *
 * Wraps content with an animated midnight backdrop fading between `0f` and [targetAlpha].
 * Tapping the backdrop invokes [onDismiss].
 */
@Composable
fun AsterScrim(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    targetAlpha: Float = AsterMotionTokens.Scrim.Midnight,
    scrimColor: Color = AsterNight,
    enterDurationMillis: Int = AsterMotionTokens.Duration.Standard,
    exitDurationMillis: Int = AsterMotionTokens.Duration.Short,
    content: (@Composable BoxScope.() -> Unit)? = null
) {
    val isReduced = LocalReducedMotion.current
    val effectiveEnter = AsterMotion.duration(enterDurationMillis, isReduced)
    val effectiveExit = AsterMotion.duration(exitDurationMillis, isReduced)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = AsterMotion.tween(
                durationMillis = effectiveEnter,
                easing = AsterMotionTokens.EasingCurve.StandardDecelerate,
                reducedMotion = isReduced
            )
        ),
        exit = fadeOut(
            animationSpec = AsterMotion.tween(
                durationMillis = effectiveExit,
                easing = AsterMotionTokens.EasingCurve.StandardAccelerate,
                reducedMotion = isReduced
            )
        ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimColor.copy(alpha = targetAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            content?.invoke(this)
        }
    }
}
