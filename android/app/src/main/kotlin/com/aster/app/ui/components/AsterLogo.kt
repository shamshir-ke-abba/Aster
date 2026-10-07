package com.aster.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterViolet
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import com.aster.app.ui.theme.AsterTheme

/**
 * Aster Logo — canonical reusable composable.
 *
 * A four-pointed geometric star:
 * - Four long diamond arms (N, S, E, W) in Aster Violet
 * - Four short diagonal arms (NE, NW, SE, SW) in Aster Glow at 40% the length
 *   of the main arms, giving the symbol an 8-pointed character at larger sizes
 *   while reading as a clean 4-pointed star at small sizes.
 *
 * The geometry is normalized to a unit square and scaled by [size].
 * This composable is intentionally simple and has no animation — appropriate
 * for use in headers, launch screens, empty states, and app icons.
 *
 * Usage:
 *   AsterLogo(size = 48.dp)
 *   AsterLogo(size = 24.dp, primaryColor = AsterViolet, accentColor = AsterGlow)
 *
 * @param size Uniform size of the logo canvas (width = height = size)
 * @param primaryColor Color for the four primary arms (default Aster Violet)
 * @param accentColor Color for the four diagonal accent arms (default Aster Glow)
 */
@Composable
fun AsterLogo(
    size: Dp = 40.dp,
    primaryColor: Color = AsterViolet,
    accentColor: Color = AsterGlow,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.size(size),
    ) {
        drawAsterStar(
            center = Offset(this.size.width / 2f, this.size.height / 2f),
            radius = this.size.width / 2f,
            primaryColor = primaryColor,
            accentColor = accentColor,
        )
    }
}

/**
 * Draws the Aster star geometry onto a [DrawScope].
 *
 * The star is composed of:
 *  - 4 primary diamond arms (N/S/E/W) that form the dominant cross shape
 *  - 4 secondary diagonal arms (NE/NW/SE/SW) that are 38% the length and
 *    half the width of the primary arms, suggesting an 8-point star
 *
 * All points are computed relative to [center] and [radius], making the
 * drawing independent of canvas size.
 *
 * @param center Center point of the star
 * @param radius Half-width of the canvas — the primary arms extend 90% of radius
 * @param primaryColor Fill color for the primary arms
 * @param accentColor Fill color for the secondary diagonal arms
 */
private fun DrawScope.drawAsterStar(
    center: Offset,
    radius: Float,
    primaryColor: Color,
    accentColor: Color,
) {
    val cx = center.x
    val cy = center.y

    // Primary arm geometry
    // Arms extend 90% of radius from center; half-width at base is 10% of radius.
    val arm = radius * 0.90f
    val hw = radius * 0.10f  // half-width at the base of each arm

    // Secondary (diagonal) arm geometry
    val shortArm = radius * 0.42f
    val shortHw = radius * 0.055f

    // ── Draw secondary (diagonal) arms first so primary arms paint over them ──
    val secondaryPath = buildDiagonalArms(cx, cy, shortArm, shortHw)
    drawPath(path = secondaryPath, color = accentColor, style = Fill)

    // ── Draw primary (cardinal) arms ──
    val primaryPath = buildCardinalArms(cx, cy, arm, hw)
    drawPath(path = primaryPath, color = primaryColor, style = Fill)

    // ── Centre glow dot in accent color ──
    drawCircle(
        color = accentColor,
        radius = radius * 0.08f,
        center = center,
    )
}

/**
 * Builds a [Path] for the four cardinal arms (N, S, E, W) of the Aster star.
 * Each arm is a thin diamond: center-base → tip → center-base on the other side.
 */
private fun buildCardinalArms(
    cx: Float,
    cy: Float,
    arm: Float,
    hw: Float,
): Path = Path().apply {
    // North arm
    moveTo(cx, cy - arm)  // tip
    lineTo(cx + hw, cy)   // right base
    lineTo(cx, cy + arm)  // south tip (bottom of vertical diamond)
    lineTo(cx - hw, cy)   // left base
    close()

    // East arm
    moveTo(cx + arm, cy)  // tip
    lineTo(cx, cy + hw)
    lineTo(cx - arm, cy)  // west tip
    lineTo(cx, cy - hw)
    close()
}

/**
 * Builds a [Path] for the four diagonal accent arms (NE, NW, SE, SW).
 * Same diamond shape, rotated 45°.
 */
private fun buildDiagonalArms(
    cx: Float,
    cy: Float,
    arm: Float,
    hw: Float,
): Path {
    // 45° rotated: arm along diagonal, half-width along the perpendicular diagonal
    // tip_ne = (cx + arm/√2, cy - arm/√2)
    val d = arm * 0.7071f  // arm / √2
    val w = hw * 0.7071f   // hw / √2

    return Path().apply {
        // NE arm
        moveTo(cx + d, cy - d)          // NE tip
        lineTo(cx + w, cy + w)          // SE base
        lineTo(cx - d, cy + d)          // SW tip
        lineTo(cx - w, cy - w)          // NW base
        close()

        // NW arm
        moveTo(cx - d, cy - d)          // NW tip
        lineTo(cx + w, cy - w)          // NE base
        lineTo(cx + d, cy + d)          // SE tip
        lineTo(cx - w, cy + w)          // SW base
        close()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF080A12, name = "AsterLogo 48dp")
@Composable
private fun AsterLogoPreviewLarge() {
    AsterTheme {
        Box(Modifier.size(80.dp).background(AsterNight)) {
            AsterLogo(size = 48.dp, modifier = Modifier)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF080A12, name = "AsterLogo 24dp")
@Composable
private fun AsterLogoPreviewSmall() {
    AsterTheme {
        Box(Modifier.size(48.dp).background(AsterNight)) {
            AsterLogo(size = 24.dp)
        }
    }
}
