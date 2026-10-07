package com.aster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aster.app.domain.model.Message
import com.aster.app.domain.model.MessageStatus
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

    ChatScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onInputTextChanged = viewModel::onInputTextChanged,
        onSendMessage = viewModel::sendMessage,
        modifier = modifier
    )
}

@Composable
fun ChatScreenContent(
    uiState: ChatUiState,
    onBackClick: () -> Unit,
    onInputTextChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val imeInsets = WindowInsets.ime
    val navBarInsets = WindowInsets.navigationBars
    val density = LocalDensity.current

    // Bottom insets take either the IME height or the navigation bar height
    val bottomPadding = with(density) {
        val imeBottom = imeInsets.getBottom(this)
        val navBottom = navBarInsets.getBottom(this)
        maxOf(imeBottom, navBottom).toDp()
    }

    val listState = rememberLazyListState()

    // Scroll to bottom when new messages arrive
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

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
                isOnline = uiState.otherUser?.isOnline ?: false,
                onBackClick = onBackClick
            )

            HorizontalDivider(
                thickness = 0.5.dp,
                color = AsterElevated
            )

            // Message list or empty state
            if (uiState.messages.isEmpty() && !uiState.isLoading) {
                EmptyChatState(
                    title = uiState.conversation?.title ?: "Conversation",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = uiState.messages,
                        key = { it.id }
                    ) { message ->
                        val isOutgoing = message.senderId == uiState.localUser?.id
                        MessageBubble(
                            message = message,
                            isOutgoing = isOutgoing
                        )
                    }
                }
            }

            // Message Composer
            ChatComposer(
                inputText = uiState.inputText,
                isSending = uiState.isSending,
                onInputTextChanged = onInputTextChanged,
                onSendMessage = onSendMessage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    isOnline: Boolean,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back navigation button (min touch target >= 48dp)
        Box(
            modifier = Modifier
                .size(48.dp)
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

        Spacer(modifier = Modifier.width(8.dp))

        // Conversation Title & Presence Indicator
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = AsterStar,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Presence indicator
            if (isOnline) {
                Text(
                    text = "✦",
                    color = AsterViolet,
                    fontSize = 14.sp
                )
            } else {
                Text(
                    text = "·",
                    color = AsterGlow.copy(alpha = 0.5f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Aster emblem accent
        AsterLogo(size = 22.dp)
        Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier
) {
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (isOutgoing) AsterElevated else AsterSurface
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 60.dp, max = 290.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                // Message body
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = AsterStar,
                        fontSize = 15.sp,
                        lineHeight = 21.sp
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Timestamp & status
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = formatBubbleTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = AsterGlow.copy(alpha = 0.55f),
                            fontSize = 10.sp
                        )
                    )

                    if (isOutgoing) {
                        MessageStatusIndicator(status = message.status)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageStatusIndicator(status: MessageStatus) {
    when (status) {
        MessageStatus.SENDING -> {
            Text(
                text = "·",
                color = AsterGlow.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
        }
        MessageStatus.SENT -> {
            Text(
                text = "✦",
                color = AsterGlow.copy(alpha = 0.7f),
                fontSize = 9.sp
            )
        }
        MessageStatus.DELIVERED -> {
            Text(
                text = "✦✦",
                color = AsterGlow,
                fontSize = 8.sp
            )
        }
        MessageStatus.READ -> {
            Text(
                text = "✦✦",
                color = AsterViolet,
                fontSize = 8.sp
            )
        }
        MessageStatus.FAILED -> {
            Text(
                text = "!",
                color = MaterialTheme.colorScheme.error,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ChatComposer(
    inputText: String,
    isSending: Boolean,
    onInputTextChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSend = inputText.isNotBlank() && !isSending

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(AsterSurface)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Text Input
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (inputText.isEmpty()) {
                Text(
                    text = "Message...",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = AsterGlow.copy(alpha = 0.5f),
                        fontSize = 15.sp
                    )
                )
            }

            BasicTextField(
                value = inputText,
                onValueChange = onInputTextChanged,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = AsterStar,
                    fontSize = 15.sp
                ),
                cursorBrush = SolidColor(AsterViolet),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = if (canSend) ImeAction.Send else ImeAction.Default
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (canSend) {
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

        // Send Button (min touch target >= 44dp)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (canSend) AsterViolet else AsterElevated)
                .clickable(
                    enabled = canSend,
                    onClick = onSendMessage
                )
                .semantics { contentDescription = "Send message" },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "↑",
                color = if (canSend) AsterNight else AsterGlow.copy(alpha = 0.4f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EmptyChatState(
    title: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AsterLogo(size = 36.dp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Connected with $title",
            style = MaterialTheme.typography.bodyLarge.copy(
                color = AsterStar,
                fontWeight = FontWeight.Normal
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Messages in this constellation are private and stored locally.",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = AsterGlow.copy(alpha = 0.6f)
            )
        )
    }
}

private fun formatBubbleTime(epochMillis: Long): String {
    val format = SimpleDateFormat("h:mm a", Locale.getDefault())
    return format.format(Date(epochMillis))
}
