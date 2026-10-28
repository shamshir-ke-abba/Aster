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
    val replyToId: String? = null,
    @ColumnInfo(name = "reactions", defaultValue = "{}")
    val reactions: String = "{}",
    @ColumnInfo(name = "effect", defaultValue = "NULL")
    val effect: String? = null,
    @ColumnInfo(name = "is_effect_played", defaultValue = "0")
    val isEffectPlayed: Boolean = false
) {
    fun toDomain(): Message = Message(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        body = body,
        createdAt = createdAt,
        status = status,
        replyToId = replyToId,
        reactions = parseReactions(reactions),
        effect = effect,
        isEffectPlayed = isEffectPlayed
    )

    companion object {
        fun parseReactions(json: String?): Map<String, List<String>> {
            if (json.isNullOrBlank() || json == "{}") return emptyMap()
            return try {
                val jsonObj = org.json.JSONObject(json)
                val result = mutableMapOf<String, List<String>>()
                val keys = jsonObj.keys()
                while (keys.hasNext()) {
                    val emoji = keys.next()
                    val array = jsonObj.optJSONArray(emoji)
                    if (array != null) {
                        val list = mutableListOf<String>()
                        for (i in 0 until array.length()) {
                            list.add(array.getString(i))
                        }
                        if (list.isNotEmpty()) {
                            result[emoji] = list
                        }
                    }
                }
                result
            } catch (e: Exception) {
                emptyMap()
            }
        }

        fun serializeReactions(reactions: Map<String, List<String>>): String {
            if (reactions.isEmpty()) return "{}"
            val jsonObj = org.json.JSONObject()
            reactions.forEach { (emoji, userList) ->
                if (userList.isNotEmpty()) {
                    jsonObj.put(emoji, org.json.JSONArray(userList))
                }
            }
            return jsonObj.toString()
        }

        fun fromDomain(message: Message): MessageEntity = MessageEntity(
            id = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            body = message.body,
            createdAt = message.createdAt,
            status = message.status,
            replyToId = message.replyToId,
            reactions = serializeReactions(message.reactions),
            effect = message.effect,
            isEffectPlayed = message.isEffectPlayed
        )
    }
}
