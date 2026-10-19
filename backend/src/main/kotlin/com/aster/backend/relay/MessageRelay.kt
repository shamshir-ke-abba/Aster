package com.aster.backend.relay

import com.aster.backend.database.DeliveryState
import com.aster.backend.database.MailboxDatabase
import com.aster.backend.database.MailboxMessage
import com.aster.backend.protocol.ClientMessage
import com.aster.backend.protocol.GroupMemberSummary
import com.aster.backend.protocol.GroupSummary
import com.aster.backend.protocol.ProtocolLimits
import com.aster.backend.protocol.ServerMessage
import com.aster.backend.protocol.asterJson
import com.aster.backend.session.SessionManager
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
import com.aster.backend.push.PushGateway
import com.aster.backend.push.NoOpPushGateway
import com.aster.backend.push.PushPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

class MessageRelay(
    private val sessionManager: SessionManager,
    private val mailboxDatabase: MailboxDatabase = MailboxDatabase(),
    private val pushGateway: PushGateway = NoOpPushGateway()
) {
    private val logger = LoggerFactory.getLogger(MessageRelay::class.java)

    private val knownTypes = setOf(
        "AUTH", "SEND_MESSAGE", "ACK_RECEIVED", "PING",
        "CREATE_GROUP", "GET_GROUPS", "GET_GROUP_MEMBERS",
        "ADD_GROUP_MEMBER", "REMOVE_GROUP_MEMBER", "LEAVE_GROUP", "RENAME_GROUP"
    )

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
            if (type !in knownTypes) {
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
            is ClientMessage.CreateGroup -> handleCreateGroup(session, clientMsg)
            is ClientMessage.GetGroups -> handleGetGroups(session, clientMsg)
            is ClientMessage.GetGroupMembers -> handleGetGroupMembers(session, clientMsg)
            is ClientMessage.AddGroupMember -> handleAddGroupMember(session, clientMsg)
            is ClientMessage.RemoveGroupMember -> handleRemoveGroupMember(session, clientMsg)
            is ClientMessage.LeaveGroup -> handleLeaveGroup(session, clientMsg)
            is ClientMessage.RenameGroup -> handleRenameGroup(session, clientMsg)
        }
    }

    private suspend fun handleAuth(session: WebSocketSession, auth: ClientMessage.Auth) {
        val token = auth.token.trim()
        if (token.isBlank()) {
            logger.warn("Authentication failed: empty or blank token presented")
            sendServerMessage(
                session,
                ServerMessage.AuthError("Missing or blank authentication token")
            )
            return
        }

        val record = mailboxDatabase.validateToken(token)
        if (record == null) {
            logger.warn("Authentication failed: invalid or revoked token presented")
            sendServerMessage(
                session,
                ServerMessage.AuthError("Invalid or revoked authentication token")
            )
            return
        }

        val authenticatedUserId = record.userId
        if (auth.userId != null && auth.userId != authenticatedUserId) {
            logger.warn(
                "Client claimed userId '${auth.userId}', but credential belongs to '$authenticatedUserId'. " +
                "Overriding with authenticated identity."
            )
        }

        sessionManager.registerSession(authenticatedUserId, session)
        logger.info("User authenticated: $authenticatedUserId (device: ${record.deviceId})")
        sendServerMessage(session, ServerMessage.AuthOk(userId = authenticatedUserId))

        // Deliver any offline queued messages from SQLite mailbox
        deliverPendingMailboxMessages(authenticatedUserId, session)
    }

    private suspend fun deliverPendingMailboxMessages(userId: String, session: WebSocketSession) {
        val pendingMessages = mailboxDatabase.getPendingMessagesForRecipient(userId)
        if (pendingMessages.isNotEmpty()) {
            logger.info("Delivering ${pendingMessages.size} queued mailbox message(s) to reconnected user '$userId'")
            for (m in pendingMessages) {
                if (m.conversationId.startsWith("group_") && !mailboxDatabase.isMember(m.conversationId, userId)) {
                    logger.warn("Skipping delivery of group message ${m.messageId} to $userId: user is no longer an active member of ${m.conversationId}")
                    continue
                }
                val relayPayload = ServerMessage.RelayMessage(
                    messageId = m.messageId,
                    conversationId = m.conversationId,
                    senderId = m.senderUserId,
                    body = m.body,
                    timestamp = m.clientTimestamp
                )
                try {
                    sendServerMessage(session, relayPayload)
                    mailboxDatabase.updateDeliveryState(m.messageId, userId, DeliveryState.DELIVERED)
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

        if (msg.senderId != null && msg.senderId != senderId) {
            logger.warn(
                "Client attempted to forge senderId '${msg.senderId}' on session authenticated as '$senderId'. " +
                "Enforcing authenticated sender identity."
            )
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

        val isGroup = msg.conversationId.startsWith("group_")

        if (isGroup) {
            // Group Message Flow
            val group = mailboxDatabase.getGroup(msg.conversationId)
            if (group == null || !group.isActive) {
                sendServerMessage(session, ServerMessage.Error("GROUP_NOT_FOUND", "Group does not exist or is inactive"))
                return
            }

            if (!mailboxDatabase.isMember(msg.conversationId, senderId)) {
                sendServerMessage(session, ServerMessage.Error("UNAUTHORIZED_GROUP_ACCESS", "You are not an active member of this group"))
                return
            }

            // Deduplication check
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
                logger.info("Duplicate group message ${msg.messageId} acknowledged idempotently; skipped relay")
                return
            }

            // Backend resolves active membership and fan-out
            val activeMembers = mailboxDatabase.getActiveMembers(msg.conversationId)
            val recipientIds = activeMembers.map { it.userId }.filter { it != senderId }

            val serverReceivedTimestamp = System.currentTimeMillis()
            val mailboxMsg = MailboxMessage(
                messageId = msg.messageId,
                conversationId = msg.conversationId,
                senderUserId = senderId,
                recipientUserId = "", // set during batch insert
                body = msg.body,
                clientTimestamp = msg.timestamp,
                serverReceivedTimestamp = serverReceivedTimestamp,
                deliveryState = DeliveryState.QUEUED
            )
            mailboxDatabase.enqueueGroupMessage(mailboxMsg, recipientIds)
            sessionManager.recordSeenMessage(msg.messageId)

            // Immediately ACK to sender
            sendServerMessage(
                session,
                ServerMessage.MessageAccepted(
                    messageId = msg.messageId,
                    timestamp = serverReceivedTimestamp
                )
            )
            logger.info("Group message ${msg.messageId} queued for ${recipientIds.size} recipient(s) in group ${msg.conversationId}")

            // Relay in real-time to active members currently online
            val relayPayload = ServerMessage.RelayMessage(
                messageId = msg.messageId,
                conversationId = msg.conversationId,
                senderId = senderId,
                body = msg.body,
                timestamp = msg.timestamp
            )
            for (recipientId in recipientIds) {
                val recipientSessions = sessionManager.getSessions(recipientId)
                if (recipientSessions.isNotEmpty()) {
                    var delivered = false
                    for (rs in recipientSessions) {
                        try {
                            sendServerMessage(rs, relayPayload)
                            delivered = true
                        } catch (e: Exception) {
                            logger.error("Failed to relay group message ${msg.messageId} to $recipientId: ${e.message}")
                        }
                    }
                    if (delivered) {
                        mailboxDatabase.updateDeliveryState(msg.messageId, recipientId, DeliveryState.DELIVERED)
                    }
                } else {
                    dispatchPushForOfflineRecipient(recipientId, msg.conversationId, senderId, isGroup = true, msg.messageId, msg.timestamp)
                }
            }
            return
        }

        // Direct 1:1 Message Flow
        val recipientId = msg.recipientId
        if (recipientId.isNullOrBlank() || recipientId.length > ProtocolLimits.MAX_USER_ID_LENGTH || !ProtocolLimits.USER_ID_REGEX.matches(recipientId)) {
            sendServerMessage(session, ServerMessage.Error("INVALID_RECIPIENT_ID", "recipientId must match ^user_[a-zA-Z0-9_]{1,64}$"))
            return
        }

        if (recipientId == senderId) {
            sendServerMessage(session, ServerMessage.Error("SELF_MESSAGE_NOT_ALLOWED", "Cannot send message to yourself"))
            return
        }

        if (mailboxDatabase.isUserBlocked(recipientId, senderId)) {
            logger.warn("Message ${msg.messageId} from $senderId dropped: blocked by recipient $recipientId")
            sendServerMessage(
                session,
                ServerMessage.Error("BLOCKED", "Cannot deliver message: communication blocked by recipient")
            )
            return
        }

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
            logger.info("Duplicate direct message ${msg.messageId} acknowledged idempotently; skipped relay")
            return
        }

        val serverReceivedTimestamp = System.currentTimeMillis()
        val mailboxMsg = MailboxMessage(
            messageId = msg.messageId,
            conversationId = msg.conversationId,
            senderUserId = senderId,
            recipientUserId = recipientId,
            body = msg.body,
            clientTimestamp = msg.timestamp,
            serverReceivedTimestamp = serverReceivedTimestamp,
            deliveryState = DeliveryState.QUEUED
        )
        mailboxDatabase.enqueueMessage(mailboxMsg)
        sessionManager.recordSeenMessage(msg.messageId)

        sendServerMessage(
            session,
            ServerMessage.MessageAccepted(
                messageId = msg.messageId,
                timestamp = serverReceivedTimestamp
            )
        )
        logger.info("Message ${msg.messageId} accepted and queued from $senderId -> $recipientId")

        val recipientSessions = sessionManager.getSessions(recipientId)
        if (recipientSessions.isEmpty()) {
            logger.info("Recipient $recipientId is offline; message ${msg.messageId} remains stored in mailbox")
            dispatchPushForOfflineRecipient(recipientId, msg.conversationId, senderId, isGroup = false, msg.messageId, msg.timestamp)
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
                    logger.info("Message ${msg.messageId} relayed to online recipient $recipientId")
                } catch (e: Exception) {
                    logger.error("Failed to relay message ${msg.messageId} to recipient session: ${e.message}")
                }
            }
            if (delivered) {
                mailboxDatabase.updateDeliveryState(msg.messageId, recipientId, DeliveryState.DELIVERED)
            }
        }
    }

    private fun dispatchPushForOfflineRecipient(
        recipientUserId: String,
        conversationId: String,
        senderUserId: String,
        isGroup: Boolean,
        messageId: String,
        timestamp: Long
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tokens = mailboxDatabase.getPushTokensForUser(recipientUserId)
                for (pushToken in tokens) {
                    try {
                        pushGateway.sendPushNotification(
                            PushPayload(
                                recipientUserId = recipientUserId,
                                pushToken = pushToken,
                                conversationId = conversationId,
                                senderUserId = senderUserId,
                                isGroup = isGroup,
                                messageId = messageId,
                                timestamp = timestamp
                            )
                        )
                    } catch (e: Exception) {
                        logger.warn("Push delivery failed for user $recipientUserId: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed fetching push tokens for user $recipientUserId: ${e.message}")
            }
        }
    }

    private suspend fun handleAckReceived(session: WebSocketSession, ack: ClientMessage.AckReceived) {
        val receiverId = sessionManager.getUserForSession(session)
        if (receiverId == null) {
            logger.warn("Unauthenticated client attempted to send ACK")
            sendServerMessage(
                session,
                ServerMessage.Error(
                    code = "UNAUTHENTICATED",
                    message = "Session must be authenticated before sending ACKs"
                )
            )
            return
        }

        if (ack.messageId.isBlank() || ack.messageId.length > ProtocolLimits.MAX_MESSAGE_ID_LENGTH) {
            logger.warn("Invalid ACK_RECEIVED messageId: '${ack.messageId}'")
            return
        }

        val message = mailboxDatabase.getMessageForRecipient(ack.messageId, receiverId)
        if (message == null) {
            val existsForOther = mailboxDatabase.hasMessage(ack.messageId)
            if (existsForOther) {
                logger.warn("User '$receiverId' attempted to ACK message '${ack.messageId}' intended for another user! Rejecting unauthorized ACK.")
                sendServerMessage(
                    session,
                    ServerMessage.Error(
                        code = "UNAUTHORIZED_ACK",
                        message = "Cannot acknowledge message intended for another user"
                    )
                )
                return
            }
            logger.debug("ACK for unknown messageId: ${ack.messageId}")
            return
        }

        mailboxDatabase.updateDeliveryState(ack.messageId, receiverId, DeliveryState.ACKED)
        logger.info("Client $receiverId acknowledged receipt of message ${ack.messageId} (state -> ACKED)")
    }

    // =========================================================================
    // Group Operation Handlers
    // =========================================================================

    private suspend fun handleCreateGroup(session: WebSocketSession, msg: ClientMessage.CreateGroup) {
        val senderId = sessionManager.getUserForSession(session)
        if (senderId == null) {
            sendServerMessage(session, ServerMessage.Error("UNAUTHENTICATED", "Session not authenticated", msg.requestId))
            return
        }

        val name = msg.name.trim()
        if (name.length < ProtocolLimits.MIN_GROUP_NAME_LENGTH || name.length > ProtocolLimits.MAX_GROUP_NAME_LENGTH) {
            sendServerMessage(session, ServerMessage.Error("INVALID_GROUP_NAME", "Group name must be 1-${ProtocolLimits.MAX_GROUP_NAME_LENGTH} characters", msg.requestId))
            return
        }

        for (memberId in msg.memberIds) {
            if (!ProtocolLimits.USER_ID_REGEX.matches(memberId)) {
                sendServerMessage(session, ServerMessage.Error("INVALID_MEMBER_ID", "Member ID '$memberId' is invalid", msg.requestId))
                return
            }
        }

        if (msg.memberIds.size > ProtocolLimits.MAX_GROUP_MEMBERS) {
            sendServerMessage(session, ServerMessage.Error("TOO_MANY_MEMBERS", "Group cannot have more than ${ProtocolLimits.MAX_GROUP_MEMBERS} members", msg.requestId))
            return
        }

        val group = mailboxDatabase.createGroup(name, senderId, msg.memberIds)
        sendServerMessage(session, ServerMessage.GroupOperationOk(msg.requestId, group.groupId, "CREATE_GROUP", group.name))

        val now = System.currentTimeMillis()
        val event = ServerMessage.GroupEvent(group.groupId, "CREATED", senderId, null, group.name, now)
        broadcastToGroup(group.groupId, event, excludeUserId = senderId)
    }

    private suspend fun handleGetGroups(session: WebSocketSession, msg: ClientMessage.GetGroups) {
        val senderId = sessionManager.getUserForSession(session)
        if (senderId == null) {
            sendServerMessage(session, ServerMessage.Error("UNAUTHENTICATED", "Session not authenticated", msg.requestId))
            return
        }

        val groups = mailboxDatabase.getGroupsForUser(senderId)
        val summaries = groups.map { g ->
            val members = mailboxDatabase.getActiveMembers(g.groupId)
            val myRole = members.firstOrNull { it.userId == senderId }?.role ?: "MEMBER"
            GroupSummary(
                id = g.groupId,
                name = g.name,
                creatorId = g.creatorUserId,
                memberCount = members.size,
                role = myRole,
                createdAt = g.createdAt,
                updatedAt = g.updatedAt
            )
        }
        sendServerMessage(session, ServerMessage.GroupList(msg.requestId, summaries))
    }

    private suspend fun handleGetGroupMembers(session: WebSocketSession, msg: ClientMessage.GetGroupMembers) {
        val senderId = sessionManager.getUserForSession(session)
        if (senderId == null) {
            sendServerMessage(session, ServerMessage.Error("UNAUTHENTICATED", "Session not authenticated", msg.requestId))
            return
        }

        if (!mailboxDatabase.isMember(msg.groupId, senderId)) {
            sendServerMessage(session, ServerMessage.Error("UNAUTHORIZED_GROUP_ACCESS", "Not a member of this group", msg.requestId))
            return
        }

        val members = mailboxDatabase.getActiveMembers(msg.groupId)
        val memberSummaries = members.map {
            GroupMemberSummary(it.userId, it.role, it.joinedAt)
        }
        sendServerMessage(session, ServerMessage.GroupMembersList(msg.requestId, msg.groupId, memberSummaries))
    }

    private suspend fun handleAddGroupMember(session: WebSocketSession, msg: ClientMessage.AddGroupMember) {
        val senderId = sessionManager.getUserForSession(session)
        if (senderId == null) {
            sendServerMessage(session, ServerMessage.Error("UNAUTHENTICATED", "Session not authenticated", msg.requestId))
            return
        }

        val callerRole = mailboxDatabase.getMemberRole(msg.groupId, senderId)
        if (callerRole !in listOf("CREATOR", "ADMIN")) {
            sendServerMessage(session, ServerMessage.Error("PERMISSION_DENIED", "Only creator or admins can add members", msg.requestId))
            return
        }

        val targetUserId = msg.userId.trim()
        if (!ProtocolLimits.USER_ID_REGEX.matches(targetUserId)) {
            sendServerMessage(session, ServerMessage.Error("INVALID_USER_ID", "Invalid user ID format", msg.requestId))
            return
        }

        val success = mailboxDatabase.addGroupMember(msg.groupId, targetUserId)
        if (success) {
            sendServerMessage(session, ServerMessage.GroupOperationOk(msg.requestId, msg.groupId, "ADD_MEMBER"))
            val now = System.currentTimeMillis()
            broadcastToGroup(msg.groupId, ServerMessage.GroupEvent(msg.groupId, "MEMBER_ADDED", senderId, targetUserId, null, now), excludeUserId = senderId)
        } else {
            sendServerMessage(session, ServerMessage.Error("OPERATION_FAILED", "Failed to add member", msg.requestId))
        }
    }

    private suspend fun handleRemoveGroupMember(session: WebSocketSession, msg: ClientMessage.RemoveGroupMember) {
        val senderId = sessionManager.getUserForSession(session)
        if (senderId == null) {
            sendServerMessage(session, ServerMessage.Error("UNAUTHENTICATED", "Session not authenticated", msg.requestId))
            return
        }

        val callerRole = mailboxDatabase.getMemberRole(msg.groupId, senderId)
        if (callerRole !in listOf("CREATOR", "ADMIN")) {
            sendServerMessage(session, ServerMessage.Error("PERMISSION_DENIED", "Only creator or admins can remove members", msg.requestId))
            return
        }

        val targetRole = mailboxDatabase.getMemberRole(msg.groupId, msg.userId)
        if (targetRole == "CREATOR") {
            sendServerMessage(session, ServerMessage.Error("CANNOT_REMOVE_CREATOR", "Cannot remove creator from group", msg.requestId))
            return
        }

        val success = mailboxDatabase.removeGroupMember(msg.groupId, msg.userId)
        if (success) {
            sendServerMessage(session, ServerMessage.GroupOperationOk(msg.requestId, msg.groupId, "REMOVE_MEMBER"))
            val now = System.currentTimeMillis()
            broadcastToGroup(msg.groupId, ServerMessage.GroupEvent(msg.groupId, "MEMBER_REMOVED", senderId, msg.userId, null, now), excludeUserId = senderId)
            // Also notify target if online
            val targetSessions = sessionManager.getSessions(msg.userId)
            for (ts in targetSessions) {
                sendServerMessage(ts, ServerMessage.GroupEvent(msg.groupId, "MEMBER_REMOVED", senderId, msg.userId, null, now))
            }
        } else {
            sendServerMessage(session, ServerMessage.Error("OPERATION_FAILED", "Failed to remove member", msg.requestId))
        }
    }

    private suspend fun handleLeaveGroup(session: WebSocketSession, msg: ClientMessage.LeaveGroup) {
        val senderId = sessionManager.getUserForSession(session)
        if (senderId == null) {
            sendServerMessage(session, ServerMessage.Error("UNAUTHENTICATED", "Session not authenticated", msg.requestId))
            return
        }

        if (!mailboxDatabase.isMember(msg.groupId, senderId)) {
            sendServerMessage(session, ServerMessage.Error("UNAUTHORIZED_GROUP_ACCESS", "Not a member of this group", msg.requestId))
            return
        }

        val success = mailboxDatabase.leaveGroup(msg.groupId, senderId)
        if (success) {
            sendServerMessage(session, ServerMessage.GroupOperationOk(msg.requestId, msg.groupId, "LEAVE_GROUP"))
            val now = System.currentTimeMillis()
            broadcastToGroup(msg.groupId, ServerMessage.GroupEvent(msg.groupId, "MEMBER_LEFT", senderId, null, null, now), excludeUserId = senderId)
        } else {
            sendServerMessage(session, ServerMessage.Error("OPERATION_FAILED", "Failed to leave group", msg.requestId))
        }
    }

    private suspend fun handleRenameGroup(session: WebSocketSession, msg: ClientMessage.RenameGroup) {
        val senderId = sessionManager.getUserForSession(session)
        if (senderId == null) {
            sendServerMessage(session, ServerMessage.Error("UNAUTHENTICATED", "Session not authenticated", msg.requestId))
            return
        }

        val callerRole = mailboxDatabase.getMemberRole(msg.groupId, senderId)
        if (callerRole !in listOf("CREATOR", "ADMIN")) {
            sendServerMessage(session, ServerMessage.Error("PERMISSION_DENIED", "Only creator or admins can rename the group", msg.requestId))
            return
        }

        val newName = msg.newName.trim()
        if (newName.length < ProtocolLimits.MIN_GROUP_NAME_LENGTH || newName.length > ProtocolLimits.MAX_GROUP_NAME_LENGTH) {
            sendServerMessage(session, ServerMessage.Error("INVALID_GROUP_NAME", "Group name must be 1-${ProtocolLimits.MAX_GROUP_NAME_LENGTH} characters", msg.requestId))
            return
        }

        val success = mailboxDatabase.renameGroup(msg.groupId, newName)
        if (success) {
            sendServerMessage(session, ServerMessage.GroupOperationOk(msg.requestId, msg.groupId, "RENAME_GROUP", newName))
            val now = System.currentTimeMillis()
            broadcastToGroup(msg.groupId, ServerMessage.GroupEvent(msg.groupId, "RENAMED", senderId, null, newName, now), excludeUserId = senderId)
        } else {
            sendServerMessage(session, ServerMessage.Error("OPERATION_FAILED", "Failed to rename group", msg.requestId))
        }
    }

    private suspend fun broadcastToGroup(groupId: String, serverMsg: ServerMessage, excludeUserId: String? = null) {
        val members = mailboxDatabase.getActiveMembers(groupId)
        for (member in members) {
            if (member.userId != excludeUserId) {
                val sessions = sessionManager.getSessions(member.userId)
                for (s in sessions) {
                    try {
                        sendServerMessage(s, serverMsg)
                    } catch (e: Exception) {
                        logger.debug("Failed sending broadcast to ${member.userId}: ${e.message}")
                    }
                }
            }
        }
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
