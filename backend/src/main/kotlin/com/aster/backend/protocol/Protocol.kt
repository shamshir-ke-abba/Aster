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
    const val MIN_TIMESTAMP = 1L // Must be positive
    const val MAX_FUTURE_DRIFT_MS = 86_400_000L // 24 hours

    val USER_ID_REGEX = Regex("^user_[a-zA-Z0-9_]{1,64}$")
    val CONVERSATION_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    val MESSAGE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,128}$")
}

@Serializable
sealed interface ClientMessage {

    @Serializable
    @SerialName("AUTH")
    data class Auth(
        val userId: String
    ) : ClientMessage

    @Serializable
    @SerialName("SEND_MESSAGE")
    data class SendMessage(
        val messageId: String,
        val conversationId: String,
        val recipientId: String,
        val body: String,
        val timestamp: Long
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
        val message: String
    ) : ServerMessage

    @Serializable
    @SerialName("PONG")
    data class Pong(
        val timestamp: Long = System.currentTimeMillis()
    ) : ServerMessage
}
