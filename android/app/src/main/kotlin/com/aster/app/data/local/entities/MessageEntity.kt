package com.aster.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aster.app.domain.model.Message
import com.aster.app.domain.model.MessageStatus

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["sender_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversation_id"),
        Index("sender_id"),
        Index("created_at")
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "sender_id")
    val senderId: String,
    val body: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    val status: MessageStatus,
    @ColumnInfo(name = "reply_to_id")
    val replyToId: String? = null
) {
    fun toDomain(): Message = Message(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        body = body,
        createdAt = createdAt,
        status = status,
        replyToId = replyToId
    )

    companion object {
        fun fromDomain(message: Message): MessageEntity = MessageEntity(
            id = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            body = message.body,
            createdAt = message.createdAt,
            status = message.status,
            replyToId = message.replyToId
        )
    }
}
