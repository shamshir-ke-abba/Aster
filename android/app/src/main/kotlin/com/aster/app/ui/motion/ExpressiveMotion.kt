package com.aster.app.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aster.app.ui.theme.AsterBorder
import com.aster.app.ui.theme.AsterCrimson
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterMuted
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurfaceElevated
import com.aster.app.ui.theme.AsterViolet
import com.aster.app.ui.theme.AsterWarm
import kotlin.math.PI
import kotlin.math.sin

/**
 * Aster Phase 8.4: Expressive Messaging Motion & Tokens
 *
 * Implements:
 * 1. Curated reaction dock and reactive badge tokens.
 * 2. Restrained, auto-terminating (<= 1.5s) canvas-based celestial message effects:
 *    - Nebula (soft violet/cyan cosmic cloud)
 *    - Stardust (gentle luminous starlight particles)
 *    - Ripple (expanding gravitational waves)
 *    - Pulse (soft starlight breath)
 *    - Comet (directional streak and tail)
 * 3. Expressive typography emphasis (shimmer, pulse, alert).
 * 4. Full accessibility and reduced-motion compliance.
 */
object AsterReactionTokens {
    val CuratedEmojis = listOf("❤️", "😂", "😮", "😢", "👍", "🔥")
    val CelestialEffects = listOf("nebula", "stardust", "ripple", "pulse", "comet")

    fun effectDisplayName(effect: String): String = when (effect.lowercase()) {
        "nebula" -> "Nebula"
        "stardust" -> "Stardust"
        "ripple" -> "Ripple"
        "pulse" -> "Pulse"
        "comet" -> "Comet"
        else -> effect.replaceFirstChar { it.uppercase() }
    }

    fun effectIcon(effect: String): String = when (effect.lowercase()) {
        "nebula" -> "🌌"
        "stardust" -> "✨"
        "ripple" -> "🌊"
        "pulse" -> "💫"
        "comet" -> "☄️"
        else -> "✨"
    }

    fun effectDescription(effect: String): String = when (effect.lowercase()) {
        "nebula" -> "Atmospheric violet cloud pulse"
        "stardust" -> "Luminous drifting starlight embers"
        "ripple" -> "Expanding gravitational ring"
        "pulse" -> "Gentle starlight breathing glow"
        "comet" -> "Directional starlight trail"
        else -> "Celestial aura"
    }
}

/**
 * Lightweight floating reaction dock positioned above or anchored to the focused message bubble.
 */
@Composable
fun AsterReactionDock(
    onReactionSelected: (String) -> Unit,
    userReactions: Set<String> = emptySet(),
    modifier: Modifier = Modifier
) {
    val isReduced = LocalReducedMotion.current

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(AsterSurfaceElevated)
            .border(1.dp, AsterViolet.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsterReactionTokens.CuratedEmojis.forEachIndexed { index, emoji ->
            val isSelected = userReactions.contains(emoji)
            val delayMs = if (isReduced) 0 else index * 25

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) AsterViolet.copy(alpha = 0.28f) else Color.Transparent)
                    .border(
                        width = if (isSelected) 1.dp else 0.dp,
                        color = if (isSelected) AsterViolet else Color.Transparent,
                        shape = CircleShape
                    )
                    .asterPressable(
                        pressedScale = if (isReduced) 1.0f else 1.15f,
                        hapticEvent = AsterHapticEvent.Reaction,
                        onClick = { onReactionSelected(emoji) }
                    )
                    .semantics {
                        contentDescription = "React with $emoji" + if (isSelected) " (Selected)" else ""
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = emoji,
                    fontSize = 20.sp
                )
            }
        }
    }
}

/**
 * Reaction badge chip attached to the message bubble.
 */
@Composable
fun AsterReactionBadge(
    emoji: String,
    count: Int,
    reactedByMe: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isReduced = LocalReducedMotion.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (reactedByMe) AsterViolet.copy(alpha = 0.24f) else AsterNight.copy(alpha = 0.85f))
            .border(
                width = 0.75.dp,
                color = if (reactedByMe) AsterViolet.copy(alpha = 0.7f) else AsterBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .asterPressable(
                pressedScale = if (isReduced) 1.0f else 0.94f,
                hapticEvent = AsterHapticEvent.Reaction,
                onClick = onToggle
            )
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .semantics {
                contentDescription = "$emoji reaction, count $count${if (reactedByMe) ", you reacted" else ""}"
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = emoji,
                fontSize = 12.sp
            )

            AnimatedContent(
                targetState = count,
                transitionSpec = {
                    if (isReduced) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else if (targetState > initialState) {
                        (slideInVertically { height -> height } + fadeIn()) togetherWith
                                (slideOutVertically { height -> -height } + fadeOut())
                    } else {
                        (slideInVertically { height -> -height } + fadeIn()) togetherWith
                                (slideOutVertically { height -> height } + fadeOut())
                    }
                },
                label = "ReactionCountAnim"
            ) { targetCount ->
                Text(
                    text = "$targetCount",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (reactedByMe) AsterStar else AsterMuted,
                        fontSize = 10.5.sp,
                        fontWeight = if (reactedByMe) FontWeight.Bold else FontWeight.Medium
                    )
                )
            }
        }
    }
}

