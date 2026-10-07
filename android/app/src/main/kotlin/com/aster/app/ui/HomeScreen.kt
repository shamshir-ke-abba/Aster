package com.aster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aster.app.domain.model.Conversation
import com.aster.app.ui.components.AsterLogo
import com.aster.app.ui.components.ConstellationBackground
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterViolet
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Aster Home Screen — conversation list & constellation overview.
 *
 * Implements canonical Aster design from /Aster/design/design.md.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    onConversationClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    HomeScreenContent(
        uiState = uiState,
        onConversationClick = onConversationClick,
        modifier = modifier
    )
}

@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    onConversationClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AsterNight)
    ) {
        // Atmospheric constellation background
        ConstellationBackground(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding())
        ) {
            // Header Area
            HomeHeader(
                displayName = uiState.localUser?.displayName ?: "Saad",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Section Header: "Your constellation"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Your constellation",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterGlow.copy(alpha = 0.8f),
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.semantics { heading() }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                thickness = 0.5.dp,
                color = AsterElevated
            )

            if (uiState.conversations.isEmpty() && !uiState.isLoading) {
                EmptyConstellationState(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = navBarPadding.calculateBottomPadding() + 16.dp
                    )
                ) {
                    items(
                        items = uiState.conversations,
                        key = { it.id }
                    ) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            onClick = { onConversationClick(conversation.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    displayName: String,
    modifier: Modifier = Modifier
) {
    val greeting = rememberTimeGreeting()

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Personal greeting per design spec: "Good evening, Saad"
            Text(
                text = "$greeting, $displayName",
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = AsterGlow,
                    fontWeight = FontWeight.Normal
                )
            )

            // Minimalist branding with small Aster logo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AsterLogo(size = 18.dp)
                Text(
                    text = "ASTER",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterStar,
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Light
                    )
                )
            }
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit
) {
    val isOnline = conversation.otherMember?.isOnline ?: false

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Presence indicator: ✦ for online, · for offline
        Box(
            modifier = Modifier
                .size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isOnline) {
                Text(
                    text = "✦",
                    color = AsterViolet,
                    fontSize = 16.sp
                )
            } else {
                Text(
                    text = "·",
                    color = AsterGlow.copy(alpha = 0.5f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and snippet preview
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = AsterStar,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            val lastMessageText = conversation.lastMessage?.body ?: "No messages yet"
            Text(
                text = lastMessageText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = if (conversation.unreadCount > 0) AsterStar else AsterGlow.copy(alpha = 0.7f),
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.Medium else FontWeight.Normal
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Timestamp and unread count
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center
        ) {
            val timestampText = conversation.lastMessage?.createdAt?.let { formatMessageTime(it) } ?: ""
            if (timestampText.isNotEmpty()) {
                Text(
                    text = timestampText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterGlow.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                )
            }

            if (conversation.unreadCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AsterViolet)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = conversation.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = AsterNight,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyConstellationState(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AsterLogo(size = 48.dp)
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Your constellation is quiet",
            style = MaterialTheme.typography.bodyLarge.copy(
                color = AsterStar,
                fontWeight = FontWeight.Normal
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Conversations with your trusted circle will appear here.",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = AsterGlow.copy(alpha = 0.6f)
            )
        )
    }
}

@Composable
private fun rememberTimeGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..22 -> "Good evening"
        else -> "Still awake?"
    }
}

private fun formatMessageTime(epochMillis: Long): String {
    val now = Calendar.getInstance()
    val msgTime = Calendar.getInstance().apply { timeInMillis = epochMillis }

    return if (now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == msgTime.get(Calendar.DAY_OF_YEAR)
    ) {
        val format = SimpleDateFormat("h:mm a", Locale.getDefault())
        format.format(Date(epochMillis))
    } else {
        val format = SimpleDateFormat("MMM d", Locale.getDefault())
        format.format(Date(epochMillis))
    }
}
