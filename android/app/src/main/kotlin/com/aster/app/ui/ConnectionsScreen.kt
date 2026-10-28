package com.aster.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.aster.app.data.network.ConnectionState
import com.aster.app.data.network.UserSummary
import com.aster.app.ui.motion.asterDialogTransition
import com.aster.app.ui.components.AsterAvatar
import com.aster.app.ui.components.AsterBottomNavigation
import com.aster.app.ui.components.AsterEmptyState
import com.aster.app.ui.components.AsterNavDestination
import com.aster.app.ui.components.AsterSearchField
import com.aster.app.ui.components.ConstellationBackground
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

enum class ConnectionsTab {
    CONTACTS,
    REQUESTS,
    BLOCKED
}

@Composable
fun ConnectionsScreen(
    onNavigate: (AsterNavDestination) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onStartChat: (String) -> Unit = {},
    viewModel: ConnectionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(ConnectionsTab.CONTACTS) }
    var userToRemove by remember { mutableStateOf<UserSummary?>(null) }
    var selectedContactForContext by remember { mutableStateOf<UserSummary?>(null) }
    var selectedBlockedForContext by remember { mutableStateOf<UserSummary?>(null) }
    var profileUserToShow by remember { mutableStateOf<UserSummary?>(null) }
    var userToBlock by remember { mutableStateOf<UserSummary?>(null) }
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val context = androidx.compose.ui.platform.LocalContext.current

    androidx.compose.runtime.LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearFeedback()
        }
    }

    androidx.compose.runtime.LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { err ->
            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearFeedback()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AsterNight)
    ) {
        ConstellationBackground(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Connections",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = AsterStar,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.semantics { heading() }
                    )
                    // Clean connection status indicator
                    val (dotColor, statusText) = when (uiState.connectionState) {
                        ConnectionState.CONNECTED -> Pair(Color(0xFF4CAF50), "Connected")
                        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Pair(Color(0xFFFFB300), "Reconnecting…")
                        ConnectionState.DISCONNECTED -> Pair(Color(0xFFFF5252), "Offline")
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
                                color = when (uiState.connectionState) {
                                    ConnectionState.DISCONNECTED -> Color(0xFFFF8A80)
                                    ConnectionState.RECONNECTING, ConnectionState.CONNECTING -> Color(0xFFFFD54F)
                                    else -> AsterGlow.copy(alpha = 0.6f)
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = AsterViolet,
                            strokeWidth = 2.dp
                        )
                    }

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

            // Search Bar
            AsterSearchField(
                query = uiState.searchQuery,
                onQueryChange = viewModel::onSearchQueryChanged,
                placeholder = "Search by Aster ID (AST-...) or username",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Search Results overlay if searching
            if (uiState.searchQuery.isNotBlank()) {
                Text(
                    text = "SEARCH RESULTS (${uiState.searchResults.size})",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterGlow.copy(alpha = 0.6f),
                        letterSpacing = 1.sp
                    ),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )

                if (uiState.searchResults.isEmpty() && !uiState.isSearching) {
                    AsterEmptyState(
                        title = "No users found",
                        description = "No contacts or users match \"${uiState.searchQuery}\"",
                        icon = "🔍",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 24.dp)
                    ) {
                        items(uiState.searchResults, key = { it.userId }) { user ->
                            UserSearchRow(
                                user = user,
                                isFriend = uiState.friends.any { it.userId == user.userId },
                                isOutgoing = uiState.outgoingRequests.any { it.recipientUserId == user.userId },
                                isBlocked = uiState.blockedUsers.any { it.userId == user.userId },
                                onAddFriend = { viewModel.sendFriendRequest(targetUserId = user.userId) },
                                onChat = { viewModel.startChat(user, onStartChat) },
                                onUnblock = { viewModel.unblockUser(user.userId) }
                            )
                        }
                    }
                }
            } else {
                // Segmented Tabs: CONTACTS | REQUESTS | BLOCKED
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AsterSurface)
                        .border(0.75.dp, AsterBorder, RoundedCornerShape(12.dp))
                        .padding(4.dp)
                ) {
                    ConnectionsTab.values().forEach { tab ->
                        val isSelected = tab == selectedTab
                        val count = when (tab) {
                            ConnectionsTab.CONTACTS -> uiState.friends.size
                            ConnectionsTab.REQUESTS -> uiState.incomingRequests.size + uiState.outgoingRequests.size
                            ConnectionsTab.BLOCKED -> uiState.blockedUsers.size
                        }
                        val title = when (tab) {
                            ConnectionsTab.CONTACTS -> "Contacts ($count)"
                            ConnectionsTab.REQUESTS -> "Requests ($count)"
                            ConnectionsTab.BLOCKED -> "Blocked ($count)"
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) AsterSurfaceElevated else Color.Transparent)
                                .clickable { selectedTab = tab }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (isSelected) AsterViolet else AsterMuted,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Tab Content
                when (selectedTab) {
                    ConnectionsTab.CONTACTS -> {
                        if (uiState.friends.isEmpty()) {
                            AsterEmptyState(
                                title = "Your circle is empty",
                                description = "Search users by Aster ID or username above to connect.",
                                icon = "✦",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 24.dp)
                            ) {
                                items(uiState.friends, key = { it.userId }) { friend ->
                                    FriendRow(
                                        user = friend,
                                        onMessage = { viewModel.startChat(friend, onStartChat) },
                                        onLongClick = { selectedContactForContext = friend },
                                        onRemove = { userToRemove = friend },
                                        onBlock = { userToBlock = friend }
                                    )
                                }
                            }
                        }
                    }

                    ConnectionsTab.REQUESTS -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 24.dp)
                        ) {
                            if (uiState.incomingRequests.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "INCOMING REQUESTS",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = AsterViolet,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                                items(uiState.incomingRequests, key = { it.id }) { req ->
                                    IncomingRequestRow(
                                        request = req,
                                        onAccept = { viewModel.respondToRequest(req.id, true) },
                                        onDecline = { viewModel.respondToRequest(req.id, false) }
                                    )
                                }
                            }

                            if (uiState.outgoingRequests.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "PENDING OUTGOING",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = AsterMuted,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                                    )
                                }
                                items(uiState.outgoingRequests, key = { it.id }) { req ->
                                    OutgoingRequestRow(
                                        request = req,
                                        onCancel = { viewModel.cancelRequest(req.id) }
                                    )
                                }
                            }

                            if (uiState.incomingRequests.isEmpty() && uiState.outgoingRequests.isEmpty()) {
                                item {
                                    AsterEmptyState(
                                        title = "No pending requests",
                                        description = "Incoming and outgoing friend requests will appear here.",
                                        icon = "✉",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 40.dp)
                                    )
                                }
                            }
                        }
                    }

                    ConnectionsTab.BLOCKED -> {
                        if (uiState.blockedUsers.isEmpty()) {
                            AsterEmptyState(
                                title = "No blocked users",
                                description = "Users you have blocked will be listed here.",
                                icon = "🛡",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 24.dp)
                            ) {
                                items(uiState.blockedUsers, key = { it.userId }) { user ->
                                    BlockedUserRow(
                                        user = user,
                                        onLongClick = { selectedBlockedForContext = user },
                                        onUnblock = { viewModel.unblockUser(user.userId) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Navigation
            AsterBottomNavigation(
                currentDestination = AsterNavDestination.CONNECTIONS,
                onNavigate = onNavigate
            )
        }

        userToRemove?.let { friend ->
            AlertDialog(
                onDismissRequest = { userToRemove = null },
                title = { Text(text = "Remove Contact?", color = AsterStar) },
                text = {
                    Text(
                        text = "Are you sure you want to remove ${friend.displayName} (@${friend.username}) from your constellation?",
                        color = AsterGlow.copy(alpha = 0.8f)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.removeFriend(friend.userId)
                            userToRemove = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B), contentColor = AsterNight)
                    ) {
                        Text(text = "Remove", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { userToRemove = null },
                        colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar)
                    ) {
                        Text(text = "Cancel")
                    }
                },
                containerColor = AsterSurface
            )
        }

        userToBlock?.let { target ->
            AlertDialog(
                onDismissRequest = { userToBlock = null },
                title = { Text(text = "Block ${target.displayName}?", color = AsterStar) },
                text = {
                    Text(
                        text = "Are you sure you want to block ${target.displayName} (@${target.username})? They will no longer be able to message you or view your presence.",
                        color = AsterGlow.copy(alpha = 0.8f)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.blockUser(target.userId)
                            userToBlock = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B), contentColor = AsterNight)
                    ) {
                        Text(text = "Block", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { userToBlock = null },
                        colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar)
                    ) {
                        Text(text = "Cancel")
                    }
                },
                containerColor = AsterSurface
            )
        }

        selectedContactForContext?.let { contact ->
            ContactContextDialog(
                user = contact,
                onDismiss = { selectedContactForContext = null },
                onMessage = {
                    selectedContactForContext = null
                    viewModel.startChat(contact, onStartChat)
                },
                onViewProfile = {
                    profileUserToShow = contact
                    selectedContactForContext = null
                },
                onRemove = {
                    userToRemove = contact
                    selectedContactForContext = null
                },
                onBlock = {
                    userToBlock = contact
                    selectedContactForContext = null
                }
            )
        }

        selectedBlockedForContext?.let { blockedUser ->
            BlockedContextDialog(
                user = blockedUser,
                onDismiss = { selectedBlockedForContext = null },
                onViewProfile = {
                    profileUserToShow = blockedUser
                    selectedBlockedForContext = null
                },
                onUnblock = {
                    selectedBlockedForContext = null
                    viewModel.unblockUser(blockedUser.userId)
                }
            )
        }

        profileUserToShow?.let { user ->
            UserProfileDialog(
                user = user,
                isFriend = uiState.friends.any { it.userId == user.userId },
                isBlocked = uiState.blockedUsers.any { it.userId == user.userId },
                onDismiss = { profileUserToShow = null }
            )
        }
    }
}

