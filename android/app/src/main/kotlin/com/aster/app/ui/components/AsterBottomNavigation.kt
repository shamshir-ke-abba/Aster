package com.aster.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterViolet

enum class AsterNavDestination(val title: String, val icon: String, val route: String) {
    CHATS("Constellations", "✦", "home"),
    CONNECTIONS("Connections", "⚯", "connections"),
    PROFILE("Profile", "⊚", "profile"),
    SETTINGS("Settings", "☼", "settings")
}

@Composable
fun AsterBottomNavigation(
    currentDestination: AsterNavDestination,
    onNavigate: (AsterNavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(AsterNight.copy(alpha = 0.95f))
            .border(width = 0.5.dp, color = AsterElevated, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(
                top = 8.dp,
                bottom = navBarPadding.calculateBottomPadding() + 4.dp,
                start = 16.dp,
                end = 16.dp
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsterNavDestination.values().forEach { destination ->
                val isSelected = destination == currentDestination

                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onNavigate(destination) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { contentDescription = "Navigate to ${destination.title}" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) AsterViolet.copy(alpha = 0.15f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = destination.icon,
                            color = if (isSelected) AsterViolet else AsterGlow.copy(alpha = 0.6f),
                            fontSize = 18.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = destination.title,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (isSelected) AsterStar else AsterGlow.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    )
                }
            }
        }
    }
}
