package com.aster.app.domain.model

/**
 * Domain model representing a chat message.
 *
 * Adheres to logical message structure defined in protocol/protocol.md:
 * - message_id
 * - conversation_id
 * - sender_id
 * - timestamp (createdAt epoch millis)
 * - body (text payload)
 * - status
 * - replyToId (optional reference to another message)
 */
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val createdAt: Long,
    val status: MessageStatus = MessageStatus.SENDING,
    val replyToId: String? = null
)