@Composable
private fun UserSearchRow(
    user: UserSummary,
    isFriend: Boolean,
    isOutgoing: Boolean,
    isBlocked: Boolean = false,
    onAddFriend: () -> Unit,
    onChat: () -> Unit = {},
    onUnblock: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AsterSurface)
            .border(0.75.dp, AsterBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            AsterAvatar(name = user.displayName, size = 42.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = user.displayName, color = AsterStar, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(text = "@${user.username} • ${user.asterId}", color = AsterMuted, fontSize = 12.sp)
            }
        }

        when {
            isFriend -> {
                Button(
                    onClick = onChat,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AsterViolet, contentColor = AsterNight)
                ) {
                    Text(text = "MESSAGE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                }
            }
            isBlocked -> {
                Button(
                    onClick = onUnblock,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar)
                ) {
                    Text(text = "Unblock", fontSize = 12.sp)
                }
            }
            isOutgoing -> {
                Text(text = "Requested", color = AsterMuted, fontSize = 12.sp)
            }
            else -> {
                Button(
                    onClick = onAddFriend,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AsterViolet, contentColor = AsterNight)
                ) {
                    Text(text = "+ Connect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendRow(
    user: UserSummary,
    onMessage: () -> Unit,
    onLongClick: () -> Unit = {},
    onRemove: () -> Unit,
    onBlock: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AsterSurface)
            .border(0.75.dp, AsterBorder, RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onMessage,
                onLongClick = onLongClick
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            val isDeleted = user.isDeleted
            val displayName = if (isDeleted) "Deleted User" else user.displayName
            val isOnline = !isDeleted && user.isOnline
            AsterAvatar(name = displayName, isOnline = isOnline, size = 44.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = displayName, color = AsterStar, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                val subText = if (isDeleted) {
                    "This user no longer exists"
                } else {
                    val pres = com.aster.app.domain.model.formatPresenceText(user.isOnline, user.lastSeen, false) ?: "Offline"
                    "${user.asterId} • $pres"
                }
                Text(
                    text = subText,
                    color = AsterMuted,
                    fontSize = 12.sp,
                    fontStyle = if (isDeleted) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal
                )
            }
        }

        if (!user.isDeleted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onMessage,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AsterViolet, contentColor = AsterNight)
                ) {
                    Text(text = "MESSAGE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Remove",
                    color = AsterMuted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(onClick = onRemove)
                        .padding(4.dp)
                )
                Text(
                    text = "Block",
                    color = Color(0xFFFF6B6B).copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(onClick = onBlock)
                        .padding(4.dp)
                )
            }
        }
    }
}

