package com.aster.app.data.repository

import android.util.Log
import com.aster.app.data.local.dao.ConversationDao
import com.aster.app.data.local.dao.ConversationMemberDao
import com.aster.app.data.local.dao.MessageDao
import com.aster.app.data.local.dao.UserDao
import com.aster.app.data.local.entities.MessageEntity
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ClientMessage
import com.aster.app.data.network.ConnectionState
import com.aster.app.domain.model.Message
import com.aster.app.domain.model.MessageStatus
import com.aster.app.domain.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
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
    private val webSocketClient: AsterWebSocketClient
) : MessageRepository {

    private val TAG = "MessageRepository"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSentTimestamp = 0L

    init {
        // Coroutine 1: Observe server ACKs -> transition status from SENDING to SENT
        // Hardened: ignores ACKs for unknown messages, preserves terminal/future states, idempotent
        scope.launch {
            webSocketClient.serverAcks.collect { ack ->
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
            }
        }

        // Coroutine 2: Observe incoming relayed messages -> persist into Room and send ACK_RECEIVED
        // Hardened: uses DB-enforced uniqueness (insertMessageIfNotExists) to prevent duplicate local rows
        // Always sends ACK_RECEIVED back to server even on duplicate frame
        scope.launch {
            webSocketClient.incomingMessages.collect { msg ->
                val entity = MessageEntity(
                    id = msg.messageId,
                    conversationId = msg.conversationId,
                    senderId = msg.senderId,
                    body = msg.body,
                    createdAt = msg.timestamp,
                    status = MessageStatus.DELIVERED
                )
                val rowId = messageDao.insertMessageIfNotExists(entity)
                if (rowId != -1L) {
                    conversationDao.updateConversationTimestamp(msg.conversationId, msg.timestamp)
                    Log.i(TAG, "Inserted incoming message ${msg.messageId} into Room")
                } else {
                    Log.i(TAG, "Duplicate incoming message ${msg.messageId} ignored by Room uniqueness")
                }
                // Confirm receipt over network regardless of whether newly inserted or duplicate
                webSocketClient.sendAckReceived(msg.messageId)
            }
        }

        // Coroutine 3: Observe connection state becoming CONNECTED -> retry any unsent (SENDING) messages
        // Ordered by created_at ASC, id ASC to preserve deterministic send sequence
        scope.launch {
            webSocketClient.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    val localUser = userDao.getLocalUserOnce()
                    val localUserId = localUser?.id ?: "user_saad"
                    val pendingMessages = messageDao.getPendingMessages(localUserId)
                    for (pending in pendingMessages) {
                        val recipient = conversationMemberDao.getOtherMemberOnce(pending.conversationId)
                        val recipientId = recipient?.id ?: ""
                        webSocketClient.sendMessage(
                            ClientMessage.SendMessage(
                                messageId = pending.id,
                                conversationId = pending.conversationId,
                                recipientId = recipientId,
                                body = pending.body,
                                timestamp = pending.createdAt
                            )
                        )
                    }
                }
            }
        }
    }

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun sendMessage(
        conversationId: String,
        senderId: String,
        body: String,
        replyToId: String?
    ): Message {
        val messageId = UUID.randomUUID().toString()

        // Guarantee strictly monotonic timestamp per client device
        val now = System.currentTimeMillis()
        val timestamp = synchronized(this) {
            lastSentTimestamp = maxOf(now, lastSentTimestamp + 1)
            lastSentTimestamp
        }

        // 1. Create message entity in SENDING state
        val sendingEntity = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = senderId,
            body = body,
            createdAt = timestamp,
            status = MessageStatus.SENDING,
            replyToId = replyToId
        )

        // 2. Persist in Room (SENDING) immediately — user sees their message instantly
        messageDao.insertMessage(sendingEntity)
        conversationDao.updateConversationTimestamp(conversationId, timestamp)

        // 3. Attempt WebSocket dispatch
        val recipient = conversationMemberDao.getOtherMemberOnce(conversationId)
        val recipientId = recipient?.id ?: ""
        webSocketClient.sendMessage(
            ClientMessage.SendMessage(
                messageId = messageId,
                conversationId = conversationId,
                recipientId = recipientId,
                body = body,
                timestamp = timestamp
            )
        )

        // 4. Return message in SENDING state
        return sendingEntity.toDomain()
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
