package com.aster.app.domain.model

/**
 * Domain model representing a conversation (connection or group in Aster).
 *
 * @property id Unique identifier for the conversation.
 * @property title Name of the conversation (e.g. contact name for 1:1, or group title).
 * @property isGroup False for one-to-one direct messages; true for group constellations.
 * @property createdAt Epoch millis timestamp of conversation creation.
 * @property lastMessage Most recent message for conversation preview in the list.
 * @property unreadCount Number of unread incoming messages in this conversation.
 * @property otherMember For 1:1 direct messages, the other participant in the conversation.
 * @property members All participants in the conversation (useful for group constellations).
 */
data class Conversation(
    val id: String,
    val title: String,
    val isGroup: Boolean = false,
    val createdAt: Long,
    val lastMessage: Message? = null,
    val unreadCount: Int = 0,
    val otherMember: User? = null,
    val members: List<User> = emptyList(),
    val isHighlighted: Boolean = false,
    val customNormalizedX: Float? = null,
    val customNormalizedY: Float? = null
) {
    val isBlocked: Boolean get() = !isGroup && otherMember?.isBlocked == true
}
