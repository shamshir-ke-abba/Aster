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
import com.aster.app.domain.model.sanitizeDisplayName
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.aster.app.ui.motion.asterDialogTransition
import com.aster.app.ui.motion.asterPressable
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.User
import com.aster.app.ui.components.AsterAvatar
import com.aster.app.ui.components.AsterBottomNavigation
import com.aster.app.ui.components.AsterEmptyState
import com.aster.app.ui.components.AsterNavDestination
import com.aster.app.ui.components.AsterSearchField
import com.aster.app.ui.components.ConstellationBackground
import com.aster.app.ui.components.ConstellationChatView
import com.aster.app.ui.theme.AsterBorder
import com.aster.app.ui.theme.AsterCrimson
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterMuted
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterOnline
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterSurfaceElevated
import com.aster.app.ui.theme.AsterViolet
import com.aster.app.ui.theme.AsterWarm
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
    onNavigateToSettings: () -> Unit = {},
    onConversationClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    HomeScreenContent(
        uiState = uiState,
        onConversationClick = onConversationClick,
        onNavigate = onNavigate,
        onNavigateToSettings = onNavigateToSettings,
        onNewChatClick = viewModel::openNewChat,
        onCloseNewChat = viewModel::closeNewChat,
        onSelectUser = { userId ->
            viewModel.startConversation(userId, onConversationClick)
        },
        onCreateGroup = { name, memberIds ->
            viewModel.createGroup(name, memberIds, onConversationClick)
        },
        onSaveNodePosition = viewModel::saveNodePosition,
        onToggleHighlight = viewModel::toggleHighlight,
        onLeaveGroup = viewModel::leaveGroup,
        onBlockUser = viewModel::blockUser,
        onUnblockUser = viewModel::unblockUser,
        onRemoveContact = viewModel::removeContact,
        onAcceptPendingRequest = { reqId ->
            viewModel.respondToFriendRequest(reqId, true)
        },
        onDeclinePendingRequest = { reqId ->
            viewModel.respondToFriendRequest(reqId, false)
        },
        modifier = modifier
    )
}

