package com.aster.app.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

// ─────────────────────────────────────────────────────────────────────────────
// Aster Fluid Navigation Motion Primitives
// Authoritative source: PHASE_8_ROADMAP.md (Phase 8.2 Fluid Navigation)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Navigation motion specifications for Aster's screen and dialog transitions.
 * Coordinates spatial continuity between Constellation, Chat, and Profile.
 */
object AsterNavigationMotion {

    /**
     * Standard screen enter transition: subtle upward slide (+6% screen height),
     * subtle expansion scale (0.95f -> 1.0f), and smooth alpha fade.
     */
    fun enterTransition(isReduced: Boolean = false): EnterTransition {
        if (isReduced) {
            return fadeIn(animationSpec = AsterMotion.tween(0, reducedMotion = true))
        }
        return fadeIn(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Standard,
                easing = AsterMotionTokens.EasingCurve.StandardDecelerate
            )
        ) + slideInVertically(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Standard,
                easing = AsterMotionTokens.EasingCurve.Emphasized
            ),
            initialOffsetY = { (it * 0.06f).toInt() }
        ) + scaleIn(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Standard,
                easing = AsterMotionTokens.EasingCurve.Emphasized
            ),
            initialScale = 0.95f
        )
    }

    /**
     * Standard screen exit transition: recedes with gentle scale (1.0f -> 0.96f) and fade.
     */
    fun exitTransition(isReduced: Boolean = false): ExitTransition {
        if (isReduced) {
            return fadeOut(animationSpec = AsterMotion.tween(0, reducedMotion = true))
        }
        return fadeOut(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Short,
                easing = AsterMotionTokens.EasingCurve.StandardAccelerate
            )
        ) + scaleOut(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Short,
                easing = AsterMotionTokens.EasingCurve.EmphasizedAccelerate
            ),
            targetScale = AsterMotionTokens.Scale.Pressed
        )
    }

    /**
     * Standard pop enter transition: restores scale from 0.96f to 1.0f with smooth fade.
     */
    fun popEnterTransition(isReduced: Boolean = false): EnterTransition {
        if (isReduced) {
            return fadeIn(animationSpec = AsterMotion.tween(0, reducedMotion = true))
        }
        return fadeIn(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Standard,
                easing = AsterMotionTokens.EasingCurve.StandardDecelerate
            )
        ) + scaleIn(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Standard,
                easing = AsterMotionTokens.EasingCurve.Emphasized
            ),
            initialScale = AsterMotionTokens.Scale.Pressed
        )
    }

    /**
     * Standard pop exit transition: folds down (+6% screen height) and fades out.
     */
    fun popExitTransition(isReduced: Boolean = false): ExitTransition {
        if (isReduced) {
            return fadeOut(animationSpec = AsterMotion.tween(0, reducedMotion = true))
        }
        return fadeOut(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Short,
                easing = AsterMotionTokens.EasingCurve.StandardAccelerate
            )
        ) + slideOutVertically(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Short,
                easing = AsterMotionTokens.EasingCurve.EmphasizedAccelerate
            ),
            targetOffsetY = { (it * 0.06f).toInt() }
        ) + scaleOut(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Short,
                easing = AsterMotionTokens.EasingCurve.EmphasizedAccelerate
            ),
            targetScale = 0.95f
        )
    }

    /**
     * Constellation Map -> Chat transition:
     * Represents the selected star node expanding outward into the conversation canvas.
     */
    fun constellationToChatEnter(isReduced: Boolean = false): EnterTransition {
        if (isReduced) {
            return fadeIn(animationSpec = AsterMotion.tween(0, reducedMotion = true))
        }
        return fadeIn(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Emphasis,
                easing = AsterMotionTokens.EasingCurve.StandardDecelerate
            )
        ) + scaleIn(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Emphasis,
                easing = AsterMotionTokens.EasingCurve.Emphasized
            ),
            initialScale = 0.92f
        ) + slideInVertically(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Emphasis,
                easing = AsterMotionTokens.EasingCurve.Emphasized
            ),
            initialOffsetY = { (it * 0.04f).toInt() }
        )
    }

    /**
     * Chat -> Constellation Map exit transition:
     * Folds smoothly back into the glowing star resting on the constellation map.
     */
    fun chatToConstellationPopExit(isReduced: Boolean = false): ExitTransition {
        if (isReduced) {
            return fadeOut(animationSpec = AsterMotion.tween(0, reducedMotion = true))
        }
        return fadeOut(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Standard,
                easing = AsterMotionTokens.EasingCurve.StandardAccelerate
            )
        ) + scaleOut(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Standard,
                easing = AsterMotionTokens.EasingCurve.EmphasizedAccelerate
            ),
            targetScale = 0.92f
        ) + slideOutVertically(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Standard,
                easing = AsterMotionTokens.EasingCurve.EmphasizedAccelerate
            ),
            targetOffsetY = { (it * 0.04f).toInt() }
        )
    }

    /**
     * Profile expansion transition:
     * Used when expanding the profile surface from an identity header or contact avatar.
     */
    fun profileEnterTransition(isReduced: Boolean = false): EnterTransition {
        if (isReduced) {
            return fadeIn(animationSpec = AsterMotion.tween(0, reducedMotion = true))
        }
        return fadeIn(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Standard,
                easing = AsterMotionTokens.EasingCurve.StandardDecelerate
            )
        ) + scaleIn(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Standard,
                easing = AsterMotionTokens.EasingCurve.Emphasized
            ),
            initialScale = 0.90f
        )
    }

    /**
     * Profile collapse transition.
     */
    fun profilePopExitTransition(isReduced: Boolean = false): ExitTransition {
        if (isReduced) {
            return fadeOut(animationSpec = AsterMotion.tween(0, reducedMotion = true))
        }
        return fadeOut(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Short,
                easing = AsterMotionTokens.EasingCurve.StandardAccelerate
            )
        ) + scaleOut(
            animationSpec = AsterMotion.tween(
                durationMillis = AsterMotionTokens.Duration.Short,
                easing = AsterMotionTokens.EasingCurve.EmphasizedAccelerate
            ),
            targetScale = 0.90f
        )
    }
}

