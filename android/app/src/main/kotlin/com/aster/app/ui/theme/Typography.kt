package com.aster.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

// ─────────────────────────────────────────────────────────────────────────────
// Aster Typography
//
// Design direction: Inter / Manrope / Geist-style geometric sans-serif.
// The Inter font is NOT bundled in this project. We use the platform default
// sans-serif (which is Roboto on Android) as an intentional fallback.
//
// When Inter is bundled (via downloadable fonts or bundled assets), update
// FontFamily.Default to reference the Inter font family here.
// ─────────────────────────────────────────────────────────────────────────────

val AsterTypography = Typography(
    // Wordmark / large display — ASTER branding
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Light,
        fontSize = 32.sp,
        letterSpacing = 12.sp,
    ),
    // Screen titles
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 0.sp,
    ),
    // Body text — conversations, descriptions
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        letterSpacing = 0.15.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        letterSpacing = 0.25.sp,
        lineHeight = 20.sp,
    ),
    // Labels and captions
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
)
