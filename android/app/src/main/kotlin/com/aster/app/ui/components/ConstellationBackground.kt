package com.aster.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.tooling.preview.Preview
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterTheme
import com.aster.app.ui.theme.AsterViolet
import kotlin.math.min

/**
 * ConstellationBackground — lightweight static constellation decoration.
 *
 * Renders a sparse set of star points connected by faint lines, evenly
 * distributed across the canvas using a deterministic layout (no random seed,
 * so the layout is stable across recompositions and on low-end devices).
 *
 * Design constraints:
 *  - NO particle system, NO animation, NO physics
 *  - NO uniform grid — positions are varied and asymmetric
 *  - Connecting lines are very faint (low alpha) to remain atmospheric
 *  - Points vary in size to suggest depth
 *  - Background is Aster Night
 *
 * Performance notes:
 *  - All geometry is pre-computed in [remember] so DrawScope is allocation-free
 *  - Canvas draw calls are minimal (no layer save/restore, no blend modes)
 *  - Safe for use on mid/low-end devices
 *
 * @param modifier Modifier to apply to the Canvas (typically fillMaxSize)
 */
@Composable
fun ConstellationBackground(
    modifier: Modifier = Modifier,
) {
    // Pre-compute static star layout — deterministic, not randomized.
    // Expressed as normalized (0..1, 0..1) coordinates so they scale to any canvas.
    // 22 points in varied positions — sparse, not a grid.
    val starPositions: List<StarPoint> = remember {
        listOf(
            StarPoint(0.08f, 0.11f, 2.8f, 0.85f),
            StarPoint(0.24f, 0.05f, 1.6f, 0.60f),
            StarPoint(0.45f, 0.09f, 2.2f, 0.75f),
            StarPoint(0.71f, 0.04f, 1.4f, 0.55f),
            StarPoint(0.88f, 0.14f, 2.6f, 0.80f),
            StarPoint(0.96f, 0.31f, 1.8f, 0.65f),
            StarPoint(0.13f, 0.28f, 1.4f, 0.50f),
            StarPoint(0.33f, 0.22f, 2.0f, 0.70f),
            StarPoint(0.58f, 0.27f, 1.6f, 0.60f),
            StarPoint(0.79f, 0.22f, 2.4f, 0.78f),
            StarPoint(0.06f, 0.52f, 1.8f, 0.55f),
            StarPoint(0.22f, 0.47f, 1.2f, 0.45f),
            StarPoint(0.51f, 0.43f, 2.6f, 0.88f),
            StarPoint(0.68f, 0.51f, 1.4f, 0.52f),
            StarPoint(0.91f, 0.48f, 2.0f, 0.70f),
            StarPoint(0.14f, 0.70f, 2.2f, 0.72f),
            StarPoint(0.38f, 0.65f, 1.6f, 0.58f),
            StarPoint(0.62f, 0.72f, 1.8f, 0.65f),
            StarPoint(0.83f, 0.68f, 2.4f, 0.80f),
            StarPoint(0.05f, 0.89f, 1.4f, 0.50f),
            StarPoint(0.29f, 0.88f, 2.0f, 0.68f),
            StarPoint(0.55f, 0.93f, 1.6f, 0.60f),
        )
    }

    // Connecting line pairs — indices into starPositions.
    // Chosen to form a plausible sparse constellation, not a complete graph.
    val connections: List<Pair<Int, Int>> = remember {
        listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            4 to 5, 0 to 6, 1 to 7, 2 to 7,
            3 to 8, 4 to 9, 5 to 9, 6 to 7,
            7 to 10, 7 to 12, 8 to 12, 9 to 13,
            10 to 11, 11 to 12, 12 to 13, 13 to 14,
            10 to 15, 12 to 16, 13 to 17, 14 to 18,
            15 to 19, 15 to 20, 16 to 20, 17 to 21,
        )
    }

    Canvas(modifier = modifier) {
        drawConstellationLines(starPositions, connections)
        drawConstellationStars(starPositions)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private drawing helpers
// ─────────────────────────────────────────────────────────────────────────────

private data class StarPoint(
    val nx: Float,      // normalized x [0,1]
    val ny: Float,      // normalized y [0,1]
    val radius: Float,  // point radius in dp-equivalent pixels (before density)
    val alpha: Float,   // point alpha [0,1]
)

private fun DrawScope.drawConstellationLines(
    stars: List<StarPoint>,
    connections: List<Pair<Int, Int>>,
) {
    val w = size.width
    val h = size.height
    val lineColor = AsterViolet.copy(alpha = 0.10f)  // very faint — atmospheric only

    connections.forEach { (i, j) ->
        if (i < stars.size && j < stars.size) {
            val a = stars[i]
            val b = stars[j]
            drawLine(
                color = lineColor,
                start = Offset(a.nx * w, a.ny * h),
                end = Offset(b.nx * w, b.ny * h),
                strokeWidth = 0.8f,
            )
        }
    }
}

private fun DrawScope.drawConstellationStars(stars: List<StarPoint>) {
    val w = size.width
    val h = size.height
    val minDim = min(w, h)

    stars.forEach { star ->
        // Scale radius relative to canvas size for density independence
        val r = star.radius * (minDim / 360f)

        // Point in Aster Glow at the star's alpha
        drawCircle(
            color = AsterGlow.copy(alpha = star.alpha),
            radius = r,
            center = Offset(star.nx * w, star.ny * h),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Preview
// ─────────────────────────────────────────────────────────────────────────────

@Preview(
    showBackground = true,
    backgroundColor = 0xFF080A12,
    widthDp = 360,
    heightDp = 640,
    name = "ConstellationBackground",
)
@Composable
private fun ConstellationBackgroundPreview() {
    AsterTheme {
        ConstellationBackground(modifier = Modifier.fillMaxSize())
    }
}
