package com.aster.app.domain.model

/**
 * Association between a user and a conversation.
 */
data class ConversationMember(
    val conversationId: String,
    val userId: String,
    val role: String = "MEMBER",
    val joinedAt: Long = System.currentTimeMillis()
)
