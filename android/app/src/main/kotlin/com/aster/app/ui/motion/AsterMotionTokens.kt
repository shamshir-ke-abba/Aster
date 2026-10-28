package com.aster.app.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring

// ─────────────────────────────────────────────────────────────────────────────
// Aster Motion Design Tokens
// Authoritative source: PHASE_8_ROADMAP.md (Phase 8.1 Motion Foundation)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Immutable spring parameter definition.
 *
 * @param stiffness Controls the spring's speed/responsiveness.
 * @param dampingRatio Controls the spring's oscillation/bounciness.
 */
data class AsterSpringSpec(
    val stiffness: Float,
    val dampingRatio: Float
)

/**
 * Master motion design tokens for the Aster application.
 * Defines semantic durations, easing curves, spring physics, interaction scales,
 * alpha levels, and scrim opacities.
 */
object AsterMotionTokens {

    /**
     * Semantic animation durations in milliseconds.
     * Prevents arbitrary hard-coded duration numbers throughout the codebase.
     */
    object Duration {
        /** Micro interaction (150ms): Tap feedback, checkmark transitions, icon toggles. */
        const val Micro = 150

        /** Short transition (250ms): Tooltips, quick fades, reaction response, badge updates. */
        const val Short = 250

        /** Standard transition (350ms): Dialog entrances, surface reveals, message state ticks. */
        const val Standard = 350

        /** Emphasis transition (500ms): Selection focus, node highlights, hero expansions. */
        const val Emphasis = 500

        /** Complex transition (700ms): Multi-stage constellation re-arrangements, canvas settling. */
        const val Complex = 700
    }

    /**
     * Semantic easing curves using Compose's native CubicBezierEasing.
     */
    object EasingCurve {
        /**
         * Emphasized curve (Android 14 standard deceleration).
         * Used for focal entrances and dominant UI transitions.
         */
        val Emphasized: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

        /**
         * Emphasized accelerate.
         * Used for deliberate, energetic exits.
         */
        val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

        /**
         * Emphasized decelerate.
         * Used when an incoming element enters and glides into position.
         */
        val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

        /**
         * Standard easing (FastOutSlowIn).
         * Used for everyday UI state changes, color transitions, and element shifts.
         */
        val Standard: Easing = FastOutSlowInEasing

        /** Standard accelerate (FastOutLinearIn) for standard element exits. */
        val StandardAccelerate: Easing = FastOutLinearInEasing

        /** Standard decelerate (LinearOutSlowIn) for standard element entrances. */
        val StandardDecelerate: Easing = LinearOutSlowInEasing

        /** Linear easing for uniform angular rotations or cyclic background effects. */
        val Linear: Easing = LinearEasing
    }

    /**
     * Semantic spring physics specifications.
     * Tuned for controlled, tactile responsiveness without cartoonish oscillation.
     */
    object SpringPhysics {
        /**
         * Press spring: Quick, tactile button compression.
         * Slight low-bouncy damping for organic finger feel.
         */
        val Press = AsterSpringSpec(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioLowBouncy
        )

        /**
         * Release spring: Snappy recovery back to unpressed state with zero bounce.
         */
        val Release = AsterSpringSpec(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioNoBouncy
        )

        /**
         * Drag spring: High-stiffness, immediate finger tracking with minimal lag.
         */
        val Drag = AsterSpringSpec(
            stiffness = Spring.StiffnessHigh,
            dampingRatio = Spring.DampingRatioNoBouncy
        )

        /**
         * Settle spring: Gentle, organic landing when a dragged object is released.
         */
        val Settle = AsterSpringSpec(
            stiffness = Spring.StiffnessLow,
            dampingRatio = 0.85f
        )

        /**
         * Expansion spring: Used when a node or thumbnail expands into a larger surface.
         */
        val Expansion = AsterSpringSpec(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = 0.80f
        )

        /**
         * Dismissal spring: Used when an overlay, dialog, or sheet leaves the viewport.
         */
        val Dismissal = AsterSpringSpec(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioNoBouncy
        )

        /**
         * Reposition spring: Specifically tuned for Constellation Map node relocation.
         * Allows nodes to part and find equilibrium without chaotic bouncing.
         */
        val Reposition = AsterSpringSpec(
            stiffness = Spring.StiffnessLow,
            dampingRatio = 0.88f
        )

        /**
         * Non-bouncy instant spring used when reduced motion is requested.
         */
        val ReducedMotion = AsterSpringSpec(
            stiffness = Spring.StiffnessHigh,
            dampingRatio = Spring.DampingRatioNoBouncy
        )
    }

    /**
     * Semantic interaction scale factors.
     * Keep interaction scaling subtle to avoid jarring layout shifts.
     */
    object Scale {
        /** Normal unpressed resting scale (1.0x). */
        const val Normal = 1.0f

        /** Pressed scale (0.96x): Subtle tactile button or bubble compression. */
        const val Pressed = 0.96f

        /** Focused scale (1.04x): Gentle magnification for focused or active items. */
        const val Focused = 1.04f

        /** Elevated scale (1.08x): Lifted state for long-pressed messages or nodes. */
        const val Elevated = 1.08f

        /** Diminished scale (0.92x): Used when adjacent elements recede. */
        const val Diminished = 0.92f
    }

    /**
     * Semantic alpha / opacity tokens for transitions and focus states.
     */
    object Alpha {
        /** Fully visible rest state. */
        const val Opaque = 1.0f

        /** Subtle secondary emphasis. */
        const val Subtle = 0.75f

        /** De-emphasized background content during modal focus. */
        const val Deemphasized = 0.35f

        /** Muted / distant element opacity. */
        const val Muted = 0.20f

        /** Completely invisible ghost state. */
        const val Ghost = 0.0f

        /** Standard disabled content opacity. */
        const val Disabled = 0.38f
    }

    /**
     * Semantic scrim opacity tokens for overlays, dialogs, and context focus.
     */
    object Scrim {
        /** Standard midnight scrim opacity (72%) for message context and dialogs. */
        const val Midnight = 0.72f

        /** High-focus scrim opacity (85%) for critical destructive confirmations. */
        const val Focus = 0.85f

        /** Light atmospheric backdrop scrim opacity (40%). */
        const val Light = 0.40f
    }
}
