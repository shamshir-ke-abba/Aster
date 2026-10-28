package com.aster.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// Aster Design System Typography
// Authoritative references:
//   /Aster/design/ui-redesign/aster_design_system.png
//   /Aster/design/ui-redesign/aster_product_ui_reference.png
// Modern Sans (Inter / Manrope / Geist-style clean geometric sans-serif)
// ─────────────────────────────────────────────────────────────────────────────

val AsterTypography = Typography(
    // Wordmark / Large Branding Display: "ASTER"
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = 6.sp,
        color = AsterStar
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        letterSpacing = 2.sp,
        color = AsterStar
    ),
    // Screen Titles / Top Bar Headers: "Aster", "Connections", "Your Profile"
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = 0.sp,
        color = AsterStar
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        letterSpacing = 0.sp,
        color = AsterStar
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 0.sp,
        color = AsterStar
    ),
    // Body Text: Chat Messages, Description
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        letterSpacing = 0.15.sp,
        lineHeight = 22.sp,
        color = AsterStar
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp,
        lineHeight = 18.sp,
        color = AsterStar
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.25.sp,
        lineHeight = 16.sp,
        color = AsterMuted
    ),
    // Section Labels, Navigation Labels, Badges
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 0.5.sp,
        color = AsterStar
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
        color = AsterStar
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp,
        color = AsterMuted
    )
)
