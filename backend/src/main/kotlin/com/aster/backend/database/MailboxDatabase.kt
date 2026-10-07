package com.aster.backend.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

enum class DeliveryState {
    QUEUED,     // Accepted by server, stored in SQLite mailbox, waiting for recipient
    DELIVERED,  // Written to recipient's active WebSocket connection (in-flight)
    ACKED       // Recipient sent ACK_RECEIVED back to server
}

data class MailboxMessage(
    val messageId: String,
    val conversationId: String,
    val senderUserId: String,
    val recipientUserId: String,
    val body: String,
    val clientTimestamp: Long,
    val serverReceivedTimestamp: Long,
    val deliveryState: DeliveryState
)

/**
 * Thread-safe SQLite repository for Aster's durable server-side mailbox.
 * Supports configurable database path (via ASTER_DB_PATH or constructor argument),
 * WAL journal mode, busy timeouts, and indexed deterministic offline queues.
 */
class MailboxDatabase(
    val dbPath: String = System.getenv("ASTER_DB_PATH") ?: "aster.db"
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(MailboxDatabase::class.java)
    private val lock = Any()
    private val connection: Connection

    init {
        if (dbPath != ":memory:") {
            val file = File(dbPath)
            file.parentFile?.mkdirs()
        }

        logger.info("Initializing SQLite Mailbox database at: $dbPath")
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

        synchronized(lock) {
            connection.createStatement().use { stmt ->
                if (dbPath != ":memory:") {
                    stmt.execute("PRAGMA journal_mode = WAL;")
                    stmt.execute("PRAGMA synchronous = NORMAL;")
                }
                stmt.execute("PRAGMA busy_timeout = 5000;")
                stmt.execute("PRAGMA foreign_keys = ON;")

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS mailbox_messages (
                        message_id TEXT PRIMARY KEY NOT NULL,
                        conversation_id TEXT NOT NULL,
                        sender_user_id TEXT NOT NULL,
                        recipient_user_id TEXT NOT NULL,
                        body TEXT NOT NULL,
                        client_timestamp INTEGER NOT NULL,
                        server_received_timestamp INTEGER NOT NULL,
                        delivery_state TEXT NOT NULL
                    );
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_mailbox_recipient_state 
                    ON mailbox_messages (recipient_user_id, delivery_state);
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_mailbox_order 
                    ON mailbox_messages (server_received_timestamp, message_id);
                    """.trimIndent()
                )
            }
        }
    }

    /**
     * Checks if a message with the given ID already exists in the database.
     */
    suspend fun hasMessage(messageId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                "SELECT 1 FROM mailbox_messages WHERE message_id = ? LIMIT 1"
            ).use { ps ->
                ps.setString(1, messageId)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    /**
     * Enqueues a new message into SQLite.
     * Returns true if newly inserted, false if message_id was already present (idempotent).
     */
    suspend fun enqueueMessage(msg: MailboxMessage): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO mailbox_messages (
                    message_id, conversation_id, sender_user_id, recipient_user_id,
                    body, client_timestamp, server_received_timestamp, delivery_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, msg.messageId)
                ps.setString(2, msg.conversationId)
                ps.setString(3, msg.senderUserId)
                ps.setString(4, msg.recipientUserId)
                ps.setString(5, msg.body)
                ps.setLong(6, msg.clientTimestamp)
                ps.setLong(7, msg.serverReceivedTimestamp)
                ps.setString(8, msg.deliveryState.name)
                val rows = ps.executeUpdate()
                rows > 0
            }
        }
    }

    /**
     * Updates the delivery state of a message.
     */
    suspend fun updateDeliveryState(messageId: String, newState: DeliveryState): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                "UPDATE mailbox_messages SET delivery_state = ? WHERE message_id = ?"
            ).use { ps ->
                ps.setString(1, newState.name)
                ps.setString(2, messageId)
                val rows = ps.executeUpdate()
                rows > 0
            }
        }
    }

    /**
     * Retrieves all unacknowledged (QUEUED or in-flight DELIVERED) messages for the given recipient,
     * ordered deterministically by server_received_timestamp ASC, then message_id ASC.
     */
    suspend fun getPendingMessagesForRecipient(recipientUserId: String): List<MailboxMessage> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                """
                SELECT message_id, conversation_id, sender_user_id, recipient_user_id,
                       body, client_timestamp, server_received_timestamp, delivery_state
                FROM mailbox_messages
                WHERE recipient_user_id = ? AND delivery_state IN ('QUEUED', 'DELIVERED')
                ORDER BY server_received_timestamp ASC, message_id ASC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, recipientUserId)
                ps.executeQuery().use { rs ->
                    val messages = mutableListOf<MailboxMessage>()
                    while (rs.next()) {
                        messages.add(
                            MailboxMessage(
                                messageId = rs.getString("message_id"),
                                conversationId = rs.getString("conversation_id"),
                                senderUserId = rs.getString("sender_user_id"),
                                recipientUserId = rs.getString("recipient_user_id"),
                                body = rs.getString("body"),
                                clientTimestamp = rs.getLong("client_timestamp"),
                                serverReceivedTimestamp = rs.getLong("server_received_timestamp"),
                                deliveryState = DeliveryState.valueOf(rs.getString("delivery_state"))
                            )
                        )
                    }
                    messages
                }
            }
        }
    }

    /**
     * Retrieves a single message by ID.
     */
    suspend fun getMessage(messageId: String): MailboxMessage? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                """
                SELECT message_id, conversation_id, sender_user_id, recipient_user_id,
                       body, client_timestamp, server_received_timestamp, delivery_state
                FROM mailbox_messages
                WHERE message_id = ? LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, messageId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        MailboxMessage(
                            messageId = rs.getString("message_id"),
                            conversationId = rs.getString("conversation_id"),
                            senderUserId = rs.getString("sender_user_id"),
                            recipientUserId = rs.getString("recipient_user_id"),
                            body = rs.getString("body"),
                            clientTimestamp = rs.getLong("client_timestamp"),
                            serverReceivedTimestamp = rs.getLong("server_received_timestamp"),
                            deliveryState = DeliveryState.valueOf(rs.getString("delivery_state"))
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    /**
     * Returns total count of messages in the mailbox database.
     */
    suspend fun getMessageCount(): Long = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM mailbox_messages").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!connection.isClosed) {
                logger.info("Closing SQLite Mailbox database at: $dbPath")
                connection.close()
            }
        }
    }
}
