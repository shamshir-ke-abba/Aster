package com.aster.app.data.network

import com.aster.app.data.local.entities.MessageEntity
import org.json.JSONArray
import org.json.JSONObject

object ProtocolLimits {
    const val MAX_FRAME_SIZE = 65_536L
    const val MAX_BODY_LENGTH = 4096
    const val MIN_BODY_LENGTH = 1
    const val MAX_USER_ID_LENGTH = 64
    const val MAX_CONVERSATION_ID_LENGTH = 64
    const val MAX_MESSAGE_ID_LENGTH = 128
    const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_GROUP_NAME_LENGTH = 64
    const val MIN_GROUP_NAME_LENGTH = 1
    const val MAX_GROUP_MEMBERS = 100
    const val MIN_TIMESTAMP = 1L
    const val MAX_FUTURE_DRIFT_MS = 86_400_000L

    val USER_ID_REGEX = Regex("^user_[a-zA-Z0-9_]{1,64}$")
    val DEVICE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,128}$")
    val CONVERSATION_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    val GROUP_ID_REGEX = Regex("^group_[a-zA-Z0-9_-]{1,64}$")
    val MESSAGE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,128}$")
    val REQUEST_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,128}$")
    val USERNAME_REGEX = Regex("^[a-zA-Z0-9_]{3,24}$")
    val ASTER_ID_REGEX = Regex("^AST-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}$")
}

data class UserSummary(
    val userId: String,
    val asterId: String,
    val username: String,
    val displayName: String,
    val isOnline: Boolean = false,
    val lastSeen: Long = 0L,
    val isDeleted: Boolean = false
)

data class FriendRequestSummary(
    val id: String,
    val senderUserId: String,
    val recipientUserId: String,
    val status: String,
    val sender: UserSummary? = null,
    val recipient: UserSummary? = null,
    val createdAt: Long
)

data class GroupSummary(
    val id: String,
    val name: String,
    val creatorId: String,
    val memberCount: Int,
    val role: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class GroupMemberSummary(
    val userId: String,
    val role: String,
    val joinedAt: Long
)

sealed interface ClientMessage {
    fun toJson(): String

    data class Auth(
        val token: String,
        val userId: String? = null,
        val deviceId: String? = null
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "AUTH")
            put("token", token)
            if (userId != null) put("userId", userId)
            if (deviceId != null) put("deviceId", deviceId)
        }.toString()
    }

    data class SendMessage(
        val messageId: String,
        val conversationId: String,
        val recipientId: String? = null,
        val body: String,
        val timestamp: Long,
        val senderId: String? = null,
        val replyToId: String? = null,
        val effect: String? = null
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "SEND_MESSAGE")
            put("messageId", messageId)
            put("conversationId", conversationId)
            if (recipientId != null) put("recipientId", recipientId)
            put("body", body)
            put("timestamp", timestamp)
            if (senderId != null) put("senderId", senderId)
            if (replyToId != null) put("replyToId", replyToId)
            if (effect != null) put("effect", effect)
        }.toString()
    }

    data class AckReceived(
        val messageId: String
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "ACK_RECEIVED")
            put("messageId", messageId)
        }.toString()
    }

    data class Ping(
        val timestamp: Long = System.currentTimeMillis()
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "PING")
            put("timestamp", timestamp)
        }.toString()
    }

    // --- Group Operations ---

    data class CreateGroup(
        val requestId: String,
        val name: String,
        val memberIds: List<String> = emptyList()
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "CREATE_GROUP")
            put("requestId", requestId)
            put("name", name)
            put("memberIds", JSONArray(memberIds))
        }.toString()
    }

    data class GetGroups(
        val requestId: String
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "GET_GROUPS")
            put("requestId", requestId)
        }.toString()
    }

    data class GetGroupMembers(
        val requestId: String,
        val groupId: String
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "GET_GROUP_MEMBERS")
            put("requestId", requestId)
            put("groupId", groupId)
        }.toString()
    }

    data class AddGroupMember(
        val requestId: String,
        val groupId: String,
        val userId: String
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "ADD_GROUP_MEMBER")
            put("requestId", requestId)
            put("groupId", groupId)
            put("userId", userId)
        }.toString()
    }

    data class RemoveGroupMember(
        val requestId: String,
        val groupId: String,
        val userId: String
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "REMOVE_GROUP_MEMBER")
            put("requestId", requestId)
            put("groupId", groupId)
            put("userId", userId)
        }.toString()
    }

    data class LeaveGroup(
        val requestId: String,
        val groupId: String
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "LEAVE_GROUP")
            put("requestId", requestId)
            put("groupId", groupId)
        }.toString()
    }

    data class RenameGroup(
        val requestId: String,
        val groupId: String,
        val newName: String
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "RENAME_GROUP")
            put("requestId", requestId)
            put("groupId", groupId)
            put("newName", newName)
        }.toString()
    }

    data class MarkRead(
        val messageId: String,
        val conversationId: String
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "MARK_READ")
            put("messageId", messageId)
            put("conversationId", conversationId)
        }.toString()
    }

    data class UnsendMessage(
        val messageId: String,
        val conversationId: String
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "UNSEND_MESSAGE")
            put("messageId", messageId)
            put("conversationId", conversationId)
        }.toString()
    }

    data class ToggleReaction(
        val messageId: String,
        val conversationId: String,
        val emoji: String
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "TOGGLE_REACTION")
            put("messageId", messageId)
            put("conversationId", conversationId)
            put("emoji", emoji)
        }.toString()
    }
}

