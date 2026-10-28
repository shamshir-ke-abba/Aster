package com.aster.app.data.repository

import android.util.Log
import com.aster.app.data.local.dao.ConversationDao
import com.aster.app.data.local.dao.ConversationMemberDao
import com.aster.app.data.local.dao.DeletedMessageDao
import com.aster.app.data.local.dao.MessageDao
import com.aster.app.data.local.dao.UserDao
import com.aster.app.data.local.entities.ConversationEntity
import com.aster.app.data.local.entities.ConversationMemberEntity
import com.aster.app.data.local.entities.DeletedMessageEntity
import com.aster.app.data.local.entities.MessageEntity
import com.aster.app.data.local.entities.UserEntity
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ApiResult
import com.aster.app.data.network.ClientMessage
import com.aster.app.data.network.ConnectionState
import com.aster.app.domain.model.Message
import com.aster.app.domain.model.MessageStatus
import com.aster.app.domain.repository.MessageRepository
import com.aster.app.ui.notification.AsterNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val conversationMemberDao: ConversationMemberDao,
    private val userDao: UserDao,
    private val webSocketClient: AsterWebSocketClient,
    private val notificationManager: AsterNotificationManager? = null,
    private val apiClient: AsterApiClient? = null,
    private val deletedMessageDao: DeletedMessageDao? = null
) : MessageRepository {

    private val TAG = "MessageRepository"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSentTimestamp = 0L
    private val _messageReadsFlow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    init {
        // Coroutine 1: Observe server ACKs -> transition status from SENDING to SENT
        // Hardened: ignores ACKs for unknown messages, preserves terminal/future states, idempotent
        scope.launch {
            webSocketClient.serverAcks.collect { ack ->
                try {
                    val existing = messageDao.getMessageById(ack.messageId) ?: run {
                        Log.d(TAG, "Ignoring ACK for unknown messageId: ${ack.messageId}")
                        return@collect
                    }
                    if (existing.status.canTransitionTo(MessageStatus.SENT)) {
                        messageDao.updateMessageStatus(ack.messageId, MessageStatus.SENT)
                        Log.d(TAG, "Updated message ${ack.messageId} status to SENT")
                    } else {
                        Log.d(TAG, "Preserving status ${existing.status} for message ${ack.messageId}; ignored ACK regression")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Error or DB closed during serverAck: ${e.message}")
                }
            }
        }

        // Coroutine 2: Observe incoming relayed messages -> persist into Room and send ACK_RECEIVED
        // Hardened: uses DB-enforced uniqueness (insertMessageIfNotExists) to prevent duplicate local rows
        // Always sends ACK_RECEIVED back to server even on duplicate frame
        scope.launch {
            webSocketClient.incomingMessages.collect { msg ->
                try {
                    // 1. Ensure sender exists in DB to prevent foreign key constraint violations
                    val sender = userDao.getUserByIdOnce(msg.senderId)
                    if (sender == null) {
                        val displayName = msg.senderId.removePrefix("user_").replaceFirstChar { it.uppercase() }
                        userDao.insertUser(
                            UserEntity(
                                id = msg.senderId,
                                username = msg.senderId.removePrefix("user_"),
                                displayName = displayName,
                                isLocalUser = false,
                                isOnline = false
                            )
                        )
                    }

                    val localUser = userDao.getLocalUserOnce()
                    val localUserId = localUser?.id ?: webSocketClient.authStorage.getAuthenticatedUserId().orEmpty()

                    // 2. Resolve target conversation ID deterministically
                    val isGroup = msg.conversationId.startsWith("group_")

                    // Blocking guard: do not receive or notify messages from blocked users in 1:1 chats
                    if (!isGroup) {
                        val sender = userDao.getUserByIdOnce(msg.senderId)
                        if (sender?.isBlocked == true) {
                            Log.d(TAG, "Dropping message from blocked user ${msg.senderId}")
                            webSocketClient.sendAckReceived(msg.messageId)
                            return@collect
                        }
                    }

                    val targetConversationId = if (isGroup) {
                        msg.conversationId
                    } else {
                        val existingDirect = if (localUserId.isNotBlank()) {
                            conversationDao.findDirectConversation(localUserId, msg.senderId)
                        } else null

                        existingDirect?.id
                            ?: conversationDao.getConversationByIdOnce(msg.conversationId)?.id
                            ?: if (localUserId.isNotBlank()) {
                                com.aster.app.domain.repository.getCanonicalDirectConversationId(localUserId, msg.senderId)
                            } else {
                                msg.conversationId
                            }
                    }

                    // 3. Ensure conversation and members exist in DB to prevent foreign key constraint violations
                    val conv = conversationDao.getConversationByIdOnce(targetConversationId)
                    if (conv == null) {
                        if (isGroup) {
                            conversationDao.insertConversation(
                                ConversationEntity(
                                    id = targetConversationId,
                                    title = "Group",
                                    isGroup = true,
                                    createdAt = msg.timestamp,
                                    updatedAt = msg.timestamp
                                )
                            )
                            val members = mutableListOf(
                                ConversationMemberEntity(targetConversationId, msg.senderId, "MEMBER", msg.timestamp)
                            )
                            if (localUserId.isNotBlank()) {
                                members.add(ConversationMemberEntity(targetConversationId, localUserId, "MEMBER", msg.timestamp))
                            }
                            conversationMemberDao.insertMembers(members)
                            webSocketClient.getGroups()
                            webSocketClient.getGroupMembers(targetConversationId)
                        } else {
                            val senderDisplayName = sender?.displayName ?: msg.senderId.removePrefix("user_").replaceFirstChar { it.uppercase() }
                            conversationDao.insertConversation(
                                ConversationEntity(
                                    id = targetConversationId,
                                    title = senderDisplayName,
                                    isGroup = false,
                                    createdAt = msg.timestamp,
                                    updatedAt = msg.timestamp
                                )
                            )
                            val members = mutableListOf(
                                ConversationMemberEntity(targetConversationId, msg.senderId, "MEMBER", msg.timestamp)
                            )
                            if (localUserId.isNotBlank() && localUserId != msg.senderId) {
                                members.add(ConversationMemberEntity(targetConversationId, localUserId, "MEMBER", msg.timestamp))
                            }
                            conversationMemberDao.insertMembers(members)
                        }
                    } else {
                        // Ensure membership records exist for both participants
                        conversationMemberDao.insertMember(
                            ConversationMemberEntity(targetConversationId, msg.senderId, "MEMBER", msg.timestamp)
                        )
                        if (localUserId.isNotBlank() && localUserId != msg.senderId) {
                            conversationMemberDao.insertMember(
                                ConversationMemberEntity(targetConversationId, localUserId, "MEMBER", msg.timestamp)
                            )
                        }
                    }

                    val isActivelyViewed = (notificationManager?.isAppForeground == true) &&
                        (notificationManager.getActiveConversation() == targetConversationId)

                    // Tombstone guard: if message was deleted locally for self, do not recreate it
                    if (deletedMessageDao?.isDeleted(msg.messageId) != null) {
                        Log.d(TAG, "Message ${msg.messageId} was previously deleted locally; ignoring replay")
                        webSocketClient.sendAckReceived(msg.messageId)
                        return@collect
                    }

                    val initialStatus = if (isActivelyViewed) MessageStatus.READ else MessageStatus.DELIVERED

                    val entity = MessageEntity(
                        id = msg.messageId,
                        conversationId = targetConversationId,
                        senderId = msg.senderId,
                        body = msg.body,
                        createdAt = msg.timestamp,
                        status = initialStatus,
                        replyToId = msg.replyToId,
                        reactions = MessageEntity.serializeReactions(msg.reactions),
                        effect = msg.effect,
                        isEffectPlayed = false
                    )
                    val rowId = messageDao.insertMessageIfNotExists(entity)
                    if (rowId != -1L) {
                        conversationDao.updateConversationTimestamp(targetConversationId, msg.timestamp)
                        Log.i(TAG, "Inserted incoming message ${msg.messageId} into Room (conv: $targetConversationId)")

                        // Dispatch system notification if conversation is not currently being viewed on-screen,
                        // message is not sent by local user, and message is brand new (not a replay/duplicate)
                        if (!isActivelyViewed && msg.senderId != localUserId && notificationManager != null) {
                            val updatedConv = conversationDao.getConversationByIdOnce(targetConversationId)
                            val conversationTitle = updatedConv?.title ?: if (isGroup) "Group" else (sender?.displayName ?: "Unknown")
                            val senderDisplayName = sender?.displayName ?: msg.senderId.removePrefix("user_").replaceFirstChar { it.uppercase() }

                            notificationManager.showMessageNotification(
                                conversationId = targetConversationId,
                                conversationTitle = conversationTitle,
                                isGroup = isGroup,
                                senderDisplayName = senderDisplayName,
                                messageText = msg.body,
                                messageTimestamp = msg.timestamp
                            )
                        }
                    } else {
                        Log.d(TAG, "Duplicate message ${msg.messageId} already in Room; synchronizing latest reaction/effect state")
                        if (msg.reactions.isNotEmpty()) {
                            messageDao.updateMessageReactions(msg.messageId, MessageEntity.serializeReactions(msg.reactions))
                        }
                        if (!msg.effect.isNullOrBlank()) {
                            messageDao.updateMessageEffect(msg.messageId, msg.effect)
                        }
                    }
                    // Confirm receipt over network regardless of whether newly inserted or duplicate
                    webSocketClient.sendAckReceived(msg.messageId)
                    if (isActivelyViewed) {
                        webSocketClient.sendMarkRead(msg.messageId, targetConversationId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling incoming message ${msg.messageId}: ${e.message}", e)
                }
            }
        }

        // Coroutine 3: Observe connection state becoming CONNECTED -> retry any unsent (SENDING) messages
        // Ordered by created_at ASC, id ASC to preserve deterministic send sequence
        scope.launch {
            webSocketClient.connectionState.collect { state ->
                try {
                    if (state == ConnectionState.CONNECTED) {
                        val localUser = userDao.getLocalUserOnce()
                        val localUserId = localUser?.id ?: webSocketClient.authStorage.getAuthenticatedUserId().orEmpty()
                        if (localUserId.isNotBlank()) {
                            val pendingMessages = messageDao.getPendingMessages(localUserId)
                            Log.i(TAG, "[AsterLifecycle] Mailbox synchronization started: flushing ${pendingMessages.size} pending local messages")
                            var dispatchedCount = 0
                            for (pending in pendingMessages) {
                                val isGroup = pending.conversationId.startsWith("group_")
                                val recipientId = if (isGroup) {
                                    null
                                } else {
                                    val recipient = conversationMemberDao.getOtherMemberOnce(pending.conversationId, localUserId)
                                    val rId = recipient?.id ?: resolveRecipientId(pending.conversationId, localUserId)
                                    if (rId.isNullOrBlank()) null else rId
                                }
                                val sent = webSocketClient.sendMessage(
                                    ClientMessage.SendMessage(
                                        messageId = pending.id,
                                        conversationId = pending.conversationId,
                                        recipientId = recipientId,
                                        body = pending.body,
                                        timestamp = pending.createdAt,
                                        replyToId = pending.replyToId,
                                        effect = pending.effect
                                    )
                                )
                                if (sent) {
                                    dispatchedCount++
                                }
                            }
                            Log.i(TAG, "[AsterLifecycle] Mailbox synchronization completed: $dispatchedCount/${pendingMessages.size} pending messages dispatched")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Error or DB closed during connectionState change: ${e.message}")
                }
            }
        }

        // Coroutine 3.5: Observe server errors (e.g. BLOCKED) -> mark pending SENDING messages FAILED
        scope.launch {
            webSocketClient.errors.collect { err ->
                if (err.code == "BLOCKED") {
                    val pendingMessages = messageDao.getAllPendingMessages()
                    for (pending in pendingMessages) {
                        messageDao.updateMessageStatus(pending.id, MessageStatus.FAILED)
                    }
                }
            }
        }

        // Coroutine 4: Observe delivery events -> transition status from SENT to DELIVERED
        scope.launch {
            webSocketClient.deliveredEvents.collect { delivered ->
                try {
                    val existing = messageDao.getMessageById(delivered.messageId) ?: run {
                        Log.d(TAG, "Ignoring delivery confirmation for unknown messageId: ${delivered.messageId}")
                        return@collect
                    }
                    if (existing.status.canTransitionTo(MessageStatus.DELIVERED)) {
                        messageDao.updateMessageStatus(delivered.messageId, MessageStatus.DELIVERED)
                        Log.d(TAG, "Updated message ${delivered.messageId} status to DELIVERED")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Error or DB closed during deliveredEvent: ${e.message}")
                }
            }
        }

        // Coroutine 5: Observe read events -> transition status to READ and record reader
        scope.launch {
            webSocketClient.readEvents.collect { readEvent ->
                try {
                    val existing = messageDao.getMessageById(readEvent.messageId)
                    if (existing != null && existing.status.canTransitionTo(MessageStatus.READ)) {
                        messageDao.updateMessageStatus(readEvent.messageId, MessageStatus.READ)
                        Log.d(TAG, "Updated message ${readEvent.messageId} status to READ")
                    }
                    val current = _messageReadsFlow.value.toMutableMap()
                    val readers = (current[readEvent.messageId] ?: emptySet()).toMutableSet()
                    readers.add(readEvent.readerId)
                    current[readEvent.messageId] = readers
                    _messageReadsFlow.value = current
                } catch (e: Exception) {
                    Log.d(TAG, "Error or DB closed during readEvent: ${e.message}")
                }
            }
        }

        // Coroutine 6: Observe unsent events -> transition status to UNSENT
        scope.launch {
            webSocketClient.unsentEvents.collect { unsentEvent ->
                try {
                    val existing = messageDao.getMessageById(unsentEvent.messageId)
                    if (existing != null && existing.status.canTransitionTo(MessageStatus.UNSENT)) {
                        messageDao.markMessageAsUnsent(unsentEvent.messageId)
                        Log.d(TAG, "Updated message ${unsentEvent.messageId} status to UNSENT")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Error or DB closed during unsentEvent: ${e.message}")
                }
            }
        }

        // Coroutine 7: Observe reaction updates -> update message reactions in Room
        scope.launch {
            webSocketClient.reactionUpdates.collect { reactionEvent ->
                try {
                    val existing = messageDao.getMessageById(reactionEvent.messageId) ?: run {
                        Log.d(TAG, "Ignoring reaction update for unknown messageId: ${reactionEvent.messageId}")
                        return@collect
                    }
                    val updatedJson = if (reactionEvent.reactions.isNotEmpty()) {
                        MessageEntity.serializeReactions(reactionEvent.reactions)
                    } else {
                        // Apply delta
                        val currentReactions = MessageEntity.parseReactions(existing.reactions).toMutableMap()
                        val userList = (currentReactions[reactionEvent.emoji] ?: emptyList()).toMutableList()
                        if (reactionEvent.action == "REMOVED") {
                            userList.remove(reactionEvent.userId)
                            if (userList.isEmpty()) {
                                currentReactions.remove(reactionEvent.emoji)
                            } else {
                                currentReactions[reactionEvent.emoji] = userList
                            }
                        } else {
                            if (!userList.contains(reactionEvent.userId)) {
                                userList.add(reactionEvent.userId)
                            }
                            currentReactions[reactionEvent.emoji] = userList
                        }
                        MessageEntity.serializeReactions(currentReactions)
                    }
                    messageDao.updateMessageReactions(reactionEvent.messageId, updatedJson)
                    Log.i(TAG, "Synchronized reaction update for message ${reactionEvent.messageId}: $updatedJson")
                } catch (e: Exception) {
                    Log.d(TAG, "Error or DB closed during reactionUpdate: ${e.message}")
                }
            }
        }
    }

    fun cancelScope() {
        scope.cancel()
    }

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        val deletedIdsFlow: Flow<List<String>> = deletedMessageDao?.getDeletedMessageIds()
            ?: kotlinx.coroutines.flow.flowOf(emptyList())

        return combine(
            messageDao.getMessagesForConversation(conversationId),
            _messageReadsFlow,
            deletedIdsFlow
        ) { entities, readsMap, deletedIds ->
            val deletedSet = deletedIds.toSet()
            entities
                .filter { it.id !in deletedSet }
                .map { entity ->
                    val readers = readsMap[entity.id] ?: emptySet()
                    val domain = entity.toDomain()
                    val effectiveStatus = if (domain.status != MessageStatus.UNSENT && readers.isNotEmpty() && domain.status.canTransitionTo(MessageStatus.READ)) {
                        MessageStatus.READ
                    } else {
                        domain.status
                    }
                    domain.copy(
                        status = effectiveStatus,
                        readBy = readers
                    )
                }
        }
    }

    override suspend fun syncMessageReads(conversationId: String) {
        if (apiClient == null) return
        val result = apiClient.getMessageReads(conversationId)
        if (result is ApiResult.Success) {
            val current = _messageReadsFlow.value.toMutableMap()
            for (read in result.data) {
                val existing = messageDao.getMessageById(read.messageId)
                if (existing != null && existing.status.canTransitionTo(MessageStatus.READ)) {
                    messageDao.updateMessageStatus(read.messageId, MessageStatus.READ)
                }
                val set = (current[read.messageId] ?: emptySet()).toMutableSet()
                set.add(read.readerUserId)
                current[read.messageId] = set
            }
            _messageReadsFlow.value = current
        }
    }

    override suspend fun syncUnsentMessages(conversationId: String) {
        if (apiClient == null) return
        val result = apiClient.getUnsentMessages(conversationId)
        if (result is ApiResult.Success) {
            for (unsent in result.data) {
                val existing = messageDao.getMessageById(unsent.messageId)
                if (existing != null && existing.status.canTransitionTo(MessageStatus.UNSENT)) {
                    messageDao.markMessageAsUnsent(unsent.messageId)
                }
            }
        }
    }

    override suspend fun deleteForSelf(messageId: String) {
        deletedMessageDao?.insertDeletedMessage(
            DeletedMessageEntity(messageId = messageId, deletedAt = System.currentTimeMillis())
        )
        messageDao.deleteMessageById(messageId)
        Log.i(TAG, "Locally deleted message $messageId (tombstoned)")
    }

    override suspend fun unsendMessage(messageId: String, conversationId: String): Boolean {
        val existing = messageDao.getMessageById(messageId) ?: return false
        val now = System.currentTimeMillis()
        if (now - existing.createdAt > 5 * 60 * 1000L) {
            Log.w(TAG, "Cannot unsend message $messageId: exceeds 5-minute window")
            return false
        }
        val sent = webSocketClient.sendUnsend(messageId, conversationId)
        if (sent) {
            messageDao.markMessageAsUnsent(messageId)
        }
        return sent
    }

    override suspend fun sendMessage(
        conversationId: String,
        senderId: String,
        body: String,
        replyToId: String?,
        effect: String?
    ): Message {
        val messageId = UUID.randomUUID().toString()

        // Guarantee strictly monotonic timestamp per client device
        val now = System.currentTimeMillis()
        val timestamp = synchronized(this) {
            lastSentTimestamp = maxOf(now, lastSentTimestamp + 1)
            lastSentTimestamp
        }

        // 1. Ensure conversation and sender entities exist in Room before inserting message
        val recipientId = ensureConversationExists(conversationId, senderId, timestamp)

        // 2. Create message entity in SENDING state
        val sendingEntity = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = senderId,
            body = body,
            createdAt = timestamp,
            status = MessageStatus.SENDING,
            replyToId = replyToId,
            effect = effect,
            isEffectPlayed = false
        )

        // 3. Persist in Room (SENDING) immediately — user sees their message instantly
        messageDao.insertMessage(sendingEntity)
        conversationDao.updateConversationTimestamp(conversationId, timestamp)

        // 4. Attempt WebSocket dispatch
        val dispatchRecipientId = if (recipientId.isNullOrBlank()) null else recipientId
        if (dispatchRecipientId != null) {
            val targetUser = userDao.getUserByIdOnce(dispatchRecipientId)
            if (targetUser?.isBlocked == true) {
                Log.w(TAG, "Cannot send message: recipient $dispatchRecipientId is blocked")
                messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                return sendingEntity.copy(status = MessageStatus.FAILED).toDomain()
            }
        }
        webSocketClient.sendMessage(
            ClientMessage.SendMessage(
                messageId = messageId,
                conversationId = conversationId,
                recipientId = dispatchRecipientId,
                body = body,
                timestamp = timestamp,
                replyToId = replyToId,
                effect = effect
            )
        )

        // 5. Return message in SENDING state
        return sendingEntity.toDomain()
    }

    override suspend fun toggleReaction(messageId: String, emoji: String, userId: String): Boolean {
        val existing = messageDao.getMessageById(messageId) ?: return false
        return toggleReaction(messageId, existing.conversationId, emoji, userId)
    }

    override suspend fun toggleReaction(
        messageId: String,
        conversationId: String,
        emoji: String,
        userId: String
    ): Boolean {
        val existing = messageDao.getMessageById(messageId) ?: return false
        val currentReactions = MessageEntity.parseReactions(existing.reactions).toMutableMap()
        val userList = (currentReactions[emoji] ?: emptyList()).toMutableList()
        val isReacted: Boolean
        if (userList.contains(userId)) {
            userList.remove(userId)
            if (userList.isEmpty()) {
                currentReactions.remove(emoji)
            } else {
                currentReactions[emoji] = userList
            }
            isReacted = false
        } else {
            userList.add(userId)
            currentReactions[emoji] = userList
            isReacted = true
        }
        val newJson = MessageEntity.serializeReactions(currentReactions)
        messageDao.updateMessageReactions(messageId, newJson)

        val targetConvId = if (conversationId.isNotBlank()) conversationId else existing.conversationId
        if (!targetConvId.startsWith("group_")) {
            val recipientId = resolveRecipientId(targetConvId, userId)
            if (!recipientId.isNullOrBlank()) {
                val other = userDao.getUserByIdOnce(recipientId)
                if (other?.isBlocked == true) {
                    Log.w(TAG, "Cannot react to message: recipient $recipientId is blocked")
                    return false
                }
            }
        }
        webSocketClient.toggleReaction(messageId, targetConvId, emoji)
        return isReacted
    }

    override suspend fun setMessageEffect(messageId: String, effect: String?) {
        messageDao.updateMessageEffect(messageId, effect)
    }

    override suspend fun markEffectPlayed(messageId: String) {
        messageDao.markEffectPlayed(messageId)
    }

    private suspend fun ensureConversationExists(
        conversationId: String,
        senderId: String,
        timestamp: Long
    ): String? {
        val recipientId = resolveRecipientId(conversationId, senderId)

        // 1. Ensure sender exists in DB to prevent foreign key constraint violations
        if (userDao.getUserByIdOnce(senderId) == null) {
            val dName = senderId.removePrefix("user_").replaceFirstChar { it.uppercase() }
            userDao.insertUser(
                UserEntity(
                    id = senderId,
                    username = senderId.removePrefix("user_"),
                    displayName = dName,
                    isLocalUser = true,
                    isOnline = true
                )
            )
        }

        // 2. Ensure recipient user exists in DB if known
        if (!recipientId.isNullOrBlank() && userDao.getUserByIdOnce(recipientId) == null) {
            val dName = recipientId.removePrefix("user_").replaceFirstChar { it.uppercase() }
            userDao.insertUser(
                UserEntity(
                    id = recipientId,
                    username = recipientId.removePrefix("user_"),
                    displayName = dName,
                    isLocalUser = false,
                    isOnline = false
                )
            )
        }

        // 3. Ensure conversation exists in DB
        val existing = conversationDao.getConversationByIdOnce(conversationId)
        if (existing == null) {
            val isGroup = conversationId.startsWith("group_")
            val otherUser = if (!recipientId.isNullOrBlank()) userDao.getUserByIdOnce(recipientId) else null
            val convTitle = if (isGroup) {
                "Group"
            } else {
                otherUser?.displayName ?: "Direct Chat"
            }

            conversationDao.insertConversation(
                ConversationEntity(
                    id = conversationId,
                    title = convTitle,
                    isGroup = isGroup,
                    createdAt = timestamp,
                    updatedAt = timestamp
                )
            )

            val members = mutableListOf(
                ConversationMemberEntity(
                    conversationId = conversationId,
                    userId = senderId,
                    role = "MEMBER",
                    joinedAt = timestamp
                )
            )
            if (!recipientId.isNullOrBlank() && recipientId != senderId) {
                members.add(
                    ConversationMemberEntity(
                        conversationId = conversationId,
                        userId = recipientId,
                        role = "MEMBER",
                        joinedAt = timestamp
                    )
                )
            }
            conversationMemberDao.insertMembers(members)
            Log.i(TAG, "Created missing conversation row for $conversationId with ${members.size} members")
        }

        return recipientId
    }

    override suspend fun retryMessage(messageId: String): Boolean {
        val message = messageDao.getMessageById(messageId) ?: return false
        if (message.status != MessageStatus.FAILED && message.status != MessageStatus.SENDING) {
            return false
        }
        val recipientId = resolveRecipientId(message.conversationId, message.senderId)
        messageDao.updateMessageStatus(messageId, MessageStatus.SENDING)
        return webSocketClient.sendMessage(
            ClientMessage.SendMessage(
                messageId = message.id,
                conversationId = message.conversationId,
                recipientId = recipientId,
                body = message.body,
                timestamp = message.createdAt,
                replyToId = message.replyToId,
                effect = message.effect
            )
        )
    }

    private suspend fun resolveRecipientId(conversationId: String, senderId: String): String? {
        if (conversationId.startsWith("group_")) return null
        val recipient = conversationMemberDao.getOtherMemberOnce(conversationId, senderId)
            ?: conversationMemberDao.getOtherMemberOnce(conversationId)
        if (recipient != null && recipient.id.isNotBlank()) {
            return recipient.id
        }
        // Fallback for deterministic conv_userA_userB direct conversations
        if (conversationId.startsWith("conv_")) {
            val match = Regex("(user_[a-zA-Z0-9_]+)_(user_[a-zA-Z0-9_]+)").find(conversationId.removePrefix("conv_"))
            if (match != null) {
                val (u1, u2) = match.destructured
                return if (u1 == senderId) u2 else u1
            }
        }
        return ""
    }

    override suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        val current = messageDao.getMessageById(messageId) ?: run {
            Log.d(TAG, "updateMessageStatus: message $messageId not found")
            return
        }
        if (current.status == status) return // Idempotent no-op
        if (current.status.canTransitionTo(status)) {
            messageDao.updateMessageStatus(messageId, status)
        } else {
            Log.w(TAG, "Rejected invalid status transition: ${current.status} -> $status for message $messageId")
        }
    }
}
