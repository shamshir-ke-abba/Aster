package com.aster.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aster.app.domain.model.Conversation

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    @ColumnInfo(name = "is_group")
    val isGroup: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
) {
    fun toDomain(): Conversation = Conversation(
        id = id,
        title = title,
        isGroup = isGroup,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(conv: Conversation): ConversationEntity = ConversationEntity(
            id = conv.id,
            title = conv.title,
            isGroup = conv.isGroup,
            createdAt = conv.createdAt,
            updatedAt = conv.createdAt
        )
    }
}