sealed interface ServerMessage {
    data class AuthOk(val userId: String) : ServerMessage
    data class AuthError(val reason: String) : ServerMessage
    data class MessageAccepted(val messageId: String, val timestamp: Long) : ServerMessage
    data class MessageDelivered(val messageId: String) : ServerMessage
    data class RelayMessage(
        val messageId: String,
        val conversationId: String,
        val senderId: String,
        val body: String,
        val timestamp: Long,
        val replyToId: String? = null,
        val effect: String? = null,
        val reactions: Map<String, List<String>> = emptyMap()
    ) : ServerMessage

    data class ReactionUpdate(
        val messageId: String,
        val conversationId: String,
        val userId: String,
        val emoji: String,
        val action: String,
        val reactions: Map<String, List<String>> = emptyMap(),
        val timestamp: Long
    ) : ServerMessage
    data class Error(val code: String, val message: String, val requestId: String? = null) : ServerMessage
    data class Pong(val timestamp: Long) : ServerMessage

    // Group Operation Responses
    data class GroupOperationOk(
        val requestId: String,
        val groupId: String,
        val action: String,
        val name: String? = null
    ) : ServerMessage

    data class GroupList(
        val requestId: String,
        val groups: List<GroupSummary>
    ) : ServerMessage

    data class GroupMembersList(
        val requestId: String,
        val groupId: String,
        val members: List<GroupMemberSummary>
    ) : ServerMessage

    data class GroupEvent(
        val groupId: String,
        val eventType: String,
        val actorId: String,
        val targetUserId: String? = null,
        val name: String? = null,
        val timestamp: Long
    ) : ServerMessage

    data class MessageRead(
        val messageId: String,
        val conversationId: String,
        val readerId: String,
        val timestamp: Long
    ) : ServerMessage

    data class MessageUnsent(
        val messageId: String,
        val conversationId: String,
        val senderId: String,
        val timestamp: Long
    ) : ServerMessage

    data class PresenceUpdate(
        val userId: String,
        val isOnline: Boolean,
        val lastSeen: Long,
        val timestamp: Long = 0L
    ) : ServerMessage

    data class UserDeleted(
        val userId: String
    ) : ServerMessage

    data class FriendRequestReceived(
        val request: FriendRequestSummary
    ) : ServerMessage

    data class FriendRequestUpdated(
        val requestId: String,
        val status: String,
        val senderUserId: String,
        val recipientUserId: String
    ) : ServerMessage

