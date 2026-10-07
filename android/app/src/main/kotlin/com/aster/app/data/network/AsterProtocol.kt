package com.aster.app.data.network

import org.json.JSONObject

object ProtocolLimits {
    const val MAX_FRAME_SIZE = 65_536L
    const val MAX_BODY_LENGTH = 4096
    const val MIN_BODY_LENGTH = 1
    const val MAX_USER_ID_LENGTH = 64
    const val MAX_CONVERSATION_ID_LENGTH = 64
    const val MAX_MESSAGE_ID_LENGTH = 128
    const val MIN_TIMESTAMP = 1L
    const val MAX_FUTURE_DRIFT_MS = 86_400_000L

    val USER_ID_REGEX = Regex("^user_[a-zA-Z0-9_]{1,64}$")
    val CONVERSATION_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    val MESSAGE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,128}$")
}

sealed interface ClientMessage {
    fun toJson(): String

    data class Auth(
        val userId: String
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "AUTH")
            put("userId", userId)
        }.toString()
    }

    data class SendMessage(
        val messageId: String,
        val conversationId: String,
        val recipientId: String,
        val body: String,
        val timestamp: Long
    ) : ClientMessage {
        override fun toJson(): String = JSONObject().apply {
            put("type", "SEND_MESSAGE")
            put("messageId", messageId)
            put("conversationId", conversationId)
            put("recipientId", recipientId)
            put("body", body)
            put("timestamp", timestamp)
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
        val timestamp: Long
    ) : ServerMessage
    data class Error(val code: String, val message: String) : ServerMessage
    data class Pong(val timestamp: Long) : ServerMessage

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
                        if (messageId.isNotBlank() && conversationId.isNotBlank() && senderId.isNotBlank()) {
                            RelayMessage(
                                messageId = messageId,
                                conversationId = conversationId,
                                senderId = senderId,
                                body = body,
                                timestamp = json.optLong("timestamp", System.currentTimeMillis())
                            )
                        } else null
                    }
                    "ERROR" -> Error(
                        code = json.optString("code", "UNKNOWN"),
                        message = json.optString("message", "")
                    )
                    "PONG" -> Pong(
                        timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    )
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
