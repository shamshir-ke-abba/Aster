package com.aster.app.ui.motion

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

// ─────────────────────────────────────────────────────────────────────────────
// Aster Haptics Architecture
// Authoritative source: PHASE_8_ROADMAP.md (Phase 8.7 Haptics & Final Polish)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Semantic tactile intensity tier for Aster haptics.
 */
enum class AsterHapticIntensity {
    /** Subtle, restrained clicks for ordinary taps and selections */
    Light,

    /** Distinct confirmation pulses for threshold crossings, long-press, and settles */
    Medium,

    /** Emphatic feedback for destructive actions, major confirmations, or critical errors */
    Strong
}

/**
 * Semantic haptic events for all meaningful interactions in Aster.
 *
 * UI components request semantic haptic feedback; they do NOT directly manipulate
 * vibration hardware or arbitrary waveforms.
 */
enum class AsterHapticEvent(val intensity: AsterHapticIntensity) {
    /** Ordinary press feedback, small button taps, opening normal controls */
    Press(AsterHapticIntensity.Light),

    /** Selecting an option, picking an effect, toggling a selection */
    Selection(AsterHapticIntensity.Light),

    /** Adding or removing an expressive reaction */
    Reaction(AsterHapticIntensity.Light),

    /** Crossing an interaction threshold (swipe-to-reply 64dp, constellation reposition 12dp) */
    Threshold(AsterHapticIntensity.Medium),

    /** Long-press recognition for message context overlay or constellation context card */
    ContextOpen(AsterHapticIntensity.Medium),

    /** Committing a reply, copying text, or successfully completing an action */
    Success(AsterHapticIntensity.Medium),

    /** Constellation node successfully settling into its authoritative destination */
    Reposition(AsterHapticIntensity.Medium),

    /** Local message submission to the outbound network pipeline */
    Send(AsterHapticIntensity.Light),

    /** Gravitational collision boundary resistance when a destination is occupied */
    Warning(AsterHapticIntensity.Medium),

    /** Critical send failure or operation error */
    Error(AsterHapticIntensity.Strong),

    /** Destructive action confirmation (unsend message, delete conversation, remove contact) */
    Destructive(AsterHapticIntensity.Strong)
}

/**
 * Canonical interface for Aster haptic feedback.
 */
interface AsterHapticFeedback {
    /**
     * Requests tactile feedback for a semantic [event].
     *
     * Best-effort execution: gracefully no-ops if haptics are disabled or unavailable.
     */
    fun perform(event: AsterHapticEvent)
}

/**
 * Resolves an [AsterHapticEvent] to the most appropriate platform [HapticFeedbackConstants] value
 * based on the device's [sdkInt].
 */
fun getPlatformHapticConstant(event: AsterHapticEvent, sdkInt: Int = Build.VERSION.SDK_INT): Int? {
    return when (event) {
        AsterHapticEvent.Press -> {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        AsterHapticEvent.Selection, AsterHapticEvent.Reaction -> {
            if (sdkInt >= 34) {
                HapticFeedbackConstants.SEGMENT_TICK
            } else if (sdkInt >= 21) {
                HapticFeedbackConstants.CLOCK_TICK
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        }
        AsterHapticEvent.Threshold -> {
            if (sdkInt >= 30) {
                HapticFeedbackConstants.GESTURE_START
            } else if (sdkInt >= 27) {
                HapticFeedbackConstants.TEXT_HANDLE_MOVE
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
        }
        AsterHapticEvent.Reposition -> {
            if (sdkInt >= 30) {
                HapticFeedbackConstants.GESTURE_END
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
        }
        AsterHapticEvent.Success -> {
            if (sdkInt >= 30) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
        }
        AsterHapticEvent.Send -> {
            if (sdkInt >= 30) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        }
        AsterHapticEvent.ContextOpen -> {
            HapticFeedbackConstants.LONG_PRESS
        }
        AsterHapticEvent.Warning -> {
            if (sdkInt >= 30) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        }
        AsterHapticEvent.Error -> {
            if (sdkInt >= 30) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        }
        AsterHapticEvent.Destructive -> {
            if (sdkInt >= 30) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        }
    }
}

/**
 * Android View-backed implementation of [AsterHapticFeedback].
 *
 * Honors system haptic settings, handles SDK discrepancies, and guarantees safe
 * best-effort execution without crashes.
 */
class AndroidAsterHapticFeedback(
    private val view: View?,
    private val isReducedMotion: Boolean = false,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : AsterHapticFeedback {
    override fun perform(event: AsterHapticEvent) {
        val targetView = view ?: return
        try {
            val constant = getPlatformHapticConstant(event, sdkInt) ?: return
            targetView.performHapticFeedback(constant)
        } catch (_: Throwable) {
            // Graceful best-effort fallback: never crash if hardware haptics fail
        }
    }
}

/**
 * No-op implementation for headless previews, environments without haptic support, or default fallback.
 */
object NoOpAsterHapticFeedback : AsterHapticFeedback {
    override fun perform(event: AsterHapticEvent) {
        // No-op
    }
}

/**
 * Test recorder implementation of [AsterHapticFeedback] for deterministic unit testing.
 */
class RecordingAsterHapticFeedback : AsterHapticFeedback {
    val events = mutableListOf<AsterHapticEvent>()

    override fun perform(event: AsterHapticEvent) {
        events.add(event)
    }

    fun clear() {
        events.clear()
    }
}

/**
 * CompositionLocal providing the authoritative [AsterHapticFeedback] across Aster UI.
 */
val LocalAsterHaptics = staticCompositionLocalOf<AsterHapticFeedback> { NoOpAsterHapticFeedback }

/**
 * Remembers the canonical [AsterHapticFeedback] instance for the current composition.
 */
@Composable
fun rememberAsterHaptics(
    isReducedMotion: Boolean = LocalReducedMotion.current
): AsterHapticFeedback {
    val view = runCatching { LocalView.current }.getOrNull()
    return remember(view, isReducedMotion) {
        if (view != null) {
            AndroidAsterHapticFeedback(view, isReducedMotion)
        } else {
            NoOpAsterHapticFeedback
        }
    }
}
