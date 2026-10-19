package com.aster.backend.push

import org.slf4j.LoggerFactory

/**
 * Minimal, privacy-conscious push payload metadata.
 *
 * NOTE: Message bodies and credentials are NEVER included in push payloads.
 * Clients receive event indicators to wake up or display alerts, and fetch
 * authoritative message content strictly over authenticated TLS/WSS.
 */
data class PushPayload(
    val recipientUserId: String,
    val pushToken: String,
    val conversationId: String,
    val senderUserId: String,
    val isGroup: Boolean,
    val messageId: String,
    val timestamp: Long
)

/**
 * Extensible push notification gateway interface.
 *
 * Supports zero-cost local operation, UnifiedPush distributors, or FCM gateways.
 */
interface PushGateway {
    suspend fun sendPushNotification(payload: PushPayload): Boolean
}

/**
 * Default zero-cost push provider.
 * Logs dispatch events without requiring external third-party credentials.
 */
class NoOpPushGateway : PushGateway {
    private val logger = LoggerFactory.getLogger(NoOpPushGateway::class.java)

    override suspend fun sendPushNotification(payload: PushPayload): Boolean {
        logger.debug(
            "Dispatched push notification event to user '{}' (messageId='{}', conv='{}')",
            payload.recipientUserId,
            payload.messageId,
            payload.conversationId
        )
        return true
    }
}