@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    onConversationClick: (String) -> Unit,
    onNavigate: (AsterNavDestination) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNewChatClick: () -> Unit = {},
    onCloseNewChat: () -> Unit = {},
    onSelectUser: (String) -> Unit = {},
    onCreateGroup: (String, List<String>) -> Unit = { _, _ -> },
    onSaveNodePosition: (String, Float?, Float?) -> Unit = { _, _, _ -> },
    onToggleHighlight: (String, Boolean) -> Unit = { _, _ -> },
    onLeaveGroup: (String) -> Unit = {},
    onBlockUser: (String) -> Unit = {},
    onUnblockUser: (String) -> Unit = {},
    onRemoveContact: (String) -> Unit = {},
    onAcceptPendingRequest: (String) -> Unit = {},
    onDeclinePendingRequest: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
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
                onNavigateToSettings = onNavigateToSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            )

            if (uiState.connectionState == com.aster.app.data.network.ConnectionState.DISCONNECTED) {
                OfflineTipCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }

            // Title Bar (Chat mode is managed exclusively in Settings)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (uiState.viewMode == "CONSTELLATION") "Constellation Map" else "Conversations",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterGlow.copy(alpha = 0.8f),
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.semantics { heading() }
                )
            }

            // Search Bar
            AsterSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Filter chats...",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp)
            )

            // Filter Chips: All | Direct | Groups
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
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
                            .background(if (isSelected) AsterSurfaceElevated else AsterSurface)
                            .border(0.75.dp, if (isSelected) AsterViolet else AsterBorder, RoundedCornerShape(8.dp))
                            .clickable { activeFilter = filter }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) AsterViolet else AsterMuted,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                thickness = 0.75.dp,
                color = AsterBorder
            )

            // Main Chat Area: Constellation Canvas vs List
            if (filteredConversations.isEmpty() && uiState.incomingFriendRequests.isEmpty() && !uiState.isLoading) {
                if (searchQuery.isNotBlank()) {
                    AsterEmptyState(
                        title = "No chats found",
                        description = "No conversations match \"$searchQuery\"",
                        icon = "🔍",
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                } else if (activeFilter == ChatFilter.GROUPS) {
                    AsterEmptyState(
                        title = "No group chats",
                        description = "Create a group constellation with the + button above.",
                        icon = "✦",
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                } else if (activeFilter == ChatFilter.DIRECT) {
                    AsterEmptyState(
                        title = "No direct chats",
                        description = "Direct conversations with your contacts will appear here.",
                        icon = "✦",
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                } else {
                    EmptyConstellationState(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                }
            } else if (uiState.viewMode == "CONSTELLATION") {
                ConstellationChatView(
                    conversations = filteredConversations,
                    currentUserId = uiState.localUser?.id.orEmpty(),
                    pendingRequests = uiState.incomingFriendRequests,
                    onAcceptPendingRequest = onAcceptPendingRequest,
                    onDeclinePendingRequest = onDeclinePendingRequest,
                    onConversationClick = onConversationClick,
                    onSaveNodePosition = onSaveNodePosition,
                    onToggleHighlight = onToggleHighlight,
                    onLeaveGroup = onLeaveGroup,
                    onBlockUser = onBlockUser,
                    onUnblockUser = onUnblockUser,
                    onRemoveContact = onRemoveContact,
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
                    if (uiState.incomingFriendRequests.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AsterWarm.copy(alpha = 0.12f))
                                    .border(0.75.dp, AsterWarm.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .clickable { onNavigate(AsterNavDestination.CONNECTIONS) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = "✧", color = AsterWarm, fontSize = 16.sp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "${uiState.incomingFriendRequests.size} pending connection request${if (uiState.incomingFriendRequests.size > 1) "s" else ""}",
                                        color = AsterStar,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "View ⚯",
                                        color = AsterWarm,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

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
                .asterDialogTransition()
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
private fun OfflineTipCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF241416))
            .border(1.dp, Color(0xFF4E2226), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF5252))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tip: You're offline",
                    color = Color(0xFFFF8A80),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Messages will be delivered automatically when internet connectivity returns.",
                    color = AsterGlow.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun HomeHeader(
    displayName: String,
    connectionState: com.aster.app.data.network.ConnectionState = com.aster.app.data.network.ConnectionState.CONNECTED,
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val greeting = rememberTimeGreeting()

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Personal greeting: "Good afternoon, Saad" or "Good afternoon"
            Column {
                val cleanName = sanitizeDisplayName(displayName)
                val greetingText = if (!cleanName.isNullOrBlank()) "$greeting, $cleanName" else greeting
                Text(
                    text = greetingText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = AsterGlow,
                        fontWeight = FontWeight.Normal
                    )
                )

                // Clean status indicator
                val (dotColor, statusText) = when (connectionState) {
                    com.aster.app.data.network.ConnectionState.CONNECTED -> Pair(Color(0xFF4CAF50), "Connected")
                    com.aster.app.data.network.ConnectionState.CONNECTING,
                    com.aster.app.data.network.ConnectionState.RECONNECTING -> Pair(Color(0xFFFFB300), "Reconnecting…")
                    com.aster.app.data.network.ConnectionState.DISCONNECTED -> Pair(Color(0xFFFF5252), "Offline")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = when (connectionState) {
                                com.aster.app.data.network.ConnectionState.DISCONNECTED -> Color(0xFFFF8A80)
                                com.aster.app.data.network.ConnectionState.RECONNECTING,
                                com.aster.app.data.network.ConnectionState.CONNECTING -> Color(0xFFFFD54F)
                                else -> AsterGlow.copy(alpha = 0.6f)
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            // Permanent Settings access (replaces old Aster logo branding)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AsterSurface)
                    .border(0.75.dp, AsterBorder, RoundedCornerShape(12.dp))
                    .clickable(
                        onClick = onNavigateToSettings,
                        onClickLabel = "Open Settings"
                    )
                    .semantics { contentDescription = "Open Settings" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚙",
                    color = AsterStar,
                    fontSize = 20.sp
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
    val isBlocked = !conversation.isGroup && conversation.otherMember?.isBlocked == true
    val isDeleted = !conversation.isGroup && conversation.otherMember?.isDeleted == true
    val titleText = if (isDeleted) "Deleted User" else conversation.title
    val isOnline = !isDeleted && !isBlocked && (conversation.otherMember?.isOnline ?: false)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .asterPressable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Aster avatar with initials, online presence and unread badge
            AsterAvatar(
                name = titleText,
                size = 46.dp,
                isOnline = isOnline && !conversation.isGroup,
                unreadCount = 0 // unread badge shown on right
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Title and last message snippet
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = AsterStar,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (conversation.isGroup) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "✦✦",
                            color = AsterViolet,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                val lastMessageText = if (isDeleted) {
                    "This user no longer exists"
                } else if (isBlocked) {
                    "Blocked"
                } else if (conversation.lastMessage?.status == com.aster.app.domain.model.MessageStatus.UNSENT) {
                    "This message was unsent"
                } else {
                    conversation.lastMessage?.body ?: "No messages yet"
                }
                Text(
                    text = lastMessageText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (conversation.unreadCount > 0) AsterStar else AsterGlow.copy(alpha = 0.75f),
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Timestamp and violet unread pill badge
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

        HorizontalDivider(
            modifier = Modifier.padding(start = 80.dp, end = 20.dp),
            thickness = 0.5.dp,
            color = AsterBorder
        )
    }
}

@Composable
private fun EmptyConstellationState(
    modifier: Modifier = Modifier
) {
    AsterEmptyState(
        title = "Your constellation is quiet",
        description = "Conversations with your trusted circle will appear here.",
        icon = "✦",
        modifier = modifier
    )
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