/**
 * Attached reaction row positioned on the lower corner of the message bubble.
 */
@Composable
fun AsterReactionBadgeRow(
    reactions: Map<String, List<String>>,
    currentUserId: String?,
    onToggleReaction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (reactions.isEmpty()) return

    val isReduced = LocalReducedMotion.current

    Row(
        modifier = modifier
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        reactions.forEach { (emoji, userList) ->
            if (userList.isNotEmpty()) {
                val reactedByMe = currentUserId != null && userList.contains(currentUserId)
                AnimatedVisibility(
                    visible = true,
                    enter = scaleIn(
                        animationSpec = AsterMotion.spring(AsterMotionTokens.SpringPhysics.Press, reducedMotion = isReduced),
                        initialScale = 0.7f
                    ) + fadeIn(),
                    exit = scaleOut(
                        animationSpec = AsterMotion.spring(AsterMotionTokens.SpringPhysics.Release, reducedMotion = isReduced),
                        targetScale = 0.7f
                    ) + fadeOut()
                ) {
                    AsterReactionBadge(
                        emoji = emoji,
                        count = userList.size,
                        reactedByMe = reactedByMe,
                        onToggle = { onToggleReaction(emoji) }
                    )
                }
            }
        }
    }
}

/**
 * Container box that wraps a message bubble and renders optional, restrained celestial effects.
 *
 * All effects auto-terminate within 1.2 seconds, never run infinitely, and collapse cleanly under reduced motion.
 */