/**
 * Modifier that animates a dialog surface's entrance using Aster motion tokens.
 *
 * Expands scale from [initialScale] (0.95f) to 1.0f and fades alpha from 0f to 1.0f,
 * while translating vertically (+[initialOffsetY] dp -> 0dp).
 * Automatically collapses to instant appearance when reduced motion is preferred.
 */
fun Modifier.asterDialogTransition(
    initialScale: Float = 0.95f,
    initialOffsetY: Float = 16f
): Modifier = composed {
    val isReduced = LocalReducedMotion.current
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    val targetScale = if (isVisible || isReduced) 1.0f else initialScale
    val targetAlpha = if (isVisible || isReduced) AsterMotionTokens.Alpha.Opaque else AsterMotionTokens.Alpha.Ghost
    val targetOffset = if (isVisible || isReduced) 0f else initialOffsetY

    val springSpec = AsterMotion.spring<Float>(
        spec = AsterMotionTokens.SpringPhysics.Expansion,
        reducedMotion = isReduced
    )
    val tweenSpec = AsterMotion.tween<Float>(
        durationMillis = AsterMotionTokens.Duration.Standard,
        easing = AsterMotionTokens.EasingCurve.StandardDecelerate,
        reducedMotion = isReduced
    )

    val scale by animateFloatAsState(targetValue = targetScale, animationSpec = springSpec, label = "DialogScale")
    val alpha by animateFloatAsState(targetValue = targetAlpha, animationSpec = tweenSpec, label = "DialogAlpha")
    val offsetY by animateFloatAsState(targetValue = targetOffset, animationSpec = springSpec, label = "DialogOffset")

    this.graphicsLayer {
        this.scaleX = scale
        this.scaleY = scale
        this.alpha = alpha
        this.translationY = offsetY * density
    }
}
