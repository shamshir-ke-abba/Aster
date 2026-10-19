package com.aster.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aster.app.domain.model.Conversation
import com.aster.app.ui.theme.AsterCrimson
import com.aster.app.ui.theme.AsterCrimsonGlow
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterViolet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Interactive 2D Constellation Canvas for Aster conversations.
 *
 * Direct chats: Connected star pairs (current user <-> partner) with partner avatar initials.
 * Group chats: Member constellation web with a prominent central hub and group identity.
 * Layout: Deterministic polar distribution derived from immutable conversation IDs.
 * Unread indicator: Restrained celestial crimson aura and unread badge.
 */
@Composable
fun ConstellationChatView(
    conversations: List<Conversation>,
    currentUserId: String = "",
    onConversationClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Subtle breathing pulse for unread activity
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Precalculate deterministic positions based on stable IDs
    val nodes = remember(conversations) {
        calculateDeterministicPositions(conversations)
    }

    val hitRadiusPx = with(density) { 60.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(conversations) {
                detectTapGestures { tapOffset ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val worldTap = tapOffset - center - panOffset

                    // Find nearest node within hit radius
                    val clicked = nodes.minByOrNull { node ->
                        val dx = node.centerOffset.x - worldTap.x
                        val dy = node.centerOffset.y - worldTap.y
                        dx * dx + dy * dy
                    }

                    if (clicked != null) {
                        val dx = clicked.centerOffset.x - worldTap.x
                        val dy = clicked.centerOffset.y - worldTap.y
                        val distSq = dx * dx + dy * dy
                        if (distSq <= hitRadiusPx * hitRadiusPx) {
                            onConversationClick(clicked.conversation.id)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    panOffset += dragAmount
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasCenter = Offset(size.width / 2f, size.height / 2f)
            val baseOrigin = canvasCenter + panOffset

            // Draw celestial constellation grid lines connecting neighboring conversation clusters
            for (i in nodes.indices) {
                val nodeA = nodes[i]
                val posA = baseOrigin + nodeA.centerOffset

                // Connect to nearest 1-2 neighbors with faint background filament lines
                for (j in (i + 1) until (i + 3).coerceAtMost(nodes.size)) {
                    val nodeB = nodes[j]
                    val posB = baseOrigin + nodeB.centerOffset
                    drawLine(
                        color = AsterGlow.copy(alpha = 0.08f),
                        start = posA,
                        end = posB,
                        strokeWidth = 1f
                    )
                }
            }

            // Draw individual conversation constellations
            for (node in nodes) {
                val pos = baseOrigin + node.centerOffset
                val conv = node.conversation
                val hasUnread = conv.unreadCount > 0

                if (conv.isGroup) {
                    drawGroupConstellation(
                        center = pos,
                        conv = conv,
                        hasUnread = hasUnread,
                        pulseScale = pulseScale
                    )
                } else {
                    drawDirectChatPair(
                        center = pos,
                        conv = conv,
                        hasUnread = hasUnread,
                        pulseScale = pulseScale
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawDirectChatPair(
    center: Offset,
    conv: Conversation,
    hasUnread: Boolean,
    pulseScale: Float
) {
    val pairDist = 42.dp.toPx()
    val userStar = Offset(center.x - pairDist / 2f, center.y + 10.dp.toPx())
    val partnerStar = Offset(center.x + pairDist / 2f, center.y - 10.dp.toPx())

    // 1. Connection line between user star and partner star
    drawLine(
        color = if (hasUnread) AsterCrimson.copy(alpha = 0.7f) else AsterViolet.copy(alpha = 0.45f),
        start = userStar,
        end = partnerStar,
        strokeWidth = if (hasUnread) 2f else 1.2f
    )

    // 2. User star (subtle, fixed anchor)
    drawCircle(
        color = AsterGlow.copy(alpha = 0.5f),
        radius = 4.dp.toPx(),
        center = userStar
    )

    // 3. Unread activity aura on partner star
    if (hasUnread) {
        drawCircle(
            color = AsterCrimsonGlow.copy(alpha = 0.35f),
            radius = (16.dp.toPx()) * pulseScale,
            center = partnerStar
        )
        drawCircle(
            color = AsterCrimson.copy(alpha = 0.6f),
            radius = 11.dp.toPx(),
            center = partnerStar
        )
    }

    // 4. Partner star
    val partnerColor = if (hasUnread) AsterCrimson else AsterViolet
    drawCircle(
        color = partnerColor,
        radius = 8.dp.toPx(),
        center = partnerStar
    )

    // 5. Star cross sparkle for high-contrast visibility
    drawStarSparkle(partnerStar, 7.dp.toPx(), AsterStar.copy(alpha = 0.9f))

    // 6. Name label & unread count badge
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 34f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    val subtitlePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(180, 184, 185, 255)
        textSize = 26f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }

    drawContext.canvas.nativeCanvas.drawText(
        conv.title,
        center.x,
        center.y + 34.dp.toPx(),
        paint
    )

    if (hasUnread) {
        val badgeText = "${conv.unreadCount} new"
        drawContext.canvas.nativeCanvas.drawText(
            badgeText,
            center.x,
            center.y + 48.dp.toPx(),
            android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#FFD9383A")
                textSize = 24f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
        )
    } else if (!conv.lastMessage?.body.isNullOrBlank()) {
        val snippet = conv.lastMessage!!.body.take(18) + if (conv.lastMessage!!.body.length > 18) "…" else ""
        drawContext.canvas.nativeCanvas.drawText(
            snippet,
            center.x,
            center.y + 48.dp.toPx(),
            subtitlePaint
        )
    }
}

private fun DrawScope.drawGroupConstellation(
    center: Offset,
    conv: Conversation,
    hasUnread: Boolean,
    pulseScale: Float
) {
    val memberCount = (3 + (kotlin.math.abs(conv.id.hashCode()) % 4)).coerceIn(3, 6)
    val clusterRadius = 32.dp.toPx()
    val memberOffsets = mutableListOf<Offset>()

    // Compute star positions around central hub
    for (i in 0 until memberCount) {
        val angle = (2.0 * PI * i / memberCount) - (PI / 2.0)
        val mOffset = Offset(
            center.x + (clusterRadius * cos(angle)).toFloat(),
            center.y + (clusterRadius * sin(angle)).toFloat()
        )
        memberOffsets.add(mOffset)
    }

    // 1. Draw web of fine connecting lines
    for (i in memberOffsets.indices) {
        // Line to central hub
        drawLine(
            color = if (hasUnread) AsterCrimson.copy(alpha = 0.5f) else AsterGlow.copy(alpha = 0.25f),
            start = center,
            end = memberOffsets[i],
            strokeWidth = 1f
        )
        // Line to neighbor star forming polygon constellation
        val nextIdx = (i + 1) % memberOffsets.size
        drawLine(
            color = if (hasUnread) AsterCrimson.copy(alpha = 0.6f) else AsterViolet.copy(alpha = 0.4f),
            start = memberOffsets[i],
            end = memberOffsets[nextIdx],
            strokeWidth = 1.2f
        )
    }

    // 2. Unread pulse aura around central cluster
    if (hasUnread) {
        drawCircle(
            color = AsterCrimsonGlow.copy(alpha = 0.35f),
            radius = (clusterRadius + 14.dp.toPx()) * pulseScale,
            center = center
        )
    }

    // 3. Central group hub star
    drawCircle(
        color = if (hasUnread) AsterCrimson else AsterStar,
        radius = 9.dp.toPx(),
        center = center
    )
    drawStarSparkle(center, 9.dp.toPx(), if (hasUnread) AsterStar else AsterViolet)

    // 4. Member stars
    for (mOffset in memberOffsets) {
        drawCircle(
            color = AsterViolet.copy(alpha = 0.85f),
            radius = 4.dp.toPx(),
            center = mOffset
        )
    }

    // 5. Group Name & Member count labels
    val titlePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 34f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    val subtitlePaint = android.graphics.Paint().apply {
        color = if (hasUnread) android.graphics.Color.parseColor("#FFD9383A") else android.graphics.Color.argb(180, 184, 185, 255)
        textSize = 25f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }

    drawContext.canvas.nativeCanvas.drawText(
        conv.title,
        center.x,
        center.y + clusterRadius + 22.dp.toPx(),
        titlePaint
    )

    val subtitleText = if (hasUnread) "${conv.unreadCount} unread" else "Constellation • Circle"
    drawContext.canvas.nativeCanvas.drawText(
        subtitleText,
        center.x,
        center.y + clusterRadius + 36.dp.toPx(),
        subtitlePaint
    )
}

private fun DrawScope.drawStarSparkle(center: Offset, size: Float, color: Color) {
    drawLine(
        color = color,
        start = Offset(center.x - size, center.y),
        end = Offset(center.x + size, center.y),
        strokeWidth = 1.5f
    )
    drawLine(
        color = color,
        start = Offset(center.x, center.y - size),
        end = Offset(center.x, center.y + size),
        strokeWidth = 1.5f
    )
}

data class ConstellationNode(
    val conversation: Conversation,
    val centerOffset: Offset
)

/**
 * Distributes conversation nodes deterministically using a golden spiral phyllotaxis pattern.
 * Coordinates are 100% deterministic and stable across recompositions and app restarts.
 */
private fun calculateDeterministicPositions(conversations: List<Conversation>): List<ConstellationNode> {
    if (conversations.isEmpty()) return emptyList()

    // Stable sort by ID so insertion order doesn't shift existing nodes
    val sorted = conversations.sortedBy { it.id }
    val result = ArrayList<ConstellationNode>(sorted.size)

    val baseSpacing = 210f // px spacing between clusters
    val goldenAngle = 2.39996322972865332 // radians (golden angle)

    for (i in sorted.indices) {
        val conv = sorted[i]
        if (i == 0) {
            // First conversation in the center
            result.add(ConstellationNode(conv, Offset(0f, -40f)))
            continue
        }

        val r = baseSpacing * sqrt(i.toFloat())
        val theta = i * goldenAngle
        val x = (r * cos(theta)).toFloat()
        val y = (r * sin(theta)).toFloat()
        result.add(ConstellationNode(conv, Offset(x, y)))
    }

    return result
}
