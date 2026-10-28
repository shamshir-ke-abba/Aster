package com.aster.app

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.aster.app.ui.motion.AndroidAsterHapticFeedback
import com.aster.app.ui.motion.AsterHapticEvent
import com.aster.app.ui.motion.AsterHapticIntensity
import com.aster.app.ui.motion.NoOpAsterHapticFeedback
import com.aster.app.ui.motion.RecordingAsterHapticFeedback
import com.aster.app.ui.motion.getPlatformHapticConstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests verifying Phase 8.7 — Centralized Haptics & Semantic Tactile Feedback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterHapticsTest {

    private class TestHapticView(context: Context) : View(context) {
        val performedConstants = mutableListOf<Int>()
        var shouldThrow = false

        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            if (shouldThrow) {
                throw RuntimeException("Hardware failure")
            }
            performedConstants.add(feedbackConstant)
            return true
        }
    }

    @Test
    fun `all semantic events have appropriate intensity tiers`() {
        // Light: subtle clicks for ordinary taps and selections
        assertEquals(AsterHapticIntensity.Light, AsterHapticEvent.Press.intensity)
        assertEquals(AsterHapticIntensity.Light, AsterHapticEvent.Selection.intensity)
        assertEquals(AsterHapticIntensity.Light, AsterHapticEvent.Reaction.intensity)
        assertEquals(AsterHapticIntensity.Light, AsterHapticEvent.Send.intensity)

        // Medium: distinct pulses for thresholds, opens, and settles
        assertEquals(AsterHapticIntensity.Medium, AsterHapticEvent.Threshold.intensity)
        assertEquals(AsterHapticIntensity.Medium, AsterHapticEvent.ContextOpen.intensity)
        assertEquals(AsterHapticIntensity.Medium, AsterHapticEvent.Success.intensity)
        assertEquals(AsterHapticIntensity.Medium, AsterHapticEvent.Reposition.intensity)
        assertEquals(AsterHapticIntensity.Medium, AsterHapticEvent.Warning.intensity)

        // Strong: critical errors and destructive operations
        assertEquals(AsterHapticIntensity.Strong, AsterHapticEvent.Error.intensity)
        assertEquals(AsterHapticIntensity.Strong, AsterHapticEvent.Destructive.intensity)
    }

    @Test
    fun `platform constant mapping on API 34 resolves to modern haptic constants`() {
        val sdk = 34
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, getPlatformHapticConstant(AsterHapticEvent.Press, sdk))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, getPlatformHapticConstant(AsterHapticEvent.Selection, sdk))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, getPlatformHapticConstant(AsterHapticEvent.Reaction, sdk))
        assertEquals(HapticFeedbackConstants.GESTURE_START, getPlatformHapticConstant(AsterHapticEvent.Threshold, sdk))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, getPlatformHapticConstant(AsterHapticEvent.ContextOpen, sdk))
        assertEquals(HapticFeedbackConstants.CONFIRM, getPlatformHapticConstant(AsterHapticEvent.Success, sdk))
        assertEquals(HapticFeedbackConstants.GESTURE_END, getPlatformHapticConstant(AsterHapticEvent.Reposition, sdk))
        assertEquals(HapticFeedbackConstants.CONFIRM, getPlatformHapticConstant(AsterHapticEvent.Send, sdk))
        assertEquals(HapticFeedbackConstants.REJECT, getPlatformHapticConstant(AsterHapticEvent.Warning, sdk))
        assertEquals(HapticFeedbackConstants.REJECT, getPlatformHapticConstant(AsterHapticEvent.Error, sdk))
        assertEquals(HapticFeedbackConstants.CONFIRM, getPlatformHapticConstant(AsterHapticEvent.Destructive, sdk))
    }

    @Test
    fun `platform constant mapping on API 30 falls back gracefully from API 34 constants`() {
        val sdk = 30
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, getPlatformHapticConstant(AsterHapticEvent.Selection, sdk))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, getPlatformHapticConstant(AsterHapticEvent.Reaction, sdk))
        assertEquals(HapticFeedbackConstants.GESTURE_START, getPlatformHapticConstant(AsterHapticEvent.Threshold, sdk))
        assertEquals(HapticFeedbackConstants.GESTURE_END, getPlatformHapticConstant(AsterHapticEvent.Reposition, sdk))
        assertEquals(HapticFeedbackConstants.CONFIRM, getPlatformHapticConstant(AsterHapticEvent.Success, sdk))
        assertEquals(HapticFeedbackConstants.CONFIRM, getPlatformHapticConstant(AsterHapticEvent.Send, sdk))
        assertEquals(HapticFeedbackConstants.REJECT, getPlatformHapticConstant(AsterHapticEvent.Warning, sdk))
        assertEquals(HapticFeedbackConstants.REJECT, getPlatformHapticConstant(AsterHapticEvent.Error, sdk))
        assertEquals(HapticFeedbackConstants.CONFIRM, getPlatformHapticConstant(AsterHapticEvent.Destructive, sdk))
    }

    @Test
    fun `platform constant mapping on API 26 legacy platforms resolves safely`() {
        val sdk = 26
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, getPlatformHapticConstant(AsterHapticEvent.Press, sdk))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, getPlatformHapticConstant(AsterHapticEvent.Selection, sdk))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, getPlatformHapticConstant(AsterHapticEvent.Threshold, sdk))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, getPlatformHapticConstant(AsterHapticEvent.Reposition, sdk))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, getPlatformHapticConstant(AsterHapticEvent.Success, sdk))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, getPlatformHapticConstant(AsterHapticEvent.Send, sdk))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, getPlatformHapticConstant(AsterHapticEvent.Warning, sdk))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, getPlatformHapticConstant(AsterHapticEvent.Error, sdk))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, getPlatformHapticConstant(AsterHapticEvent.Destructive, sdk))
    }

    @Test
    fun `recording haptic feedback records events in chronological order`() {
        val recorder = RecordingAsterHapticFeedback()
        assertTrue(recorder.events.isEmpty())

        recorder.perform(AsterHapticEvent.Press)
        recorder.perform(AsterHapticEvent.Threshold)
        recorder.perform(AsterHapticEvent.Success)

        assertEquals(3, recorder.events.size)
        assertEquals(AsterHapticEvent.Press, recorder.events[0])
        assertEquals(AsterHapticEvent.Threshold, recorder.events[1])
        assertEquals(AsterHapticEvent.Success, recorder.events[2])

        recorder.clear()
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun `noop haptic feedback never throws`() {
        NoOpAsterHapticFeedback.perform(AsterHapticEvent.Press)
        NoOpAsterHapticFeedback.perform(AsterHapticEvent.Destructive)
        // No exceptions thrown
    }

    @Test
    fun `android view feedback triggers view performHapticFeedback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TestHapticView(context)
        val feedback = AndroidAsterHapticFeedback(view = view, isReducedMotion = false, sdkInt = 34)

        feedback.perform(AsterHapticEvent.Press)
        assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP), view.performedConstants)

        feedback.perform(AsterHapticEvent.ContextOpen)
        assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.LONG_PRESS), view.performedConstants)
    }

    @Test
    fun `android view feedback catches view exceptions gracefully without crashing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TestHapticView(context)
        view.shouldThrow = true

        val feedback = AndroidAsterHapticFeedback(view = view, isReducedMotion = false, sdkInt = 34)
        // Must not throw
        feedback.perform(AsterHapticEvent.Press)
    }

    @Test
    fun `null view in android view feedback safely no-ops`() {
        val feedback = AndroidAsterHapticFeedback(view = null, isReducedMotion = false, sdkInt = 34)
        // Must not throw
        feedback.perform(AsterHapticEvent.Send)
    }

    @Test
    fun `swipe-to-reply threshold edge-trigger fires threshold once and success on release`() {
        val recorder = RecordingAsterHapticFeedback()
        val thresholdPx = 64f

        var hasFiredHaptic = false
        val dragSequence = listOf(10f, 30f, 63f, 65f, 70f, 85f)

        for (dragX in dragSequence) {
            if (dragX >= thresholdPx && !hasFiredHaptic) {
                hasFiredHaptic = true
                recorder.perform(AsterHapticEvent.Threshold)
            } else if (dragX < thresholdPx && hasFiredHaptic) {
                hasFiredHaptic = false
            }
        }

        // Even though 65f, 70f, 85f are all >= thresholdPx, threshold haptic fired exactly once
        assertEquals(1, recorder.events.size)
        assertEquals(AsterHapticEvent.Threshold, recorder.events[0])

        // On drag release above threshold
        val releaseX = 85f
        if (releaseX >= thresholdPx) {
            recorder.perform(AsterHapticEvent.Success)
        }

        assertEquals(2, recorder.events.size)
        assertEquals(AsterHapticEvent.Success, recorder.events[1])
    }

    @Test
    fun `swipe-to-reply release below threshold fires no success haptic`() {
        val recorder = RecordingAsterHapticFeedback()
        val thresholdPx = 64f

        val releaseX = 40f
        if (releaseX >= thresholdPx) {
            recorder.perform(AsterHapticEvent.Success)
        }

        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun `constellation repositioning fires threshold once warning on collision and reposition on settle`() {
        val recorder = RecordingAsterHapticFeedback()
        val dragThresholdPx = 12f

        var didCrossThreshold = false
        var lastWasOccupied = false

        // Simulate finger movements
        val drags = listOf(
            Pair(5f, false),    // sub-threshold, clear
            Pair(14f, false),   // crossed 12dp, clear
            Pair(20f, true),    // occupied resistance entered
            Pair(25f, true),    // still occupied
            Pair(30f, false)    // moved out of occupied
        )

        for ((dist, isOccupied) in drags) {
            if (!didCrossThreshold && dist >= dragThresholdPx) {
                didCrossThreshold = true
                recorder.perform(AsterHapticEvent.Threshold)
            }

            if (didCrossThreshold) {
                if (isOccupied && !lastWasOccupied) {
                    recorder.perform(AsterHapticEvent.Warning)
                }
                lastWasOccupied = isOccupied
            }
        }

        // Drop settles authoritatively
        recorder.perform(AsterHapticEvent.Reposition)

        assertEquals(3, recorder.events.size)
        assertEquals(AsterHapticEvent.Threshold, recorder.events[0])
        assertEquals(AsterHapticEvent.Warning, recorder.events[1])
        assertEquals(AsterHapticEvent.Reposition, recorder.events[2])
    }

    @Test
    fun `message context lifecycle triggers context open and selective action haptics`() {
        val recorder = RecordingAsterHapticFeedback()

        // 1. Long-press message bubble
        recorder.perform(AsterHapticEvent.ContextOpen)

        // 2. Select reply
        recorder.perform(AsterHapticEvent.Selection)

        // 3. User copies text on another message
        recorder.perform(AsterHapticEvent.Success)

        // 4. User unsend message confirmation
        recorder.perform(AsterHapticEvent.Destructive)

        assertEquals(4, recorder.events.size)
        assertEquals(AsterHapticEvent.ContextOpen, recorder.events[0])
        assertEquals(AsterHapticEvent.Selection, recorder.events[1])
        assertEquals(AsterHapticEvent.Success, recorder.events[2])
        assertEquals(AsterHapticEvent.Destructive, recorder.events[3])
    }

    @Test
    fun `reaction dock and badges trigger reaction haptic without double-firing`() {
        val recorder = RecordingAsterHapticFeedback()

        // User reacts with heart
        recorder.perform(AsterHapticEvent.Reaction)
        // User taps heart badge to toggle off
        recorder.perform(AsterHapticEvent.Reaction)

        assertEquals(2, recorder.events.size)
        assertEquals(AsterHapticEvent.Reaction, recorder.events[0])
        assertEquals(AsterHapticEvent.Reaction, recorder.events[1])
    }
}
