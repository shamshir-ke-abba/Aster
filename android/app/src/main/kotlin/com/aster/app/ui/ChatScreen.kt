package com.aster.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.aster.app.ui.motion.AsterCelestialEffectBox
import com.aster.app.ui.motion.AsterCelestialEffectPicker
import com.aster.app.ui.motion.AsterEmphasizedText
import com.aster.app.ui.motion.AsterHapticEvent
import com.aster.app.ui.motion.AsterMessageStatusTransition
import com.aster.app.ui.motion.AsterMotion
import com.aster.app.ui.motion.AsterMotionTokens
import com.aster.app.ui.motion.AsterReactionBadgeRow
import com.aster.app.ui.motion.AsterReactionDock
import com.aster.app.ui.motion.AsterReactionTokens
import com.aster.app.ui.motion.AsterSwipeToReplyBox
import com.aster.app.ui.motion.LocalAsterHaptics
import com.aster.app.ui.motion.LocalReducedMotion
import com.aster.app.ui.motion.asterDialogTransition
import com.aster.app.ui.motion.asterMessageArrival
import com.aster.app.ui.motion.asterPressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aster.app.domain.model.User
import com.aster.app.data.network.ConnectionState
import com.aster.app.domain.model.Message
import com.aster.app.domain.model.MessageStatus
import com.aster.app.ui.components.AsterAvatar
import com.aster.app.ui.components.AsterEmptyState
import com.aster.app.ui.components.ConstellationBackground
import com.aster.app.ui.theme.AsterBorder
import com.aster.app.ui.theme.AsterCrimson
import com.aster.app.ui.theme.AsterElevated
import com.aster.app.ui.theme.AsterGlow
import com.aster.app.ui.theme.AsterMuted
import com.aster.app.ui.theme.AsterNight
import com.aster.app.ui.theme.AsterOnline
import com.aster.app.ui.theme.AsterStar
import com.aster.app.ui.theme.AsterSurfaceElevated
import com.aster.app.ui.theme.AsterSurface
import com.aster.app.ui.theme.AsterSurfaceElevated
import com.aster.app.ui.theme.AsterViolet
import com.aster.app.ui.theme.AsterWarm
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Aster Chat Screen — real local-first conversation view.
 *
 * Implements canonical Aster chat specifications from /Aster/design/design.md.
 */
