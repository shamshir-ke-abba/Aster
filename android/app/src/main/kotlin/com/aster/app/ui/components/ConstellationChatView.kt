package com.aster.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aster.app.ui.motion.asterDialogTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aster.app.ui.motion.AsterHapticEvent
import com.aster.app.ui.motion.AsterMotion
import com.aster.app.ui.motion.AsterMotionTokens
import com.aster.app.ui.motion.LocalAsterHaptics
import com.aster.app.ui.motion.LocalReducedMotion
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.User
import com.aster.app.ui.theme.AsterBorder
import com.aster.app.ui.theme.AsterCrimson
import com.aster.app.ui.theme.AsterCrimsonGlow
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterOnline
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurfaceElevated
import com.aster.app.ui.theme.AsterViolet
import com.aster.app.ui.theme.AsterVioletSoft
import com.aster.app.ui.theme.AsterWarm
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Axis-Aligned Bounding Box for constellation visual elements.
 * Operates on the full combined visual layout unit (Avatar/Nexus + Satellite Nodes + Labels).
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun intersects(other: BoundingBox): Boolean {
        return left < other.right && right > other.left &&
               top < other.bottom && bottom > other.top
    }

    fun contains(point: Offset): Boolean {
        return point.x in left..right && point.y in top..bottom
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * 1:1 Direct Conversation Node in the Constellation.
 */
data class DirectConstellationNode(
    val conversation: Conversation,
    val offset: Offset,
    val bounds: BoundingBox,
    val titleDisplay: String
)

/**
 * Satellite member node branching from a Group Nexus.
 */
data class GroupSatelliteMember(
    val id: String,
    val name: String,
    val initial: String,
    val isOnline: Boolean,
    val offset: Offset
)

/**
 * Group Constellation Cluster participating as a large unified layout unit.
 */
data class GroupConstellationNode(
    val conversation: Conversation,
    val nexusOffset: Offset,
    val satellites: List<GroupSatelliteMember>,
    val bounds: BoundingBox,
    val titleDisplay: String,
    val subtitleDisplay: String,
    val labelOffsetY: Float
)

/**
 * Pending connection candidate node in the Constellation.
 */
data class PendingConstellationNode(
    val request: com.aster.app.data.network.FriendRequestSummary,
    val offset: Offset,
    val bounds: BoundingBox,
    val titleDisplay: String
)

/**
 * Complete calculated constellation layout.
 */
data class ConstellationLayout(
    val directNodes: List<DirectConstellationNode>,
    val groupNodes: List<GroupConstellationNode>,
    val pendingNodes: List<PendingConstellationNode> = emptyList(),
    val youBounds: BoundingBox
)

// Constellation Zoom Constants (Phase 4.3)
const val CONSTELLATION_MIN_ZOOM = 0.55f
const val CONSTELLATION_DEFAULT_ZOOM = 1.0f
const val CONSTELLATION_MAX_ZOOM = 2.25f

/**
 * Clamps zoom scale to reasonable bounds.
 */
fun clampZoom(scale: Float): Float = scale.coerceIn(CONSTELLATION_MIN_ZOOM, CONSTELLATION_MAX_ZOOM)

/**
 * Clamps pan translation so the constellation remains easily retrievable.
 */
fun clampPanOffset(pan: Offset, zoomScale: Float, maxPanPx: Float = 1600f): Offset {
    val bound = maxPanPx * maxOf(1f, zoomScale)
    return Offset(
        x = pan.x.coerceIn(-bound, bound),
        y = pan.y.coerceIn(-bound, bound)
    )
}

/**
 * Transforms world coordinates to screen pixel coordinates.
 */
fun worldToScreen(worldOffset: Offset, canvasCenter: Offset, panOffset: Offset, zoomScale: Float): Offset {
    return canvasCenter + panOffset + (worldOffset * zoomScale)
}

/**
 * Transforms screen pixel coordinates to world coordinates.
 */
fun screenToWorld(screenOffset: Offset, canvasCenter: Offset, panOffset: Offset, zoomScale: Float): Offset {
    return (screenOffset - canvasCenter - panOffset) / zoomScale
}

/**
 * Transforms a world-space BoundingBox into screen-space coordinates.
 */
fun worldBoxToScreenBox(
    worldBox: BoundingBox,
    canvasCenter: Offset,
    panOffset: Offset,
    zoomScale: Float
): BoundingBox {
    val l = canvasCenter.x + panOffset.x + (worldBox.left * zoomScale)
    val t = canvasCenter.y + panOffset.y + (worldBox.top * zoomScale)
    val r = canvasCenter.x + panOffset.x + (worldBox.right * zoomScale)
    val b = canvasCenter.y + panOffset.y + (worldBox.bottom * zoomScale)
    return BoundingBox(
        left = minOf(l, r),
        top = minOf(t, b),
        right = maxOf(l, r),
        bottom = maxOf(t, b)
    )
}

/**
 * Calculates the current visible viewport rectangle in world coordinates.
 */
fun getViewportWorldBounds(
    viewportWidth: Float,
    viewportHeight: Float,
    canvasCenter: Offset,
    panOffset: Offset,
    zoomScale: Float
): BoundingBox {
    val left = (0f - canvasCenter.x - panOffset.x) / zoomScale
    val top = (0f - canvasCenter.y - panOffset.y) / zoomScale
    val right = (viewportWidth - canvasCenter.x - panOffset.x) / zoomScale
    val bottom = (viewportHeight - canvasCenter.y - panOffset.y) / zoomScale
    return BoundingBox(left = left, top = top, right = right, bottom = bottom)
}

/**
 * Detected conversation that is outside the visible viewport.
 */
data class OffScreenConversation(
    val conversation: Conversation,
    val worldOffset: Offset,
    val isGroup: Boolean,
    val bounds: BoundingBox
)

/**
 * Detects which conversations are meaningfully outside the current visible viewport.
 *
 * Requirements (Phase 4.3.1):
 *  - Evaluates visibility strictly against actual screen/viewport boundaries after zoom and pan.
 *  - Distance from YOU has ZERO influence on whether an object is considered off-screen.
 *  - Direct contacts: avatar circle + label bounding box tested against viewport.
 *  - Groups: full transformed cluster bounds (nexus + satellites + labels) tested against viewport.
 *  - Avoids false indicators when the meaningful conversation node is clearly visible.
 */
fun findOffScreenConversations(
    layout: ConstellationLayout,
    viewportWidth: Float,
    viewportHeight: Float,
    canvasCenter: Offset,
    panOffset: Offset,
    zoomScale: Float,
    density: Density
): List<OffScreenConversation> {
    if (viewportWidth <= 0f || viewportHeight <= 0f) return emptyList()

    val viewportBox = BoundingBox(
        left = 0f,
        top = 0f,
        right = viewportWidth,
        bottom = viewportHeight
    )

    val offScreen = mutableListOf<OffScreenConversation>()

    // 1. Direct contacts: visible if avatar or label bounds intersect viewport
    for (node in layout.directNodes) {
        val screenBounds = worldBoxToScreenBox(node.bounds, canvasCenter, panOffset, zoomScale)
        val nodeScreenPos = worldToScreen(node.offset, canvasCenter, panOffset, zoomScale)
        val avatarRadiusPx = with(density) { 22.dp.toPx() } * zoomScale

        val avatarVisible = nodeScreenPos.x in (-avatarRadiusPx)..(viewportWidth + avatarRadiusPx) &&
                            nodeScreenPos.y in (-avatarRadiusPx)..(viewportHeight + avatarRadiusPx)
        val boundsVisible = screenBounds.intersects(viewportBox)

        if (!avatarVisible && !boundsVisible) {
            offScreen.add(
                OffScreenConversation(
                    conversation = node.conversation,
                    worldOffset = node.offset,
                    isGroup = false,
                    bounds = node.bounds
                )
            )
        }
    }

    // 2. Group clusters: visible if nexus, any satellite, or cluster bounds intersect viewport
    for (group in layout.groupNodes) {
        val screenBounds = worldBoxToScreenBox(group.bounds, canvasCenter, panOffset, zoomScale)
        val nexusScreenPos = worldToScreen(group.nexusOffset, canvasCenter, panOffset, zoomScale)
        val nexusRadiusPx = with(density) { 26.dp.toPx() } * zoomScale

        val nexusVisible = nexusScreenPos.x in (-nexusRadiusPx)..(viewportWidth + nexusRadiusPx) &&
                           nexusScreenPos.y in (-nexusRadiusPx)..(viewportHeight + nexusRadiusPx)

        val anySatVisible = group.satellites.any { sat ->
            val satScreenPos = worldToScreen(sat.offset, canvasCenter, panOffset, zoomScale)
            val satRadiusPx = with(density) { 13.dp.toPx() } * zoomScale
            satScreenPos.x in (-satRadiusPx)..(viewportWidth + satRadiusPx) &&
            satScreenPos.y in (-satRadiusPx)..(viewportHeight + satRadiusPx)
        }

        val boundsVisible = screenBounds.intersects(viewportBox)

        if (!nexusVisible && !anySatVisible && !boundsVisible) {
            offScreen.add(
                OffScreenConversation(
                    conversation = group.conversation,
                    worldOffset = group.nexusOffset,
                    isGroup = true,
                    bounds = group.bounds
                )
            )
        }
    }

    return offScreen
}

/**
 * Backward-compatible overload for world-space viewport tests.
 */
fun findOffScreenConversations(
    layout: ConstellationLayout,
    viewportBounds: BoundingBox
): List<OffScreenConversation> {
    val offScreen = mutableListOf<OffScreenConversation>()

    for (node in layout.directNodes) {
        if (!viewportBounds.intersects(node.bounds)) {
            offScreen.add(
                OffScreenConversation(
                    conversation = node.conversation,
                    worldOffset = node.offset,
                    isGroup = false,
                    bounds = node.bounds
                )
            )
        }
    }

    for (group in layout.groupNodes) {
        if (!viewportBounds.intersects(group.bounds)) {
            offScreen.add(
                OffScreenConversation(
                    conversation = group.conversation,
                    worldOffset = group.nexusOffset,
                    isGroup = true,
                    bounds = group.bounds
                )
            )
        }
    }

    return offScreen
}

/**
 * Intersects a ray from rayOrigin at angleRad with an axis-aligned bounding rectangle.
 */
fun projectRayToRectEdge(
    rayOrigin: Offset,
    angleRad: Double,
    rectLeft: Float,
    rectTop: Float,
    rectRight: Float,
    rectBottom: Float
): Offset {
    val cosA = cos(angleRad).toFloat()
    val sinA = sin(angleRad).toFloat()

    val tx = if (cosA > 1e-5f) {
        (rectRight - rayOrigin.x) / cosA
    } else if (cosA < -1e-5f) {
        (rectLeft - rayOrigin.x) / cosA
    } else {
        Float.MAX_VALUE
    }

    val ty = if (sinA > 1e-5f) {
        (rectBottom - rayOrigin.y) / sinA
    } else if (sinA < -1e-5f) {
        (rectTop - rayOrigin.y) / sinA
    } else {
        Float.MAX_VALUE
    }

    val t = minOf(tx, ty).coerceAtLeast(0f)
    return Offset(
        x = (rayOrigin.x + t * cosA).coerceIn(rectLeft, rectRight),
        y = (rayOrigin.y + t * sinA).coerceIn(rectTop, rectBottom)
    )
}

/**
 * Renderable directional indicator pointing toward off-screen content.
 * Merges nearby items cleanly to prevent visual clustering.
 */
data class OffScreenIndicator(
    val id: String,
    val screenPosition: Offset,
    val angleRad: Double,
    val items: List<OffScreenConversation>,
    val targetWorldOffset: Offset
) {
    val count: Int get() = items.size
    val hasGroup: Boolean get() = items.any { it.isGroup }
    val isGroupOnly: Boolean get() = items.all { it.isGroup }
    val hasUnread: Boolean get() = items.any { it.conversation.unreadCount > 0 }
    val totalUnreadCount: Int get() = items.sumOf { it.conversation.unreadCount }
}

/**
 * Calculates aggregated off-screen indicators clamped to the screen margin.
 */
fun calculateOffScreenIndicators(
    offScreenList: List<OffScreenConversation>,
    viewportWidth: Float,
    viewportHeight: Float,
    canvasCenter: Offset,
    panOffset: Offset,
    zoomScale: Float,
    density: Density
): List<OffScreenIndicator> {
    if (offScreenList.isEmpty() || viewportWidth <= 0f || viewportHeight <= 0f) {
        return emptyList()
    }

    val marginHorizontal = with(density) { 26.dp.toPx() }
    val marginTop = with(density) { 20.dp.toPx() }
    val marginBottom = with(density) { 26.dp.toPx() }

    val safeLeft = marginHorizontal
    val safeTop = marginTop
    val safeRight = (viewportWidth - marginHorizontal).coerceAtLeast(safeLeft + 1f)
    val safeBottom = (viewportHeight - marginBottom).coerceAtLeast(safeTop + 1f)

    val safeCenter = Offset((safeLeft + safeRight) / 2f, (safeTop + safeBottom) / 2f)
    val viewportCenter = Offset(viewportWidth / 2f, viewportHeight / 2f)

    data class ProjectedItem(
        val item: OffScreenConversation,
        val angleRad: Double,
        val edgePos: Offset
    )

    val projectedItems = offScreenList.map { item ->
        val itemScreenPos = worldToScreen(item.worldOffset, canvasCenter, panOffset, zoomScale)
        val dx = itemScreenPos.x - viewportCenter.x
        val dy = itemScreenPos.y - viewportCenter.y
        val angle = atan2(dy.toDouble(), dx.toDouble())
        val edgePos = projectRayToRectEdge(viewportCenter, angle, safeLeft, safeTop, safeRight, safeBottom)
        ProjectedItem(item, angle, edgePos)
    }

    val sorted = projectedItems.sortedBy { it.angleRad }

    // Merge nearby off-screen items within ~52dp along edge or small angular distance
    val clusterThresholdPx = with(density) { 52.dp.toPx() }
    val clusters = mutableListOf<MutableList<ProjectedItem>>()

    for (p in sorted) {
        val lastCluster = clusters.lastOrNull()
        if (lastCluster == null) {
            clusters.add(mutableListOf(p))
        } else {
            val prev = lastCluster.last()
            val dist = hypot(p.edgePos.x - prev.edgePos.x, p.edgePos.y - prev.edgePos.y)
            val angleDiff = abs(p.angleRad - prev.angleRad)
            if (dist <= clusterThresholdPx || angleDiff < 0.45) {
                lastCluster.add(p)
            } else {
                clusters.add(mutableListOf(p))
            }
        }
    }

    // Handle wrap-around across -π / +π seam
    if (clusters.size > 1) {
        val firstCluster = clusters.first()
        val lastCluster = clusters.last()
        val dist = hypot(
            firstCluster.first().edgePos.x - lastCluster.last().edgePos.x,
            firstCluster.first().edgePos.y - lastCluster.last().edgePos.y
        )
        if (dist <= clusterThresholdPx) {
            lastCluster.addAll(firstCluster)
            clusters.removeAt(0)
        }
    }

    return clusters.map { cluster ->
        val avgAngle = cluster.map { it.angleRad }.average()
        val edgePos = projectRayToRectEdge(viewportCenter, avgAngle, safeLeft, safeTop, safeRight, safeBottom)
        val priorityTarget = cluster.firstOrNull { it.item.conversation.unreadCount > 0 }?.item ?: cluster.first().item
        OffScreenIndicator(
            id = cluster.joinToString("_") { it.item.conversation.id },
            screenPosition = edgePos,
            angleRad = avgAngle,
            items = cluster.map { it.item },
            targetWorldOffset = priorityTarget.worldOffset
        )
    }
}

/**
 * Deterministic pseudo-random number generator for stable seeded placement.
 */
class DeterministicRng(seed: Long) {
    private var state = (seed xor 0x5DEECE66DL) and ((1L shl 48) - 1)

    fun nextFloat(): Float {
        state = (state * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
        return (state ushr 24).toFloat() / (1 shl 24).toFloat()
    }

    fun nextInt(bound: Int): Int {
        if (bound <= 0) return 0
        return (nextFloat() * bound).toInt().coerceIn(0, bound - 1)
    }
}

/**
 * Derives a stable 64-bit seed from a string identifier.
 */
fun getStableSeed(id: String): Long {
    var hash = 1125899906842597L
    for (c in id) {
        hash = 31L * hash + c.code
    }
    return hash
}

/**
 * Controlled name truncation: handles long names gracefully with an ellipsis
 * without allowing collision or micro-text degradation.
 */
fun truncateTitle(title: String, maxLength: Int = 14): String {
    val trimmed = title.trim()
    return if (trimmed.length > maxLength) {
        trimmed.take(maxLength - 2) + "…"
    } else {
        trimmed.ifEmpty { "Chat" }
    }
}

/**
 * Safe text width measurement supporting both real Android Canvas and test/mock environments.
 */
fun measureTextWidthSafe(paint: android.graphics.Paint?, text: String): Float {
    return try {
        val measured = paint?.measureText(text) ?: 0f
        if (measured > 0f) measured else (text.length * 15f)
    } catch (_: Throwable) {
        text.length * 15f
    }
}

/**
 * Calculates a deterministic spatial constellation layout with mandatory collision avoidance.
 *
 * Rules:
 *  - YOU is an anchor at (0, 0), not the center of a fixed circular orbit.
 *  - Contacts & groups are distributed at variable organic distances and directions.
 *  - Every conversation uses a deterministic seeded candidate generator based on conversation ID.
 *  - Avatar + Label + Badges + Satellites are treated as ONE combined layout unit.
 *  - Mandatory collision detection rejects candidate positions that cause visual overlaps.
 */
fun calculateConstellationLayout(
    conversations: List<Conversation>,
    currentUserId: String,
    density: Density,
    textPaint: android.graphics.Paint? = null,
    pendingRequests: List<com.aster.app.data.network.FriendRequestSummary> = emptyList()
): ConstellationLayout {
    val youCoreRadius = with(density) { 22.dp.toPx() }
    val youAuraRadius = with(density) { 44.dp.toPx() }
    val youMargin = with(density) { 8.dp.toPx() }

    val youBounds = BoundingBox(
        left = -youAuraRadius - youMargin,
        top = -youAuraRadius - youMargin,
        right = youAuraRadius + youMargin,
        bottom = youCoreRadius + with(density) { 26.dp.toPx() } + youMargin
    )

    if (conversations.isEmpty() && pendingRequests.isEmpty()) {
        return ConstellationLayout(emptyList(), emptyList(), emptyList(), youBounds)
    }

    val placedBoxes = mutableListOf<BoundingBox>()
    placedBoxes.add(youBounds)

    val stableOrdered = conversations.sortedBy { it.id }

    val sortedConvs = conversations.sortedWith(
        compareBy<Conversation> { it.customNormalizedX == null }
            .thenBy { !it.isHighlighted }
            .thenBy { !it.isGroup }
            .thenBy { it.id }
    )

    val directNodes = mutableListOf<DirectConstellationNode>()
    val groupNodes = mutableListOf<GroupConstellationNode>()

    val goldenAngle = 2.399963229728653 // Radians (~137.507764 degrees)

    val directTiers = listOf(
        with(density) { 125.dp.toPx() },
        with(density) { 175.dp.toPx() },
        with(density) { 230.dp.toPx() },
        with(density) { 285.dp.toPx() },
        with(density) { 345.dp.toPx() },
        with(density) { 410.dp.toPx() }
    )

    val groupTiers = listOf(
        with(density) { 165.dp.toPx() },
        with(density) { 225.dp.toPx() },
        with(density) { 285.dp.toPx() },
        with(density) { 345.dp.toPx() },
        with(density) { 410.dp.toPx() }
    )

    val avatarRadius = with(density) { 22.dp.toPx() }
    val nexusRadius = with(density) { 26.dp.toPx() }
    val satDist = with(density) { 48.dp.toPx() }
    val satRadius = with(density) { 13.dp.toPx() }
    val safetyMargin = with(density) { 10.dp.toPx() }

    for (conv in sortedConvs) {
        val stableIndex = stableOrdered.indexOfFirst { it.id == conv.id }.coerceAtLeast(0)
        val seed = getStableSeed(conv.id)
        val rng = DeterministicRng(seed)

        val angleJitter = (rng.nextFloat() - 0.5f) * 0.35f
        val baseAngle = -PI / 2.0 + (stableIndex * goldenAngle) + angleJitter

        val baseTierDist = if (conv.isHighlighted) {
            // Highlighted nodes get deterministic priority in viewing sweet-spot Tier 0
            if (conv.isGroup) {
                groupTiers[0] + (rng.nextFloat() - 0.5f) * with(density) { 16.dp.toPx() }
            } else {
                directTiers[0] + (rng.nextFloat() - 0.5f) * with(density) { 16.dp.toPx() }
            }
        } else if (conv.isGroup) {
            val tierIdx = (stableIndex / 2) % groupTiers.size
            val distJitter = (rng.nextFloat() - 0.5f) * with(density) { 24.dp.toPx() }
            groupTiers[tierIdx] + distJitter
        } else {
            val tierIdx = (stableIndex / 2) % directTiers.size
            val distJitter = (rng.nextFloat() - 0.5f) * with(density) { 22.dp.toPx() }
            directTiers[tierIdx] + distJitter
        }

        val hasCustomPos = conv.customNormalizedX != null && conv.customNormalizedY != null
        val customX = if (hasCustomPos) conv.customNormalizedX!! * density.density else 0f
        val customY = if (hasCustomPos) conv.customNormalizedY!! * density.density else 0f

        // Authoritative custom position handling (Phase 8.6 Repair):
        // If a node was explicitly positioned by the user, honor its resolved world position 100%.
        // Never subject custom positions to spiral relocation or fallback pushing.
        if (hasCustomPos) {
            val cx = customX
            val cy = customY

            if (!conv.isGroup) {
                val rawTitle = if (conv.otherMember?.isDeleted == true) "Deleted User" else conv.title
                val titleDisplay = truncateTitle(rawTitle)
                val textWidth = measureTextWidthSafe(textPaint, titleDisplay)
                val halfW = maxOf(avatarRadius + with(density) { 4.dp.toPx() }, (textWidth / 2f) + with(density) { 6.dp.toPx() })
                val top = cy - avatarRadius - with(density) { 8.dp.toPx() }
                val bottom = cy + avatarRadius + with(density) { 24.dp.toPx() }

                val box = BoundingBox(
                    left = cx - halfW - safetyMargin,
                    top = top - safetyMargin,
                    right = cx + halfW + safetyMargin,
                    bottom = bottom + safetyMargin
                )

                placedBoxes.add(box)
                directNodes.add(
                    DirectConstellationNode(
                        conversation = conv,
                        offset = Offset(cx, cy),
                        bounds = box,
                        titleDisplay = titleDisplay
                    )
                )
            } else {
                val angleFromCenter = atan2(cy.toDouble(), cx.toDouble())
                val rawMembers = conv.members.filter { it.id != currentUserId }
                val displayMembers: List<User> = if (rawMembers.size >= 2) {
                    rawMembers.take(4)
                } else if (rawMembers.size == 1) {
                    val words = conv.title.split(" ", "_", "-").filter { it.isNotBlank() }
                    val extraName = words.firstOrNull { !it.equals(rawMembers[0].displayName, ignoreCase = true) } ?: "Member"
                    listOf(
                        rawMembers[0],
                        User(id = "${conv.id}_m_extra", username = extraName.lowercase(), displayName = extraName, isLocalUser = false, isOnline = false)
                    )
                } else {
                    val words = conv.title.split(" ", "_", "-").filter { it.isNotBlank() }
                    if (words.size >= 2) {
                        words.take(3).mapIndexed { idx, word ->
                            User(id = "${conv.id}_m$idx", username = word.lowercase(), displayName = word, isLocalUser = false, isOnline = false)
                        }
                    } else {
                        listOf(
                            User(id = "${conv.id}_m1", username = "alpha", displayName = "Alpha", isLocalUser = false, isOnline = false),
                            User(id = "${conv.id}_m2", username = "beta", displayName = "Beta", isLocalUser = false, isOnline = false),
                            User(id = "${conv.id}_m3", username = "gamma", displayName = "Gamma", isLocalUser = false, isOnline = false)
                        )
                    }
                }

                val numSat = displayMembers.size.coerceIn(1, 4)
                val deltaAngles = when (numSat) {
                    1 -> listOf(0.0)
                    2 -> listOf(-0.42, 0.42)
                    3 -> listOf(-0.52, 0.0, 0.52)
                    else -> listOf(-0.68, -0.22, 0.22, 0.68)
                }

                val satellites = mutableListOf<GroupSatelliteMember>()
                val countToRender = minOf(numSat, displayMembers.size, deltaAngles.size)
                for (idx in 0 until countToRender) {
                    val member = displayMembers[idx]
                    val satAngle = angleFromCenter + deltaAngles[idx]
                    val sx = cx + (satDist * cos(satAngle)).toFloat()
                    val sy = cy + (satDist * sin(satAngle)).toFloat()
                    val memberName = if (member.isDeleted) "Deleted User" else member.displayName
                    val initial = memberName.trim().take(1).uppercase().ifEmpty { "✦" }
                    satellites.add(
                        GroupSatelliteMember(
                            id = member.id,
                            name = memberName,
                            initial = initial,
                            isOnline = !member.isDeleted && member.isOnline,
                            offset = Offset(sx, sy)
                        )
                    )
                }

                val titleDisplay = truncateTitle(conv.title)
                val subtitleDisplay = "Cluster • ${if (conv.members.isNotEmpty()) conv.members.size else satellites.size + 1}"
                val titleWidth = measureTextWidthSafe(textPaint, titleDisplay)
                val subtitleWidth = measureTextWidthSafe(textPaint, subtitleDisplay)
                val maxTextHalfW = maxOf(titleWidth, subtitleWidth) / 2f + with(density) { 6.dp.toPx() }

                val maxSatY = satellites.maxOfOrNull { it.offset.y + satRadius } ?: (cy + nexusRadius)
                val labelOffsetY = maxOf(nexusRadius + with(density) { 16.dp.toPx() }, (maxSatY - cy) + with(density) { 16.dp.toPx() })

                val leftExtent = minOf(
                    cx - nexusRadius - with(density) { 6.dp.toPx() },
                    satellites.minOfOrNull { it.offset.x - satRadius - with(density) { 4.dp.toPx() } } ?: cx,
                    cx - maxTextHalfW
                )
                val rightExtent = maxOf(
                    cx + nexusRadius + with(density) { 6.dp.toPx() },
                    satellites.maxOfOrNull { it.offset.x + satRadius + with(density) { 4.dp.toPx() } } ?: cx,
                    cx + maxTextHalfW
                )
                val topExtent = minOf(
                    cy - nexusRadius - with(density) { 8.dp.toPx() },
                    satellites.minOfOrNull { it.offset.y - satRadius - with(density) { 4.dp.toPx() } } ?: cy
                )
                val bottomExtent = maxOf(
                    cy + nexusRadius,
                    satellites.maxOfOrNull { it.offset.y + satRadius } ?: cy,
                    cy + labelOffsetY + with(density) { 20.dp.toPx() }
                )

                val groupMargin = with(density) { 12.dp.toPx() }
                val groupBox = BoundingBox(
                    left = leftExtent - groupMargin,
                    top = topExtent - groupMargin,
                    right = rightExtent + groupMargin,
                    bottom = bottomExtent + groupMargin
                )

                placedBoxes.add(groupBox)
                groupNodes.add(
                    GroupConstellationNode(
                        conversation = conv,
                        nexusOffset = Offset(cx, cy),
                        satellites = satellites,
                        bounds = groupBox,
                        titleDisplay = titleDisplay,
                        subtitleDisplay = subtitleDisplay,
                        labelOffsetY = labelOffsetY
                    )
                )
            }
            continue
        }

        var placed = false

        for (attempt in 0 until 48) {
            val candidateAngle: Double
            val candidateDist: Float

            if (attempt == 0) {
                candidateAngle = baseAngle
                candidateDist = baseTierDist
            } else {
                val sign = if (attempt % 2 == 1) 1f else -1f
                val step = ((attempt + 1) / 2) % 6
                val deltaAngle = sign * step * 0.28f
                val deltaDist = (attempt / 6) * with(density) { 30.dp.toPx() }
                candidateAngle = baseAngle + deltaAngle
                candidateDist = baseTierDist + deltaDist
            }

            val cx = (candidateDist * cos(candidateAngle)).toFloat()
            val cy = (candidateDist * sin(candidateAngle)).toFloat()

            if (!conv.isGroup) {
                val rawTitle = if (conv.otherMember?.isDeleted == true) "Deleted User" else conv.title
                val titleDisplay = truncateTitle(rawTitle)
                val textWidth = measureTextWidthSafe(textPaint, titleDisplay)
                val halfW = maxOf(avatarRadius + with(density) { 4.dp.toPx() }, (textWidth / 2f) + with(density) { 6.dp.toPx() })
                val top = cy - avatarRadius - with(density) { 8.dp.toPx() }
                val bottom = cy + avatarRadius + with(density) { 24.dp.toPx() }

                val box = BoundingBox(
                    left = cx - halfW - safetyMargin,
                    top = top - safetyMargin,
                    right = cx + halfW + safetyMargin,
                    bottom = bottom + safetyMargin
                )

                if (placedBoxes.none { it.intersects(box) }) {
                    placedBoxes.add(box)
                    directNodes.add(
                        DirectConstellationNode(
                            conversation = conv,
                            offset = Offset(cx, cy),
                            bounds = box,
                            titleDisplay = titleDisplay
                        )
                    )
                    placed = true
                    break
                }
            } else {
                val angleFromCenter = atan2(cy.toDouble(), cx.toDouble())
                val rawMembers = conv.members.filter { it.id != currentUserId }
                val displayMembers: List<User> = if (rawMembers.size >= 2) {
                    rawMembers.take(4)
                } else if (rawMembers.size == 1) {
                    val words = conv.title.split(" ", "_", "-").filter { it.isNotBlank() }
                    val extraName = words.firstOrNull { !it.equals(rawMembers[0].displayName, ignoreCase = true) } ?: "Member"
                    listOf(
                        rawMembers[0],
                        User(id = "${conv.id}_m_extra", username = extraName.lowercase(), displayName = extraName, isLocalUser = false, isOnline = false)
                    )
                } else {
                    val words = conv.title.split(" ", "_", "-").filter { it.isNotBlank() }
                    if (words.size >= 2) {
                        words.take(3).mapIndexed { idx, word ->
                            User(id = "${conv.id}_m$idx", username = word.lowercase(), displayName = word, isLocalUser = false, isOnline = false)
                        }
                    } else {
                        listOf(
                            User(id = "${conv.id}_m1", username = "alpha", displayName = "Alpha", isLocalUser = false, isOnline = false),
                            User(id = "${conv.id}_m2", username = "beta", displayName = "Beta", isLocalUser = false, isOnline = false),
                            User(id = "${conv.id}_m3", username = "gamma", displayName = "Gamma", isLocalUser = false, isOnline = false)
                        )
                    }
                }

                val numSat = displayMembers.size.coerceIn(1, 4)
                val deltaAngles = when (numSat) {
                    1 -> listOf(0.0)
                    2 -> listOf(-0.42, 0.42)
                    3 -> listOf(-0.52, 0.0, 0.52)
                    else -> listOf(-0.68, -0.22, 0.22, 0.68)
                }

                val satellites = mutableListOf<GroupSatelliteMember>()
                val countToRender = minOf(numSat, displayMembers.size, deltaAngles.size)
                for (idx in 0 until countToRender) {
                    val member = displayMembers[idx]
                    val satAngle = angleFromCenter + deltaAngles[idx]
                    val sx = cx + (satDist * cos(satAngle)).toFloat()
                    val sy = cy + (satDist * sin(satAngle)).toFloat()
                    val memberName = if (member.isDeleted) "Deleted User" else member.displayName
                    val initial = memberName.trim().take(1).uppercase().ifEmpty { "✦" }
                    satellites.add(
                        GroupSatelliteMember(
                            id = member.id,
                            name = memberName,
                            initial = initial,
                            isOnline = !member.isDeleted && member.isOnline,
                            offset = Offset(sx, sy)
                        )
                    )
                }

                val titleDisplay = truncateTitle(conv.title)
                val subtitleDisplay = "Cluster • ${if (conv.members.isNotEmpty()) conv.members.size else satellites.size + 1}"
                val titleWidth = measureTextWidthSafe(textPaint, titleDisplay)
                val subtitleWidth = measureTextWidthSafe(textPaint, subtitleDisplay)
                val maxTextHalfW = maxOf(titleWidth, subtitleWidth) / 2f + with(density) { 6.dp.toPx() }

                val maxSatY = satellites.maxOfOrNull { it.offset.y + satRadius } ?: (cy + nexusRadius)
                val labelOffsetY = maxOf(nexusRadius + with(density) { 16.dp.toPx() }, (maxSatY - cy) + with(density) { 16.dp.toPx() })

                val leftExtent = minOf(
                    cx - nexusRadius - with(density) { 6.dp.toPx() },
                    satellites.minOfOrNull { it.offset.x - satRadius - with(density) { 4.dp.toPx() } } ?: cx,
                    cx - maxTextHalfW
                )
                val rightExtent = maxOf(
                    cx + nexusRadius + with(density) { 6.dp.toPx() },
                    satellites.maxOfOrNull { it.offset.x + satRadius + with(density) { 4.dp.toPx() } } ?: cx,
                    cx + maxTextHalfW
                )
                val topExtent = minOf(
                    cy - nexusRadius - with(density) { 8.dp.toPx() },
                    satellites.minOfOrNull { it.offset.y - satRadius - with(density) { 4.dp.toPx() } } ?: cy
                )
                val bottomExtent = maxOf(
                    cy + nexusRadius,
                    satellites.maxOfOrNull { it.offset.y + satRadius } ?: cy,
                    cy + labelOffsetY + with(density) { 20.dp.toPx() }
                )

                val groupMargin = with(density) { 12.dp.toPx() }
                val groupBox = BoundingBox(
                    left = leftExtent - groupMargin,
                    top = topExtent - groupMargin,
                    right = rightExtent + groupMargin,
                    bottom = bottomExtent + groupMargin
                )

                if (placedBoxes.none { it.intersects(groupBox) }) {
                    placedBoxes.add(groupBox)
                    groupNodes.add(
                        GroupConstellationNode(
                            conversation = conv,
                            nexusOffset = Offset(cx, cy),
                            satellites = satellites,
                            bounds = groupBox,
                            titleDisplay = titleDisplay,
                            subtitleDisplay = subtitleDisplay,
                            labelOffsetY = labelOffsetY
                        )
                    )
                    placed = true
                    break
                }
            }
        }

        if (!placed) {
            val fallbackDist = baseTierDist + with(density) { 150.dp.toPx() }
            val fx = (fallbackDist * cos(baseAngle)).toFloat()
            val fy = (fallbackDist * sin(baseAngle)).toFloat()

            if (!conv.isGroup) {
                val rawTitle = if (conv.otherMember?.isDeleted == true) "Deleted User" else conv.title
                val titleDisplay = truncateTitle(rawTitle)
                val box = BoundingBox(
                    fx - avatarRadius - safetyMargin,
                    fy - avatarRadius - safetyMargin,
                    fx + avatarRadius + safetyMargin,
                    fy + avatarRadius + with(density) { 24.dp.toPx() } + safetyMargin
                )
                placedBoxes.add(box)
                directNodes.add(
                    DirectConstellationNode(
                        conversation = conv,
                        offset = Offset(fx, fy),
                        bounds = box,
                        titleDisplay = titleDisplay
                    )
                )
            } else {
                val titleDisplay = truncateTitle(conv.title)
                val groupBox = BoundingBox(
                    fx - nexusRadius - safetyMargin,
                    fy - nexusRadius - safetyMargin,
                    fx + nexusRadius + safetyMargin,
                    fy + nexusRadius + with(density) { 36.dp.toPx() } + safetyMargin
                )
                placedBoxes.add(groupBox)
                groupNodes.add(
                    GroupConstellationNode(
                        conversation = conv,
                        nexusOffset = Offset(fx, fy),
                        satellites = emptyList(),
                        bounds = groupBox,
                        titleDisplay = titleDisplay,
                        subtitleDisplay = "Cluster",
                        labelOffsetY = nexusRadius + with(density) { 16.dp.toPx() }
                    )
                )
            }
        }
    }

    val pendingNodes = mutableListOf<PendingConstellationNode>()
    for ((pIdx, req) in pendingRequests.withIndex()) {
        val pSeed = getStableSeed(req.id)
        val pRng = DeterministicRng(pSeed)
        val pAngle = -PI / 2.0 + (sortedConvs.size + pIdx) * goldenAngle + (pRng.nextFloat() - 0.5f) * 0.25f
        val pDist = directTiers[pIdx % directTiers.size]
        val rawTitle = req.sender?.displayName?.takeIf { it.isNotBlank() } ?: req.senderUserId
        val titleDisplay = truncateTitle(rawTitle)
        val textWidth = measureTextWidthSafe(textPaint, titleDisplay)
        val halfW = maxOf(avatarRadius + with(density) { 4.dp.toPx() }, (textWidth / 2f) + with(density) { 6.dp.toPx() })

        var candidatePos = Offset((pDist * cos(pAngle)).toFloat(), (pDist * sin(pAngle)).toFloat())
        var candidateBox = BoundingBox(
            left = candidatePos.x - halfW - safetyMargin,
            top = candidatePos.y - avatarRadius - with(density) { 8.dp.toPx() } - safetyMargin,
            right = candidatePos.x + halfW + safetyMargin,
            bottom = candidatePos.y + avatarRadius + with(density) { 24.dp.toPx() } + safetyMargin
        )

        for (attempt in 0..16) {
            val curDist = pDist + (attempt * with(density) { 18.dp.toPx() })
            val curAngle = pAngle + (attempt * 0.35)
            val cx = (curDist * cos(curAngle)).toFloat()
            val cy = (curDist * sin(curAngle)).toFloat()

            val box = BoundingBox(
                left = cx - halfW - safetyMargin,
                top = cy - avatarRadius - with(density) { 8.dp.toPx() } - safetyMargin,
                right = cx + halfW + safetyMargin,
                bottom = cy + avatarRadius + with(density) { 24.dp.toPx() } + safetyMargin
            )

            if (!placedBoxes.any { it.intersects(box) }) {
                candidatePos = Offset(cx, cy)
                candidateBox = box
                break
            }
        }

        placedBoxes.add(candidateBox)
        pendingNodes.add(
            PendingConstellationNode(
                request = req,
                offset = candidatePos,
                bounds = candidateBox,
                titleDisplay = titleDisplay
            )
        )
    }

    return ConstellationLayout(directNodes, groupNodes, pendingNodes, youBounds)
}

/**
 * Calculates collision separation for constellation nodes during dragging or settling (Phase 8.6.3).
 * Ensures nodes never overlap each other or the central YOU hub.
 */
fun resolveNodeCollisions(
    targetId: String,
    targetPos: Offset,
    layout: ConstellationLayout,
    density: Density,
    isGroup: Boolean = false
): Offset {
    val youAvoidRadius = with(density) { 54.dp.toPx() }
    var resolved = targetPos
    val targetRadius = if (isGroup) with(density) { 38.dp.toPx() } else with(density) { 30.dp.toPx() }
    val safetyMargin = with(density) { 14.dp.toPx() }

    val sortedDirect = layout.directNodes.filter { it.conversation.id != targetId }.sortedBy { it.conversation.id }
    val sortedGroup = layout.groupNodes.filter { it.conversation.id != targetId }.sortedBy { it.conversation.id }

    for (pass in 0 until 3) {
        // 1. Avoid YOU hub at (0, 0)
        val distToYou = hypot(resolved.x, resolved.y)
        if (distToYou < youAvoidRadius) {
            val angle: Double = if (distToYou > 1e-4f) atan2(resolved.y.toDouble(), resolved.x.toDouble()) else -PI / 2.0
            resolved = Offset(
                (youAvoidRadius * cos(angle)).toFloat(),
                (youAvoidRadius * sin(angle)).toFloat()
            )
        }

        var anyAdjusted = false

        // 2. Avoid collision with all other direct nodes
        for (node in sortedDirect) {
            val otherRadius = with(density) { 30.dp.toPx() }
            val minDist = targetRadius + otherRadius + safetyMargin
            val dx = resolved.x - node.offset.x
            val dy = resolved.y - node.offset.y
            val dist = hypot(dx, dy)
            if (dist < minDist) {
                val angle: Double = if (dist > 1e-4f) atan2(dy.toDouble(), dx.toDouble()) else ((node.conversation.id.hashCode() % 628) / 100.0)
                resolved = Offset(
                    node.offset.x + (minDist * cos(angle)).toFloat(),
                    node.offset.y + (minDist * sin(angle)).toFloat()
                )
                anyAdjusted = true
            }
        }

        // 3. Avoid collision with group clusters
        for (group in sortedGroup) {
            val otherRadius = with(density) { 38.dp.toPx() }
            val minDist = targetRadius + otherRadius + safetyMargin
            val dx = resolved.x - group.nexusOffset.x
            val dy = resolved.y - group.nexusOffset.y
            val dist = hypot(dx, dy)
            if (dist < minDist) {
                val angle: Double = if (dist > 1e-4f) atan2(dy.toDouble(), dx.toDouble()) else ((group.conversation.id.hashCode() % 628) / 100.0)
                resolved = Offset(
                    group.nexusOffset.x + (minDist * cos(angle)).toFloat(),
                    group.nexusOffset.y + (minDist * sin(angle)).toFloat()
                )
                anyAdjusted = true
            }
        }

        if (!anyAdjusted) break
    }

    return resolved
}

/**
 * Calculates dynamic separation displacements for surrounding nodes while dragging (Phase 8.6.3).
 * Surrounding nodes gently part to make room without violent teleportation.
 */
fun calculateDynamicSeparation(
    draggedId: String,
    draggedPos: Offset,
    layout: ConstellationLayout,
    density: Density
): Map<String, Offset> {
    val offsets = mutableMapOf<String, Offset>()
    val influenceDist = with(density) { 72.dp.toPx() }
    val maxNudge = with(density) { 18.dp.toPx() }

    for (node in layout.directNodes) {
        if (node.conversation.id == draggedId) continue
        val dx = node.offset.x - draggedPos.x
        val dy = node.offset.y - draggedPos.y
        val dist = hypot(dx, dy)
        if (dist < influenceDist && dist > 1e-4f) {
            val factor = (1f - (dist / influenceDist)).coerceIn(0f, 1f)
            val angle = atan2(dy.toDouble(), dx.toDouble())
            val nudge = maxNudge * factor
            offsets[node.conversation.id] = Offset(
                node.offset.x + (nudge * cos(angle)).toFloat(),
                node.offset.y + (nudge * sin(angle)).toFloat()
            )
        }
    }

    for (group in layout.groupNodes) {
        if (group.conversation.id == draggedId) continue
        val dx = group.nexusOffset.x - draggedPos.x
        val dy = group.nexusOffset.y - draggedPos.y
        val dist = hypot(dx, dy)
        if (dist < influenceDist && dist > 1e-4f) {
            val factor = (1f - (dist / influenceDist)).coerceIn(0f, 1f)
            val angle = atan2(dy.toDouble(), dx.toDouble())
            val nudge = maxNudge * factor
            offsets[group.conversation.id] = Offset(
                group.nexusOffset.x + (nudge * cos(angle)).toFloat(),
                group.nexusOffset.y + (nudge * sin(angle)).toFloat()
            )
        }
    }

    return offsets
}

/**
 * Clamps a world-space point so constellation elements remain comfortably inside the usable viewport (Phase 8.6.3).
 */
fun clampToViewportSafeZone(
    rawWorld: Offset,
    viewportWorld: BoundingBox,
    density: Density,
    zoomScale: Float
): Offset {
    val marginX = with(density) { 36.dp.toPx() } / zoomScale
    val marginTop = with(density) { 72.dp.toPx() } / zoomScale
    val marginBottom = with(density) { 84.dp.toPx() } / zoomScale

    val minX = viewportWorld.left + marginX
    val maxX = viewportWorld.right - marginX
    val minY = viewportWorld.top + marginTop
    val maxY = viewportWorld.bottom - marginBottom

    val safeX = if (minX < maxX) rawWorld.x.coerceIn(minX, maxX) else rawWorld.x
    val safeY = if (minY < maxY) rawWorld.y.coerceIn(minY, maxY) else rawWorld.y

    // Avoid sitting directly on YOU (0, 0)
    val youMinDist = with(density) { 50.dp.toPx() }
    val dist = hypot(safeX, safeY)
    return if (dist < youMinDist) {
        val angle: Double = if (dist > 1e-4f) atan2(safeY.toDouble(), safeX.toDouble()) else -PI / 2.0
        Offset(
            (youMinDist * cos(angle)).toFloat(),
            (youMinDist * sin(angle)).toFloat()
        )
    } else {
        Offset(safeX, safeY)
    }
}

/**
 * Authoritative reposition resolution containing all derived world-space coordinates,
 * preview destination, occupied status, and neighbor displacements (Phase 8.6 Repair).
 */
data class RepositionResolution(
    val rawWorld: Offset,
    val clampedWorld: Offset,
    val resolvedDestination: Offset,
    val isOccupied: Boolean,
    val dynamicDisplacements: Map<String, Offset>
)

/**
 * Single authoritative pipeline for constellation node repositioning (Phase 8.6 Repair).
 *
 * Flow:
 *  pointer screen position
 *        ↓
 *  screenToWorld()
 *        ↓
 *  candidate world position
 *        ↓
 *  clampToViewportSafeZone()
 *        ↓
 *  resolveNodeCollisions()
 *        ↓
 *  authoritative resolved destination
 */
fun computeAuthoritativeDestination(
    targetConvId: String,
    isGroup: Boolean,
    pointerScreenPos: Offset,
    canvasWidth: Float,
    canvasHeight: Float,
    panOffset: Offset,
    zoomScale: Float,
    layout: ConstellationLayout,
    density: Density
): RepositionResolution {
    val canvasCenter = Offset(canvasWidth / 2f, canvasHeight / 2f)
    val viewportWorld = getViewportWorldBounds(canvasWidth, canvasHeight, canvasCenter, panOffset, zoomScale)
    val rawWorld = screenToWorld(pointerScreenPos, canvasCenter, panOffset, zoomScale)
    val clampedWorld = clampToViewportSafeZone(rawWorld, viewportWorld, density, zoomScale)
    val resolvedDestination = resolveNodeCollisions(
        targetId = targetConvId,
        targetPos = clampedWorld,
        layout = layout,
        density = density,
        isGroup = isGroup
    )
    val occupiedThresholdPx = with(density) { 8.dp.toPx() }
    val isOccupied = (clampedWorld - resolvedDestination).getDistance() > occupiedThresholdPx
    val dynamicDisplacements = calculateDynamicSeparation(
        draggedId = targetConvId,
        draggedPos = clampedWorld,
        layout = layout,
        density = density
    )
    return RepositionResolution(
        rawWorld = rawWorld,
        clampedWorld = clampedWorld,
        resolvedDestination = resolvedDestination,
        isOccupied = isOccupied,
        dynamicDisplacements = dynamicDisplacements
    )
}

/**
 * Interactive 2D Constellation Canvas for Aster conversations (Phase 4.3).
 *
 * Capabilities:
 *  - Two-finger pinch-to-zoom (0.55x .. 2.25x) preserving gesture focal point.
 *  - 1-finger drag panning to freely explore the constellation canvas.
 *  - Zero layout recalculation on zoom/pan: spatial layout is 100% stable in world space.
 *  - Screen-edge directional awareness: lucid indicators point toward off-screen conversations.
 *  - Clustered indicators: merges nearby off-screen items cleanly (e.g. "→ ⁂ 3").
 *  - Tapping an off-screen indicator smoothly animates the viewport to center the target.
 *  - Tapping central YOU node smoothly recenters the constellation.
 *  - Protected touch targets (48-56dp) on all nodes regardless of current zoom scale.
 */
private val OffsetSaver = Saver<Offset, List<Float>>(
    save = { listOf(it.x, it.y) },
    restore = { Offset(it[0], it[1]) }
)

@Composable
fun ConstellationChatView(
    conversations: List<Conversation>,
    currentUserId: String = "",
    onConversationClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    pendingRequests: List<com.aster.app.data.network.FriendRequestSummary> = emptyList(),
    onAcceptPendingRequest: ((requestId: String) -> Unit)? = null,
    onDeclinePendingRequest: ((requestId: String) -> Unit)? = null,
    onSaveNodePosition: ((conversationId: String, normX: Float?, normY: Float?) -> Unit)? = null,
    onToggleHighlight: ((conversationId: String, currentHighlighted: Boolean) -> Unit)? = null,
    onViewProfile: ((user: User) -> Unit)? = null,
    onRemoveContact: ((userId: String) -> Unit)? = null,
    onBlockUser: ((userId: String) -> Unit)? = null,
    onUnblockUser: ((userId: String) -> Unit)? = null,
    onViewGroupInfo: ((conversation: Conversation) -> Unit)? = null,
    onViewGroupMembers: ((conversation: Conversation) -> Unit)? = null,
    onLeaveGroup: ((groupId: String) -> Unit)? = null
) {
    val isReduced = LocalReducedMotion.current
    val haptics = LocalAsterHaptics.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    var animJob by remember { mutableStateOf<Job?>(null) }
    var repositionAnimJob by remember { mutableStateOf<Job?>(null) }

    // Preserve pan and zoom across navigation to maintain spatial continuity
    var panOffset by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Zero) }
    var zoomScale by rememberSaveable { mutableFloatStateOf(CONSTELLATION_DEFAULT_ZOOM) }
    var canvasWidth by remember { mutableFloatStateOf(0f) }
    var canvasHeight by remember { mutableFloatStateOf(0f) }

    // Tracks selected conversation node for origin bloom transition
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var selectedPendingRequest by remember { mutableStateOf<com.aster.app.data.network.FriendRequestSummary?>(null) }

    // Phase 8.6: Interaction & Repositioning states
    var focusedNodeId by remember { mutableStateOf<String?>(null) }
    var repositionConvId by remember { mutableStateOf<String?>(null) }
    var isRepositioning by remember { mutableStateOf(false) }
    var draggedWorldOffset by remember { mutableStateOf<Offset?>(null) }
    var dynamicNodeOffsets by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }
    var previewSettledOffset by remember { mutableStateOf<Offset?>(null) }
    var isDestinationOccupied by remember { mutableStateOf(false) }
    var customPositionOverrides by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }

    // Context Card and subsidiary dialog states
    var contextCardConversation by remember { mutableStateOf<Conversation?>(null) }
    var profileDialogUser by remember { mutableStateOf<User?>(null) }
    var groupInfoDialogConv by remember { mutableStateOf<Conversation?>(null) }
    var groupMembersDialogConv by remember { mutableStateOf<Conversation?>(null) }

    LaunchedEffect(Unit) {
        selectedNodeId = null
    }

    // Breathing pulse for unread activity and celestial glow
    val infiniteTransition = rememberInfiniteTransition(label = "constellationPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Gentle flowing wave phase for organic celestial filaments
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    // Ambient constellation breathing (Phase 8.6.1: Slow 4s organic pulse)
    val ambientTransition = rememberInfiniteTransition(label = "ambientConstellationLife")
    val ambientPhase by ambientTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambientPhase"
    )

    // Stable layout calculation: ONLY recomputes when conversations, pendingRequests, user, or screen density change
    val layout = remember(conversations, pendingRequests, currentUserId, density) {
        calculateConstellationLayout(conversations, currentUserId, density, pendingRequests = pendingRequests)
    }

    LaunchedEffect(layout) {
        if (customPositionOverrides.isNotEmpty()) {
            val remaining = customPositionOverrides.filter { (id, overridePos) ->
                val directNode = layout.directNodes.firstOrNull { it.conversation.id == id }
                val groupNode = layout.groupNodes.firstOrNull { it.conversation.id == id }
                val currentPos = directNode?.offset ?: groupNode?.nexusOffset
                currentPos == null || (currentPos - overridePos).getDistance() > 1.0f
            }
            if (remaining.size != customPositionOverrides.size) {
                customPositionOverrides = remaining
            }
        }
    }

    // Reactive off-screen indicators (computed efficiently strictly from current screen/viewport bounds)
    val offScreenIndicators = remember(layout, canvasWidth, canvasHeight, panOffset, zoomScale, density) {
        if (canvasWidth <= 0f || canvasHeight <= 0f) {
            emptyList()
        } else {
            val canvasCenter = Offset(canvasWidth / 2f, canvasHeight / 2f)
            val offScreenList = findOffScreenConversations(
                layout = layout,
                viewportWidth = canvasWidth,
                viewportHeight = canvasHeight,
                canvasCenter = canvasCenter,
                panOffset = panOffset,
                zoomScale = zoomScale,
                density = density
            )
            calculateOffScreenIndicators(
                offScreenList = offScreenList,
                viewportWidth = canvasWidth,
                viewportHeight = canvasHeight,
                canvasCenter = canvasCenter,
                panOffset = panOffset,
                zoomScale = zoomScale,
                density = density
            )
        }
    }

    val handleTap: (Offset) -> Unit = { tapOffset ->
        val hitRadiusPx = with(density) { 28.dp.toPx() } // Minimum 56dp interactive touch target

        // 1. Check if user tapped an off-screen indicator
        var tappedIndicator: OffScreenIndicator? = null
        var closestIndicatorDistSq = Float.MAX_VALUE
        for (indicator in offScreenIndicators) {
            val dx = indicator.screenPosition.x - tapOffset.x
            val dy = indicator.screenPosition.y - tapOffset.y
            val distSq = dx * dx + dy * dy
            if (distSq <= (hitRadiusPx * 1.2f) * (hitRadiusPx * 1.2f) && distSq < closestIndicatorDistSq) {
                closestIndicatorDistSq = distSq
                tappedIndicator = indicator
            }
        }

        if (tappedIndicator != null) {
            val targetPan = clampPanOffset(-tappedIndicator.targetWorldOffset * zoomScale, zoomScale)
            animJob?.cancel()
            animJob = coroutineScope.launch {
                val startPan = panOffset
                val anim = Animatable(0f)
                anim.animateTo(
                    1f,
                    animationSpec = AsterMotion.tween(
                        durationMillis = AsterMotionTokens.Duration.Standard,
                        easing = AsterMotionTokens.EasingCurve.Standard,
                        reducedMotion = isReduced
                    )
                ) {
                    panOffset = Offset(
                        x = startPan.x + (targetPan.x - startPan.x) * value,
                        y = startPan.y + (targetPan.y - startPan.y) * value
                    )
                }
            }
        } else {
            val canvasCenter = Offset(canvasWidth / 2f, canvasHeight / 2f)
            val worldTap = screenToWorld(tapOffset, canvasCenter, panOffset, zoomScale)

            var clickedConvId: String? = null
            var closestDistSq = Float.MAX_VALUE

            // 2. Direct conversation nodes (check screen distance or world bounding box)
            for (node in layout.directNodes) {
                val nodePos = customPositionOverrides[node.conversation.id] ?: (dynamicNodeOffsets[node.conversation.id] ?: node.offset)
                val nodeScreenPos = worldToScreen(nodePos, canvasCenter, panOffset, zoomScale)
                val sdx = nodeScreenPos.x - tapOffset.x
                val sdy = nodeScreenPos.y - tapOffset.y
                val screenDistSq = sdx * sdx + sdy * sdy
                val insideBounds = node.bounds.contains(worldTap)
                val insideAvatar = screenDistSq <= hitRadiusPx * hitRadiusPx

                if ((insideBounds || insideAvatar) && screenDistSq < closestDistSq) {
                    closestDistSq = screenDistSq
                    clickedConvId = node.conversation.id
                }
            }

            // 3. Group clusters (Nexus, satellites, or bounding box)
            for (group in layout.groupNodes) {
                val groupPos = customPositionOverrides[group.conversation.id] ?: (dynamicNodeOffsets[group.conversation.id] ?: group.nexusOffset)
                val nexusScreenPos = worldToScreen(groupPos, canvasCenter, panOffset, zoomScale)
                val sdx = nexusScreenPos.x - tapOffset.x
                val sdy = nexusScreenPos.y - tapOffset.y
                val screenDistSq = sdx * sdx + sdy * sdy
                val insideBounds = group.bounds.contains(worldTap)
                val insideNexus = screenDistSq <= (hitRadiusPx * 1.25f) * (hitRadiusPx * 1.25f)
                val insideAnySat = group.satellites.any { sat ->
                    val satPos = groupPos + (sat.offset - group.nexusOffset)
                    val satScreenPos = worldToScreen(satPos, canvasCenter, panOffset, zoomScale)
                    val satDx = satScreenPos.x - tapOffset.x
                    val satDy = satScreenPos.y - tapOffset.y
                    (satDx * satDx + satDy * satDy) <= hitRadiusPx * hitRadiusPx
                }

                if ((insideBounds || insideNexus || insideAnySat) && screenDistSq < closestDistSq) {
                    closestDistSq = screenDistSq
                    clickedConvId = group.conversation.id
                }
            }

            var clickedPendingRequest: com.aster.app.data.network.FriendRequestSummary? = null
            for (pNode in layout.pendingNodes) {
                val nodeScreenPos = worldToScreen(pNode.offset, canvasCenter, panOffset, zoomScale)
                val sdx = nodeScreenPos.x - tapOffset.x
                val sdy = nodeScreenPos.y - tapOffset.y
                val screenDistSq = sdx * sdx + sdy * sdy
                val insideBounds = pNode.bounds.contains(worldTap)
                val insideAvatar = screenDistSq <= hitRadiusPx * hitRadiusPx

                if ((insideBounds || insideAvatar) && screenDistSq < closestDistSq) {
                    closestDistSq = screenDistSq
                    clickedConvId = null
                    clickedPendingRequest = pNode.request
                }
            }

            // 4. Central YOU node: tapping YOU recenters and resets zoom smoothly
            val youScreenPos = worldToScreen(Offset.Zero, canvasCenter, panOffset, zoomScale)
            val ydx = youScreenPos.x - tapOffset.x
            val ydy = youScreenPos.y - tapOffset.y
            val youDistSq = ydx * ydx + ydy * ydy
            val youHitRadius = with(density) { 32.dp.toPx() }

            if (clickedConvId == null && youDistSq <= youHitRadius * youHitRadius) {
                animJob?.cancel()
                animJob = coroutineScope.launch {
                    val startPan = panOffset
                    val startZoom = zoomScale
                    val anim = Animatable(0f)
                    anim.animateTo(
                        1f,
                        animationSpec = AsterMotion.tween(
                            durationMillis = AsterMotionTokens.Duration.Standard,
                            easing = AsterMotionTokens.EasingCurve.Standard,
                            reducedMotion = isReduced
                        )
                    ) {
                        panOffset = Offset(
                            x = startPan.x + (0f - startPan.x) * value,
                            y = startPan.y + (0f - startPan.y) * value
                        )
                        zoomScale = startZoom + (CONSTELLATION_DEFAULT_ZOOM - startZoom) * value
                    }
                }
            }

            clickedConvId?.let {
                haptics.perform(AsterHapticEvent.Selection)
                selectedNodeId = it
                onConversationClick(it)
            }

            clickedPendingRequest?.let { req ->
                haptics.perform(AsterHapticEvent.Selection)
                selectedPendingRequest = req
            }
        }
    }

    val currentLayout by androidx.compose.runtime.rememberUpdatedState(layout)
    val currentDensity by androidx.compose.runtime.rememberUpdatedState(density)
    val currentCanvasWidth by androidx.compose.runtime.rememberUpdatedState(canvasWidth)
    val currentCanvasHeight by androidx.compose.runtime.rememberUpdatedState(canvasHeight)
    val currentIsReduced by androidx.compose.runtime.rememberUpdatedState(isReduced)
    val currentHaptics by androidx.compose.runtime.rememberUpdatedState(haptics)
    val currentOnSaveNodePosition by androidx.compose.runtime.rememberUpdatedState(onSaveNodePosition)
    val currentHandleTap by androidx.compose.runtime.rememberUpdatedState(handleTap)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size ->
                canvasWidth = size.width.toFloat()
                canvasHeight = size.height.toFloat()
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    animJob?.cancel()
                    repositionAnimJob?.cancel()

                    val initialCenter = Offset(currentCanvasWidth / 2f, currentCanvasHeight / 2f)
                    val worldDown = screenToWorld(down.position, initialCenter, panOffset, zoomScale)
                    val hitRadiusPx = with(currentDensity) { 36.dp.toPx() }

                    // Test hit on direct nodes
                    val hitDirect = currentLayout.directNodes.firstOrNull { node ->
                        val currentPos = customPositionOverrides[node.conversation.id] ?: (dynamicNodeOffsets[node.conversation.id] ?: node.offset)
                        val screenPos = worldToScreen(currentPos, initialCenter, panOffset, zoomScale)
                        (screenPos - down.position).getDistance() <= hitRadiusPx || node.bounds.contains(worldDown)
                    }

                    // Test hit on group clusters
                    val hitGroup = if (hitDirect == null) {
                        currentLayout.groupNodes.firstOrNull { group ->
                            val currentPos = customPositionOverrides[group.conversation.id] ?: (dynamicNodeOffsets[group.conversation.id] ?: group.nexusOffset)
                            val screenPos = worldToScreen(currentPos, initialCenter, panOffset, zoomScale)
                            (screenPos - down.position).getDistance() <= hitRadiusPx * 1.3f ||
                            group.bounds.contains(worldDown) ||
                            group.satellites.any { sat ->
                                val satWorld = currentPos + (sat.offset - group.nexusOffset)
                                val satScreen = worldToScreen(satWorld, initialCenter, panOffset, zoomScale)
                                (satScreen - down.position).getDistance() <= hitRadiusPx
                            }
                        }
                    } else null

                    val targetConv = hitDirect?.conversation ?: hitGroup?.conversation

                    if (targetConv != null) {
                        // Node was pressed: evaluate tap vs map-pan vs long-press (Phase 8.6.2)
                        val touchSlop = viewConfiguration.touchSlop
                        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                        var totalPan = Offset.Zero
                        var releasedEarly = false
                        var movedEarly = false

                        withTimeoutOrNull(longPressTimeout) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val activePointers = event.changes.filter { it.pressed }
                                if (activePointers.isEmpty()) {
                                    releasedEarly = true
                                    break
                                }
                                if (activePointers.size > 1) {
                                    movedEarly = true
                                    break
                                }
                                val pointer = activePointers[0]
                                val dragDelta = pointer.position - pointer.previousPosition
                                totalPan += dragDelta
                                if (totalPan.getDistance() > touchSlop) {
                                    movedEarly = true
                                    break
                                }
                            }
                        }

                        if (releasedEarly) {
                            // Quick tap on constellation -> open conversation
                            currentHandleTap(down.position)
                        } else if (movedEarly) {
                            // Moved > touchSlop before long press timeout -> continuous map pan / pinch
                            do {
                                val event = awaitPointerEvent()
                                val activePointers = event.changes.filter { it.pressed }
                                if (activePointers.isEmpty()) break

                                val canvasCenterNow = Offset(currentCanvasWidth / 2f, currentCanvasHeight / 2f)

                                if (activePointers.size >= 2) {
                                    val p0 = activePointers[0]
                                    val p1 = activePointers[1]
                                    val currentCentroid = (p0.position + p1.position) / 2f
                                    val prevCentroidPos = (p0.previousPosition + p1.previousPosition) / 2f
                                    val currentDist = (p0.position - p1.position).getDistance()
                                    val prevDist = (p0.previousPosition - p1.previousPosition).getDistance()
                                    val scaleFactor = if (p0.previousPressed && p1.previousPressed && prevDist > 1f) currentDist / prevDist else 1f

                                    val oldZoom = zoomScale
                                    val newZoom = clampZoom(oldZoom * scaleFactor)
                                    val focalWorld = (prevCentroidPos - canvasCenterNow - panOffset) / oldZoom
                                    val newPan = currentCentroid - canvasCenterNow - (focalWorld * newZoom)

                                    zoomScale = newZoom
                                    panOffset = clampPanOffset(newPan, newZoom)
                                    activePointers.forEach { it.consume() }
                                } else if (activePointers.size == 1) {
                                    val pointer = activePointers[0]
                                    val dragDelta = pointer.position - pointer.previousPosition
                                    panOffset = clampPanOffset(panOffset + dragDelta, zoomScale)
                                    pointer.consume()
                                }
                            } while (event.changes.any { it.pressed })
                        } else {
                            // LONG-PRESS CONFIRMED (Phase 8.6.2 & 8.7): Enter focused / lifted state
                            currentHaptics.perform(AsterHapticEvent.ContextOpen)
                            focusedNodeId = targetConv.id
                            val initialWorldPos = hitDirect?.offset ?: hitGroup?.nexusOffset ?: worldDown
                            val dragThresholdPx = with(currentDensity) { 12.dp.toPx() }
                            var didCrossThreshold = false
                            var currentWorld = initialWorldPos
                            val isGrp = hitGroup != null
                            var lastResolution: RepositionResolution? = null
                            var lastWasOccupied = false

                            do {
                                val event = awaitPointerEvent()
                                val activePointers = event.changes.filter { it.pressed }
                                if (activePointers.isEmpty()) break

                                val pointer = activePointers[0]
                                pointer.consume()
                                val distFromDown = (pointer.position - down.position).getDistance()

                                if (!didCrossThreshold && distFromDown >= dragThresholdPx) {
                                    didCrossThreshold = true
                                    currentHaptics.perform(AsterHapticEvent.Threshold)
                                    isRepositioning = true
                                    repositionConvId = targetConv.id
                                }

                                if (didCrossThreshold) {
                                    // Authoritative reposition pipeline (Phase 8.6 Repair):
                                    // Live preview, boundary clamping, circular collision resolution, and dynamic separation
                                    val resolution = computeAuthoritativeDestination(
                                        targetConvId = targetConv.id,
                                        isGroup = isGrp,
                                        pointerScreenPos = pointer.position,
                                        canvasWidth = currentCanvasWidth,
                                        canvasHeight = currentCanvasHeight,
                                        panOffset = panOffset,
                                        zoomScale = zoomScale,
                                        layout = currentLayout,
                                        density = currentDensity
                                    )
                                    if (resolution.isOccupied && !lastWasOccupied) {
                                        currentHaptics.perform(AsterHapticEvent.Warning)
                                    }
                                    lastWasOccupied = resolution.isOccupied

                                    lastResolution = resolution
                                    currentWorld = resolution.clampedWorld
                                    draggedWorldOffset = resolution.clampedWorld
                                    previewSettledOffset = resolution.resolvedDestination
                                    isDestinationOccupied = resolution.isOccupied
                                    dynamicNodeOffsets = resolution.dynamicDisplacements
                                }
                            } while (event.changes.any { it.pressed })

                            // Pointer lifted: Branch A (dragged past 12dp) vs Branch B (released within 12dp)
                            if (didCrossThreshold) {
                                // Authoritative destination is 100% IDENTICAL to what was displayed in the preview (Phase 8.6 Repair)
                                val settledWorld = lastResolution?.resolvedDestination ?: previewSettledOffset ?: currentWorld
                                val startWorld = draggedWorldOffset ?: currentWorld
                                val initialDisplacedOffsets = dynamicNodeOffsets

                                isRepositioning = false
                                focusedNodeId = null
                                // Keep repositionConvId active during the spring animation so draggedWorldOffset is rendered

                                repositionAnimJob = coroutineScope.launch {
                                    try {
                                        val anim = Animatable(0f)
                                        anim.animateTo(
                                            1f,
                                            animationSpec = AsterMotion.spring(
                                                AsterMotion.Spring.Reposition,
                                                reducedMotion = currentIsReduced
                                            )
                                        ) {
                                            val t = value
                                            draggedWorldOffset = Offset(
                                                startWorld.x + (settledWorld.x - startWorld.x) * t,
                                                startWorld.y + (settledWorld.y - startWorld.y) * t
                                            )
                                            // Displaced neighboring nodes smoothly spring back to resting positions
                                            dynamicNodeOffsets = initialDisplacedOffsets.mapValues { (convId, displacedPos) ->
                                                val restingPos = currentLayout.directNodes.firstOrNull { it.conversation.id == convId }?.offset
                                                    ?: currentLayout.groupNodes.firstOrNull { it.conversation.id == convId }?.nexusOffset
                                                    ?: displacedPos
                                                Offset(
                                                    displacedPos.x + (restingPos.x - displacedPos.x) * t,
                                                    displacedPos.y + (restingPos.y - displacedPos.y) * t
                                                )
                                            }
                                        }

                                        // Authoritative drop settle (Phase 8.7)
                                        currentHaptics.perform(AsterHapticEvent.Reposition)

                                        // Pin settled position until Room emits the layout update to ensure zero-flicker continuity
                                        customPositionOverrides = customPositionOverrides + (targetConv.id to settledWorld)

                                        // Persist normalized coordinates to Room (Phase 8.6.4)
                                        val normX = settledWorld.x / currentDensity.density
                                        val normY = settledWorld.y / currentDensity.density
                                        currentOnSaveNodePosition?.invoke(targetConv.id, normX, normY)
                                    } finally {
                                        draggedWorldOffset = null
                                        dynamicNodeOffsets = emptyMap()
                                        previewSettledOffset = null
                                        isDestinationOccupied = false
                                        repositionConvId = null
                                    }
                                }
                            } else {
                                // Released within 12dp deadzone -> reveal Constellation Context Card (Phase 8.6.4)
                                contextCardConversation = targetConv
                                previewSettledOffset = null
                                isDestinationOccupied = false
                                focusedNodeId = null
                                isRepositioning = false
                                repositionConvId = null
                            }
                        }
                    } else {
                        // Empty space, YOU node, or indicator touch
                        val touchSlop = viewConfiguration.touchSlop
                        var isGesture = false
                        var totalPan = Offset.Zero

                        do {
                            val event = awaitPointerEvent()
                            val activePointers = event.changes.filter { it.pressed }
                            if (activePointers.isEmpty()) break

                            val canvasCenterNow = Offset(currentCanvasWidth / 2f, currentCanvasHeight / 2f)

                            if (activePointers.size >= 2) {
                                isGesture = true
                                val p0 = activePointers[0]
                                val p1 = activePointers[1]

                                val currentCentroid = (p0.position + p1.position) / 2f
                                val prevCentroidPos = (p0.previousPosition + p1.previousPosition) / 2f

                                val currentDist = (p0.position - p1.position).getDistance()
                                val prevDist = (p0.previousPosition - p1.previousPosition).getDistance()

                                val scaleFactor = if (p0.previousPressed && p1.previousPressed && prevDist > 1f) currentDist / prevDist else 1f

                                val oldZoom = zoomScale
                                val newZoom = clampZoom(oldZoom * scaleFactor)

                                val focalWorld = (prevCentroidPos - canvasCenterNow - panOffset) / oldZoom
                                val newPan = currentCentroid - canvasCenterNow - (focalWorld * newZoom)

                                zoomScale = newZoom
                                panOffset = clampPanOffset(newPan, newZoom)

                                activePointers.forEach { it.consume() }
                            } else if (activePointers.size == 1) {
                                val pointer = activePointers[0]
                                val dragDelta = pointer.position - pointer.previousPosition
                                totalPan += dragDelta

                                if (!isGesture && totalPan.getDistance() > touchSlop) {
                                    isGesture = true
                                    panOffset = clampPanOffset(panOffset + totalPan, zoomScale)
                                    pointer.consume()
                                } else if (isGesture) {
                                    panOffset = clampPanOffset(panOffset + dragDelta, zoomScale)
                                    pointer.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (!isGesture) {
                            currentHandleTap(down.position)
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasCenter = Offset(size.width / 2f, size.height / 2f)
            val center = canvasCenter + panOffset
            val effectivePulseScale = if (isReduced) 1.0f else pulseScale

            // 1. Constellation World Layer (transformed by zoom & pan)
            withTransform({
                translate(center.x, center.y)
                scale(scaleX = zoomScale, scaleY = zoomScale, pivot = Offset.Zero)
            }) {
                // Filaments from YOU to direct 1:1 conversation nodes
                for (node in layout.directNodes) {
                    val currentPos = if (node.conversation.id == repositionConvId && draggedWorldOffset != null) {
                        draggedWorldOffset!!
                    } else {
                        customPositionOverrides[node.conversation.id] ?: (dynamicNodeOffsets[node.conversation.id] ?: node.offset)
                    }
                    val hasUnread = node.conversation.unreadCount > 0
                    val isHighlighted = node.conversation.isHighlighted
                    val isFocused = node.conversation.id == focusedNodeId

                    val isBlocked = node.conversation.isBlocked
                    val strokeWidth = when {
                        isBlocked -> 0.9.dp.toPx()
                        isFocused -> 2.6.dp.toPx()
                        isHighlighted -> 2.2.dp.toPx()
                        hasUnread -> 2.dp.toPx()
                        else -> 1.2.dp.toPx()
                    }
                    val filamentColor = when {
                        isBlocked -> AsterCrimson.copy(alpha = 0.25f)
                        isFocused -> AsterViolet
                        isHighlighted -> AsterWarm.copy(alpha = 0.65f)
                        hasUnread -> AsterViolet
                        else -> AsterViolet.copy(alpha = 0.32f)
                    }

                    drawOrganicFilament(
                        start = Offset.Zero,
                        end = currentPos,
                        color = filamentColor,
                        strokeWidth = strokeWidth,
                        amplitude = if (isBlocked) 3.dp.toPx() else 7.dp.toPx(),
                        cycles = 2.0f,
                        phase = wavePhase,
                        harmonicCounterWave = false
                    )
                }

                // Cross-filaments between adjacent direct nodes for celestial web
                if (layout.directNodes.size > 1) {
                    val angularSorted = layout.directNodes.sortedBy { atan2(it.offset.y.toDouble(), it.offset.x.toDouble()) }
                    val maxLinkDistPx = 240.dp.toPx()

                    for (i in angularSorted.indices) {
                        val nextIdx = (i + 1) % angularSorted.size
                        val nodeA = angularSorted[i]
                        val nodeB = angularSorted[nextIdx]

                        // Do not draw cross-filaments to or from blocked nodes (severed celestial web)
                        if (nodeA.conversation.isBlocked || nodeB.conversation.isBlocked) {
                            continue
                        }
                        val posA = if (nodeA.conversation.id == repositionConvId && draggedWorldOffset != null) {
                            draggedWorldOffset!!
                        } else customPositionOverrides[nodeA.conversation.id] ?: (dynamicNodeOffsets[nodeA.conversation.id] ?: nodeA.offset)
                        val posB = if (nodeB.conversation.id == repositionConvId && draggedWorldOffset != null) {
                            draggedWorldOffset!!
                        } else customPositionOverrides[nodeB.conversation.id] ?: (dynamicNodeOffsets[nodeB.conversation.id] ?: nodeB.offset)

                        val dx = posA.x - posB.x
                        val dy = posA.y - posB.y
                        val dist = hypot(dx, dy)

                        if (dist <= maxLinkDistPx) {
                            drawOrganicFilament(
                                start = posA,
                                end = posB,
                                color = AsterViolet.copy(alpha = 0.10f),
                                strokeWidth = 0.8.dp.toPx(),
                                amplitude = 4.dp.toPx(),
                                cycles = 1.0f,
                                phase = wavePhase * 0.5f,
                                harmonicCounterWave = false
                            )
                        }
                    }
                }

                // Harmonic filaments from YOU to Group Clusters
                for (group in layout.groupNodes) {
                    val currentPos = if (group.conversation.id == repositionConvId && draggedWorldOffset != null) {
                        draggedWorldOffset!!
                    } else {
                        customPositionOverrides[group.conversation.id] ?: (dynamicNodeOffsets[group.conversation.id] ?: group.nexusOffset)
                    }
                    val hasUnread = group.conversation.unreadCount > 0
                    val isHighlighted = group.conversation.isHighlighted
                    val isFocused = group.conversation.id == focusedNodeId

                    val strokeWidth = when {
                        isFocused -> 2.8.dp.toPx()
                        isHighlighted -> 2.4.dp.toPx()
                        hasUnread -> 2.dp.toPx()
                        else -> 1.4.dp.toPx()
                    }
                    val filamentColor = when {
                        isFocused -> AsterViolet
                        isHighlighted -> AsterWarm.copy(alpha = 0.70f)
                        hasUnread -> AsterViolet
                        else -> AsterViolet.copy(alpha = 0.45f)
                    }

                    drawOrganicFilament(
                        start = Offset.Zero,
                        end = currentPos,
                        color = filamentColor,
                        strokeWidth = strokeWidth,
                        amplitude = 8.5.dp.toPx(),
                        cycles = 2.5f,
                        phase = wavePhase,
                        harmonicCounterWave = true
                    )

                    // Internal filaments from Group Nexus to satellites
                    for (sat in group.satellites) {
                        val satPos = currentPos + (sat.offset - group.nexusOffset)
                        drawLine(
                            color = AsterVioletSoft.copy(alpha = 0.42f),
                            start = currentPos,
                            end = satPos,
                            strokeWidth = 1.2.dp.toPx()
                        )
                    }
                }

                // Nascent filaments from YOU to candidate connection nodes
                for (pNode in layout.pendingNodes) {
                    drawOrganicFilament(
                        start = Offset.Zero,
                        end = pNode.offset,
                        color = AsterWarm.copy(alpha = 0.45f),
                        strokeWidth = 1.2.dp.toPx(),
                        amplitude = 5.dp.toPx(),
                        cycles = 2.5f,
                        phase = wavePhase,
                        harmonicCounterWave = false
                    )
                }

                // Destination Preview (Phase 8.6.3 / 8.6 Repair)
                if (isRepositioning && previewSettledOffset != null) {
                    val previewPos = previewSettledOffset!!
                    val currentDragPos = draggedWorldOffset ?: previewPos
                    val isGroupNode = layout.groupNodes.any { it.conversation.id == repositionConvId }
                    val previewRadius = if (isGroupNode) 28.dp.toPx() else 24.dp.toPx()

                    if (isDestinationOccupied) {
                        // Colliding / Occupied state:
                        // 1. Warm gravitational boundary resistance halo at the finger
                        drawCircle(
                            color = AsterWarm.copy(alpha = 0.35f),
                            radius = previewRadius + 12.dp.toPx(),
                            center = currentDragPos
                        )
                        drawCircle(
                            color = AsterWarm.copy(alpha = 0.70f),
                            radius = previewRadius + 4.dp.toPx(),
                            center = currentDragPos,
                            style = Stroke(width = 1.8.dp.toPx())
                        )

                        // 2. Trajectory starlight connecting finger to alternate open destination
                        drawLine(
                            color = AsterWarm.copy(alpha = 0.55f),
                            start = currentDragPos,
                            end = previewPos,
                            strokeWidth = 1.6.dp.toPx()
                        )

                        // 3. Alternate destination ring at previewPos
                        drawCircle(
                            color = AsterWarm.copy(alpha = 0.20f),
                            radius = previewRadius + 8.dp.toPx(),
                            center = previewPos
                        )
                        drawCircle(
                            color = AsterWarm.copy(alpha = 0.85f),
                            radius = previewRadius,
                            center = previewPos,
                            style = Stroke(width = 2.dp.toPx())
                        )

                        val previewMarkerPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#E8C587")
                            textSize = 24f
                            isAntiAlias = true
                            textAlign = android.graphics.Paint.Align.CENTER
                            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        }
                        drawContext.canvas.nativeCanvas.drawText("✦", previewPos.x, previewPos.y + 8f, previewMarkerPaint)
                    } else {
                        // Open Space state:
                        // Calm starlight preview ring at target destination
                        drawCircle(
                            color = AsterViolet.copy(alpha = 0.18f),
                            radius = previewRadius + 8.dp.toPx(),
                            center = previewPos
                        )
                        drawCircle(
                            color = AsterViolet.copy(alpha = 0.65f),
                            radius = previewRadius,
                            center = previewPos,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                        drawCircle(
                            color = AsterStar.copy(alpha = 0.60f),
                            radius = 3.dp.toPx(),
                            center = previewPos
                        )
                    }
                }

                // Central User Hub Node (YOU)
                drawCentralUserNode(Offset.Zero, effectivePulseScale)

                // Direct Conversation Nodes (Phase 8.6.1 Ambient Life & 8.6.8 Highlight)
                for (node in layout.directNodes) {
                    val currentPos = if (node.conversation.id == repositionConvId && draggedWorldOffset != null) {
                        draggedWorldOffset!!
                    } else {
                        customPositionOverrides[node.conversation.id] ?: (dynamicNodeOffsets[node.conversation.id] ?: node.offset)
                    }
                    val isSelected = node.conversation.id == selectedNodeId
                    val isFocused = node.conversation.id == focusedNodeId
                    val isDimmed = (selectedNodeId != null && !isSelected) || (focusedNodeId != null && !isFocused)

                    val nodeSeed = (node.conversation.id.hashCode() and 0xFFFF) / 65535f
                    val nodePhase = ambientPhase + (nodeSeed * 2 * PI).toFloat()
                    val ambientScale = if (isReduced) 1.0f else (1.0f + 0.02f * sin(nodePhase))
                    val ambientGlow = if (isReduced) 1.0f else (0.85f + 0.15f * cos(nodePhase))

                    drawDirectConversationNode(
                        center = currentPos,
                        node = node,
                        pulseScale = effectivePulseScale,
                        isSelected = isSelected,
                        isDimmed = isDimmed,
                        isFocused = isFocused,
                        ambientScale = ambientScale,
                        ambientGlow = ambientGlow
                    )
                }

                // Group Constellation Clusters (Phase 8.6.1 Ambient Life & 8.6.8 Highlight)
                for (group in layout.groupNodes) {
                    val currentPos = if (group.conversation.id == repositionConvId && draggedWorldOffset != null) {
                        draggedWorldOffset!!
                    } else {
                        customPositionOverrides[group.conversation.id] ?: (dynamicNodeOffsets[group.conversation.id] ?: group.nexusOffset)
                    }
                    val isSelected = group.conversation.id == selectedNodeId
                    val isFocused = group.conversation.id == focusedNodeId
                    val isDimmed = (selectedNodeId != null && !isSelected) || (focusedNodeId != null && !isFocused)

                    val groupSeed = (group.conversation.id.hashCode() and 0xFFFF) / 65535f
                    val groupPhase = ambientPhase + (groupSeed * 2 * PI).toFloat()
                    val ambientScale = if (isReduced) 1.0f else (1.0f + 0.02f * sin(groupPhase))
                    val ambientGlow = if (isReduced) 1.0f else (0.85f + 0.15f * cos(groupPhase))

                    drawGroupCluster(
                        screenCenter = Offset.Zero,
                        group = group,
                        currentNexusPos = currentPos,
                        pulseScale = effectivePulseScale,
                        isSelected = isSelected,
                        isDimmed = isDimmed,
                        isFocused = isFocused,
                        ambientScale = ambientScale,
                        ambientGlow = ambientGlow
                    )
                }

                // Pending candidate connection nodes (Nascent constellation nodes)
                for (pNode in layout.pendingNodes) {
                    val pSeed = (pNode.request.id.hashCode() and 0xFFFF) / 65535f
                    val pPhase = ambientPhase + (pSeed * 2 * PI).toFloat()
                    val ambientScale = if (isReduced) 1.0f else (1.0f + 0.025f * sin(pPhase))
                    val ambientGlow = if (isReduced) 1.0f else (0.85f + 0.20f * cos(pPhase))
                    drawPendingRequestNode(
                        center = pNode.offset,
                        node = pNode,
                        pulseScale = effectivePulseScale,
                        isDimmed = selectedNodeId != null || focusedNodeId != null,
                        ambientScale = ambientScale,
                        ambientGlow = ambientGlow
                    )
                }
            }

            // 2. Viewport Screen-Space Overlay: Lucid Off-Screen Directional Indicators
            for (indicator in offScreenIndicators) {
                drawOffScreenIndicator(indicator, effectivePulseScale)
            }
        }
    }

    // Constellation Context Card dialog reveal (Phase 8.6.4 - 8.6.7)
    if (contextCardConversation != null) {
        ConstellationContextCard(
            conversation = contextCardConversation!!,
            currentUserId = currentUserId,
            onDismiss = { contextCardConversation = null },
            onOpenConversation = { convId ->
                contextCardConversation = null
                onConversationClick(convId)
            },
            onToggleHighlight = { convId, isHigh ->
                onToggleHighlight?.invoke(convId, isHigh)
            },
            onViewProfile = { user ->
                if (onViewProfile != null) onViewProfile.invoke(user) else profileDialogUser = user
            },
            onRemoveContact = { userId ->
                onRemoveContact?.invoke(userId)
            },
            onBlockUser = { userId ->
                onBlockUser?.invoke(userId)
            },
            onUnblockUser = { userId ->
                onUnblockUser?.invoke(userId)
            },
            onViewGroupInfo = { conv ->
                if (onViewGroupInfo != null) onViewGroupInfo.invoke(conv) else groupInfoDialogConv = conv
            },
            onViewGroupMembers = { conv ->
                if (onViewGroupMembers != null) onViewGroupMembers.invoke(conv) else groupMembersDialogConv = conv
            },
            onLeaveGroup = { groupId ->
                onLeaveGroup?.invoke(groupId)
            }
        )
    }

    if (profileDialogUser != null) {
        com.aster.app.ui.ChatUserProfileDialog(
            user = profileDialogUser!!,
            onDismiss = { profileDialogUser = null }
        )
    }

    if (groupInfoDialogConv != null) {
        val c = groupInfoDialogConv!!
        com.aster.app.ui.GroupInfoDialog(
            title = c.title,
            conversationId = c.id,
            memberCount = c.members.size,
            onDismiss = { groupInfoDialogConv = null }
        )
    }

    if (groupMembersDialogConv != null) {
        val c = groupMembersDialogConv!!
        com.aster.app.ui.GroupMembersDialog(
            members = c.members,
            localUserId = currentUserId,
            creatorUserId = null,
            onDismiss = { groupMembersDialogConv = null },
            onRemoveMember = {}
        )
    }

    if (selectedPendingRequest != null) {
        PendingConnectionDialog(
            request = selectedPendingRequest!!,
            onDismiss = { selectedPendingRequest = null },
            onAccept = { reqId ->
                selectedPendingRequest = null
                onAcceptPendingRequest?.invoke(reqId)
            },
            onDecline = { reqId ->
                selectedPendingRequest = null
                onDeclinePendingRequest?.invoke(reqId)
            }
        )
    }
}

/**
 * Draws a subtle, lucid off-screen directional indicator chip clamped to the viewport safe border.
 */
private fun DrawScope.drawOffScreenIndicator(
    indicator: OffScreenIndicator,
    pulseScale: Float
) {
    val center = indicator.screenPosition
    val isGroup = indicator.hasGroup
    val hasUnread = indicator.hasUnread
    val count = indicator.count

    val baseW = when {
        count > 1 && isGroup -> 54.dp.toPx()
        count > 1 -> 46.dp.toPx()
        isGroup -> 42.dp.toPx()
        else -> 36.dp.toPx()
    }
    val h = 26.dp.toPx()
    val halfW = baseW / 2f
    val halfH = h / 2f

    val isBlockedOnly = indicator.items.isNotEmpty() && indicator.items.all { it.conversation.isBlocked }

    // 1. Unread breathing aura glow
    if (hasUnread) {
        drawRoundRect(
            color = AsterViolet.copy(alpha = 0.26f),
            topLeft = Offset(center.x - halfW - 4.dp.toPx(), center.y - halfH - 4.dp.toPx()),
            size = androidx.compose.ui.geometry.Size((baseW + 8.dp.toPx()) * pulseScale, (h + 8.dp.toPx()) * pulseScale),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(halfH + 4.dp.toPx())
        )
    } else if (isBlockedOnly) {
        drawRoundRect(
            color = AsterCrimsonGlow.copy(alpha = 0.25f),
            topLeft = Offset(center.x - halfW - 3.dp.toPx(), center.y - halfH - 3.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(baseW + 6.dp.toPx(), h + 6.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(halfH + 3.dp.toPx())
        )
    }

    // 2. Chip surface background
    drawRoundRect(
        color = AsterSurfaceElevated.copy(alpha = 0.92f),
        topLeft = Offset(center.x - halfW, center.y - halfH),
        size = androidx.compose.ui.geometry.Size(baseW, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(halfH)
    )

    // 3. Chip border
    drawRoundRect(
        color = when {
            isBlockedOnly -> AsterCrimson.copy(alpha = 0.90f)
            hasUnread -> AsterViolet
            else -> AsterBorder.copy(alpha = 0.85f)
        },
        topLeft = Offset(center.x - halfW, center.y - halfH),
        size = androidx.compose.ui.geometry.Size(baseW, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(halfH),
        style = Stroke(width = if (hasUnread || isBlockedOnly) 1.5.dp.toPx() else 1.dp.toPx())
    )

    // 4. Directional arrow chevron pointing towards angleRad
    val arrowCenterX = center.x - halfW + 11.dp.toPx()
    val arrowCenterY = center.y
    val arrowLen = 5.dp.toPx()
    val cosA = cos(indicator.angleRad).toFloat()
    val sinA = sin(indicator.angleRad).toFloat()

    val tip = Offset(arrowCenterX + cosA * arrowLen, arrowCenterY + sinA * arrowLen)
    val wing1 = Offset(
        arrowCenterX + cos(indicator.angleRad + 2.5).toFloat() * arrowLen * 0.9f,
        arrowCenterY + sin(indicator.angleRad + 2.5).toFloat() * arrowLen * 0.9f
    )
    val wing2 = Offset(
        arrowCenterX + cos(indicator.angleRad - 2.5).toFloat() * arrowLen * 0.9f,
        arrowCenterY + sin(indicator.angleRad - 2.5).toFloat() * arrowLen * 0.9f
    )

    val arrowPath = Path().apply {
        moveTo(wing1.x, wing1.y)
        lineTo(tip.x, tip.y)
        lineTo(wing2.x, wing2.y)
    }
    drawPath(
        path = arrowPath,
        color = when {
            isBlockedOnly -> AsterCrimson
            hasUnread -> AsterViolet
            else -> AsterStar
        },
        style = Stroke(width = 1.8.dp.toPx())
    )

    // 5. Symbol / Count
    val textPaint = android.graphics.Paint().apply {
        color = when {
            isBlockedOnly -> android.graphics.Color.parseColor("#FFA0A0")
            hasUnread -> android.graphics.Color.WHITE
            else -> android.graphics.Color.parseColor("#E0E2EC")
        }
        textSize = 20f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    val labelX = arrowCenterX + 14.dp.toPx() + (if (count > 1 && isGroup) 4.dp.toPx() else 0f)
    val labelText = when {
        count > 1 && isGroup -> "⁂ $count"
        count > 1 -> "$count"
        isGroup -> "⁂"
        else -> "•"
    }

    drawContext.canvas.nativeCanvas.drawText(
        labelText,
        labelX,
        arrowCenterY + 6.5f,
        textPaint
    )
}

/**
 * Renders smooth flowing organic wave-like celestial filaments between two points.
 * Uses an envelope window sin(t * π) so the wave smoothly begins and ends at the exact node centers.
 */
private fun DrawScope.drawOrganicFilament(
    start: Offset,
    end: Offset,
    color: Color,
    strokeWidth: Float,
    amplitude: Float,
    cycles: Float = 2.0f,
    phase: Float = 0f,
    harmonicCounterWave: Boolean = false
) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val dist = hypot(dx, dy)
    if (dist < 1f) return

    val nx = -dy / dist
    val ny = dx / dist

    val steps = 32
    val pathA = Path()
    pathA.moveTo(start.x, start.y)

    for (i in 1..steps) {
        val t = i / steps.toFloat()
        val envelope = sin(t * Math.PI).toFloat()
        val wave = sin((t * cycles * 2.0 * Math.PI) + phase).toFloat()
        val disp = envelope * wave * amplitude

        val px = start.x + dx * t + nx * disp
        val py = start.y + dy * t + ny * disp
        pathA.lineTo(px, py)
    }

    drawPath(
        path = pathA,
        color = color,
        style = Stroke(width = strokeWidth)
    )

    if (harmonicCounterWave) {
        val pathB = Path()
        pathB.moveTo(start.x, start.y)

        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val envelope = sin(t * Math.PI).toFloat()
            val wave = sin((t * cycles * 2.0 * Math.PI) + phase + Math.PI).toFloat()
            val disp = envelope * wave * amplitude

            val px = start.x + dx * t + nx * disp
            val py = start.y + dy * t + ny * disp
            pathB.lineTo(px, py)
        }

        drawPath(
            path = pathB,
            color = color.copy(alpha = color.alpha * 0.85f),
            style = Stroke(width = strokeWidth)
        )
    }
}

/**
 * Draws the central radiant user hub node (YOU).
 */
private fun DrawScope.drawCentralUserNode(center: Offset, pulseScale: Float) {
    val auraRadius = 44.dp.toPx() * pulseScale
    val midAuraRadius = 31.dp.toPx()
    val coreRadius = 22.dp.toPx()

    // Outer breathing radiant halo
    drawCircle(
        color = AsterViolet.copy(alpha = 0.10f),
        radius = auraRadius,
        center = center
    )
    // Middle glow ring
    drawCircle(
        color = AsterViolet.copy(alpha = 0.22f),
        radius = midAuraRadius,
        center = center
    )
    // Core surface
    drawCircle(
        color = AsterElevated,
        radius = coreRadius,
        center = center
    )
    // Violet border ring
    drawCircle(
        color = AsterViolet,
        radius = coreRadius,
        center = center,
        style = Stroke(width = 2.dp.toPx())
    )
    // Core celestial luminous center
    drawCircle(
        color = AsterStar,
        radius = 3.5.dp.toPx(),
        center = center
    )

    // "You ✦" label with contrast shadow
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 28f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        setShadowLayer(4f, 0f, 1f, android.graphics.Color.argb(220, 8, 10, 18))
    }
    drawContext.canvas.nativeCanvas.drawText(
        "You ✦",
        center.x,
        center.y + coreRadius + 18.dp.toPx(),
        paint
    )
}

/**
 * Draws an individual 1:1 direct conversation node with Phase 8.6 living styling.
 */
private fun DrawScope.drawDirectConversationNode(
    center: Offset,
    node: DirectConstellationNode,
    pulseScale: Float,
    isSelected: Boolean = false,
    isDimmed: Boolean = false,
    isFocused: Boolean = false,
    ambientScale: Float = 1.0f,
    ambientGlow: Float = 1.0f
) {
    val conv = node.conversation
    val isBlocked = conv.isBlocked
    val isHighlighted = !isBlocked && conv.isHighlighted
    val hasUnread = !isBlocked && conv.unreadCount > 0
    val baseRadius = when {
        isSelected -> 26.dp.toPx()
        isFocused -> 25.dp.toPx()
        else -> 22.dp.toPx()
    }
    val radius = baseRadius * ambientScale
    val isDeleted = conv.otherMember?.isDeleted == true
    val isOnline = !isBlocked && !isDeleted && (conv.otherMember?.isOnline ?: false)
    val alphaMod = if (isDimmed) AsterMotionTokens.Alpha.Deemphasized else AsterMotionTokens.Alpha.Opaque

    // Focus & Highlight Halos
    if (isBlocked) {
        // Blocked restriction aura (AsterCrimson halo)
        drawCircle(
            color = AsterCrimsonGlow.copy(alpha = 0.45f * alphaMod),
            radius = radius + 9.dp.toPx(),
            center = center
        )
        drawCircle(
            color = AsterCrimson.copy(alpha = 0.22f * alphaMod),
            radius = radius + 4.dp.toPx(),
            center = center
        )
    } else if (isFocused) {
        // Focused elevated starlight halo during long-press (Phase 8.6.2)
        drawCircle(
            color = AsterViolet.copy(alpha = 0.55f),
            radius = radius + 15.dp.toPx(),
            center = center
        )
        drawCircle(
            color = AsterStar.copy(alpha = 0.35f),
            radius = radius + 8.dp.toPx(),
            center = center
        )
    } else if (isSelected) {
        drawCircle(
            color = AsterViolet.copy(alpha = 0.50f),
            radius = radius + 14.dp.toPx(),
            center = center
        )
        drawCircle(
            color = AsterGlow.copy(alpha = 0.35f),
            radius = radius + 8.dp.toPx(),
            center = center
        )
    } else if (isHighlighted) {
        // Highlighted Aura (Phase 8.6.8: Aster Violet / Warm starlight halo)
        drawCircle(
            color = AsterViolet.copy(alpha = 0.28f * ambientGlow * alphaMod),
            radius = radius + 12.dp.toPx(),
            center = center
        )
        drawCircle(
            color = AsterWarm.copy(alpha = 0.16f * ambientGlow * alphaMod),
            radius = radius + 6.dp.toPx(),
            center = center
        )
    } else if (hasUnread) {
        // Unread aura glow
        drawCircle(
            color = AsterViolet.copy(alpha = 0.28f * alphaMod),
            radius = (radius + 10.dp.toPx()) * pulseScale,
            center = center
        )
    }

    // Node background
    drawCircle(
        color = AsterSurfaceElevated.copy(alpha = alphaMod),
        radius = radius,
        center = center
    )

    // Border ring
    val borderColor = when {
        isBlocked -> AsterCrimson.copy(alpha = 0.90f * alphaMod)
        isSelected || isFocused -> AsterViolet
        isHighlighted -> AsterWarm.copy(alpha = alphaMod)
        hasUnread -> AsterViolet.copy(alpha = alphaMod)
        else -> AsterBorder.copy(alpha = alphaMod)
    }
    val borderWidth = when {
        isBlocked -> 2.2.dp.toPx()
        isSelected || isFocused -> 2.2.dp.toPx()
        isHighlighted -> 2.0.dp.toPx()
        hasUnread -> 1.8.dp.toPx()
        else -> 1.2.dp.toPx()
    }
    drawCircle(
        color = borderColor,
        radius = radius,
        center = center,
        style = Stroke(width = borderWidth)
    )

    // Blocked restriction badge (top-right of node)
    if (isBlocked) {
        val blockCenter = Offset(center.x + radius * 0.70f, center.y - radius * 0.70f)
        val blockRadius = 6.5.dp.toPx()
        drawCircle(
            color = AsterNight,
            radius = blockRadius + 1.5.dp.toPx(),
            center = blockCenter
        )
        drawCircle(
            color = AsterCrimson,
            radius = blockRadius,
            center = blockCenter
        )
        val blockTextPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 20f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        drawContext.canvas.nativeCanvas.drawText("⊘", blockCenter.x, blockCenter.y + 7f, blockTextPaint)
    }

    // Highlight Radiant Badge Indicator (top-left of node)
    if (isHighlighted) {
        val starCenter = Offset(center.x - radius * 0.70f, center.y - radius * 0.70f)
        val starRadius = 5.5.dp.toPx()
        drawCircle(
            color = AsterNight,
            radius = starRadius + 1.5.dp.toPx(),
            center = starCenter
        )
        drawCircle(
            color = AsterWarm,
            radius = starRadius,
            center = starCenter
        )
        val starTextPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#080A12")
            textSize = 18f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        drawContext.canvas.nativeCanvas.drawText("✦", starCenter.x, starCenter.y + 6f, starTextPaint)
    }

    // Node initials
    val initial = if (isDeleted) "D" else conv.title.trim().take(1).uppercase().ifEmpty { "✦" }
    val initialPaint = android.graphics.Paint().apply {
        color = if (isBlocked) {
            android.graphics.Color.parseColor("#FFA0A0")
        } else if (hasUnread) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.parseColor("#F0F0F5")
        }
        textSize = 30f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        if (alphaMod < 1.0f) alpha = (alphaMod * 255).toInt()
    }
    drawContext.canvas.nativeCanvas.drawText(
        initial,
        center.x,
        center.y + 11f,
        initialPaint
    )

    // Online presence green indicator (bottom-right of node)
    if (isOnline) {
        val dotCenter = Offset(center.x + radius * 0.70f, center.y + radius * 0.70f)
        val dotRadius = 4.5.dp.toPx()

        drawCircle(
            color = AsterNight,
            radius = dotRadius + 1.5.dp.toPx(),
            center = dotCenter
        )
        drawCircle(
            color = AsterOnline.copy(alpha = alphaMod),
            radius = dotRadius,
            center = dotCenter
        )
    }

    // Unread badge (top-right of node)
    if (hasUnread) {
        val badgeCenter = Offset(center.x + radius * 0.72f, center.y - radius * 0.72f)
        val badgeRadius = 9.dp.toPx()

        drawCircle(
            color = AsterNight,
            radius = badgeRadius + 2.dp.toPx(),
            center = badgeCenter
        )
        drawCircle(
            color = AsterViolet.copy(alpha = alphaMod),
            radius = badgeRadius,
            center = badgeCenter
        )

        val badgeTextPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#080A12")
            textSize = 20f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            if (alphaMod < 1.0f) alpha = (alphaMod * 255).toInt()
        }
        val countText = if (conv.unreadCount > 9) "9+" else conv.unreadCount.toString()
        drawContext.canvas.nativeCanvas.drawText(
            countText,
            badgeCenter.x,
            badgeCenter.y + 7f,
            badgeTextPaint
        )
    }

    // Node title label with contrast shadow
    val titlePaint = android.graphics.Paint().apply {
        color = if (isBlocked) android.graphics.Color.parseColor("#FFA0A0") else android.graphics.Color.WHITE
        textSize = 26f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
        setShadowLayer(4f, 0f, 1f, android.graphics.Color.argb((220 * alphaMod).toInt(), 8, 10, 18))
        if (alphaMod < 1.0f) alpha = (alphaMod * 255).toInt()
    }
    drawContext.canvas.nativeCanvas.drawText(
        node.titleDisplay,
        center.x,
        center.y + radius + 17.dp.toPx(),
        titlePaint
    )
}

/**
 * Draws a Group Constellation Cluster with Phase 8.6 living styling.
 */
private fun DrawScope.drawGroupCluster(
    screenCenter: Offset,
    group: GroupConstellationNode,
    currentNexusPos: Offset,
    pulseScale: Float,
    isSelected: Boolean = false,
    isDimmed: Boolean = false,
    isFocused: Boolean = false,
    ambientScale: Float = 1.0f,
    ambientGlow: Float = 1.0f
) {
    val nexusPos = screenCenter + currentNexusPos
    val conv = group.conversation
    val isHighlighted = conv.isHighlighted
    val hasUnread = conv.unreadCount > 0
    val baseRadius = when {
        isSelected -> 30.dp.toPx()
        isFocused -> 29.dp.toPx()
        else -> 26.dp.toPx()
    }
    val nexusRadius = baseRadius * ambientScale
    val alphaMod = if (isDimmed) AsterMotionTokens.Alpha.Deemphasized else AsterMotionTokens.Alpha.Opaque

    if (isFocused) {
        // Focused halo
        drawCircle(
            color = AsterViolet.copy(alpha = 0.55f),
            radius = nexusRadius + 18.dp.toPx(),
            center = nexusPos
        )
        drawCircle(
            color = AsterStar.copy(alpha = 0.35f),
            radius = nexusRadius + 10.dp.toPx(),
            center = nexusPos
        )
    } else if (isSelected) {
        // Focus radiant bloom for spatial node-to-chat transition
        drawCircle(
            color = AsterViolet.copy(alpha = 0.50f),
            radius = nexusRadius + 16.dp.toPx(),
            center = nexusPos
        )
        drawCircle(
            color = AsterGlow.copy(alpha = 0.35f),
            radius = nexusRadius + 9.dp.toPx(),
            center = nexusPos
        )
    } else if (isHighlighted) {
        // Highlighted Group Cluster Aura (Phase 8.6.8)
        drawCircle(
            color = AsterViolet.copy(alpha = 0.32f * ambientGlow * alphaMod),
            radius = nexusRadius + 14.dp.toPx(),
            center = nexusPos
        )
        drawCircle(
            color = AsterWarm.copy(alpha = 0.22f * ambientGlow * alphaMod),
            radius = nexusRadius + 8.dp.toPx(),
            center = nexusPos
        )
        // Celestial orbital gold ring for nexus dignity
        drawCircle(
            color = AsterWarm.copy(alpha = 0.45f * alphaMod),
            radius = nexusRadius + 4.dp.toPx(),
            center = nexusPos,
            style = Stroke(width = 1.dp.toPx())
        )
    } else {
        // 1. Group Nexus Outer Celestial Orbital Halo
        drawCircle(
            color = AsterViolet.copy(alpha = if (hasUnread) 0.22f * alphaMod else 0.12f * alphaMod),
            radius = (nexusRadius + 10.dp.toPx()) * pulseScale,
            center = nexusPos
        )
        drawCircle(
            color = AsterViolet.copy(alpha = 0.32f * alphaMod),
            radius = nexusRadius + 5.dp.toPx(),
            center = nexusPos,
            style = Stroke(width = 1.dp.toPx())
        )
    }

    // 2. Group Nexus Core Surface
    drawCircle(
        color = AsterElevated.copy(alpha = alphaMod),
        radius = nexusRadius,
        center = nexusPos
    )

    // Nexus Border
    val borderColor = when {
        isSelected || isFocused -> AsterViolet
        isHighlighted -> AsterWarm.copy(alpha = alphaMod)
        hasUnread -> AsterViolet.copy(alpha = alphaMod)
        else -> AsterBorder.copy(alpha = alphaMod)
    }
    drawCircle(
        color = borderColor,
        radius = nexusRadius,
        center = nexusPos,
        style = Stroke(width = if (hasUnread || isHighlighted || isFocused) 2.dp.toPx() else 1.5.dp.toPx())
    )

    // Highlight Radiant Badge on Group Nexus
    if (isHighlighted) {
        val starCenter = Offset(nexusPos.x - nexusRadius * 0.70f, nexusPos.y - nexusRadius * 0.70f)
        val starRadius = 5.5.dp.toPx()
        drawCircle(
            color = AsterNight,
            radius = starRadius + 1.5.dp.toPx(),
            center = starCenter
        )
        drawCircle(
            color = AsterWarm,
            radius = starRadius,
            center = starCenter
        )
        val starTextPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#080A12")
            textSize = 18f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        drawContext.canvas.nativeCanvas.drawText("✦", starCenter.x, starCenter.y + 6f, starTextPaint)
    }

    // Distinctive Group Cluster Symbol (⁂)
    val symbolPaint = android.graphics.Paint().apply {
        color = if (hasUnread) android.graphics.Color.parseColor("#A37FF5") else android.graphics.Color.parseColor("#F0F0F5")
        textSize = 34f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        if (alphaMod < 1.0f) alpha = (alphaMod * 255).toInt()
    }
    drawContext.canvas.nativeCanvas.drawText(
        "⁂",
        nexusPos.x,
        nexusPos.y + 11f,
        symbolPaint
    )

    // Unread badge on Group Nexus
    if (hasUnread) {
        val badgeCenter = Offset(nexusPos.x + nexusRadius * 0.72f, nexusPos.y - nexusRadius * 0.72f)
        val badgeRadius = 9.dp.toPx()

        drawCircle(
            color = AsterNight,
            radius = badgeRadius + 2.dp.toPx(),
            center = badgeCenter
        )
        drawCircle(
            color = AsterViolet.copy(alpha = alphaMod),
            radius = badgeRadius,
            center = badgeCenter
        )

        val badgeTextPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#080A12")
            textSize = 20f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            if (alphaMod < 1.0f) alpha = (alphaMod * 255).toInt()
        }
        val countText = if (conv.unreadCount > 9) "9+" else conv.unreadCount.toString()
        drawContext.canvas.nativeCanvas.drawText(
            countText,
            badgeCenter.x,
            badgeCenter.y + 7f,
            badgeTextPaint
        )
    }

    // Group Title & Cluster Subtitle with contrast shadow
    val titlePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 26f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        setShadowLayer(4f, 0f, 1f, android.graphics.Color.argb((220 * alphaMod).toInt(), 8, 10, 18))
        if (alphaMod < 1.0f) alpha = (alphaMod * 255).toInt()
    }
    drawContext.canvas.nativeCanvas.drawText(
        group.titleDisplay,
        nexusPos.x,
        nexusPos.y + group.labelOffsetY,
        titlePaint
    )

    val subtitlePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#8E95AA")
        textSize = 20f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
        setShadowLayer(3f, 0f, 1f, android.graphics.Color.argb((200 * alphaMod).toInt(), 8, 10, 18))
        if (alphaMod < 1.0f) alpha = (alphaMod * 255).toInt()
    }
    drawContext.canvas.nativeCanvas.drawText(
        group.subtitleDisplay,
        nexusPos.x,
        nexusPos.y + group.labelOffsetY + 13.dp.toPx(),
        subtitlePaint
    )

    // 3. Draw Branching Satellite Member Nodes (dynamically translated with cluster)
    for (sat in group.satellites) {
        val satPos = screenCenter + currentNexusPos + (sat.offset - group.nexusOffset)
        val sRadius = 13.dp.toPx()

        // Satellite Surface
        drawCircle(
            color = AsterSurfaceElevated.copy(alpha = alphaMod),
            radius = sRadius,
            center = satPos
        )

        // Satellite Border
        drawCircle(
            color = AsterVioletSoft.copy(alpha = 0.55f * alphaMod),
            radius = sRadius,
            center = satPos,
            style = Stroke(width = 1.dp.toPx())
        )

        // Member Initial
        val satTextPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#F0F0F5")
            textSize = 20f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            if (alphaMod < 1.0f) alpha = (alphaMod * 255).toInt()
        }
        drawContext.canvas.nativeCanvas.drawText(
            sat.initial,
            satPos.x,
            satPos.y + 7f,
            satTextPaint
        )

        // Online presence dot on satellite
        if (sat.isOnline) {
            val dotCenter = Offset(satPos.x + sRadius * 0.70f, satPos.y + sRadius * 0.70f)
            val dotRadius = 3.dp.toPx()

            drawCircle(
                color = AsterNight,
                radius = dotRadius + 1.dp.toPx(),
                center = dotCenter
            )
            drawCircle(
                color = AsterOnline.copy(alpha = alphaMod),
                radius = dotRadius,
                center = dotCenter
            )
        }
    }
}

