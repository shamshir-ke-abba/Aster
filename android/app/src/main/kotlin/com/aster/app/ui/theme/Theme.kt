package com.aster.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// Aster Material 3 Color Scheme
//
// Aster is a dark-only application — there is no light theme variant.
// The Material3 color slots are mapped to Aster canonical color tokens.
// ─────────────────────────────────────────────────────────────────────────────

private val AsterColorScheme = darkColorScheme(
    // Backgrounds
    background = AsterNight,
    surface = AsterSurface,
    surfaceVariant = AsterElevated,

    // Primary (accent / interactive)
    primary = AsterViolet,
    onPrimary = AsterNight,
    primaryContainer = AsterElevated,
    onPrimaryContainer = AsterGlow,

    // Secondary (glow highlights)
    secondary = AsterGlow,
    onSecondary = AsterNight,

    // On-colors (text over backgrounds)
    onBackground = AsterStar,
    onSurface = AsterStar,
    onSurfaceVariant = AsterGlow,

    // Outline
    outline = AsterElevated,
    outlineVariant = AsterSurface,

    // Keep error colors at M3 defaults (red family) — no overrides needed.
    error = Color(0xFFCF6679),
    onError = Color(0xFF601410),
)

/**
 * Root Compose theme for the Aster application.
 *
 * Always dark — Aster has no light mode variant.
 * dynamicColor is intentionally disabled; Aster uses its own canonical palette.
 */
@Composable
fun AsterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AsterColorScheme,
        typography = AsterTypography,
        content = content,
    )
}
