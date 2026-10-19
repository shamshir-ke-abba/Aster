package com.aster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.User
import com.aster.app.ui.components.AsterBottomNavigation
import com.aster.app.ui.components.AsterLogo
import com.aster.app.ui.components.AsterNavDestination
import com.aster.app.ui.components.ConstellationBackground
import com.aster.app.ui.components.ConstellationChatView
import com.aster.app.ui.theme.AsterCrimson
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

enum class ChatFilter {
    ALL,
    DIRECT,
    GROUPS
}

/**
 * Aster Home Screen — conversation list & constellation overview.
 *
 * Implements canonical Aster design from /Aster/design/design.md.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigate: (AsterNavDestination) -> Unit = {},
    onConversationClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    HomeScreenContent(
        uiState = uiState,
        onConversationClick = onConversationClick,
        onNavigate = onNavigate,
        onNewChatClick = viewModel::openNewChat,
        onCloseNewChat = viewModel::closeNewChat,
        onSelectUser = { userId ->
            viewModel.startConversation(userId, onConversationClick)
        },
        onCreateGroup = { name, memberIds ->
            viewModel.createGroup(name, memberIds, onConversationClick)
        },
        modifier = modifier
    )
}

@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    onConversationClick: (String) -> Unit,
    onNavigate: (AsterNavDestination) -> Unit = {},
    onNewChatClick: () -> Unit = {},
    onCloseNewChat: () -> Unit = {},
    onSelectUser: (String) -> Unit = {},
    onCreateGroup: (String, List<String>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    var viewMode by remember { mutableStateOf("CONSTELLATION") }
    var activeFilter by remember { mutableStateOf(ChatFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredConversations = remember(uiState.conversations, activeFilter, searchQuery) {
        uiState.conversations.filter { conv ->
            val matchesFilter = when (activeFilter) {
                ChatFilter.ALL -> true
                ChatFilter.DIRECT -> !conv.isGroup
                ChatFilter.GROUPS -> conv.isGroup
            }
            val matchesSearch = if (searchQuery.isBlank()) true else {
                conv.title.contains(searchQuery, ignoreCase = true) ||
                conv.lastMessage?.body?.contains(searchQuery, ignoreCase = true) == true
            }
            matchesFilter && matchesSearch
        }
    }

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
                displayName = uiState.localUser?.displayName.orEmpty(),
                connectionState = uiState.connectionState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            )

            // View Mode Toggle & Title Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (viewMode == "CONSTELLATION") "Constellation Map" else "Conversations",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterGlow.copy(alpha = 0.8f),
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.semantics { heading() }
                )

                // View Mode Pill Toggle
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AsterElevated)
                        .padding(2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (viewMode == "CONSTELLATION") AsterViolet else Color.Transparent)
                            .clickable { viewMode = "CONSTELLATION" }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "✦ Map",
                            color = if (viewMode == "CONSTELLATION") AsterNight else AsterGlow.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (viewMode == "LIST") AsterViolet else Color.Transparent)
                            .clickable { viewMode = "LIST" }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "☰ List",
                            color = if (viewMode == "LIST") AsterNight else AsterGlow.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Search Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AsterSurface)
                    .border(0.5.dp, AsterElevated, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = AsterStar, fontSize = 13.sp),
                    cursorBrush = SolidColor(AsterViolet),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "🔍 ", fontSize = 12.sp)
                            Box(modifier = Modifier.weight(1f)) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Filter chats...",
                                        style = MaterialTheme.typography.bodySmall.copy(color = AsterGlow.copy(alpha = 0.4f))
                                    )
                                }
                                innerTextField()
                            }
                            if (searchQuery.isNotEmpty()) {
                                Text(
                                    text = "✕",
                                    color = AsterGlow.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable { searchQuery = "" }
                                )
                            }
                        }
                    }
                )
            }

            // Filter Chips: All | Direct | Groups
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChatFilter.values().forEach { filter ->
                    val isSelected = filter == activeFilter
                    val count = when (filter) {
                        ChatFilter.ALL -> uiState.conversations.size
                        ChatFilter.DIRECT -> uiState.conversations.count { !it.isGroup }
                        ChatFilter.GROUPS -> uiState.conversations.count { it.isGroup }
                    }
                    val title = when (filter) {
                        ChatFilter.ALL -> "All ($count)"
                        ChatFilter.DIRECT -> "Direct ($count)"
                        ChatFilter.GROUPS -> "Groups ($count)"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) AsterElevated else Color.Transparent)
                            .border(0.5.dp, if (isSelected) AsterViolet.copy(alpha = 0.6f) else AsterElevated, RoundedCornerShape(8.dp))
                            .clickable { activeFilter = filter }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) AsterViolet else AsterGlow.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                thickness = 0.5.dp,
                color = AsterElevated
            )

            // Main Chat Area: Constellation Canvas vs List
            if (filteredConversations.isEmpty() && !uiState.isLoading) {
                EmptyConstellationState(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                )
            } else if (viewMode == "CONSTELLATION") {
                ConstellationChatView(
                    conversations = filteredConversations,
                    currentUserId = uiState.localUser?.id.orEmpty(),
                    onConversationClick = onConversationClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                )
            } else {
                val listState = rememberLazyListState()
                val topConvId = filteredConversations.firstOrNull()?.id
                val topMsgId = filteredConversations.firstOrNull()?.lastMessage?.id
                LaunchedEffect(topConvId, topMsgId) {
                    if (filteredConversations.isNotEmpty()) {
                        listState.scrollToItem(0)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = navBarPadding.calculateBottomPadding() + 84.dp
                    )
                ) {
                    items(
                        items = filteredConversations,
                        key = { it.id }
                    ) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            onClick = { onConversationClick(conversation.id) }
                        )
                    }
                }
            }

            // Bottom Navigation
            AsterBottomNavigation(
                currentDestination = AsterNavDestination.CHATS,
                onNavigate = onNavigate
            )
        }

        // Restrained celestial New Chat action button (positioned above bottom nav)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 24.dp,
                    bottom = navBarPadding.calculateBottomPadding() + 68.dp
                )
                .size(54.dp)
                .clip(CircleShape)
                .background(AsterViolet)
                .clickable(onClick = onNewChatClick)
                .semantics { contentDescription = "Start new conversation" },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✦",
                color = AsterNight,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Aster-native user selection dialog
        if (uiState.isNewChatOpen) {
            NewChatDialog(
                availableUsers = uiState.availableUsers,
                errorMessage = uiState.errorMessage,
                onDismiss = onCloseNewChat,
                onSelectUser = onSelectUser,
                onCreateGroup = onCreateGroup
            )
        }
    }
}

@Composable
private fun NewChatDialog(
    availableUsers: List<User>,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSelectUser: (String) -> Unit,
    onCreateGroup: (String, List<String>) -> Unit = { _, _ -> }
) {
    var isGroupMode by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var selectedMemberIds by remember { mutableStateOf(setOf<String>()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(20.dp))
                .background(AsterSurface)
                .border(0.5.dp, AsterElevated, RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Dialog Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isGroupMode) "New Constellation" else "New Connection",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = AsterStar,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isGroupMode) "Gather a circle of friends" else "Choose a contact from your circle",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = AsterGlow.copy(alpha = 0.6f)
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss)
                            .semantics { contentDescription = "Close dialog" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            color = AsterGlow.copy(alpha = 0.7f),
                            fontSize = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Mode Selector Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AsterElevated)
                        .padding(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isGroupMode) AsterViolet else Color.Transparent)
                            .clickable { isGroupMode = false }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Direct (1:1)",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (!isGroupMode) AsterNight else AsterGlow,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isGroupMode) AsterViolet else Color.Transparent)
                            .clickable { isGroupMode = true }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Group ✦✦",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (isGroupMode) AsterNight else AsterGlow,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.error
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isGroupMode) {
                    // Group name input
                    BasicTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = AsterStar),
                        cursorBrush = SolidColor(AsterViolet),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AsterNight)
                                    .border(0.5.dp, AsterElevated, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                if (groupName.isEmpty()) {
                                    Text(
                                        text = "Group name...",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = AsterGlow.copy(alpha = 0.4f)
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Select members (${selectedMemberIds.size} selected):",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = AsterGlow.copy(alpha = 0.7f)
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = AsterElevated
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (availableUsers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No contacts found in your circle",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = AsterGlow.copy(alpha = 0.5f)
                            )
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (isGroupMode) 180.dp else 300.dp)
                    ) {
                        items(
                            items = availableUsers,
                            key = { it.id }
                        ) { user ->
                            val isSelected = selectedMemberIds.contains(user.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isGroupMode && isSelected) AsterElevated.copy(alpha = 0.4f) else Color.Transparent)
                                    .clickable {
                                        if (isGroupMode) {
                                            selectedMemberIds = if (isSelected) {
                                                selectedMemberIds - user.id
                                            } else {
                                                selectedMemberIds + user.id
                                            }
                                        } else {
                                            onSelectUser(user.id)
                                        }
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isGroupMode) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) AsterViolet else AsterElevated),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Text(
                                                text = "✓",
                                                color = AsterNight,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                } else {
                                    // Presence icon: ✦ for online, · for offline
                                    Box(
                                        modifier = Modifier.size(24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (user.isOnline) {
                                            Text(
                                                text = "✦",
                                                color = AsterViolet,
                                                fontSize = 15.sp
                                            )
                                        } else {
                                            Text(
                                                text = "·",
                                                color = AsterGlow.copy(alpha = 0.4f),
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = user.displayName,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = AsterStar,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                    Text(
                                        text = "@${user.username}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = AsterGlow.copy(alpha = 0.5f)
                                        )
                                    )
                                }

                                if (!isGroupMode) {
                                    Text(
                                        text = "→",
                                        color = AsterGlow.copy(alpha = 0.4f),
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }

                if (isGroupMode) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (groupName.isNotBlank() && selectedMemberIds.isNotEmpty()) AsterViolet
                                else AsterElevated
                            )
                            .clickable(enabled = groupName.isNotBlank() && selectedMemberIds.isNotEmpty()) {
                                onCreateGroup(groupName, selectedMemberIds.toList())
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Create Constellation",
                            color = if (groupName.isNotBlank() && selectedMemberIds.isNotEmpty()) AsterNight
                            else AsterGlow.copy(alpha = 0.4f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
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
    connectionState: com.aster.app.data.network.ConnectionState = com.aster.app.data.network.ConnectionState.CONNECTED,
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
            Column {
                val greetingText = if (displayName.isNotBlank()) "$greeting, $displayName" else greeting
                Text(
                    text = greetingText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = AsterGlow,
                        fontWeight = FontWeight.Normal
                    )
                )
                if (connectionState != com.aster.app.data.network.ConnectionState.CONNECTED) {
                    val statusText = when (connectionState) {
                        com.aster.app.data.network.ConnectionState.CONNECTING -> "connecting..."
                        com.aster.app.data.network.ConnectionState.RECONNECTING -> "reconnecting..."
                        com.aster.app.data.network.ConnectionState.DISCONNECTED -> "offline"
                        else -> ""
                    }
                    if (statusText.isNotEmpty()) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = AsterGlow.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }

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
        // Presence indicator: ✦✦ for group, ✦ for online direct, · for offline direct
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (conversation.isGroup) {
                Text(
                    text = "✦✦",
                    color = AsterViolet,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            } else if (isOnline) {
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
                        .background(AsterCrimson)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = conversation.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = AsterStar,
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
