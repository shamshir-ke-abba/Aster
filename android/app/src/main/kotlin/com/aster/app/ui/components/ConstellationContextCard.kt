package com.aster.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.User
import com.aster.app.ui.motion.asterDialogTransition
import com.aster.app.ui.motion.asterPressable
import com.aster.app.ui.theme.AsterBorder
import com.aster.app.ui.theme.AsterCrimson
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterMuted
import com.aster.app.ui.theme.AsterOnline
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterSurfaceElevated
import com.aster.app.ui.theme.AsterViolet
import com.aster.app.ui.theme.AsterWarm

/**
 * Aster Constellation Context Card (Phase 8.6.4 - 8.6.7).
 *
 * Smoothly reveals when a user long-presses and releases a constellation node within
 * the 12dp touch deadzone.
 *
 * Implements:
 *  - 1:1 Actions: Open Conversation, View Profile, Highlight toggle, Remove Contact, Block User.
 *  - Group Actions: Open Group, Highlight toggle, Group Info, Members / People, Exit Group.
 *  - Full adherence to Phase 5 relationship rules and Phase 6 deleted-user invariants.
 */
@Composable
fun ConstellationContextCard(
    conversation: Conversation,
    currentUserId: String,
    onDismiss: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onToggleHighlight: (conversationId: String, currentHighlighted: Boolean) -> Unit,
    onViewProfile: (User) -> Unit = {},
    onRemoveContact: (userId: String) -> Unit = {},
    onBlockUser: (userId: String) -> Unit = {},
    onUnblockUser: (userId: String) -> Unit = {},
    onViewGroupInfo: (Conversation) -> Unit = {},
    onViewGroupMembers: (Conversation) -> Unit = {},
    onLeaveGroup: (groupId: String) -> Unit = {}
) {
    val isGroup = conversation.isGroup
    val otherUser = conversation.otherMember
    val isBlocked = !isGroup && (conversation.isBlocked || otherUser?.isBlocked == true)
    val isDeleted = !isGroup && (otherUser?.isDeleted == true)
    val displayName = if (isGroup) {
        conversation.title
    } else {
        if (isDeleted) "Deleted User" else otherUser?.displayName ?: conversation.title
    }
    val isHighlighted = !isBlocked && conversation.isHighlighted

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .asterDialogTransition()
                .clip(RoundedCornerShape(22.dp))
                .background(AsterSurfaceElevated)
                .border(
                    0.75.dp,
                    when {
                        isBlocked -> AsterCrimson.copy(alpha = 0.6f)
                        isHighlighted -> AsterViolet.copy(alpha = 0.6f)
                        else -> AsterBorder
                    },
                    RoundedCornerShape(22.dp)
                )
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Node Avatar / Nexus representation
                    if (isGroup) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(AsterElevated)
                                .border(1.2.dp, AsterViolet, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "⁂",
                                color = AsterViolet,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        AsterAvatar(
                            name = displayName,
                            size = 48.dp,
                            isOnline = !isDeleted && !isBlocked && (otherUser?.isOnline ?: false)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = AsterStar,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isBlocked) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "⊘ Blocked",
                                    color = AsterCrimson,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else if (isHighlighted) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "✦ Highlighted",
                                    color = AsterViolet,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        val subtitle = when {
                            isGroup -> "Group Constellation • ${conversation.members.size} members"
                            isDeleted -> "This user no longer exists"
                            isBlocked -> "Blocked User • Communications restricted"
                            otherUser != null -> "@${otherUser.username} • Direct Connection"
                            else -> "Direct Connection"
                        }

                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (isDeleted || isBlocked) AsterCrimson.copy(alpha = 0.85f) else AsterMuted,
                                fontSize = 12.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss)
                            .semantics { contentDescription = "Close context card" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            color = AsterMuted,
                            fontSize = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(thickness = 0.5.dp, color = AsterBorder)
                Spacer(modifier = Modifier.height(8.dp))

                // Action Tray
                if (!isGroup) {
                    // 1:1 Context Actions
                    ContextCardActionRow(
                        icon = "💬",
                        title = "Open Conversation",
                        subtitle = if (isBlocked) "Enter read-only channel" else "Enter direct celestial channel",
                        onClick = {
                            onDismiss()
                            onOpenConversation(conversation.id)
                        }
                    )

                    ContextCardActionRow(
                        icon = "👤",
                        title = "View Profile",
                        subtitle = "Inspect Aster identity details",
                        onClick = {
                            onDismiss()
                            if (otherUser != null) {
                                onViewProfile(otherUser)
                            } else {
                                onViewProfile(
                                    User(
                                        id = conversation.id,
                                        username = displayName.lowercase(),
                                        displayName = displayName,
                                        isDeleted = isDeleted,
                                        isBlocked = isBlocked
                                    )
                                )
                            }
                        }
                    )

                    if (isBlocked) {
                        // Blocked User: Only unblock action is available
                        ContextCardActionRow(
                            icon = "✓",
                            title = "Unblock User",
                            subtitle = "Restore celestial communications",
                            titleColor = AsterStar,
                            onClick = {
                                onDismiss()
                                onUnblockUser(otherUser?.id ?: conversation.id)
                            }
                        )
                    } else {
                        // Highlight Toggle (Aster's version of Pinning)
                        ContextCardActionRow(
                            icon = if (isHighlighted) "✧" else "✦",
                            title = if (isHighlighted) "Remove Highlight" else "Highlight Constellation",
                            subtitle = if (isHighlighted) "Return to organic layout tier" else "Radiant starlight aura & layout priority",
                            titleColor = if (isHighlighted) AsterWarm else AsterViolet,
                            onClick = {
                                onDismiss()
                                onToggleHighlight(conversation.id, isHighlighted)
                            }
                        )

                        // Phase 6 Invariant: Deleted users never display invalid relationship actions
                        if (!isDeleted && otherUser != null) {
                            ContextCardActionRow(
                                icon = "✕",
                                title = "Remove Contact",
                                subtitle = "Sever direct connection link",
                                titleColor = AsterCrimson,
                                onClick = {
                                    onDismiss()
                                    onRemoveContact(otherUser.id)
                                }
                            )

                            ContextCardActionRow(
                                icon = "🚫",
                                title = "Block User",
                                subtitle = "Cease all communications",
                                titleColor = AsterCrimson,
                                onClick = {
                                    onDismiss()
                                    onBlockUser(otherUser.id)
                                }
                            )
                        }
                    }
                } else {
                    // Group Context Actions
                    ContextCardActionRow(
                        icon = "💬",
                        title = "Open Group",
                        subtitle = "Enter constellation chat",
                        onClick = {
                            onDismiss()
                            onOpenConversation(conversation.id)
                        }
                    )

                    // Highlight Toggle
                    ContextCardActionRow(
                        icon = if (isHighlighted) "✧" else "✦",
                        title = if (isHighlighted) "Remove Highlight" else "Highlight Constellation",
                        subtitle = if (isHighlighted) "Return to organic layout tier" else "Radiant starlight aura & layout priority",
                        titleColor = if (isHighlighted) AsterWarm else AsterViolet,
                        onClick = {
                            onDismiss()
                            onToggleHighlight(conversation.id, isHighlighted)
                        }
                    )

                    ContextCardActionRow(
                        icon = "ℹ",
                        title = "Group Info",
                        subtitle = "View constellation ID and metadata",
                        onClick = {
                            onDismiss()
                            onViewGroupInfo(conversation)
                        }
                    )

                    ContextCardActionRow(
                        icon = "👥",
                        title = "Members / People",
                        subtitle = "${conversation.members.size} participants",
                        onClick = {
                            onDismiss()
                            onViewGroupMembers(conversation)
                        }
                    )

                    ContextCardActionRow(
                        icon = "🚪",
                        title = "Leave Group",
                        subtitle = "Exit this constellation cluster",
                        titleColor = AsterCrimson,
                        onClick = {
                            onDismiss()
                            onLeaveGroup(conversation.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ContextCardActionRow(
    icon: String,
    title: String,
    subtitle: String? = null,
    titleColor: Color = AsterStar,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .asterPressable(onClick = onClick)
            .clip(RoundedCornerShape(12.dp))
            .background(AsterSurface.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon,
            fontSize = 18.sp,
            color = titleColor,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = titleColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = AsterMuted,
                        fontSize = 11.sp
                    )
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
}
