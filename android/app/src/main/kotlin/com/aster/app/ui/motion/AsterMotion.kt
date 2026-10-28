package com.aster.app.ui.motion

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// ─────────────────────────────────────────────────────────────────────────────
// Aster Motion Master Facade
// Authoritative source: PHASE_8_ROADMAP.md (Phase 8.1 Motion Foundation)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Unified developer-facing entry point for Aster's motion system.
 *
 * Provides semantic durations, easing curves, spring physics, interaction scales,
 * alpha levels, and scrim opacities, with first-class support for reduced motion.
 *
 * Usage examples:
 * ```kotlin
 * // Direct spec construction
 * animateFloatAsState(
 *     targetValue = if (pressed) AsterMotion.Scale.Pressed else AsterMotion.Scale.Normal,
 *     animationSpec = AsterMotion.spring(AsterMotion.Spring.Press)
 * )
 *
 * // Composable spec construction with automatic reduced-motion awareness
 * animateFloatAsState(
 *     targetValue = AsterMotion.rememberScale(if (pressed) AsterMotion.Scale.Pressed else 1f),
 *     animationSpec = AsterMotion.rememberSpring(AsterMotion.Spring.Press)
 * )
 * ```
 */
object AsterMotion {

    /** Semantic duration tokens in milliseconds. */
    val Duration = AsterMotionTokens.Duration

    /** Semantic easing curves. */
    val Easing = AsterMotionTokens.EasingCurve

    /** Semantic spring physics specifications. */
    val Spring = AsterMotionTokens.SpringPhysics

    /** Semantic interaction scale factors. */
    val Scale = AsterMotionTokens.Scale

    /** Semantic alpha / opacity tokens. */
    val Alpha = AsterMotionTokens.Alpha

    /** Semantic scrim opacity tokens. */
    val Scrim = AsterMotionTokens.Scrim

    /**
     * Creates a Compose [SpringSpec] using the provided [AsterSpringSpec].
     *
     * If [reducedMotion] is true, returns a high-stiffness non-bouncy spring for
     * instant and accessible settling.
     */
    fun <T> spring(
        spec: AsterSpringSpec,
        reducedMotion: Boolean = false,
        visibilityThreshold: T? = null
    ): SpringSpec<T> {
        val effectiveSpec = if (reducedMotion) AsterMotionTokens.SpringPhysics.ReducedMotion else spec
        return androidx.compose.animation.core.spring(
            stiffness = effectiveSpec.stiffness,
            dampingRatio = effectiveSpec.dampingRatio,
            visibilityThreshold = visibilityThreshold
        )
    }

    /**
     * Creates a Compose [TweenSpec] using semantic duration and easing.
     *
     * If [reducedMotion] is true, duration is collapsed to 0 (or a micro 50ms fade
     * when smooth visibility transition is needed) to prevent motion discomfort.
     */
    fun <T> tween(
        durationMillis: Int,
        easing: Easing = AsterMotionTokens.EasingCurve.Standard,
        delayMillis: Int = 0,
        reducedMotion: Boolean = false
    ): TweenSpec<T> {
        val effectiveDuration = if (reducedMotion) 0 else durationMillis
        val effectiveDelay = if (reducedMotion) 0 else delayMillis
        return androidx.compose.animation.core.tween(
            durationMillis = effectiveDuration,
            delayMillis = effectiveDelay,
            easing = easing
        )
    }

    /**
     * Adapts an animation duration to the reduced-motion preference.
     * Collapses to 0ms when reduced motion is active.
     */
    fun duration(baseMillis: Int, reducedMotion: Boolean = false): Int {
        return if (reducedMotion) 0 else baseMillis
    }

    /**
     * Adapts an interaction scale to the reduced-motion preference.
     * Returns 1.0f (no scale transformation) when reduced motion is active.
     */
    fun scale(targetScale: Float, reducedMotion: Boolean = false): Float {
        return if (reducedMotion) AsterMotionTokens.Scale.Normal else targetScale
    }

    /**
     * Adapts an alpha value to the reduced-motion preference.
     * Alpha changes remain intact as they communicate essential visibility.
     */
    fun alpha(targetAlpha: Float, @Suppress("UNUSED_PARAMETER") reducedMotion: Boolean = false): Float {
        return targetAlpha
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Composable Helpers with Automatic `LocalReducedMotion` Resolution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Remembers a [SpringSpec] that automatically adapts to [LocalReducedMotion].
     */
    @Composable
    fun <T> rememberSpring(
        spec: AsterSpringSpec,
        visibilityThreshold: T? = null
    ): SpringSpec<T> {
        val isReduced = LocalReducedMotion.current
        return remember(spec, isReduced, visibilityThreshold) {
            spring(spec, isReduced, visibilityThreshold)
        }
    }

    /**
     * Remembers a [TweenSpec] that automatically adapts to [LocalReducedMotion].
     */
    @Composable
    fun <T> rememberTween(
        durationMillis: Int,
        easing: Easing = AsterMotionTokens.EasingCurve.Standard,
        delayMillis: Int = 0
    ): TweenSpec<T> {
        val isReduced = LocalReducedMotion.current
        return remember(durationMillis, easing, delayMillis, isReduced) {
            tween(durationMillis, easing, delayMillis, isReduced)
        }
    }

    /**
     * Remembers a duration adapted to [LocalReducedMotion].
     */
    @Composable
    fun rememberDuration(baseMillis: Int): Int {
        val isReduced = LocalReducedMotion.current
        return remember(baseMillis, isReduced) {
            duration(baseMillis, isReduced)
        }
    }

    /**
     * Remembers a scale factor adapted to [LocalReducedMotion].
     */
    @Composable
    fun rememberScale(targetScale: Float): Float {
        val isReduced = LocalReducedMotion.current
        return remember(targetScale, isReduced) {
            scale(targetScale, isReduced)
        }
    }
}
