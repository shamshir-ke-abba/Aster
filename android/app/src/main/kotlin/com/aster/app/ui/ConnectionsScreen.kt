package com.aster.app.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.aster.app.data.network.UserSummary
import com.aster.app.ui.components.AsterBottomNavigation
import com.aster.app.ui.components.AsterNavDestination
import com.aster.app.ui.components.ConstellationBackground
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterViolet

enum class ConnectionsTab {
    CONTACTS,
    REQUESTS,
    BLOCKED
}

@Composable
fun ConnectionsScreen(
    onNavigate: (AsterNavDestination) -> Unit,
    onStartChat: (String) -> Unit = {},
    viewModel: ConnectionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(ConnectionsTab.CONTACTS) }
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

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
                    Text(
                        text = "Explore & expand your constellation",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = AsterGlow.copy(alpha = 0.7f)
                        )
                    )
                }

                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = AsterViolet,
                        strokeWidth = 2.dp
                    )
                }
            }

            // Search Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AsterSurface)
                    .border(0.5.dp, AsterElevated, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::onSearchQueryChanged,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = AsterStar, fontSize = 14.sp),
                    cursorBrush = SolidColor(AsterViolet),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "🔍 ", fontSize = 14.sp)
                            Box(modifier = Modifier.weight(1f)) {
                                if (uiState.searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search by Aster ID (AST-...) or username",
                                        style = MaterialTheme.typography.bodySmall.copy(color = AsterGlow.copy(alpha = 0.4f))
                                    )
                                }
                                innerTextField()
                            }
                            if (uiState.searchQuery.isNotEmpty()) {
                                Text(
                                    text = "✕",
                                    color = AsterGlow.copy(alpha = 0.6f),
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable { viewModel.onSearchQueryChanged("") }
                                )
                            }
                        }
                    }
                )
            }

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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No users found matching \"${uiState.searchQuery}\"",
                            style = MaterialTheme.typography.bodyMedium.copy(color = AsterGlow.copy(alpha = 0.5f))
                        )
                    }
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
                                onAddFriend = { viewModel.sendFriendRequest(targetUserId = user.userId) }
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
                        .clip(RoundedCornerShape(10.dp))
                        .background(AsterSurface)
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
                                .background(if (isSelected) AsterElevated else Color.Transparent)
                                .clickable { selectedTab = tab }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (isSelected) AsterStar else AsterGlow.copy(alpha = 0.6f),
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Tab Content
                when (selectedTab) {
                    ConnectionsTab.CONTACTS -> {
                        if (uiState.friends.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No connections yet. Search users above to connect!",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = AsterGlow.copy(alpha = 0.5f))
                                )
                            }
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
                                        onMessage = { onStartChat(friend.userId) },
                                        onBlock = { viewModel.blockUser(friend.userId) }
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
                                            color = AsterGlow.copy(alpha = 0.6f),
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
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 64.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No pending friend requests",
                                            style = MaterialTheme.typography.bodyMedium.copy(color = AsterGlow.copy(alpha = 0.5f))
                                        )
                                    }
                                }
                            }
                        }
                    }

                    ConnectionsTab.BLOCKED -> {
                        if (uiState.blockedUsers.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No blocked users",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = AsterGlow.copy(alpha = 0.5f))
                                )
                            }
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
    }
}

@Composable
private fun UserSearchRow(
    user: UserSummary,
    isFriend: Boolean,
    isOutgoing: Boolean,
    onAddFriend: () -> Unit
) {
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
            Text(text = user.displayName, color = AsterStar, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(text = "@${user.username} • ${user.asterId}", color = AsterGlow.copy(alpha = 0.6f), fontSize = 12.sp)
        }

        when {
            isFriend -> {
                Text(text = "Connected ✦", color = AsterViolet, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            isOutgoing -> {
                Text(text = "Requested", color = AsterGlow.copy(alpha = 0.6f), fontSize = 12.sp)
            }
            else -> {
                Button(
                    onClick = onAddFriend,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AsterViolet, contentColor = AsterNight)
                ) {
                    Text(text = "+ Connect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FriendRow(
    user: UserSummary,
    onMessage: () -> Unit,
    onBlock: () -> Unit
) {
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(AsterElevated),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "✦", color = AsterViolet, fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = user.displayName, color = AsterStar, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(text = "${user.asterId} • @${user.username}", color = AsterGlow.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Block",
                color = Color(0xFFFF6B6B).copy(alpha = 0.8f),
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onBlock)
                    .padding(8.dp)
            )
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

@Composable
private fun BlockedUserRow(
    user: UserSummary,
    onUnblock: () -> Unit
) {
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