@Composable
private fun IncomingRequestRow(
    request: com.aster.app.data.network.FriendRequestSummary,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val senderName = request.sender?.displayName ?: request.senderUserId
    val senderAsterId = request.sender?.asterId.orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AsterSurface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = senderName, color = AsterStar, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (senderAsterId.isNotBlank()) {
                Text(text = senderAsterId, color = AsterViolet, fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAccept,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AsterViolet, contentColor = AsterNight)
            ) {
                Text(text = "Accept", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "Decline",
                color = AsterGlow.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .clickable(onClick = onDecline)
                    .padding(8.dp)
            )
        }
    }
}

@Composable
private fun OutgoingRequestRow(
    request: com.aster.app.data.network.FriendRequestSummary,
    onCancel: () -> Unit
) {
    val recipientName = request.recipient?.displayName ?: request.recipientUserId
    val recipientAsterId = request.recipient?.asterId.orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AsterSurface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = recipientName, color = AsterStar, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (recipientAsterId.isNotBlank()) {
                Text(text = recipientAsterId, color = AsterGlow.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }

        Text(
            text = "Cancel",
            color = Color(0xFFFF6B6B).copy(alpha = 0.8f),
            fontSize = 12.sp,
            modifier = Modifier
                .clickable(onClick = onCancel)
                .padding(8.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockedUserRow(
    user: UserSummary,
    onLongClick: () -> Unit = {},
    onUnblock: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AsterSurface)
            .combinedClickable(
                onClick = onLongClick,
                onLongClick = onLongClick
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = user.displayName, color = AsterStar, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(text = "${user.asterId} • @${user.username}", color = AsterGlow.copy(alpha = 0.5f), fontSize = 12.sp)
        }

        Button(
            onClick = onUnblock,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar)
        ) {
            Text(text = "Unblock", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ContactContextDialog(
    user: UserSummary,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
    onViewProfile: () -> Unit,
    onRemove: () -> Unit,
    onBlock: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .asterDialogTransition()
                .clip(RoundedCornerShape(20.dp))
                .background(AsterSurfaceElevated)
                .border(0.75.dp, AsterBorder, RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        val isDel = user.isDeleted
                        val dName = if (isDel) "Deleted User" else user.displayName
                        AsterAvatar(name = dName, size = 44.dp, isOnline = !isDel && user.isOnline)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = dName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = AsterStar,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isDel) "This user no longer exists" else "${user.asterId} • @${user.username}",
                                color = AsterMuted,
                                fontSize = 12.sp,
                                fontStyle = if (isDel) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal
                            )
                        }
                    }
                    Text(
                        text = "✕",
                        color = AsterMuted,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(4.dp)
                            .semantics { contentDescription = "Close dialog" }
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!user.isDeleted) {
                        ContactActionRow(
                            icon = "💬",
                            title = "Open Conversation",
                            subtitle = "Send direct messages",
                            onClick = onMessage
                        )
                    }
                    ContactActionRow(
                        icon = "👤",
                        title = "View Profile",
                        subtitle = if (user.isDeleted) "Deleted account status" else "Identity & Aster ID",
                        onClick = onViewProfile
                    )
                    if (!user.isDeleted) {
                        ContactActionRow(
                            icon = "✕",
                            title = "Remove Contact",
                            subtitle = "Remove from constellation",
                            titleColor = Color(0xFFFF6B6B),
                            onClick = onRemove
                        )
                        ContactActionRow(
                            icon = "🛡",
                            title = "Block User",
                            subtitle = "Prevent messages and visibility",
                            titleColor = Color(0xFFFF6B6B),
                            onClick = onBlock
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedContextDialog(
    user: UserSummary,
    onDismiss: () -> Unit,
    onViewProfile: () -> Unit,
    onUnblock: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .asterDialogTransition()
                .clip(RoundedCornerShape(20.dp))
                .background(AsterSurfaceElevated)
                .border(0.75.dp, AsterBorder, RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        AsterAvatar(name = user.displayName, size = 44.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = user.displayName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = AsterStar,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = "@${user.username} • Blocked",
                                color = Color(0xFFFF6B6B),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Text(
                        text = "✕",
                        color = AsterMuted,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(4.dp)
                            .semantics { contentDescription = "Close dialog" }
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContactActionRow(
                        icon = "👤",
                        title = "View Profile",
                        subtitle = "Identity & Aster ID",
                        onClick = onViewProfile
                    )
                    ContactActionRow(
                        icon = "🛡",
                        title = "Unblock User",
                        subtitle = "Restore messaging capabilities",
                        titleColor = AsterOnline,
                        onClick = onUnblock
                    )
                }
            }
        }
    }
}

@Composable
private fun UserProfileDialog(
    user: UserSummary,
    isFriend: Boolean,
    isBlocked: Boolean,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .asterDialogTransition()
                .clip(RoundedCornerShape(20.dp))
                .background(AsterSurfaceElevated)
                .border(0.75.dp, AsterBorder, RoundedCornerShape(20.dp))
                .padding(22.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                    Text(
                        text = "✕",
                        color = AsterMuted,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(4.dp)
                            .semantics { contentDescription = "Close dialog" }
                    )
                }

                val isDel = user.isDeleted
                val dName = if (isDel) "Deleted User" else user.displayName
                val isOnline = !isDel && user.isOnline && !isBlocked

                AsterAvatar(name = dName, size = 64.dp, isOnline = isOnline)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = dName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = AsterStar,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                )

                Text(
                    text = if (isDel) "This user no longer exists" else "@${user.username}",
                    color = AsterMuted,
                    fontSize = 14.sp,
                    fontStyle = if (isDel) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal
                )

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AsterSurface)
                        .border(0.5.dp, AsterBorder, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column {
                        Text("Aster ID", color = AsterMuted, fontSize = 11.sp)
                        Text(if (isDel) "—" else user.asterId, color = AsterViolet, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = AsterBorder)
                    Column {
                        Text("Relationship", color = AsterMuted, fontSize = 11.sp)
                        val relText = when {
                            isDel -> "Deleted Account"
                            isBlocked -> "Blocked"
                            isFriend -> "Connected Contact"
                            else -> "Network Node"
                        }
                        val relColor = when {
                            isDel -> AsterMuted
                            isBlocked -> Color(0xFFFF6B6B)
                            isFriend -> AsterOnline
                            else -> AsterStar
                        }
                        Text(relText, color = relColor, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                    if (!isDel) {
                        HorizontalDivider(thickness = 0.5.dp, color = AsterBorder)
                        Column {
                            Text("Presence", color = AsterMuted, fontSize = 11.sp)
                            val presText = com.aster.app.domain.model.formatPresenceText(user.isOnline, user.lastSeen, false) ?: "Offline"
                            Text(presText, color = if (user.isOnline) AsterOnline else AsterMuted, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar)
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun ContactActionRow(
    icon: String,
    title: String,
    subtitle: String,
    titleColor: Color = AsterStar,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AsterSurface)
            .border(0.5.dp, AsterBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(AsterSurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Text(text = icon, fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = titleColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(text = subtitle, color = AsterMuted, fontSize = 12.sp)
        }
    }
}
