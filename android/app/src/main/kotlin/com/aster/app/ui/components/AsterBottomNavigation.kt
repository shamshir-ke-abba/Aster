package com.aster.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aster.app.ui.motion.AsterHapticEvent
import com.aster.app.ui.motion.AsterMotion
import com.aster.app.ui.motion.AsterMotionTokens
import com.aster.app.ui.motion.LocalAsterHaptics
import com.aster.app.ui.motion.LocalReducedMotion
import com.aster.app.ui.theme.AsterBorder
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterMuted
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterViolet

enum class AsterNavDestination(val title: String, val icon: String, val route: String) {
    CHATS("Constellations", "✦", "home"),
    CONNECTIONS("Connections", "⚯", "connections"),
    PROFILE("Profile", "⊚", "profile"),
    SETTINGS("Settings", "☼", "settings");

    companion object {
        val primaryTabs = listOf(CHATS, CONNECTIONS, PROFILE)
    }
}

/**
 * Aster Bottom Navigation Dock matching /Aster/design/ui-redesign/aster_product_ui_reference.png.
 */
@Composable
fun AsterBottomNavigation(
    currentDestination: AsterNavDestination,
    onNavigate: (AsterNavDestination) -> Unit,
    modifier: Modifier = Modifier,
    destinations: List<AsterNavDestination> = AsterNavDestination.primaryTabs
) {
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val haptics = LocalAsterHaptics.current
    val isReduced = LocalReducedMotion.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(AsterSurface.copy(alpha = 0.98f))
            .border(width = 0.75.dp, color = AsterBorder, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(
                top = 10.dp,
                bottom = navBarPadding.calculateBottomPadding() + 6.dp,
                start = 16.dp,
                end = 16.dp
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            destinations.forEach { destination ->
                val isSelected = destination == currentDestination ||
                    (destination == AsterNavDestination.PROFILE && currentDestination == AsterNavDestination.SETTINGS)

                val iconBgColor by animateColorAsState(
                    targetValue = if (isSelected) AsterViolet.copy(alpha = 0.16f) else Color.Transparent,
                    animationSpec = AsterMotion.tween(AsterMotionTokens.Duration.Micro, reducedMotion = isReduced),
                    label = "NavIconBg"
                )
                val iconColor by animateColorAsState(
                    targetValue = if (isSelected) AsterViolet else AsterMuted,
                    animationSpec = AsterMotion.tween(AsterMotionTokens.Duration.Micro, reducedMotion = isReduced),
                    label = "NavIconColor"
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) AsterStar else AsterMuted,
                    animationSpec = AsterMotion.tween(AsterMotionTokens.Duration.Micro, reducedMotion = isReduced),
                    label = "NavTextColor"
                )

                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            onClick = {
                                if (destination != currentDestination) {
                                    haptics.perform(AsterHapticEvent.Selection)
                                    onNavigate(destination)
                                }
                            },
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        )
                        .padding(horizontal = 18.dp, vertical = 6.dp)
                        .semantics { contentDescription = "Navigate to ${destination.title}" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(iconBgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = destination.icon,
                            color = iconColor,
                            fontSize = 18.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = destination.title,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = textColor,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    )
                }
            }
        }
    }
}
