package com.aster.app.domain.model

/**
 * Delivery status for an individual message.
 *
 * Defined in protocol/protocol.md:
 * - SENDING: Message generated locally, in the process of local transmission.
 * - SENT: Message accepted by server / acknowledged.
 * - DELIVERED: Recipient device acknowledged delivery.
 * - READ: Recipient has read the message.
 * - FAILED: Local or remote delivery failed.
 */
enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
    UNSENT;

    /**
     * Validates whether transitioning from this status to [newStatus] is permitted.
     * Transitions to the same status are always valid (idempotent no-op).
     * Regressions (e.g., SENT -> SENDING, READ -> DELIVERED) are strictly rejected.
     */
    fun canTransitionTo(newStatus: MessageStatus): Boolean {
        if (this == newStatus) return true
        if (newStatus == UNSENT) return this != UNSENT
        return when (this) {
            SENDING -> newStatus == SENT || newStatus == FAILED
            SENT -> newStatus == DELIVERED || newStatus == READ
            DELIVERED -> newStatus == READ
            READ -> false // Terminal state
            FAILED -> newStatus == SENDING // Retry initiates new transmission
            UNSENT -> false // Terminal state
        }
    }
}
