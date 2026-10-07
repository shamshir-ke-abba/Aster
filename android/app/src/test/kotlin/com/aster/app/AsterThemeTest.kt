package com.aster.app

import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterViolet
import com.aster.app.ui.theme.AsterWarm
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that the Compose color tokens match the canonical specification
 * from Aster/design/design.md exactly.
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
        assertEquals("Aster Elevated must be #181C2A", 0xFF181C2AL, AsterElevated.toArgbLong())
        assertEquals("Aster Violet must be #8B8DFF", 0xFF8B8DFFL, AsterViolet.toArgbLong())
        assertEquals("Aster Glow must be #B8B9FF", 0xFFB8B9FFL, AsterGlow.toArgbLong())
        assertEquals("Aster Star must be #F3F1FF", 0xFFF3F1FFL, AsterStar.toArgbLong())
        assertEquals("Aster Warm must be #FFDFA3", 0xFFFFDFA3L, AsterWarm.toArgbLong())
    }
}