@Composable
fun AsterCelestialEffectBox(
    effect: String?,
    modifier: Modifier = Modifier,
    shouldAnimate: Boolean = true,
    onEffectStarted: (() -> Unit)? = null,
    onEffectFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val isReduced = LocalReducedMotion.current
    val progress = remember { Animatable(0f) }
    var activeEffect by remember { mutableStateOf<String?>(null) }
    var hasStarted by remember { mutableStateOf(false) }

    LaunchedEffect(effect) {
        if (!effect.isNullOrBlank() && shouldAnimate && !hasStarted) {
            hasStarted = true
            onEffectStarted?.invoke()
            activeEffect = effect.lowercase()
            if (isReduced) {
                // Instantly complete or show static indicator for 400ms under reduced motion
                progress.snapTo(1f)
                kotlinx.coroutines.delay(400)
                activeEffect = null
                onEffectFinished?.invoke()
            } else {
                progress.snapTo(0f)
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 1200, easing = LinearEasing)
                )
                activeEffect = null
                onEffectFinished?.invoke()
            }
        } else if (effect.isNullOrBlank() || !shouldAnimate) {
            if (!hasStarted) {
                activeEffect = null
            }
        }
    }

    val currentEffect = activeEffect
    val p = progress.value

    val breathScale = if (currentEffect == "pulse" && p < 1f) {
        if (isReduced) 1f else 1.0f + 0.04f * sin(p * PI.toFloat())
    } else 1.0f

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Background celestial effects (nebula, pulse glow, ripple)
        if (currentEffect != null && p < 1f) {
            when (currentEffect) {
                "nebula" -> {
                    // Soft atmospheric violet/cyan cloud pulse behind the bubble
                    Canvas(
                        modifier = Modifier.matchParentSize()
                    ) {
                        val maxRadius = size.maxDimension * 1.25f
                        val currentRadius = maxRadius * (0.65f + 0.35f * p)
                        val pulseAlpha = if (isReduced) 0.4f else (sin(p * PI.toFloat()) * (1f - 0.25f * p)).coerceIn(0f, 1f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    AsterViolet.copy(alpha = 0.6f * pulseAlpha),
                                    Color(0xFF38B2AC).copy(alpha = 0.45f * pulseAlpha),
                                    AsterViolet.copy(alpha = 0.2f * pulseAlpha),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = currentRadius
                            ),
                            radius = currentRadius,
                            center = center
                        )
                    }
                }
                "ripple" -> {
                    // Subtle celestial gravitational wave rippling outwards from bubble edges
                    Canvas(
                        modifier = Modifier.matchParentSize()
                    ) {
                        val pNorm = p.coerceIn(0f, 1f)
                        val waveAlpha = if (isReduced) 0.35f else (1f - pNorm)
                        val expandPx = 28.dp.toPx() * pNorm
                        val expandPx2 = (28.dp.toPx() * (pNorm - 0.25f)).coerceAtLeast(0f)

                        drawRoundRect(
                            color = AsterViolet.copy(alpha = waveAlpha * 0.65f),
                            topLeft = Offset(-expandPx, -expandPx),
                            size = androidx.compose.ui.geometry.Size(size.width + expandPx * 2, size.height + expandPx * 2),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx() + expandPx),
                            style = Stroke(width = 2.dp.toPx())
                        )
                        if (expandPx2 > 0f) {
                            drawRoundRect(
                                color = AsterGlow.copy(alpha = waveAlpha * 0.45f),
                                topLeft = Offset(-expandPx2, -expandPx2),
                                size = androidx.compose.ui.geometry.Size(size.width + expandPx2 * 2, size.height + expandPx2 * 2),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx() + expandPx2),
                                style = Stroke(width = 1.2.dp.toPx())
                            )
                        }
                    }
                }
                "pulse" -> {
                    // Gentle breathing glow surrounding the bubble
                    Canvas(
                        modifier = Modifier.matchParentSize()
                    ) {
                        val glowAlpha = if (isReduced) 0.35f else (sin(p * PI.toFloat()) * 0.75f).coerceIn(0f, 1f)
                        val auraExpand = 16.dp.toPx() * sin(p * PI.toFloat()).coerceAtLeast(0f)
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    AsterViolet.copy(alpha = 0.5f * glowAlpha),
                                    AsterGlow.copy(alpha = 0.25f * glowAlpha),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = size.maxDimension * 0.8f
                            ),
                            topLeft = Offset(-auraExpand, -auraExpand),
                            size = androidx.compose.ui.geometry.Size(size.width + auraExpand * 2, size.height + auraExpand * 2),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx() + auraExpand)
                        )
                    }
                }
            }
        }

        // Message bubble content with physical scale breathing for pulse
        Box(
            modifier = if (breathScale != 1.0f) {
                Modifier.graphicsLayer {
                    scaleX = breathScale
                    scaleY = breathScale
                }
            } else Modifier
        ) {
            content()
        }

        // Foreground celestial effects (stardust embers drifting upward, comet trail streaking across)
        if (currentEffect != null && p < 1f) {
            when (currentEffect) {
                "stardust" -> {
                    Canvas(
                        modifier = Modifier.matchParentSize()
                    ) {
                        val particleCount = 18
                        for (i in 0 until particleCount) {
                            val seed = i.toFloat() / particleCount
                            val xPos = size.width * (0.05f + 0.9f * ((seed * 7.13f + 0.3f) % 1f))
                            val initialY = size.height * (0.35f + 0.55f * ((seed * 3.47f) % 1f))
                            val driftDistance = 55.dp.toPx() * (0.6f + 0.6f * seed)
                            val currentY = initialY - p * driftDistance
                            val particleAlpha = (sin((p * 0.8f + seed * 0.2f).coerceIn(0f, 1f) * PI.toFloat()) * (1f - 0.35f * p)).coerceIn(0f, 1f)
                            val color = when (i % 3) {
                                0 -> AsterStar
                                1 -> AsterViolet
                                else -> AsterWarm
                            }

                            drawCircle(
                                color = color.copy(alpha = if (isReduced) 0.35f else particleAlpha * 0.9f),
                                radius = (1.5f + 1.2f * seed).dp.toPx(),
                                center = Offset(xPos, currentY)
                            )
                        }
                    }
                }
                "comet" -> {
                    Canvas(
                        modifier = Modifier.matchParentSize()
                    ) {
                        val travel = size.width * (p * 1.25f)
                        val trailLength = 80.dp.toPx()
                        val startX = (travel - trailLength).coerceAtLeast(0f)
                        val endX = travel.coerceAtMost(size.width)
                        val y = 6.dp.toPx()

                        if (endX > startX) {
                            drawLine(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, AsterViolet.copy(alpha = 0.85f), AsterStar),
                                    startX = startX,
                                    endX = endX
                                ),
                                start = Offset(startX, y),
                                end = Offset(endX, y),
                                strokeWidth = 2.5.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawCircle(
                                color = AsterStar.copy(alpha = if (isReduced) 0.35f else (1f - 0.25f * p)),
                                radius = 3.dp.toPx(),
                                center = Offset(endX, y)
                            )
                            drawCircle(
                                color = AsterWarm.copy(alpha = (1f - p) * 0.7f),
                                radius = 1.5.dp.toPx(),
                                center = Offset(startX + (endX - startX) * 0.5f, y + 2.dp.toPx())
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Expressive typography component supporting intentional emphasis treatments:
 * - Shimmer: *phrase* or ~phrase~
 * - Soft emphasis pulse: !phrase! or **phrase**
 * - Subtle alert: !!phrase!! or !urgent!
 */
@Composable
fun AsterEmphasizedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AsterStar,
    fontSize: TextUnit = 15.sp,
    lineHeight: TextUnit = 21.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    fontStyle: FontStyle? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val isReduced = LocalReducedMotion.current

    // Parse emphasis markers
    val hasEmphasis = text.contains("*") || text.contains("~") || text.contains("!") || text.contains("✨")

    if (!hasEmphasis) {
        Text(
            text = text,
            modifier = modifier,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = color,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontWeight = fontWeight,
                fontStyle = fontStyle
            ),
            maxLines = maxLines,
            overflow = overflow
        )
        return
    }

    // Build styled AnnotatedString
    val annotatedString = remember(text, color, isReduced) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                when {
                    // Urgent Alert: !!phrase!!
                    text.startsWith("!!", i) -> {
                        val end = text.indexOf("!!", i + 2)
                        if (end != -1) {
                            val alertText = text.substring(i + 2, end)
                            withStyle(
                                SpanStyle(
                                    color = AsterCrimson,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append(alertText)
                            }
                            i = end + 2
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Bold / Strong Pulse: **phrase**
                    text.startsWith("**", i) -> {
                        val end = text.indexOf("**", i + 2)
                        if (end != -1) {
                            val boldText = text.substring(i + 2, end)
                            withStyle(
                                SpanStyle(
                                    color = AsterStar,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append(boldText)
                            }
                            i = end + 2
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Shimmer: *phrase* or ~phrase~
                    text[i] == '*' || text[i] == '~' -> {
                        val marker = text[i]
                        val end = text.indexOf(marker, i + 1)
                        if (end != -1) {
                            val shimmerText = text.substring(i + 1, end)
                            withStyle(
                                SpanStyle(
                                    color = if (isReduced) AsterWarm else AsterGlow,
                                    fontWeight = FontWeight.SemiBold
                                )
                            ) {
                                append(shimmerText)
                            }
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Soft Pulse: !phrase!
                    text[i] == '!' -> {
                        val end = text.indexOf('!', i + 1)
                        if (end != -1) {
                            val pulseText = text.substring(i + 1, end)
                            withStyle(
                                SpanStyle(
                                    color = AsterWarm,
                                    fontWeight = FontWeight.Medium
                                )
                            ) {
                                append(pulseText)
                            }
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    else -> {
                        append(text[i])
                        i++
                    }
                }
            }
        }
    }

    Text(
        text = annotatedString,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge.copy(
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            fontStyle = fontStyle
        ),
        maxLines = maxLines,
        overflow = overflow
    )
}

/**
 * Compact celestial effect selector tray for the message composer or context actions.
 */
@Composable
fun AsterCelestialEffectPicker(
    selectedEffect: String?,
    onSelectEffect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val isReduced = LocalReducedMotion.current

    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Option to clear effect
        val isNoneSelected = selectedEffect.isNullOrBlank()
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (isNoneSelected) AsterViolet.copy(alpha = 0.2f) else AsterElevated)
                .border(
                    width = 0.75.dp,
                    color = if (isNoneSelected) AsterViolet else AsterBorder,
                    shape = RoundedCornerShape(12.dp)
                )
                .asterPressable(
                    pressedScale = if (isReduced) 1f else 0.95f,
                    hapticEvent = AsterHapticEvent.Selection,
                    onClick = { onSelectEffect(null) }
                )
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = "Standard",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (isNoneSelected) AsterStar else AsterMuted,
                    fontSize = 11.sp,
                    fontWeight = if (isNoneSelected) FontWeight.Bold else FontWeight.Normal
                )
            )
        }

        AsterReactionTokens.CelestialEffects.forEach { effect ->
            val isSelected = selectedEffect?.equals(effect, ignoreCase = true) == true
            val icon = AsterReactionTokens.effectIcon(effect)
            val name = AsterReactionTokens.effectDisplayName(effect)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) AsterViolet.copy(alpha = 0.28f) else AsterElevated)
                    .border(
                        width = 0.75.dp,
                        color = if (isSelected) AsterViolet else AsterBorder,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .asterPressable(
                        pressedScale = if (isReduced) 1f else 0.95f,
                        hapticEvent = AsterHapticEvent.Selection,
                        onClick = { onSelectEffect(effect) }
                    )
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = icon, fontSize = 12.sp)
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (isSelected) AsterStar else AsterMuted,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }
        }
    }
}
