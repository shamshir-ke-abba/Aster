package com.aster.app

import com.aster.app.ui.theme.AsterBorder
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterMuted
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterOnline
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterSurfaceElevated
import com.aster.app.ui.theme.AsterViolet
import com.aster.app.ui.theme.AsterWarm
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that the Compose color tokens match the authoritative specification
 * from /Aster/design/ui-redesign/aster_design_system.png.
 */
class AsterThemeTest {

    private fun Color.toArgbLong(): Long {
        // Compose Color.value is ULong with ARGB in the upper 32 bits
        return (this.value shr 32).toLong() and 0xFFFFFFFFL
    }

    @Test
    fun verifyCanonicalColors() {
        assertEquals("Aster Night must be #080A12", 0xFF080A12L, AsterNight.toArgbLong())
        assertEquals("Aster Surface must be #111522", 0xFF111522L, AsterSurface.toArgbLong())
        assertEquals("Aster Elevated must be #161B2E", 0xFF161B2EL, AsterElevated.toArgbLong())
        assertEquals("Aster Surface Elevated must be #1E2438", 0xFF1E2438L, AsterSurfaceElevated.toArgbLong())
        assertEquals("Aster Violet must be #A37FF5", 0xFFA37FF5L, AsterViolet.toArgbLong())
        assertEquals("Aster Star must be #F0F0F5", 0xFFF0F0F5L, AsterStar.toArgbLong())
        assertEquals("Aster Muted must be #8E95AA", 0xFF8E95AAL, AsterMuted.toArgbLong())
        assertEquals("Aster Border must be #1F2538", 0xFF1F2538L, AsterBorder.toArgbLong())
        assertEquals("Aster Warm must be #E0B050", 0xFFE0B050L, AsterWarm.toArgbLong())
        assertEquals("Aster Online must be #34D399", 0xFF34D399L, AsterOnline.toArgbLong())
    }
}
