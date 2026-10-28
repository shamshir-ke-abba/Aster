package com.aster.app

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.test.core.app.ApplicationProvider
import com.aster.app.ui.motion.AsterDragSettleState
import com.aster.app.ui.motion.AsterMotion
import com.aster.app.ui.motion.AsterMotionTokens
import com.aster.app.ui.motion.isSystemReducedMotionEnabled
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests verifying the Phase 8.1 Motion System Foundation.
 * Tests semantic tokens, easing curves, spring specs, reduced-motion adaptations,
 * drag-settle interaction physics, and accessibility detection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterMotionSystemTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Reset system animation scales to normal 1.0f before each test
        Settings.Global.putFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
    }

    @Test
    fun `semantic durations follow strict monotonic hierarchy`() {
        assertTrue(AsterMotionTokens.Duration.Micro < AsterMotionTokens.Duration.Short)
        assertTrue(AsterMotionTokens.Duration.Short < AsterMotionTokens.Duration.Standard)
        assertTrue(AsterMotionTokens.Duration.Standard < AsterMotionTokens.Duration.Emphasis)
        assertTrue(AsterMotionTokens.Duration.Emphasis < AsterMotionTokens.Duration.Complex)

        assertEquals(150, AsterMotion.Duration.Micro)
        assertEquals(250, AsterMotion.Duration.Short)
        assertEquals(350, AsterMotion.Duration.Standard)
        assertEquals(500, AsterMotion.Duration.Emphasis)
        assertEquals(700, AsterMotion.Duration.Complex)
    }

    @Test
    fun `reduced motion collapses durations to zero`() {
        assertEquals(350, AsterMotion.duration(AsterMotion.Duration.Standard, reducedMotion = false))
        assertEquals(0, AsterMotion.duration(AsterMotion.Duration.Standard, reducedMotion = true))

        val normalTween = AsterMotion.tween<Float>(
            durationMillis = AsterMotion.Duration.Standard,
            reducedMotion = false
        )
        assertEquals(350, normalTween.durationMillis)

        val reducedTween = AsterMotion.tween<Float>(
            durationMillis = AsterMotion.Duration.Standard,
            reducedMotion = true
        )
        assertEquals(0, reducedTween.durationMillis)
    }

    @Test
    fun `easing curves produce valid normalized interpolations`() {
        val curves = listOf(
            AsterMotion.Easing.Emphasized,
            AsterMotion.Easing.EmphasizedAccelerate,
            AsterMotion.Easing.EmphasizedDecelerate,
            AsterMotion.Easing.Standard,
            AsterMotion.Easing.StandardAccelerate,
            AsterMotion.Easing.StandardDecelerate,
            AsterMotion.Easing.Linear
        )

        curves.forEach { easing ->
            assertEquals(0.0f, easing.transform(0.0f), 0.001f)
            assertEquals(1.0f, easing.transform(1.0f), 0.001f)
            val mid = easing.transform(0.5f)
            assertTrue("Easing mid value must be within [0f, 1f]: $mid", mid in 0.0f..1.0f)
        }
    }

    @Test
    fun `spring physics specifications adhere to controlled physicality`() {
        // Press must have slight bouncy feel, release must have zero bounce
        assertEquals(Spring.DampingRatioLowBouncy, AsterMotion.Spring.Press.dampingRatio)
        assertEquals(Spring.DampingRatioNoBouncy, AsterMotion.Spring.Release.dampingRatio)

        // Settle must be smooth with 0.85f damping to prevent jitter
        assertEquals(0.85f, AsterMotion.Spring.Settle.dampingRatio)
        assertEquals(Spring.StiffnessLow, AsterMotion.Spring.Settle.stiffness)

        // Drag must be high stiffness for instant tracking
        assertEquals(Spring.StiffnessHigh, AsterMotion.Spring.Drag.stiffness)
        assertEquals(Spring.DampingRatioNoBouncy, AsterMotion.Spring.Drag.dampingRatio)

        // Constellation reposition spring
        assertEquals(0.88f, AsterMotion.Spring.Reposition.dampingRatio)
        assertEquals(Spring.StiffnessLow, AsterMotion.Spring.Reposition.stiffness)
    }

    @Test
    fun `reduced motion collapses springs to high-stiffness non-bouncy`() {
        val normalSpring = AsterMotion.spring<Float>(AsterMotion.Spring.Press, reducedMotion = false)
        assertEquals(Spring.DampingRatioLowBouncy, normalSpring.dampingRatio)
        assertEquals(Spring.StiffnessMediumLow, normalSpring.stiffness)

        val reducedSpring = AsterMotion.spring<Float>(AsterMotion.Spring.Press, reducedMotion = true)
        assertEquals(Spring.DampingRatioNoBouncy, reducedSpring.dampingRatio)
        assertEquals(Spring.StiffnessHigh, reducedSpring.stiffness)
    }

    @Test
    fun `interaction scale tokens are subtle and collapse under reduced motion`() {
        assertEquals(1.0f, AsterMotion.Scale.Normal)
        assertEquals(0.96f, AsterMotion.Scale.Pressed)
        assertEquals(1.04f, AsterMotion.Scale.Focused)
        assertEquals(1.08f, AsterMotion.Scale.Elevated)
        assertEquals(0.92f, AsterMotion.Scale.Diminished)

        // In normal motion, target scale is honored
        assertEquals(0.96f, AsterMotion.scale(AsterMotion.Scale.Pressed, reducedMotion = false))
        assertEquals(1.08f, AsterMotion.scale(AsterMotion.Scale.Elevated, reducedMotion = false))

        // In reduced motion, scales collapse to 1.0f to avoid motion sickness
        assertEquals(1.0f, AsterMotion.scale(AsterMotion.Scale.Pressed, reducedMotion = true))
        assertEquals(1.0f, AsterMotion.scale(AsterMotion.Scale.Elevated, reducedMotion = true))
    }

    @Test
    fun `alpha and scrim tokens maintain strict hierarchy and preserve visibility`() {
        assertTrue(AsterMotion.Alpha.Ghost < AsterMotion.Alpha.Muted)
        assertTrue(AsterMotion.Alpha.Muted < AsterMotion.Alpha.Deemphasized)
        assertTrue(AsterMotion.Alpha.Deemphasized < AsterMotion.Alpha.Disabled)
        assertTrue(AsterMotion.Alpha.Disabled < AsterMotion.Alpha.Subtle)
        assertTrue(AsterMotion.Alpha.Subtle < AsterMotion.Alpha.Opaque)

        assertTrue(AsterMotion.Scrim.Light < AsterMotion.Scrim.Midnight)
        assertTrue(AsterMotion.Scrim.Midnight < AsterMotion.Scrim.Focus)

        // Alpha is preserved in reduced motion because it conveys essential visibility
        assertEquals(AsterMotion.Alpha.Subtle, AsterMotion.alpha(AsterMotion.Alpha.Subtle, reducedMotion = true))
    }

    @Test
    fun `AsterDragSettleState tracks offsets and settles cleanly`() = runTest {
        val dragSettle = AsterDragSettleState(initialX = 0f, initialY = 0f)

        assertEquals(0f, dragSettle.offsetX)
        assertEquals(0f, dragSettle.offsetY)
        assertFalse(dragSettle.isDragging)

        // Simulate finger dragging
        dragSettle.dragBy(20f, 40f)
        assertEquals(20f, dragSettle.offsetX)
        assertEquals(40f, dragSettle.offsetY)
        assertTrue(dragSettle.isDragging)

        dragSettle.dragBy(-5f, 10f)
        assertEquals(15f, dragSettle.offsetX)
        assertEquals(50f, dragSettle.offsetY)

        // Snap to coordinate
        dragSettle.snapTo(100f, 200f)
        assertEquals(100f, dragSettle.offsetX)
        assertEquals(200f, dragSettle.offsetY)
        assertFalse(dragSettle.isDragging)

        // Settle back to origin
        dragSettle.settleTo(0f, 0f, reducedMotion = true)
        assertEquals(0f, dragSettle.offsetX, 0.01f)
        assertEquals(0f, dragSettle.offsetY, 0.01f)
        assertFalse(dragSettle.isDragging)
    }

    @Test
    fun `system reduced motion detection correctly reads Android global settings`() {
        // Normal animation scales
        Settings.Global.putFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
        assertFalse(isSystemReducedMotionEnabled(context))

        // When transition animation scale is 0.0f
        Settings.Global.putFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 0.0f)
        assertTrue(isSystemReducedMotionEnabled(context))

        // Reset transition, disable animator duration scale
        Settings.Global.putFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.0f)
        assertTrue(isSystemReducedMotionEnabled(context))

        // Both disabled
        Settings.Global.putFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 0.0f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.0f)
        assertTrue(isSystemReducedMotionEnabled(context))
    }
}
