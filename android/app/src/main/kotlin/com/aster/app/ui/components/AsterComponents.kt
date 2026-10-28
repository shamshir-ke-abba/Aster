package com.aster.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aster.app.ui.theme.AsterBorder
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterMuted
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterOnline
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterSurfaceElevated
import com.aster.app.ui.theme.AsterViolet

/**
 * Reusable Aster Avatar with optional online presence indicator and unread badge.
 * Matches /Aster/design/ui-redesign/aster_product_ui_reference.png.
 */
@Composable
fun AsterAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    isOnline: Boolean = false,
    unreadCount: Int = 0,
    backgroundColor: Color = AsterSurfaceElevated
) {
    val initials = name.trim().split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercase() }
        .joinToString("")
        .ifEmpty { "✦" }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Main circular avatar
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(backgroundColor)
                .border(0.75.dp, AsterBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = AsterStar,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }

        // Online presence green dot (bottom-right)
        if (isOnline) {
            val dotSize = (size.value * 0.26f).coerceIn(9f, 13f).dp
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(AsterOnline)
                    .border(1.5.dp, AsterNight, CircleShape)
            )
        }

        // Unread badge (top-right)
        if (unreadCount > 0) {
            val badgeSize = (size.value * 0.38f).coerceAtLeast(16f).dp
            Box(
                modifier = Modifier
                    .size(badgeSize)
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(AsterViolet)
                    .border(1.5.dp, AsterNight, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                    color = AsterNight,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Primary action button matching Aster Design System.
 */
@Composable
fun AsterButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isSecondary: Boolean = false
) {
    if (isSecondary) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(42.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = AsterSurface,
                contentColor = AsterViolet
            ),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                width = 1.dp,
                brush = SolidColor(AsterViolet)
            )
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(42.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AsterViolet,
                contentColor = AsterNight,
                disabledContainerColor = AsterElevated,
                disabledContentColor = AsterMuted
            )
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Reusable Card container with consistent 16dp radius and subtle border.
 */
@Composable
fun AsterCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    backgroundColor: Color = AsterSurface,
    borderColor: Color = AsterBorder,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val cardModifier = if (onClick != null) {
        modifier
            .clip(shape)
            .background(backgroundColor)
            .border(0.75.dp, borderColor, shape)
            .clickable(onClick = onClick)
    } else {
        modifier
            .clip(shape)
            .background(backgroundColor)
            .border(0.75.dp, borderColor, shape)
    }

    Box(modifier = cardModifier) {
        content()
    }
}

/**
 * Clean search field matching Aster Design Reference.
 */
@Composable
fun AsterSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search..."
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AsterElevated)
            .border(0.75.dp, AsterBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = AsterStar, fontSize = 14.sp),
            cursorBrush = SolidColor(AsterViolet),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🔍",
                        fontSize = 13.sp,
                        color = AsterMuted
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium.copy(color = AsterMuted)
                            )
                        }
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(AsterSurfaceElevated)
                                .clickable { onQueryChange("") },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✕",
                                color = AsterStar,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        )
    }
}

/**
 * Reusable atmospheric empty state.
 */
@Composable
fun AsterEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: String = "✦",
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(AsterSurfaceElevated)
                .border(1.dp, AsterBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = icon,
                color = AsterViolet,
                fontSize = 24.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(color = AsterStar),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall.copy(color = AsterMuted),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (actionLabel != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(20.dp))
            AsterButton(
                text = actionLabel,
                onClick = onActionClick
            )
        }
    }
}
