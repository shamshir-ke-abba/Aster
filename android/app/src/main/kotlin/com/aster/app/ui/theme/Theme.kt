package com.aster.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.aster.app.ui.motion.AsterHapticFeedback
import com.aster.app.ui.motion.LocalAsterHaptics
import com.aster.app.ui.motion.LocalReducedMotion
import com.aster.app.ui.motion.rememberAsterHaptics
import com.aster.app.ui.motion.rememberReducedMotionState

// ─────────────────────────────────────────────────────────────────────────────
// Aster Material 3 Color Scheme
// Aster is exclusively dark mode — cosmic, sovereign, atmospheric.
// ─────────────────────────────────────────────────────────────────────────────

private val AsterColorScheme = darkColorScheme(
    background = AsterNight,
    surface = AsterSurface,
    surfaceVariant = AsterElevated,

    primary = AsterViolet,
    onPrimary = AsterNight,
    primaryContainer = AsterElevated,
    onPrimaryContainer = AsterViolet,

    secondary = AsterGlow,
    onSecondary = AsterNight,
    secondaryContainer = AsterSurfaceElevated,
    onSecondaryContainer = AsterStar,

    onBackground = AsterStar,
    onSurface = AsterStar,
    onSurfaceVariant = AsterMuted,

    outline = AsterBorder,
    outlineVariant = AsterElevated,

    error = AsterCrimson,
    onError = AsterStar,
    errorContainer = Color(0xFF2E1216),
    onErrorContainer = AsterCrimson
)

@Composable
fun AsterTheme(
    reducedMotion: Boolean = rememberReducedMotionState(),
    haptics: AsterHapticFeedback = rememberAsterHaptics(reducedMotion),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalReducedMotion provides reducedMotion,
        LocalAsterHaptics provides haptics
    ) {
        MaterialTheme(
            colorScheme = AsterColorScheme,
            typography = AsterTypography,
            content = content,
        )
    }
}

