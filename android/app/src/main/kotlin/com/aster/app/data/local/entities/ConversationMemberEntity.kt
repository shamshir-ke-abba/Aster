package com.aster.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.aster.app.domain.model.ConversationMember

@Entity(
    tableName = "conversation_members",
    primaryKeys = ["conversation_id", "user_id"],
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
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversation_id"),
        Index("user_id")
    ]
)
data class ConversationMemberEntity(
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    val role: String,
    @ColumnInfo(name = "joined_at")
    val joinedAt: Long
) {
    fun toDomain(): ConversationMember = ConversationMember(
        conversationId = conversationId,
        userId = userId,
        role = role,
        joinedAt = joinedAt
    )

    companion object {
        fun fromDomain(member: ConversationMember): ConversationMemberEntity =
            ConversationMemberEntity(
                conversationId = member.conversationId,
                userId = member.userId,
                role = member.role,
                joinedAt = member.joinedAt
            )
    }
}