    companion object {
        fun fromJson(jsonStr: String): ServerMessage? {
            return try {
                val json = JSONObject(jsonStr)
                when (json.optString("type")) {
                    "AUTH_OK" -> {
                        val userId = json.optString("userId")
                        if (userId.isNotBlank()) AuthOk(userId = userId) else null
                    }
                    "AUTH_ERROR" -> AuthError(reason = json.optString("reason", "Unknown authentication error"))
                    "MESSAGE_ACCEPTED" -> {
                        val messageId = json.optString("messageId")
                        if (messageId.isNotBlank()) {
                            MessageAccepted(
                                messageId = messageId,
                                timestamp = json.optLong("timestamp", System.currentTimeMillis())
                            )
                        } else null
                    }
                    "MESSAGE_DELIVERED" -> {
                        val messageId = json.optString("messageId")
                        if (messageId.isNotBlank()) MessageDelivered(messageId = messageId) else null
                    }
                    "MESSAGE" -> {
                        val messageId = json.optString("messageId")
                        val conversationId = json.optString("conversationId")
                        val senderId = json.optString("senderId")
                        val body = json.optString("body")
                        val replyToId = if (json.has("replyToId") && !json.isNull("replyToId")) {
                            json.optString("replyToId").takeIf { it.isNotBlank() }
                        } else null
                        val effect = if (json.has("effect") && !json.isNull("effect")) {
                            json.optString("effect").takeIf { it.isNotBlank() }
                        } else null
                        val reactionsJson = json.optJSONObject("reactions")?.toString()
                        val reactions = MessageEntity.parseReactions(reactionsJson)
                        if (messageId.isNotBlank() && conversationId.isNotBlank() && senderId.isNotBlank()) {
                            RelayMessage(
                                messageId = messageId,
                                conversationId = conversationId,
                                senderId = senderId,
                                body = body,
                                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                                replyToId = replyToId,
                                effect = effect,
                                reactions = reactions
                            )
                        } else null
                    }
                    "REACTION_UPDATE" -> {
                        val messageId = json.optString("messageId")
                        val conversationId = json.optString("conversationId")
                        val userId = json.optString("userId")
                        val emoji = json.optString("emoji")
                        val action = json.optString("action", "ADDED")
                        val reactionsJson = json.optJSONObject("reactions")?.toString()
                        val reactions = MessageEntity.parseReactions(reactionsJson)
                        val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                        if (messageId.isNotBlank() && conversationId.isNotBlank() && emoji.isNotBlank()) {
                            ReactionUpdate(
                                messageId = messageId,
                                conversationId = conversationId,
                                userId = userId,
                                emoji = emoji,
                                action = action,
                                reactions = reactions,
                                timestamp = timestamp
                            )
                        } else null
                    }
                    "ERROR" -> Error(
                        code = json.optString("code", "UNKNOWN"),
                        message = json.optString("message", ""),
                        requestId = if (json.has("requestId")) json.optString("requestId") else null
                    )
                    "PONG" -> Pong(
                        timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    )
                    "GROUP_OPERATION_OK" -> {
                        val reqId = json.optString("requestId")
                        val grpId = json.optString("groupId")
                        val act = json.optString("action")
                        val nm = if (json.has("name")) json.optString("name") else null
                        GroupOperationOk(reqId, grpId, act, nm)
                    }
                    "GROUP_LIST" -> {
                        val reqId = json.optString("requestId")
                        val groupsArray = json.optJSONArray("groups") ?: JSONArray()
                        val list = mutableListOf<GroupSummary>()
                        for (i in 0 until groupsArray.length()) {
                            val gObj = groupsArray.getJSONObject(i)
                            list.add(
                                GroupSummary(
                                    id = gObj.getString("id"),
                                    name = gObj.getString("name"),
                                    creatorId = gObj.getString("creatorId"),
                                    memberCount = gObj.optInt("memberCount", 0),
                                    role = gObj.optString("role", "MEMBER"),
                                    createdAt = gObj.optLong("createdAt", 0L),
                                    updatedAt = gObj.optLong("updatedAt", 0L)
                                )
                            )
                        }
                        GroupList(reqId, list)
                    }
                    "GROUP_MEMBERS_LIST" -> {
                        val reqId = json.optString("requestId")
                        val grpId = json.optString("groupId")
                        val membersArray = json.optJSONArray("members") ?: JSONArray()
                        val list = mutableListOf<GroupMemberSummary>()
                        for (i in 0 until membersArray.length()) {
                            val mObj = membersArray.getJSONObject(i)
                            list.add(
                                GroupMemberSummary(
                                    userId = mObj.getString("userId"),
                                    role = mObj.getString("role"),
                                    joinedAt = mObj.optLong("joinedAt", 0L)
                                )
                            )
                        }
                        GroupMembersList(reqId, grpId, list)
                    }
                    "GROUP_EVENT" -> {
                        val grpId = json.optString("groupId")
                        val evtType = json.optString("eventType")
                        val actor = json.optString("actorId")
                        val target = if (json.has("targetUserId")) json.optString("targetUserId") else null
                        val nm = if (json.has("name")) json.optString("name") else null
                        val ts = json.optLong("timestamp", System.currentTimeMillis())
                        GroupEvent(grpId, evtType, actor, target, nm, ts)
                    }
                    "MESSAGE_READ" -> {
                        val messageId = json.optString("messageId")
                        val conversationId = json.optString("conversationId")
                        val readerId = json.optString("readerId")
                        val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                        if (messageId.isNotBlank() && conversationId.isNotBlank() && readerId.isNotBlank()) {
                            MessageRead(messageId, conversationId, readerId, timestamp)
                        } else null
                    }
                    "MESSAGE_UNSENT" -> {
                        val messageId = json.optString("messageId")
                        val conversationId = json.optString("conversationId")
                        val senderId = json.optString("senderId")
                        val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                        if (messageId.isNotBlank() && conversationId.isNotBlank()) {
                            MessageUnsent(messageId, conversationId, senderId, timestamp)
                        } else null
                    }
                    "PRESENCE_UPDATE" -> {
                        val uid = json.optString("userId")
                        val isOnline = json.optBoolean("isOnline", false)
                        val lastSeen = json.optLong("lastSeen", 0L)
                        val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                        if (uid.isNotBlank()) PresenceUpdate(uid, isOnline, lastSeen, timestamp) else null
                    }
                    "USER_DELETED" -> {
                        val uid = json.optString("userId")
                        if (uid.isNotBlank()) UserDeleted(uid) else null
                    }
                    "FRIEND_REQUEST_RECEIVED" -> {
                        val reqObj = json.optJSONObject("request")
                        if (reqObj != null) {
                            val senderObj = reqObj.optJSONObject("sender")
                            val senderSummary = senderObj?.let {
                                UserSummary(
                                    userId = it.optString("userId"),
                                    asterId = it.optString("asterId"),
                                    username = it.optString("username"),
                                    displayName = it.optString("displayName")
                                )
                            }
                            val recipientObj = reqObj.optJSONObject("recipient")
                            val recipientSummary = recipientObj?.let {
                                UserSummary(
                                    userId = it.optString("userId"),
                                    asterId = it.optString("asterId"),
                                    username = it.optString("username"),
                                    displayName = it.optString("displayName")
                                )
                            }
                            val summary = FriendRequestSummary(
                                id = reqObj.optString("id"),
                                senderUserId = reqObj.optString("senderUserId"),
                                recipientUserId = reqObj.optString("recipientUserId"),
                                status = reqObj.optString("status", "PENDING"),
                                sender = senderSummary,
                                recipient = recipientSummary,
                                createdAt = reqObj.optLong("createdAt", System.currentTimeMillis())
                            )
                            FriendRequestReceived(summary)
                        } else null
                    }
                    "FRIEND_REQUEST_UPDATED" -> {
                        val reqId = json.optString("requestId")
                        val st = json.optString("status")
                        val sender = json.optString("senderUserId")
                        val recipient = json.optString("recipientUserId")
                        if (reqId.isNotBlank() && st.isNotBlank()) {
                            FriendRequestUpdated(reqId, st, sender, recipient)
                        } else null
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