@Composable
fun ChatScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    DisposableEffect(viewModel.conversationId) {
        onDispose {
            viewModel.onScreenExited()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.markAsReadIfActive()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    ChatScreenContent(
        conversationId = viewModel.conversationId,
        uiState = uiState,
        onBackClick = onBackClick,
        onInputTextChanged = viewModel::onInputTextChanged,
        onSendMessage = viewModel::sendMessage,
        onSelectReply = viewModel::selectReplyMessage,
        onCancelReply = viewModel::cancelReply,
        onSelectEffect = viewModel::selectEffect,
        onToggleReaction = viewModel::toggleReaction,
        onApplyEffect = viewModel::applyEffectToMessage,
        onRetryMessage = viewModel::retryMessage,
        onDeleteForSelf = viewModel::deleteForSelf,
        onUnsendMessage = viewModel::unsendMessage,
        onUnblockUser = viewModel::unblockUser,
        onBlockUser = viewModel::blockUser,
        onLeaveGroup = { viewModel.leaveGroup(onLeft = onBackClick) },
        onRemoveMember = viewModel::removeMember,
        onMarkEffectPresented = viewModel::markEffectPresented,
        modifier = modifier
    )
}

@Composable
fun ChatScreenContent(
    conversationId: String = "",
    uiState: ChatUiState,
    onBackClick: () -> Unit,
    onInputTextChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    onSelectReply: (Message) -> Unit = {},
    onCancelReply: () -> Unit = {},
    onSelectEffect: (String?) -> Unit = {},
    onToggleReaction: (String, String) -> Unit = { _, _ -> },
    onApplyEffect: (String, String?) -> Unit = { _, _ -> },
    onRetryMessage: (String) -> Unit = {},
    onDeleteForSelf: (String) -> Unit = {},
    onUnsendMessage: (String) -> Unit = {},
    onUnblockUser: () -> Unit = {},
    onBlockUser: () -> Unit = {},
    onLeaveGroup: () -> Unit = {},
    onRemoveMember: (String) -> Unit = {},
    onMarkEffectPresented: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val imeInsets = WindowInsets.ime
    val navBarInsets = WindowInsets.navigationBars
    val density = LocalDensity.current
    val haptics = LocalAsterHaptics.current
    val isReduced = LocalReducedMotion.current
    val coroutineScope = rememberCoroutineScope()

    // Bottom insets take either the IME height or the navigation bar height
    val bottomPadding = with(density) {
        val imeBottom = imeInsets.getBottom(this)
        val navBottom = navBarInsets.getBottom(this)
        maxOf(imeBottom, navBottom).toDp()
    }

    val conversationKey = if (conversationId.isNotBlank()) conversationId else (uiState.conversation?.id ?: "")
    val initialTargetIndex = remember(conversationKey, uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) uiState.messages.size - 1 else 0
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialTargetIndex)
    var hasInitiallySettled by remember(conversationKey) { mutableStateOf(false) }
    val timelineAlpha = remember(conversationKey) { Animatable(0f) }
    var previousMessageCount by remember(conversationKey) { mutableStateOf(uiState.messages.size) }
    var hasNewMessagesBelow by remember(conversationKey) { mutableStateOf(false) }

    var selectedMessageForDetails by remember { mutableStateOf<Message?>(null) }
    var selectedMessageForContext by remember { mutableStateOf<Message?>(null) }
    var messageToDeleteForSelf by remember { mutableStateOf<Message?>(null) }
    var messageToUnsend by remember { mutableStateOf<Message?>(null) }
    var showGroupActionsSheet by remember { mutableStateOf(false) }
    var showGroupMembersDialog by remember { mutableStateOf(false) }
    var showGroupInfoDialog by remember { mutableStateOf(false) }
    var showUserProfileDialog by remember { mutableStateOf(false) }
    var showLeaveGroupConfirm by remember { mutableStateOf(false) }
    var memberToRemove by remember { mutableStateOf<User?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // Track initial loaded messages vs newly arrived messages in this session
    val initialMessageIds = remember(conversationKey) { mutableSetOf<String>() }
    val playedEffectIds = remember(conversationKey) { mutableSetOf<String>() }
    var isInitialSyncComplete by remember(conversationKey) { mutableStateOf(false) }

    LaunchedEffect(uiState.isLoading, uiState.messages) {
        if (!uiState.isLoading && !isInitialSyncComplete && uiState.messages.isNotEmpty()) {
            initialMessageIds.addAll(uiState.messages.map { it.id })
            isInitialSyncComplete = true
        }
    }

    // Scroll anchoring: only auto-scroll if resting near bottom (within 1 item)
    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf true
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems - 1 - lastVisible <= 1
        }
    }

    LaunchedEffect(isNearBottom) {
        if (isNearBottom) {
            hasNewMessagesBelow = false
        }
    }

    // Grounded initial chat entry: immediate positioning at latest message + subtle celestial reveal
    LaunchedEffect(conversationKey, uiState.messages.isNotEmpty()) {
        if (uiState.messages.isNotEmpty() && !hasInitiallySettled) {
            val target = uiState.messages.size - 1
            listState.scrollToItem(target)
            hasInitiallySettled = true
            previousMessageCount = uiState.messages.size
            timelineAlpha.animateTo(
                targetValue = 1f,
                animationSpec = AsterMotion.tween(
                    durationMillis = AsterMotionTokens.Duration.Micro,
                    easing = AsterMotionTokens.EasingCurve.StandardDecelerate,
                    reducedMotion = isReduced
                )
            )
        }
    }

    // Empty conversation handling: reveal without invalid scrolling
    LaunchedEffect(conversationKey, uiState.isLoading, uiState.messages.isEmpty()) {
        if (!uiState.isLoading && uiState.messages.isEmpty() && !hasInitiallySettled) {
            hasInitiallySettled = true
            timelineAlpha.snapTo(1f)
        }
    }

    // Auto-scroll rules for new messages arriving during conversation
    LaunchedEffect(uiState.messages.size, conversationKey) {
        if (hasInitiallySettled && uiState.messages.isNotEmpty()) {
            val target = uiState.messages.size - 1
            if (uiState.messages.size > previousMessageCount) {
                val lastMsg = uiState.messages.lastOrNull()
                val isOutgoingLast = lastMsg?.senderId == uiState.localUser?.id

                if (isOutgoingLast) {
                    // Outgoing message sent by local user: scroll to show sent message
                    if (listState.firstVisibleItemIndex < target - 6) {
                        listState.scrollToItem(target - 2)
                    }
                    listState.animateScrollToItem(target)
                    hasNewMessagesBelow = false
                } else {
                    // Incoming message: auto-scroll only if user is resting near bottom and not actively dragging
                    if (isNearBottom && !listState.isScrollInProgress) {
                        listState.animateScrollToItem(target)
                        hasNewMessagesBelow = false
                    } else {
                        // User reading history: do NOT hijack viewport; show subtle floating indicator
                        hasNewMessagesBelow = true
                    }
                }
            }
            previousMessageCount = uiState.messages.size
        }
    }

    // Auto-scroll when keyboard opens: only preserve user reading position if already at bottom
    with(density) {
        val imeBottomPx = imeInsets.getBottom(this)
        LaunchedEffect(imeBottomPx) {
            if (imeBottomPx > 0 && uiState.messages.isNotEmpty() && isNearBottom && !listState.isScrollInProgress) {
                val target = uiState.messages.size - 1
                if (target >= 0) {
                    listState.animateScrollToItem(target)
                }
            }
        }
    }

    val isBlockedOtherUser = uiState.conversation?.isGroup == false && uiState.otherUser?.isBlocked == true
    val isDeletedOtherUser = uiState.conversation?.isGroup == false && uiState.otherUser?.isDeleted == true

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AsterNight)
    ) {
        ConstellationBackground(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = statusBarPadding.calculateTopPadding(),
                    bottom = bottomPadding
                )
        ) {
            // Top App Bar
            ChatTopBar(
                title = uiState.conversation?.title ?: "Chat",
                isGroup = uiState.conversation?.isGroup == true,
                memberCount = uiState.members.size,
                otherUser = uiState.otherUser,
                onBackClick = onBackClick,
                onGroupHeaderClick = if (uiState.conversation?.isGroup == true) {
                    { showGroupActionsSheet = true }
                } else null,
                onUserHeaderClick = if (uiState.conversation?.isGroup != true && uiState.otherUser != null) {
                    { showUserProfileDialog = true }
                } else null
            )

            HorizontalDivider(
                thickness = 0.75.dp,
                color = AsterBorder
            )

            // Connection state indicator banner if not connected
            if (uiState.connectionState != ConnectionState.CONNECTED) {
                val isReconnecting = uiState.connectionState == ConnectionState.CONNECTING || uiState.connectionState == ConnectionState.RECONNECTING
                val bannerBg = if (isReconnecting) AsterWarm.copy(alpha = 0.12f) else AsterCrimson.copy(alpha = 0.12f)
                val dotColor = if (isReconnecting) AsterWarm else AsterCrimson
                val textColor = if (isReconnecting) AsterWarm else AsterCrimson
                val bannerText = if (isReconnecting) "Reconnecting…" else "Offline — messages will send when back online"

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bannerBg)
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = bannerText,
                            color = textColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Message list or empty state
            if (uiState.messages.isEmpty() && !uiState.isLoading) {
                val isDeletedUser = uiState.conversation?.isGroup == false && uiState.otherUser?.isDeleted == true
                EmptyChatState(
                    title = if (isDeletedUser) "Deleted User" else (uiState.conversation?.title ?: "Conversation"),
                    description = if (isDeletedUser) "This user no longer exists" else "Messages in this constellation are private, local-first, and encrypted.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else if (uiState.messages.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = timelineAlpha.value },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        itemsIndexed(
                            items = uiState.messages,
                            key = { _, it -> it.id }
                        ) { index, message ->
                            val isNewArrival = isInitialSyncComplete && !initialMessageIds.contains(message.id)
                            if (isNewArrival) {
                                LaunchedEffect(message.id) {
                                    delay(600)
                                    initialMessageIds.add(message.id)
                                }
                            }

                            val prev = if (index > 0) uiState.messages[index - 1] else null
                            val next = if (index < uiState.messages.size - 1) uiState.messages[index + 1] else null

                            val isSameSenderPrev = prev != null && prev.senderId == message.senderId && (message.createdAt - prev.createdAt) < 120_000L
                            val isSameSenderNext = next != null && next.senderId == message.senderId && (next.createdAt - message.createdAt) < 120_000L

                            val isFirstInCluster = !isSameSenderPrev
                            val isLastInCluster = !isSameSenderNext

                            val isOutgoing = message.senderId == uiState.localUser?.id
                            val isGroup = uiState.conversation?.isGroup == true

                            val senderUser = uiState.members.firstOrNull { it.id == message.senderId }
                            val senderName = if (!isOutgoing && isGroup && isFirstInCluster) {
                                if (senderUser?.isDeleted == true) "Deleted User"
                                else senderUser?.displayName ?: message.senderId.removePrefix("user_").replaceFirstChar { it.uppercase() }
                            } else null

                            val topPadding = if (isFirstInCluster && index > 0) 12.dp else if (index == 0) 0.dp else 3.dp

                            val quotedMessage = message.replyToId?.let { replyId ->
                                uiState.messages.firstOrNull { it.id == replyId }
                            }
                            val quotedSenderName = quotedMessage?.let { qm ->
                                if (qm.senderId == uiState.localUser?.id) "You"
                                else uiState.members.firstOrNull { it.id == qm.senderId }?.displayName ?: "User"
                            }

                            val shouldAnimateEffect = !message.effect.isNullOrBlank() &&
                                !message.isEffectPlayed &&
                                !playedEffectIds.contains(message.id)

                            AsterSwipeToReplyBox(
                                onReply = { onSelectReply(message) },
                                enabled = message.status != MessageStatus.UNSENT && !isBlockedOtherUser,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = topPadding)
                            ) {
                                MessageBubble(
                                    message = message,
                                    isOutgoing = isOutgoing,
                                    isFirstInCluster = isFirstInCluster,
                                    isLastInCluster = isLastInCluster,
                                    senderDisplayName = senderName,
                                    isGroup = isGroup,
                                    quotedMessage = quotedMessage,
                                    quotedSenderDisplayName = quotedSenderName,
                                    currentUserId = uiState.localUser?.id,
                                    onToggleReaction = onToggleReaction,
                                    onMessageClick = { selectedMessageForDetails = message },
                                    onMessageLongClick = {
                                        haptics.perform(AsterHapticEvent.ContextOpen)
                                        selectedMessageForContext = message
                                    },
                                    onRetry = if (isOutgoing && (message.status == MessageStatus.FAILED || message.status == MessageStatus.SENDING)) {
                                        {
                                            haptics.perform(AsterHapticEvent.Selection)
                                            onRetryMessage(message.id)
                                        }
                                    } else null,
                                    shouldAnimateEffect = shouldAnimateEffect,
                                    onEffectStarted = {
                                        playedEffectIds.add(message.id)
                                        onMarkEffectPresented(message.id)
                                    },
                                    onEffectFinished = null,
                                    modifier = Modifier.asterMessageArrival(isNewArrival = isNewArrival)
                                )
                            }
                        }
                    }

                    // Unobtrusive "New messages ↓" floating indicator (Workstream 1.3)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = hasNewMessagesBelow && !isNearBottom,
                        enter = fadeIn(animationSpec = AsterMotion.tween(AsterMotionTokens.Duration.Micro, reducedMotion = isReduced)) +
                                slideInVertically(animationSpec = AsterMotion.spring(AsterMotion.Spring.Settle, reducedMotion = isReduced)) { it / 2 },
                        exit = fadeOut(animationSpec = AsterMotion.tween(AsterMotionTokens.Duration.Micro, reducedMotion = isReduced)) +
                               slideOutVertically(animationSpec = AsterMotion.spring(AsterMotion.Spring.Settle, reducedMotion = isReduced)) { it / 2 },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(AsterSurfaceElevated)
                                .border(1.dp, AsterViolet.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                            .clickable {
                                haptics.perform(AsterHapticEvent.Selection)
                                coroutineScope.launch {
                                    val target = uiState.messages.size - 1
                                    if (target >= 0) {
                                        if (listState.firstVisibleItemIndex < target - 6) {
                                            listState.scrollToItem(target - 2)
                                        }
                                        listState.animateScrollToItem(target)
                                    }
                                    hasNewMessagesBelow = false
                                }
                            }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "New messages",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "↓",
                                    color = AsterViolet,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Message Composer
            val isDeletedOtherUser = uiState.conversation?.isGroup == false && uiState.otherUser?.isDeleted == true
            val replyingSenderName = uiState.replyingToMessage?.let { rm ->
                if (rm.senderId == uiState.localUser?.id) "You"
                else uiState.members.firstOrNull { it.id == rm.senderId }?.displayName ?: "User"
            }
            ChatComposer(
                inputText = uiState.inputText,
                isSending = uiState.isSending,
                onInputTextChanged = onInputTextChanged,
                onSendMessage = onSendMessage,
                replyingToMessage = uiState.replyingToMessage,
                replyingToSenderName = replyingSenderName,
                onCancelReply = onCancelReply,
                selectedEffect = uiState.selectedEffect,
                onSelectEffect = onSelectEffect,
                isEnabled = !isDeletedOtherUser && !isBlockedOtherUser,
                disabledHint = if (isBlockedOtherUser) "You have blocked this user" else if (isDeletedOtherUser) "This user no longer exists" else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        if (selectedMessageForDetails != null) {
            MessageDetailDialog(
                message = selectedMessageForDetails!!,
                members = uiState.members,
                localUserId = uiState.localUser?.id,
                isGroup = uiState.conversation?.isGroup == true,
                onDismiss = { selectedMessageForDetails = null }
            )
        }

        if (selectedMessageForContext != null) {
            val ctxMsg = selectedMessageForContext!!
            val isOut = ctxMsg.senderId == uiState.localUser?.id
            val canUnsend = isOut && ctxMsg.status != MessageStatus.UNSENT &&
                (System.currentTimeMillis() - ctxMsg.createdAt <= 5 * 60 * 1000)

            MessageContextOverlay(
                message = ctxMsg,
                isOutgoing = isOut,
                canUnsend = canUnsend,
                isBlocked = isBlockedOtherUser,
                currentUserId = uiState.localUser?.id,
                onReactionSelected = { emoji ->
                    haptics.perform(AsterHapticEvent.Reaction)
                    onToggleReaction(ctxMsg.id, emoji)
                },
                onApplyEffect = { effect ->
                    haptics.perform(AsterHapticEvent.Selection)
                    onApplyEffect(ctxMsg.id, effect)
                },
                onDismiss = { selectedMessageForContext = null },
                onReply = {
                    haptics.perform(AsterHapticEvent.Selection)
                    onSelectReply(ctxMsg)
                    selectedMessageForContext = null
                },
                onViewInfo = {
                    haptics.perform(AsterHapticEvent.Selection)
                    selectedMessageForDetails = ctxMsg
                    selectedMessageForContext = null
                },
                onCopyText = {
                    haptics.perform(AsterHapticEvent.Success)
                    clipboardManager.setText(AnnotatedString(ctxMsg.body))
                    android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                    selectedMessageForContext = null
                },
                onUnsend = {
                    messageToUnsend = ctxMsg
                    selectedMessageForContext = null
                },
                onDeleteForSelf = {
                    messageToDeleteForSelf = ctxMsg
                    selectedMessageForContext = null
                }
            )
        }

        if (messageToDeleteForSelf != null) {
            AlertDialog(
                onDismissRequest = { messageToDeleteForSelf = null },
                title = { Text(text = "Delete for you?", color = AsterStar, fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        text = "This message will be removed from your device. Other participants will still be able to see it.",
                        color = AsterGlow.copy(alpha = 0.8f)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            haptics.perform(AsterHapticEvent.Destructive)
                            val id = messageToDeleteForSelf?.id
                            messageToDeleteForSelf = null
                            if (id != null) {
                                onDeleteForSelf(id)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AsterCrimson, contentColor = AsterNight)
                    ) {
                        Text(text = "Delete for Me", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { messageToDeleteForSelf = null },
                        colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar)
                    ) {
                        Text(text = "Cancel")
                    }
                },
                containerColor = AsterSurface
            )
        }

        if (messageToUnsend != null) {
            AlertDialog(
                onDismissRequest = { messageToUnsend = null },
                title = { Text(text = "Unsend message?", color = AsterStar, fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        text = "This will retract the message for everyone in this conversation within the 5-minute unsend window.",
                        color = AsterGlow.copy(alpha = 0.8f)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            haptics.perform(AsterHapticEvent.Destructive)
                            val id = messageToUnsend?.id
                            messageToUnsend = null
                            if (id != null) {
                                onUnsendMessage(id)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AsterCrimson, contentColor = AsterNight)
                    ) {
                        Text(text = "Unsend", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { messageToUnsend = null },
                        colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar)
                    ) {
                        Text(text = "Cancel")
                    }
                },
                containerColor = AsterSurface
            )
        }

        if (showGroupActionsSheet) {
            GroupActionsDialog(
                title = uiState.conversation?.title ?: "Group Constellation",
                memberCount = uiState.members.size,
                onDismiss = { showGroupActionsSheet = false },
                onViewMembers = {
                    showGroupActionsSheet = false
                    showGroupMembersDialog = true
                },
                onViewGroupInfo = {
                    showGroupActionsSheet = false
                    showGroupInfoDialog = true
                },
                onExitGroup = {
                    showGroupActionsSheet = false
                    showLeaveGroupConfirm = true
                }
            )
        }

        if (showGroupMembersDialog) {
            val localIsAdmin = uiState.memberRoles[uiState.localUser?.id] in listOf("ADMIN", "CREATOR")
            GroupMembersDialog(
                members = uiState.members,
                localUserId = uiState.localUser?.id,
                memberRoles = uiState.memberRoles,
                isLocalUserAdmin = localIsAdmin,
                creatorUserId = null,
                onDismiss = { showGroupMembersDialog = false },
                onRemoveMember = { member ->
                    memberToRemove = member
                }
            )
        }

        if (memberToRemove != null) {
            AlertDialog(
                onDismissRequest = { memberToRemove = null },
                title = { Text(text = "Remove from group?", color = AsterStar, fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        text = "Are you sure you want to remove ${memberToRemove?.displayName} (@${memberToRemove?.username}) from this group?",
                        color = AsterGlow.copy(alpha = 0.8f)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val id = memberToRemove?.id
                            memberToRemove = null
                            if (id != null) {
                                onRemoveMember(id)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AsterCrimson, contentColor = AsterNight)
                    ) {
                        Text(text = "Remove", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { memberToRemove = null },
                        colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar)
                    ) {
                        Text(text = "Cancel")
                    }
                },
                containerColor = AsterSurface
            )
        }

        if (showLeaveGroupConfirm) {
            AlertDialog(
                onDismissRequest = { showLeaveGroupConfirm = false },
                title = { Text(text = "Exit Group?", color = AsterStar, fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        text = "Are you sure you want to exit ${uiState.conversation?.title ?: "this group"}? You will no longer receive messages or updates from this constellation.",
                        color = AsterGlow.copy(alpha = 0.8f)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showLeaveGroupConfirm = false
                            onLeaveGroup()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AsterCrimson, contentColor = AsterNight)
                    ) {
                        Text(text = "Exit Group", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showLeaveGroupConfirm = false },
                        colors = ButtonDefaults.buttonColors(containerColor = AsterElevated, contentColor = AsterStar)
                    ) {
                        Text(text = "Cancel")
                    }
                },
                containerColor = AsterSurface
            )
        }

        if (showGroupInfoDialog) {
            GroupInfoDialog(
                title = uiState.conversation?.title ?: "Group Constellation",
                conversationId = uiState.conversation?.id.orEmpty(),
                memberCount = uiState.members.size,
                onDismiss = { showGroupInfoDialog = false }
            )
        }

        if (showUserProfileDialog && uiState.otherUser != null) {
            ChatUserProfileDialog(
                user = uiState.otherUser!!,
                onDismiss = { showUserProfileDialog = false },
                onUnblockUser = onUnblockUser
            )
        }
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    isGroup: Boolean = false,
    memberCount: Int = 0,
    otherUser: User? = null,
    onBackClick: () -> Unit,
    onGroupHeaderClick: (() -> Unit)? = null,
    onUserHeaderClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isBlocked = !isGroup && otherUser?.isBlocked == true
    val isDeleted = !isGroup && otherUser?.isDeleted == true
    val resolvedTitle = if (isDeleted) "Deleted User" else title
    val isOnline = !isGroup && !isDeleted && !isBlocked && (otherUser?.isOnline == true)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(AsterNight)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back navigation button (min touch target >= 48dp)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(
                    onClick = onBackClick,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                )
                .semantics { contentDescription = "Back to conversations" },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "←",
                color = AsterStar,
                fontSize = 22.sp
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Avatar with tactile press response
        Box(
            modifier = Modifier.then(
                if (!isGroup && onUserHeaderClick != null) {
                    Modifier.asterPressable(onClick = onUserHeaderClick)
                } else Modifier
            )
        ) {
            AsterAvatar(
                name = resolvedTitle,
                size = 38.dp,
                isOnline = isOnline
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Conversation Title & Presence / Member Count
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (isGroup && onGroupHeaderClick != null) {
                        Modifier.asterPressable(onClick = onGroupHeaderClick)
                    } else if (!isGroup && onUserHeaderClick != null) {
                        Modifier.asterPressable(onClick = onUserHeaderClick)
                    } else Modifier
                ),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = resolvedTitle,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = AsterStar,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(2.dp))

            if (isGroup) {
                Text(
                    text = if (memberCount > 0) "$memberCount members · Tap for options" else "Group Constellation",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        fontSize = 11.sp
                    )
                )
            } else if (isBlocked) {
                Text(
                    text = "Blocked",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterCrimson,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            } else if (isDeleted) {
                Text(
                    text = "This user no longer exists",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        fontSize = 11.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                )
            } else if (isOnline) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(AsterOnline)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Active now",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = AsterOnline,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            } else if ((otherUser?.lastSeen ?: 0L) > 0L) {
                val presenceText = com.aster.app.domain.model.formatPresenceText(otherUser?.isOnline ?: false, otherUser!!.lastSeen, false) ?: "Offline"
                Text(
                    text = presenceText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        fontSize = 11.sp
                    )
                )
            } else {
                Text(
                    text = "Offline",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        fontSize = 11.sp
                    )
                )
            }
        }

        // Right action button
        if (isGroup && onGroupHeaderClick != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        onClick = onGroupHeaderClick,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    )
                    .semantics { contentDescription = "Group options" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⋮",
                    color = AsterStar,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            // Aster emblem accent
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AsterSurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✦",
                    color = AsterViolet,
                    fontSize = 15.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    isOutgoing: Boolean,
    isFirstInCluster: Boolean,
    isLastInCluster: Boolean,
    senderDisplayName: String? = null,
    isGroup: Boolean = false,
    quotedMessage: Message? = null,
    quotedSenderDisplayName: String? = null,
    currentUserId: String? = null,
    onToggleReaction: (String, String) -> Unit = { _, _ -> },
    onMessageClick: () -> Unit = {},
    onMessageLongClick: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    shouldAnimateEffect: Boolean = false,
    onEffectStarted: (() -> Unit)? = null,
    onEffectFinished: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isUnsent = message.status == MessageStatus.UNSENT
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = when {
        isUnsent -> AsterSurface.copy(alpha = 0.5f)
        isOutgoing -> AsterSurfaceElevated
        else -> AsterSurface
    }
    val borderColor = when {
        isUnsent -> AsterBorder.copy(alpha = 0.4f)
        isOutgoing -> AsterViolet.copy(alpha = 0.35f)
        else -> AsterBorder
    }
    val textColor = if (isOutgoing) AsterStar else Color(0xFFE2E4EE)
    val timeColor = AsterMuted

    val bubbleShape = getBubbleShape(
        isOutgoing = isOutgoing,
        isFirstInCluster = isFirstInCluster,
        isLastInCluster = isLastInCluster
    )

    val horizontalPadding = if (isOutgoing) {
        Modifier.padding(start = 48.dp)
    } else {
        Modifier.padding(end = 48.dp)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(horizontalPadding),
        horizontalAlignment = alignment
    ) {
        AsterCelestialEffectBox(
            effect = message.effect,
            shouldAnimate = shouldAnimateEffect,
            onEffectStarted = onEffectStarted,
            onEffectFinished = onEffectFinished
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 68.dp, max = 290.dp)
                    .clip(bubbleShape)
                    .background(bubbleColor)
                    .border(0.75.dp, borderColor, bubbleShape)
                    .combinedClickable(
                        onClick = onMessageClick,
                        onLongClick = onMessageLongClick
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Column {
                    if (!isOutgoing && senderDisplayName != null) {
                        Text(
                            text = senderDisplayName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = getSenderColor(message.senderId),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    // Quoted reply snippet preview
                    if (quotedMessage != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AsterNight.copy(alpha = 0.5f))
                                .border(0.5.dp, AsterViolet.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.5.dp)
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(AsterViolet)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = quotedSenderDisplayName ?: "Replying to",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = AsterViolet,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 10.5.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (quotedMessage.status == MessageStatus.UNSENT) "This message was unsent" else quotedMessage.body,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = AsterMuted,
                                        fontSize = 11.sp,
                                        fontStyle = if (quotedMessage.status == MessageStatus.UNSENT) FontStyle.Italic else FontStyle.Normal
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Message body or unsent placeholder
                    if (isUnsent) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "↩",
                                color = AsterMuted,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "This message was unsent",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = AsterMuted,
                                    fontSize = 13.sp,
                                    fontStyle = FontStyle.Italic
                                )
                            )
                        }
                    } else {
                        AsterEmphasizedText(
                            text = message.body,
                            color = textColor,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    // Timestamp & status
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = formatBubbleTime(message.createdAt),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = timeColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )

                        if (isOutgoing) {
                            AsterMessageStatusTransition(status = message.status, onRetry = onRetry)
                            if (isGroup && message.readBy.isNotEmpty() && !isUnsent) {
                                Text(
                                    text = "· ${message.readBy.size} read",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = AsterOnline,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                            if (message.status == MessageStatus.FAILED && onRetry != null) {
                                Text(
                                    text = "Retry",
                                    color = AsterCrimson,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable(onClick = onRetry)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Reactions Attached Badge Row
        if (message.reactions.isNotEmpty() && !isUnsent) {
            AsterReactionBadgeRow(
                reactions = message.reactions,
                currentUserId = currentUserId,
                onToggleReaction = { emoji -> onToggleReaction(message.id, emoji) },
                modifier = Modifier.padding(
                    start = if (isOutgoing) 0.dp else 4.dp,
                    end = if (isOutgoing) 4.dp else 0.dp
                )
            )
        }
    }
}

private fun getBubbleShape(
    isOutgoing: Boolean,
    isFirstInCluster: Boolean,
    isLastInCluster: Boolean
): RoundedCornerShape {
    val large = 16.dp
    val small = 4.dp
    val grouped = 6.dp

    return if (isOutgoing) {
        when {
            isFirstInCluster && isLastInCluster -> {
                RoundedCornerShape(topStart = large, topEnd = large, bottomStart = large, bottomEnd = small)
            }
            isFirstInCluster -> {
                RoundedCornerShape(topStart = large, topEnd = large, bottomStart = large, bottomEnd = grouped)
            }
            isLastInCluster -> {
                RoundedCornerShape(topStart = large, topEnd = grouped, bottomStart = large, bottomEnd = small)
            }
            else -> {
                RoundedCornerShape(topStart = large, topEnd = grouped, bottomStart = large, bottomEnd = grouped)
            }
        }
    } else {
        when {
            isFirstInCluster && isLastInCluster -> {
                RoundedCornerShape(topStart = large, topEnd = large, bottomStart = small, bottomEnd = large)
            }
            isFirstInCluster -> {
                RoundedCornerShape(topStart = large, topEnd = large, bottomStart = grouped, bottomEnd = large)
            }
            isLastInCluster -> {
                RoundedCornerShape(topStart = grouped, topEnd = large, bottomStart = small, bottomEnd = large)
            }
            else -> {
                RoundedCornerShape(topStart = grouped, topEnd = large, bottomStart = grouped, bottomEnd = large)
            }
        }
    }
}

private fun getSenderColor(userId: String): Color {
    val palette = listOf(
        AsterViolet,
        AsterGlow,
        AsterWarm,
        AsterOnline,
        Color(0xFF60A5FA),
        Color(0xFFF472B6),
        Color(0xFF38BDF8),
        Color(0xFFA78BFA)
    )
    val index = kotlin.math.abs(userId.hashCode()) % palette.size
    return palette[index]
}

@Composable
private fun MessageStatusIndicator(
    status: MessageStatus,
    onRetry: (() -> Unit)? = null
) {
    when (status) {
        MessageStatus.SENDING -> {
            Text(
                text = "·",
                color = AsterMuted.copy(alpha = 0.6f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics {
                    contentDescription = "Message sending"
                }
            )
        }
        MessageStatus.SENT -> {
            Text(
                text = "✓",
                color = AsterMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics {
                    contentDescription = "Message sent"
                }
            )
        }
        MessageStatus.DELIVERED -> {
            Text(
                text = "✓✓",
                color = AsterMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics {
                    contentDescription = "Message delivered"
                }
            )
        }
        MessageStatus.READ -> {
            Text(
                text = "✓✓",
                color = AsterOnline,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics {
                    contentDescription = "Message read"
                }
            )
        }
        MessageStatus.FAILED -> {
            Text(
                text = "!",
                color = AsterCrimson,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = (if (onRetry != null) Modifier.clickable(onClick = onRetry) else Modifier).semantics {
                    contentDescription = "Message failed"
                }
            )
        }
        MessageStatus.UNSENT -> {
            Text(
                text = "↩",
                color = AsterMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics {
                    contentDescription = "Message unsent"
                }
            )
        }
    }
}

@Composable
private fun MessageDetailDialog(
    message: Message,
    members: List<User>,
    localUserId: String?,
    isGroup: Boolean,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .asterDialogTransition()
                .clip(RoundedCornerShape(16.dp))
                .background(AsterSurfaceElevated)
                .border(0.75.dp, AsterBorder, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Message Details",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = AsterStar,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = "✕",
                        color = AsterMuted,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(4.dp)
                            .semantics { contentDescription = "Close dialog" }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Focused message context bubble anchored at the top
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AsterSurface)
                        .border(1.2.dp, AsterViolet.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = if (message.status == MessageStatus.UNSENT) "[This message was unsent]" else message.body,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (message.status == MessageStatus.UNSENT) AsterMuted else AsterStar,
                            fontSize = 14.sp,
                            fontStyle = if (message.status == MessageStatus.UNSENT) FontStyle.Italic else FontStyle.Normal
                        ),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                val sender = members.firstOrNull { it.id == message.senderId }
                val senderName = if (sender?.isDeleted == true) "Deleted User" else (sender?.displayName ?: message.senderId.removePrefix("user_").replaceFirstChar { it.uppercase() })
                Text(
                    text = "From: $senderName",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        fontSize = 12.sp
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                val fullDateFormat = remember { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()) }
                Text(
                    text = "Sent: ${fullDateFormat.format(Date(message.createdAt))}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = AsterMuted,
                        fontSize = 12.sp
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                val statusText = when (message.status) {
                    MessageStatus.SENDING -> "Status: Sending…"
                    MessageStatus.SENT -> "Status: Sent to server"
                    MessageStatus.DELIVERED -> "Status: Delivered"
                    MessageStatus.READ -> "Status: Read"
                    MessageStatus.FAILED -> "Status: Failed to send"
                    MessageStatus.UNSENT -> "Status: Unsent"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (message.status == MessageStatus.READ) AsterOnline else AsterMuted,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    )
                )

                if (isGroup) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = AsterBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    val otherMembers = members.filter { it.id != (localUserId ?: message.senderId) }
                    val readMembers = otherMembers.filter { it.id in message.readBy }
                    val unreadMembers = otherMembers.filter { it.id !in message.readBy }

                    Text(
                        text = "Read by (${readMembers.size}/${otherMembers.size})",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = AsterStar,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (readMembers.isEmpty()) {
                        Text(
                            text = "No one has read this message yet",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = AsterMuted,
                                fontSize = 12.sp
                            )
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (member in readMembers) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(AsterOnline)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (member.isDeleted) "Deleted User" else member.displayName,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = AsterStar,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Read",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = AsterOnline,
                                            fontSize = 10.sp
                                        )
                                    )
                                }
                            }
                        }
                    }

                    if (unreadMembers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Delivered to (${unreadMembers.size})",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = AsterMuted,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (member in unreadMembers) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(AsterMuted)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (member.isDeleted) "Deleted User" else member.displayName,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = AsterMuted,
                                            fontSize = 12.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageContextOverlay(
    message: Message,
    isOutgoing: Boolean,
    canUnsend: Boolean,
    isBlocked: Boolean = false,
    currentUserId: String? = null,
    onReactionSelected: (String) -> Unit = {},
    onApplyEffect: (String?) -> Unit = {},
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onViewInfo: () -> Unit,
    onCopyText: () -> Unit,
    onUnsend: () -> Unit,
    onDeleteForSelf: () -> Unit
) {
    val isReduced = LocalReducedMotion.current
    var isVisible by remember { mutableStateOf(false) }
    var showEffectSelector by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (isVisible) AsterMotionTokens.Scrim.Midnight else 0f,
        animationSpec = AsterMotion.tween(
            durationMillis = AsterMotionTokens.Duration.Standard,
            easing = AsterMotionTokens.EasingCurve.StandardDecelerate,
            reducedMotion = isReduced
        ),
        label = "ContextOverlayScrim"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .asterDialogTransition(initialScale = 0.94f, initialOffsetY = 16f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Floating Reaction Dock directly above focused message
                if (message.status != MessageStatus.UNSENT && !isBlocked) {
                    val myReactions = if (currentUserId != null) {
                        message.reactions.filter { it.value.contains(currentUserId) }.keys
                    } else emptySet()

                    AsterReactionDock(
                        onReactionSelected = { emoji ->
                            onReactionSelected(emoji)
                            onDismiss()
                        },
                        userReactions = myReactions
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Selected message focus bubble with physical elevation & lift
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            shadowElevation = if (isReduced) 0f else 14f * density
                        }
                        .clip(RoundedCornerShape(16.dp))
                        .background(AsterSurfaceElevated)
                        .border(1.5.dp, AsterViolet, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        if (message.status == MessageStatus.UNSENT) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(text = "↩", color = AsterMuted, fontSize = 14.sp)
                                Text(
                                    text = "This message was unsent",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = AsterMuted,
                                        fontSize = 14.sp,
                                        fontStyle = FontStyle.Italic
                                    )
                                )
                            }
                        } else {
                            AsterEmphasizedText(
                                text = message.body,
                                color = AsterStar,
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatBubbleTime(message.createdAt),
                                color = AsterMuted,
                                fontSize = 11.sp
                            )
                            if (isOutgoing) {
                                AsterMessageStatusTransition(status = message.status)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Context Action Menu Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(AsterSurface)
                        .border(0.75.dp, AsterBorder, RoundedCornerShape(16.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (message.status != MessageStatus.UNSENT && !isBlocked) {
                        ContextActionItem(
                            icon = "↩",
                            label = "Reply",
                            onClick = onReply
                        )
                    }

                    if (message.status != MessageStatus.UNSENT && !isBlocked) {
                        ContextActionItem(
                            icon = "✨",
                            label = if (message.effect != null) "Celestial Effect (${AsterReactionTokens.effectDisplayName(message.effect)})" else "Celestial Effect",
                            onClick = { showEffectSelector = !showEffectSelector }
                        )

                        AnimatedVisibility(visible = showEffectSelector) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                AsterCelestialEffectPicker(
                                    selectedEffect = message.effect,
                                    onSelectEffect = { effect ->
                                        onApplyEffect(effect)
                                        showEffectSelector = false
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }

                    ContextActionItem(
                        icon = "ℹ",
                        label = "View Info",
                        onClick = onViewInfo
                    )

                    if (message.status != MessageStatus.UNSENT) {
                        ContextActionItem(
                            icon = "📋",
                            label = "Copy Text",
                            onClick = onCopyText
                        )
                    }

                    if (canUnsend) {
                        ContextActionItem(
                            icon = "↩",
                            label = "Unsend",
                            labelColor = AsterCrimson,
                            onClick = onUnsend
                        )
                    }

                    ContextActionItem(
                        icon = "🗑",
                        label = "Delete for You",
                        labelColor = AsterCrimson,
                        onClick = onDeleteForSelf
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AsterElevated)
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Dismiss",
                        color = AsterStar,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextActionItem(
    icon: String,
    label: String,
    labelColor: Color = AsterStar,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon,
            fontSize = 16.sp,
            modifier = Modifier.width(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = labelColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun GroupActionsDialog(
    title: String,
    memberCount: Int,
    onDismiss: () -> Unit,
    onViewMembers: () -> Unit,
    onViewGroupInfo: () -> Unit,
    onExitGroup: () -> Unit
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
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = AsterStar,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "✕",
                        color = AsterMuted,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                GroupActionRow(
                    icon = "👥",
                    title = "People / Members",
                    subtitle = "$memberCount participants",
                    onClick = onViewMembers
                )

                Spacer(modifier = Modifier.height(10.dp))

                GroupActionRow(
                    icon = "ℹ",
                    title = "Group Info",
                    subtitle = "Constellation details and ID",
                    onClick = onViewGroupInfo
                )

                Spacer(modifier = Modifier.height(10.dp))

                GroupActionRow(
                    icon = "🚪",
                    title = "Exit Group",
                    subtitle = "Leave this constellation",
                    titleColor = AsterCrimson,
                    onClick = onExitGroup
                )
            }
        }
    }
}

@Composable
internal fun GroupMembersDialog(
    members: List<User>,
    localUserId: String?,
    memberRoles: Map<String, String> = emptyMap(),
    isLocalUserAdmin: Boolean = false,
    creatorUserId: String? = null,
    onDismiss: () -> Unit,
    onRemoveMember: (User) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
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
                    Text(
                        text = "People in Group (${members.size})",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = AsterStar,
                            fontWeight = FontWeight.Bold
                        )
                    )
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

                Spacer(modifier = Modifier.height(14.dp))

                if (members.isEmpty()) {
                    AsterEmptyState(
                        title = "No members",
                        description = "This group has no active members.",
                        icon = "✦",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(members, key = { it.id }) { member ->
                        val isSelf = member.id == localUserId
                        val role = memberRoles[member.id] ?: (if (member.id == creatorUserId) "CREATOR" else "MEMBER")
                        val isTargetAdmin = role in listOf("ADMIN", "CREATOR")

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AsterSurface)
                                .border(0.5.dp, AsterBorder, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                AsterAvatar(name = if (member.isDeleted) "Deleted User" else member.displayName, size = 36.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (member.isDeleted) "Deleted User" else member.displayName,
                                            color = AsterStar,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        if (isSelf) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "(You)",
                                                color = AsterViolet,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        if (isTargetAdmin && !member.isDeleted) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Admin ✦",
                                                color = AsterWarm,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (member.isDeleted) "This user no longer exists" else "@${member.username}",
                                        color = AsterMuted,
                                        fontSize = 11.sp,
                                        fontStyle = if (member.isDeleted) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal
                                    )
                                }
                            }

                            if (isLocalUserAdmin && !isSelf && !member.isDeleted && !isTargetAdmin) {
                                Text(
                                    text = "Remove",
                                    color = AsterCrimson,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onRemoveMember(member) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
internal fun GroupInfoDialog(
    title: String,
    conversationId: String,
    memberCount: Int,
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
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Group Information",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = AsterStar,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "✕",
                        color = AsterMuted,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(4.dp)
                    )
                }

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
                        Text("Name", color = AsterMuted, fontSize = 11.sp)
                        Text(title, color = AsterStar, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = AsterBorder)
                    Column {
                        Text("Type", color = AsterMuted, fontSize = 11.sp)
                        Text("Multi-node Constellation", color = AsterStar, fontSize = 14.sp)
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = AsterBorder)
                    Column {
                        Text("Members", color = AsterMuted, fontSize = 11.sp)
                        Text("$memberCount connected nodes", color = AsterStar, fontSize = 14.sp)
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = AsterBorder)
                    Column {
                        Text("Constellation ID", color = AsterMuted, fontSize = 11.sp)
                        Text(conversationId, color = AsterViolet, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

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
private fun GroupActionRow(
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

@Composable
private fun ChatComposer(
    inputText: String,
    isSending: Boolean,
    onInputTextChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    replyingToMessage: Message? = null,
    replyingToSenderName: String? = null,
    onCancelReply: () -> Unit = {},
    selectedEffect: String? = null,
    onSelectEffect: (String?) -> Unit = {},
    isEnabled: Boolean = true,
    disabledHint: String? = null,
    modifier: Modifier = Modifier
) {
    val isReduced = LocalReducedMotion.current
    val haptics = LocalAsterHaptics.current
    val canSend = isEnabled && inputText.isNotBlank() && !isSending
    var showEffectPicker by remember { mutableStateOf(false) }

    val sendButtonColor by animateColorAsState(
        targetValue = if (canSend) AsterViolet else AsterSurfaceElevated,
        animationSpec = AsterMotion.tween(
            durationMillis = AsterMotionTokens.Duration.Short,
            reducedMotion = isReduced
        ),
        label = "SendButtonBg"
    )

    val sendIconColor by animateColorAsState(
        targetValue = if (canSend) AsterNight else AsterMuted.copy(alpha = 0.5f),
        animationSpec = AsterMotion.tween(
            durationMillis = AsterMotionTokens.Duration.Short,
            reducedMotion = isReduced
        ),
        label = "SendIconColor"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AsterSurface)
            .border(
                width = 0.75.dp,
                color = if (replyingToMessage != null || selectedEffect != null) AsterViolet.copy(alpha = 0.6f) else AsterBorder,
                shape = RoundedCornerShape(22.dp)
            )
    ) {
        // Reply Preview Banner
        AnimatedVisibility(
            visible = replyingToMessage != null,
            enter = expandVertically(
                animationSpec = AsterMotion.tween(
                    durationMillis = AsterMotionTokens.Duration.Standard,
                    easing = AsterMotionTokens.EasingCurve.Emphasized,
                    reducedMotion = isReduced
                )
            ) + fadeIn(
                animationSpec = AsterMotion.tween(
                    durationMillis = AsterMotionTokens.Duration.Standard,
                    easing = AsterMotionTokens.EasingCurve.StandardDecelerate,
                    reducedMotion = isReduced
                )
            ),
            exit = shrinkVertically(
                animationSpec = AsterMotion.tween(
                    durationMillis = AsterMotionTokens.Duration.Short,
                    easing = AsterMotionTokens.EasingCurve.EmphasizedAccelerate,
                    reducedMotion = isReduced
                )
            ) + fadeOut(
                animationSpec = AsterMotion.tween(
                    durationMillis = AsterMotionTokens.Duration.Short,
                    easing = AsterMotionTokens.EasingCurve.StandardAccelerate,
                    reducedMotion = isReduced
                )
            )
        ) {
            if (replyingToMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.5.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(AsterViolet)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Replying to ${replyingToSenderName ?: "message"}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = AsterViolet,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                        )
                        Text(
                            text = if (replyingToMessage.status == MessageStatus.UNSENT) "This message was unsent" else replyingToMessage.body,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = AsterMuted,
                                fontSize = 12.sp,
                                fontStyle = if (replyingToMessage.status == MessageStatus.UNSENT) FontStyle.Italic else FontStyle.Normal
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onCancelReply),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            color = AsterMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Active Celestial Effect Indicator Banner
        AnimatedVisibility(
            visible = selectedEffect != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            if (selectedEffect != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${AsterReactionTokens.effectIcon(selectedEffect)} ${AsterReactionTokens.effectDisplayName(selectedEffect)} Effect Active",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = AsterViolet,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onSelectEffect(null) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "✕", color = AsterMuted, fontSize = 11.sp)
                    }
                }
            }
        }

        // Expandable Celestial Effect Selector
        AnimatedVisibility(
            visible = showEffectPicker,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                AsterCelestialEffectPicker(
                    selectedEffect = selectedEffect,
                    onSelectEffect = {
                        onSelectEffect(it)
                        showEffectPicker = false
                    }
                )
            }
        }

        // Input and Send Button Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Text Input
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (!isEnabled) {
                    Text(
                        text = disabledHint ?: "This user no longer exists",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = AsterMuted,
                            fontSize = 14.sp,
                            fontStyle = FontStyle.Italic
                        )
                    )
                } else {
                    if (inputText.isEmpty()) {
                        Text(
                            text = "Message...",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = AsterMuted,
                                fontSize = 14.sp
                            )
                        )
                    }

                    BasicTextField(
                        value = inputText,
                        onValueChange = onInputTextChanged,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = AsterStar,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(AsterViolet),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = if (canSend) ImeAction.Send else ImeAction.Default
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (canSend) {
                                    haptics.perform(AsterHapticEvent.Send)
                                    onSendMessage()
                                }
                            }
                        ),
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Message input" }
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Celestial Effect Button
            if (isEnabled) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (selectedEffect != null) AsterViolet.copy(alpha = 0.25f) else Color.Transparent)
                        .asterPressable(
                            pressedScale = 0.94f,
                            hapticEvent = AsterHapticEvent.Selection,
                            onClick = { showEffectPicker = !showEffectPicker }
                        )
                        .semantics { contentDescription = "Select celestial effect" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedEffect != null) AsterReactionTokens.effectIcon(selectedEffect) else "✨",
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            // Send Button (accessible touch target >= 40dp)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(sendButtonColor)
                    .asterPressable(
                        enabled = canSend,
                        pressedScale = 0.92f,
                        hapticEvent = AsterHapticEvent.Send,
                        onClick = onSendMessage
                    )
                    .semantics { contentDescription = "Send message" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↑",
                    color = sendIconColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun EmptyChatState(
    title: String,
    description: String = "Messages in this constellation are private, local-first, and encrypted.",
    modifier: Modifier = Modifier
) {
    AsterEmptyState(
        title = if (title == "Deleted User") title else "Connected with $title",
        description = description,
        icon = "✦",
        modifier = modifier
    )
}

private fun formatBubbleTime(epochMillis: Long): String {
    val format = SimpleDateFormat("h:mm a", Locale.getDefault())
    return format.format(Date(epochMillis))
}

@Composable
internal fun ChatUserProfileDialog(
    user: User,
    onDismiss: () -> Unit,
    onUnblockUser: (() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .asterDialogTransition()
                .clip(RoundedCornerShape(20.dp))
                .background(AsterSurfaceElevated)
                .border(
                    0.75.dp,
                    if (user.isBlocked) AsterCrimson.copy(alpha = 0.6f) else AsterBorder,
                    RoundedCornerShape(20.dp)
                )
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
                val isBlocked = user.isBlocked
                val dName = if (isDel) "Deleted User" else user.displayName
                val isOnline = !isDel && !isBlocked && user.isOnline

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
                    text = if (isDel) "This user no longer exists" else if (isBlocked) "Blocked User" else "@${user.username}",
                    color = if (isBlocked) AsterCrimson else AsterMuted,
                    fontSize = 14.sp,
                    fontStyle = if (isDel) FontStyle.Italic else FontStyle.Normal
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
                        Text(if (isDel) "—" else user.id, color = AsterViolet, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = AsterBorder)
                    Column {
                        Text("Relationship", color = AsterMuted, fontSize = 11.sp)
                        val relText = if (isDel) "Deleted Account" else if (isBlocked) "Blocked User" else "Direct Connection"
                        val relColor = if (isDel) AsterMuted else if (isBlocked) AsterCrimson else AsterOnline
                        Text(relText, color = relColor, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                    if (!isDel) {
                        HorizontalDivider(thickness = 0.5.dp, color = AsterBorder)
                        Column {
                            Text("Presence", color = AsterMuted, fontSize = 11.sp)
                            val presText = com.aster.app.domain.model.formatPresenceText(user.isOnline, user.lastSeen, false, isBlocked) ?: "Offline"
                            val presColor = if (isBlocked) AsterCrimson else if (user.isOnline) AsterOnline else AsterMuted
                            Text(presText, color = presColor, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                if (isBlocked && onUnblockUser != null) {
                    Button(
                        onClick = {
                            onUnblockUser()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AsterCrimson, contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(text = "Unblock User", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AsterViolet, contentColor = AsterNight),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(text = "Close", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