/**
 * Draws an unformed / candidate connection node with distinct celestial styling:
 * - Subtle warm starlight aura with restrained attention pulse
 * - Warm amber celestial core and border
 * - Sender initial
 * - Small attention indicator badge ('!')
 * - Label showing displayName and '(Incoming Request)'
 */
private fun DrawScope.drawPendingRequestNode(
    center: Offset,
    node: PendingConstellationNode,
    pulseScale: Float,
    isDimmed: Boolean = false,
    ambientScale: Float = 1.0f,
    ambientGlow: Float = 1.0f
) {
    val req = node.request
    val baseRadius = 22.dp.toPx()
    val radius = baseRadius * ambientScale
    val alphaMod = if (isDimmed) AsterMotionTokens.Alpha.Deemphasized else AsterMotionTokens.Alpha.Opaque

    // Subtle warm amber nascent aura with restrained attention pulse
    val auraRadius = radius + (8.dp.toPx() * pulseScale)
    drawCircle(
        color = AsterWarm.copy(alpha = 0.22f * ambientGlow * alphaMod),
        radius = auraRadius,
        center = center
    )
    drawCircle(
        color = AsterWarm.copy(alpha = 0.12f * ambientGlow * alphaMod),
        radius = radius + 4.dp.toPx(),
        center = center
    )

    // Core body (Dark celestial elevated slate)
    drawCircle(
        color = AsterElevated,
        radius = radius,
        center = center
    )

    // Border: Warm amber border ring
    drawCircle(
        color = AsterWarm.copy(alpha = 0.85f * alphaMod),
        radius = radius,
        center = center,
        style = Stroke(width = 1.8.dp.toPx())
    )

    // Initial glyph
    val initial = (req.sender?.displayName ?: req.senderUserId).take(1).uppercase()
    val avatarPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 34f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        alpha = (255 * alphaMod).toInt()
    }
    drawContext.canvas.nativeCanvas.drawText(
        initial,
        center.x,
        center.y + 11f,
        avatarPaint
    )

    // Restrained attention badge on top-right: pill with '!'
    val badgeCenter = Offset(center.x + radius * 0.72f, center.y - radius * 0.72f)
    drawCircle(
        color = AsterWarm,
        radius = 8.5.dp.toPx(),
        center = badgeCenter
    )
    drawCircle(
        color = AsterNight,
        radius = 8.5.dp.toPx(),
        center = badgeCenter,
        style = Stroke(width = 1.5.dp.toPx())
    )
    val badgePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        textSize = 22f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    drawContext.canvas.nativeCanvas.drawText(
        "!",
        badgeCenter.x,
        badgeCenter.y + 7.5f,
        badgePaint
    )

    // Title label below
    val titlePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 26f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        setShadowLayer(4f, 0f, 1f, android.graphics.Color.argb(220, 8, 10, 18))
        alpha = (255 * alphaMod).toInt()
    }
    drawContext.canvas.nativeCanvas.drawText(
        node.titleDisplay,
        center.x,
        center.y + radius + 16.dp.toPx(),
        titlePaint
    )

    // Subtitle label "(Incoming Request)"
    val subPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(255, 195, 110) // soft warm amber
        textSize = 20f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        setShadowLayer(3f, 0f, 1f, android.graphics.Color.argb(220, 8, 10, 18))
        alpha = (220 * alphaMod).toInt()
    }
    drawContext.canvas.nativeCanvas.drawText(
        "Incoming Request",
        center.x,
        center.y + radius + 29.dp.toPx(),
        subPaint
    )
}

