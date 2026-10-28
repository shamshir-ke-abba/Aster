package com.aster.app.ui.motion

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// ─────────────────────────────────────────────────────────────────────────────
// Aster Reduced Motion / Accessibility Support
// Authoritative source: PHASE_8_ROADMAP.md (Phase 8.1 Reduced Motion)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * CompositionLocal providing whether reduced motion is currently preferred.
 *
 * When true:
 * - Large scale changes and translations collapse to 0.
 * - Long animation durations collapse to 0 or micro-fades.
 * - Springs use non-bouncy instant settling.
 * - Usability, essential feedback, and functional state changes are preserved.
 */
val LocalReducedMotion = compositionLocalOf { false }

/**
 * Checks whether the Android operating system has reduced-motion or animation scale
 * disabled in Developer Options or Accessibility settings.
 *
 * Specifically evaluates:
 * - [Settings.Global.TRANSITION_ANIMATION_SCALE]
 * - [Settings.Global.ANIMATOR_DURATION_SCALE]
 *
 * If either scale is 0.0f, reduced motion is considered active.
 */
fun isSystemReducedMotionEnabled(context: Context): Boolean {
    return try {
        val resolver = context.contentResolver
        val transitionScale = Settings.Global.getFloat(
            resolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1.0f
        )
        val animatorScale = Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
        transitionScale == 0.0f || animatorScale == 0.0f
    } catch (_: Exception) {
        false
    }
}

/**
 * Remembers the reduced-motion preference for the current platform context.
 */
@Composable
fun rememberReducedMotionState(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        isSystemReducedMotionEnabled(context)
    }
}

/**
 * Convenience wrapper to override reduced-motion preference in tests or previews.
 */
@Composable
fun ProvideReducedMotion(
    reducedMotion: Boolean,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        content()
    }
}
