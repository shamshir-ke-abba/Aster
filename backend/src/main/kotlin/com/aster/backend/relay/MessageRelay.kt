package com.aster.backend.relay

import com.aster.backend.database.DeliveryState
import com.aster.backend.database.MailboxDatabase
import com.aster.backend.database.MailboxMessage
import com.aster.backend.protocol.ClientMessage
import com.aster.backend.protocol.ProtocolLimits
import com.aster.backend.protocol.ServerMessage
import com.aster.backend.protocol.asterJson
import com.aster.backend.session.SessionManager
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

class MessageRelay(
    private val sessionManager: SessionManager,
    private val mailboxDatabase: MailboxDatabase = MailboxDatabase()
) {
    private val logger = LoggerFactory.getLogger(MessageRelay::class.java)

    suspend fun handleMessage(session: WebSocketSession, text: String) {
        if (text.length > ProtocolLimits.MAX_FRAME_SIZE) {
            logger.warn("Payload size ${text.length} exceeds max frame size ${ProtocolLimits.MAX_FRAME_SIZE}")
            sendServerMessage(
                session,
                ServerMessage.Error(
                    code = "PAYLOAD_TOO_LARGE",
                    message = "Frame exceeds maximum size of ${ProtocolLimits.MAX_FRAME_SIZE} bytes"
                )
            )
            return
        }

        val jsonElement = try {
            asterJson.parseToJsonElement(text)
        } catch (e: Exception) {
            logger.warn("Malformed JSON syntax received: ${e.message}")
            sendServerMessage(
                session,
                ServerMessage.Error(
                    code = "MALFORMED_JSON",
                    message = "Invalid JSON syntax: ${e.message}"
                )
            )
            return
        }

        val jsonObject = jsonElement as? JsonObject ?: run {
            logger.warn("Payload is not a JSON object")
            sendServerMessage(
                session,
                ServerMessage.Error(
                    code = "MALFORMED_JSON",
                    message = "Payload must be a JSON object"
                )
            )
            return
        }

        val typePrimitive = jsonObject["type"] as? JsonPrimitive
        val type = typePrimitive?.contentOrNull
        if (type == null) {
            logger.warn("Received JSON without 'type' field: $text")
            sendServerMessage(
                session,
                ServerMessage.Error(
                    code = "MISSING_TYPE",
                    message = "Field 'type' is required"
                )
            )
            return
        }

        val clientMsg: ClientMessage = try {
            asterJson.decodeFromJsonElement(ClientMessage.serializer(), jsonObject)
        } catch (e: Exception) {
            if (type !in listOf("AUTH", "SEND_MESSAGE", "ACK_RECEIVED", "PING")) {
                logger.warn("Unknown message type: '$type'")
                sendServerMessage(
                    session,
                    ServerMessage.Error(
                        code = "UNKNOWN_TYPE",
                        message = "Unknown message type: '$type'"
                    )
                )
            } else {
                logger.warn("Malformed fields for message type '$type': ${e.message}")
                sendServerMessage(
                    session,
                    ServerMessage.Error(
                        code = "MALFORMED_JSON",
                        message = "Missing or invalid fields for $type: ${e.message}"
                    )
                )
            }
            return
        }

        when (clientMsg) {
            is ClientMessage.Auth -> handleAuth(session, clientMsg)
            is ClientMessage.Ping -> handlePing(session, clientMsg)
            is ClientMessage.SendMessage -> handleSendMessage(session, clientMsg)
            is ClientMessage.AckReceived -> handleAckReceived(session, clientMsg)
        }
    }

    private suspend fun handleAuth(session: WebSocketSession, auth: ClientMessage.Auth) {
        val userId = auth.userId.trim()
        if (!ProtocolLimits.USER_ID_REGEX.matches(userId)) {
            logger.warn("Authentication failed for invalid userId format: '$userId'")
            sendServerMessage(
                session,
                ServerMessage.AuthError("Invalid userId format. Must match ^user_[a-zA-Z0-9_]{1,64}$")
            )
            return
        }

        sessionManager.registerSession(userId, session)
        logger.info("User authenticated: $userId")
        sendServerMessage(session, ServerMessage.AuthOk(userId = userId))

        // Deliver any offline queued messages from SQLite mailbox
        deliverPendingMailboxMessages(userId, session)
    }

    private suspend fun deliverPendingMailboxMessages(userId: String, session: WebSocketSession) {
        val pendingMessages = mailboxDatabase.getPendingMessagesForRecipient(userId)
        if (pendingMessages.isNotEmpty()) {
            logger.info("Delivering ${pendingMessages.size} queued mailbox message(s) to reconnected user '$userId'")
            for (m in pendingMessages) {
                val relayPayload = ServerMessage.RelayMessage(
                    messageId = m.messageId,
                    conversationId = m.conversationId,
                    senderId = m.senderUserId,
                    body = m.body,
                    timestamp = m.clientTimestamp
                )
                try {
                    sendServerMessage(session, relayPayload)
                    mailboxDatabase.updateDeliveryState(m.messageId, DeliveryState.DELIVERED)
                    logger.info("Queued message ${m.messageId} delivered to $userId")
                } catch (e: Exception) {
                    logger.error("Failed delivering queued message ${m.messageId} to $userId: ${e.message}")
                    break
                }
            }
        }
    }

    private suspend fun handlePing(session: WebSocketSession, ping: ClientMessage.Ping) {
        sendServerMessage(session, ServerMessage.Pong(timestamp = System.currentTimeMillis()))
    }

    private suspend fun handleSendMessage(session: WebSocketSession, msg: ClientMessage.SendMessage) {
        val senderId = sessionManager.getUserForSession(session)
        if (senderId == null) {
            logger.warn("Unauthenticated client attempted to send message")
            sendServerMessage(
                session,
                ServerMessage.Error(
                    code = "UNAUTHENTICATED",
                    message = "Session must be authenticated before sending messages"
                )
            )
            return
        }

        // Validate messageId
        if (msg.messageId.isBlank() || msg.messageId.length > ProtocolLimits.MAX_MESSAGE_ID_LENGTH || !ProtocolLimits.MESSAGE_ID_REGEX.matches(msg.messageId)) {
            sendServerMessage(session, ServerMessage.Error("INVALID_MESSAGE_ID", "messageId must match ^[a-zA-Z0-9_-]{1,128}$"))
            return
        }

        // Validate conversationId
        if (msg.conversationId.isBlank() || msg.conversationId.length > ProtocolLimits.MAX_CONVERSATION_ID_LENGTH || !ProtocolLimits.CONVERSATION_ID_REGEX.matches(msg.conversationId)) {
            sendServerMessage(session, ServerMessage.Error("INVALID_CONVERSATION_ID", "conversationId must match ^[a-zA-Z0-9_-]{1,64}$"))
            return
        }

        // Validate recipientId
        if (msg.recipientId.isBlank() || msg.recipientId.length > ProtocolLimits.MAX_USER_ID_LENGTH || !ProtocolLimits.USER_ID_REGEX.matches(msg.recipientId)) {
            sendServerMessage(session, ServerMessage.Error("INVALID_RECIPIENT_ID", "recipientId must match ^user_[a-zA-Z0-9_]{1,64}$"))
            return
        }

        // Prevent self-messaging
        if (msg.recipientId == senderId) {
            sendServerMessage(session, ServerMessage.Error("SELF_MESSAGE_NOT_ALLOWED", "Cannot send message to yourself"))
            return
        }

        // Validate body
        if (msg.body.isBlank() || msg.body.length > ProtocolLimits.MAX_BODY_LENGTH) {
            sendServerMessage(session, ServerMessage.Error("INVALID_BODY", "body must be 1-${ProtocolLimits.MAX_BODY_LENGTH} non-blank characters"))
            return
        }

        // Validate timestamp
        val now = System.currentTimeMillis()
        if (msg.timestamp < ProtocolLimits.MIN_TIMESTAMP || msg.timestamp > now + ProtocolLimits.MAX_FUTURE_DRIFT_MS) {
            sendServerMessage(session, ServerMessage.Error("INVALID_TIMESTAMP", "timestamp must be between 2020-01-01 and 24 hours into future"))
            return
        }

        // Check deduplication (both memory LRU and durable SQLite source of truth)
        val isDuplicate = sessionManager.isDuplicateMessage(msg.messageId) || mailboxDatabase.hasMessage(msg.messageId)

        if (isDuplicate) {
            sessionManager.recordSeenMessage(msg.messageId)
            val ackTimestamp = System.currentTimeMillis()
            sendServerMessage(
                session,
                ServerMessage.MessageAccepted(
                    messageId = msg.messageId,
                    timestamp = ackTimestamp
                )
            )
            logger.info("Duplicate message ${msg.messageId} acknowledged idempotently; skipped relay")
            return
        }

        // Persist new message to SQLite mailbox in QUEUED state
        val serverReceivedTimestamp = System.currentTimeMillis()
        val mailboxMsg = MailboxMessage(
            messageId = msg.messageId,
            conversationId = msg.conversationId,
            senderUserId = senderId,
            recipientUserId = msg.recipientId,
            body = msg.body,
            clientTimestamp = msg.timestamp,
            serverReceivedTimestamp = serverReceivedTimestamp,
            deliveryState = DeliveryState.QUEUED
        )
        mailboxDatabase.enqueueMessage(mailboxMsg)
        sessionManager.recordSeenMessage(msg.messageId)

        // Always acknowledge sender immediately
        sendServerMessage(
            session,
            ServerMessage.MessageAccepted(
                messageId = msg.messageId,
                timestamp = serverReceivedTimestamp
            )
        )
        logger.info("Message ${msg.messageId} accepted and queued from $senderId -> ${msg.recipientId}")

        // Relay to recipient if currently online
        val recipientSessions = sessionManager.getSessions(msg.recipientId)
        if (recipientSessions.isEmpty()) {
            logger.info("Recipient ${msg.recipientId} is offline; message ${msg.messageId} remains stored in mailbox")
        } else {
            val relayPayload = ServerMessage.RelayMessage(
                messageId = msg.messageId,
                conversationId = msg.conversationId,
                senderId = senderId,
                body = msg.body,
                timestamp = msg.timestamp
            )
            var delivered = false
            for (recipientSession in recipientSessions) {
                try {
                    sendServerMessage(recipientSession, relayPayload)
                    delivered = true
                    logger.info("Message ${msg.messageId} relayed to online recipient ${msg.recipientId}")
                } catch (e: Exception) {
                    logger.error("Failed to relay message ${msg.messageId} to recipient session: ${e.message}")
                }
            }
            if (delivered) {
                mailboxDatabase.updateDeliveryState(msg.messageId, DeliveryState.DELIVERED)
            }
        }
    }

    private suspend fun handleAckReceived(session: WebSocketSession, ack: ClientMessage.AckReceived) {
        val receiverId = sessionManager.getUserForSession(session)
        if (ack.messageId.isBlank() || ack.messageId.length > ProtocolLimits.MAX_MESSAGE_ID_LENGTH) {
            logger.warn("Invalid ACK_RECEIVED messageId: '${ack.messageId}'")
            return
        }
        mailboxDatabase.updateDeliveryState(ack.messageId, DeliveryState.ACKED)
        logger.info("Client $receiverId acknowledged receipt of message ${ack.messageId} (state -> ACKED)")
    }

    suspend fun sendServerMessage(session: WebSocketSession, serverMsg: ServerMessage) {
        val json = asterJson.encodeToString(ServerMessage.serializer(), serverMsg)
        session.send(Frame.Text(json))
    }

    fun handleDisconnect(session: WebSocketSession) {
        val userId = sessionManager.removeSession(session)
        if (userId != null) {
            logger.info("User disconnected: $userId (remaining active users: ${sessionManager.activeUserCount()})")
        }
    }
}
