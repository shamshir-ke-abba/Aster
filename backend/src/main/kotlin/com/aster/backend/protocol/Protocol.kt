package com.aster.backend.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
val asterJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

object ProtocolLimits {
    const val MAX_FRAME_SIZE = 65_536L // 64 KB
    const val MAX_BODY_LENGTH = 4096
    const val MIN_BODY_LENGTH = 1
    const val MAX_USER_ID_LENGTH = 64
    const val MAX_CONVERSATION_ID_LENGTH = 64
    const val MAX_MESSAGE_ID_LENGTH = 128
    const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_GROUP_NAME_LENGTH = 64
    const val MIN_GROUP_NAME_LENGTH = 1
    const val MAX_GROUP_MEMBERS = 100
    const val MIN_TIMESTAMP = 1L // Must be positive
    const val MAX_FUTURE_DRIFT_MS = 86_400_000L // 24 hours

    const val MAX_TOKEN_LENGTH = 256
    const val MIN_TOKEN_LENGTH = 16

    const val MIN_USERNAME_LENGTH = 3
    const val MAX_USERNAME_LENGTH = 24
    const val MIN_DISPLAY_NAME_LENGTH = 1
    const val MAX_DISPLAY_NAME_LENGTH = 32

    val USER_ID_REGEX = Regex("^user_[a-zA-Z0-9_]{1,64}$")
    val DEVICE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,128}$")
    val CONVERSATION_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    val GROUP_ID_REGEX = Regex("^group_[a-zA-Z0-9_-]{1,64}$")
    val MESSAGE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,128}$")
    val REQUEST_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,128}$")
    val USERNAME_REGEX = Regex("^[a-zA-Z0-9_]{3,24}$")
    val ASTER_ID_REGEX = Regex("^AST-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}$")
}

@Serializable
data class UserSummary(
    val userId: String,
    val asterId: String,
    val username: String,
    val displayName: String
)

@Serializable
data class FriendRequestSummary(
    val id: String,
    val senderUserId: String,
    val recipientUserId: String,
    val status: String,
    val sender: UserSummary? = null,
    val recipient: UserSummary? = null,
    val createdAt: Long
)

@Serializable
data class GroupSummary(
    val id: String,
    val name: String,
    val creatorId: String,
    val memberCount: Int,
    val role: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class GroupMemberSummary(
    val userId: String,
    val role: String,
    val joinedAt: Long
)

@Serializable
sealed interface ClientMessage {

    @Serializable
    @SerialName("AUTH")
    data class Auth(
        val token: String,
        val userId: String? = null,
        val deviceId: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("SEND_MESSAGE")
    data class SendMessage(
        val messageId: String,
        val conversationId: String,
        val recipientId: String? = null,
        val body: String,
        val timestamp: Long,
        val senderId: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("ACK_RECEIVED")
    data class AckReceived(
        val messageId: String
    ) : ClientMessage

    @Serializable
    @SerialName("PING")
    data class Ping(
        val timestamp: Long = System.currentTimeMillis()
    ) : ClientMessage

    // --- Group Operations ---

    @Serializable
    @SerialName("CREATE_GROUP")
    data class CreateGroup(
        val requestId: String = "",
        val name: String,
        val memberIds: List<String> = emptyList()
    ) : ClientMessage

    @Serializable
    @SerialName("GET_GROUPS")
    data class GetGroups(
        val requestId: String = ""
    ) : ClientMessage

    @Serializable
    @SerialName("GET_GROUP_MEMBERS")
    data class GetGroupMembers(
        val requestId: String = "",
        val groupId: String
    ) : ClientMessage

    @Serializable
    @SerialName("ADD_GROUP_MEMBER")
    data class AddGroupMember(
        val requestId: String = "",
        val groupId: String,
        val userId: String
    ) : ClientMessage

    @Serializable
    @SerialName("REMOVE_GROUP_MEMBER")
    data class RemoveGroupMember(
        val requestId: String = "",
        val groupId: String,
        val userId: String
    ) : ClientMessage

    @Serializable
    @SerialName("LEAVE_GROUP")
    data class LeaveGroup(
        val requestId: String = "",
        val groupId: String
    ) : ClientMessage

    @Serializable
    @SerialName("RENAME_GROUP")
    data class RenameGroup(
        val requestId: String = "",
        val groupId: String,
        val newName: String
    ) : ClientMessage
}

@Serializable
sealed interface ServerMessage {

    @Serializable
    @SerialName("AUTH_OK")
    data class AuthOk(
        val userId: String
    ) : ServerMessage

    @Serializable
    @SerialName("AUTH_ERROR")
    data class AuthError(
        val reason: String
    ) : ServerMessage

    @Serializable
    @SerialName("MESSAGE_ACCEPTED")
    data class MessageAccepted(
        val messageId: String,
        val timestamp: Long
    ) : ServerMessage

    @Serializable
    @SerialName("MESSAGE_DELIVERED")
    data class MessageDelivered(
        val messageId: String
    ) : ServerMessage

    @Serializable
    @SerialName("MESSAGE")
    data class RelayMessage(
        val messageId: String,
        val conversationId: String,
        val senderId: String,
        val body: String,
        val timestamp: Long
    ) : ServerMessage

    @Serializable
    @SerialName("ERROR")
    data class Error(
        val code: String,
        val message: String,
        val requestId: String? = null
    ) : ServerMessage

    @Serializable
    @SerialName("PONG")
    data class Pong(
        val timestamp: Long = System.currentTimeMillis()
    ) : ServerMessage

    // --- Group Operation Responses ---

    @Serializable
    @SerialName("GROUP_OPERATION_OK")
    data class GroupOperationOk(
        val requestId: String,
        val groupId: String,
        val action: String,
        val name: String? = null
    ) : ServerMessage

    @Serializable
    @SerialName("GROUP_LIST")
    data class GroupList(
        val requestId: String,
        val groups: List<GroupSummary>
    ) : ServerMessage

    @Serializable
    @SerialName("GROUP_MEMBERS_LIST")
    data class GroupMembersList(
        val requestId: String,
        val groupId: String,
        val members: List<GroupMemberSummary>
    ) : ServerMessage

    @Serializable
    @SerialName("GROUP_EVENT")
    data class GroupEvent(
        val groupId: String,
        val eventType: String, // "CREATED", "MEMBER_ADDED", "MEMBER_REMOVED", "MEMBER_LEFT", "RENAMED"
        val actorId: String,
        val targetUserId: String? = null,
        val name: String? = null,
        val timestamp: Long
    ) : ServerMessage

    @Serializable
    @SerialName("FRIEND_REQUEST_RECEIVED")
    data class FriendRequestReceived(
        val request: FriendRequestSummary
    ) : ServerMessage

    @Serializable
    @SerialName("FRIEND_REQUEST_UPDATED")
    data class FriendRequestUpdated(
        val requestId: String,
        val status: String,
        val senderUserId: String,
        val recipientUserId: String
    ) : ServerMessage
}