/**
 * Lightweight candidate connection dialog for accepting or declining an incoming request
 * directly from the Constellation Map.
 */
@Composable
fun PendingConnectionDialog(
    request: com.aster.app.data.network.FriendRequestSummary,
    onDismiss: () -> Unit,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit
) {
    val haptics = LocalAsterHaptics.current
    val isReduced = LocalReducedMotion.current
    val senderName = request.sender?.displayName ?: request.senderUserId
    val senderUsername = request.sender?.username ?: ""

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .asterDialogTransition()
                    .clip(RoundedCornerShape(24.dp))
                    .background(AsterElevated)
                    .border(1.dp, AsterWarm.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(AsterWarm.copy(alpha = 0.15f))
                            .border(1.dp, AsterWarm, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✧",
                            color = AsterWarm,
                            fontSize = 28.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Candidate Connection",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = AsterWarm,
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    )

                    if (senderUsername.isNotBlank()) {
                        Text(
                            text = "@$senderUsername",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = AsterGlow.copy(alpha = 0.7f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Wants to form a constellation with you. Accepting adds this person to your constellation map.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.White.copy(alpha = 0.75f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AsterSurfaceElevated)
                                .border(0.75.dp, AsterBorder, RoundedCornerShape(12.dp))
                                .clickable {
                                    haptics.perform(AsterHapticEvent.Selection)
                                    onDecline(request.id)
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Decline",
                                color = Color.White.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AsterViolet)
                                .clickable {
                                    haptics.perform(AsterHapticEvent.Success)
                                    onAccept(request.id)
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Accept ✦",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
