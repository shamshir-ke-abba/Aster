package com.aster.backend.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

enum class DeliveryState {
    QUEUED,     // Accepted by server, stored in SQLite mailbox, waiting for recipient
    DELIVERED,  // Written to recipient's active WebSocket connection (in-flight)
    ACKED       // Recipient sent ACK_RECEIVED back to server
}

data class AuthTokenRecord(
    val tokenHash: String,
    val userId: String,
    val deviceId: String,
    val createdAt: Long,
    val revokedAt: Long? = null
)

data class UserRecord(
    val userId: String,
    val asterId: String,
    val username: String,
    val displayName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isActive: Boolean = true
)

data class FriendRequestRecord(
    val id: String,
    val senderUserId: String,
    val recipientUserId: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)


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

data class GroupRecord(
    val groupId: String,
    val name: String,
    val creatorUserId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isActive: Boolean = true
)

data class GroupMemberRecord(
    val groupId: String,
    val userId: String,
    val role: String, // "CREATOR", "ADMIN", "MEMBER"
    val joinedAt: Long,
    val isActive: Boolean = true,
    val leftAt: Long? = null
)

/**
 * Thread-safe SQLite repository for Aster's durable server-side mailbox and groups.
 * Supports configurable database path, WAL journal mode, busy timeouts, versioned migrations,
 * and deterministic offline queues.
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

                val versionRs = stmt.executeQuery("PRAGMA user_version;")
                val version = if (versionRs.next()) versionRs.getInt(1) else 0

                if (version < 2) {
                    logger.info("Migrating MailboxDatabase from version $version to Schema Version 2...")
                    val prevAutoCommit = connection.autoCommit
                    try {
                        stmt.execute("PRAGMA foreign_keys = OFF;")
                        connection.autoCommit = false

                        // Step 1: Ensure auth_tokens exists
                        stmt.execute(
                            """
                            CREATE TABLE IF NOT EXISTS auth_tokens (
                                token_hash TEXT PRIMARY KEY NOT NULL,
                                user_id TEXT NOT NULL,
                                device_id TEXT NOT NULL,
                                created_at INTEGER NOT NULL,
                                revoked_at INTEGER
                            );
                            """.trimIndent()
                        )
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_auth_tokens_user ON auth_tokens (user_id);")

                        // Step 2: Handle mailbox_messages schema & primary key
                        val tableRs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='mailbox_messages';")
                        val tableExists = tableRs.next()
                        if (!tableExists) {
                            stmt.execute(
                                """
                                CREATE TABLE mailbox_messages (
                                    message_id TEXT NOT NULL,
                                    conversation_id TEXT NOT NULL,
                                    sender_user_id TEXT NOT NULL,
                                    recipient_user_id TEXT NOT NULL,
                                    body TEXT NOT NULL,
                                    client_timestamp INTEGER NOT NULL,
                                    server_received_timestamp INTEGER NOT NULL,
                                    delivery_state TEXT NOT NULL,
                                    PRIMARY KEY (message_id, recipient_user_id)
                                );
                                """.trimIndent()
                            )
                            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mailbox_recipient_state ON mailbox_messages (recipient_user_id, delivery_state);")
                            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mailbox_order ON mailbox_messages (server_received_timestamp, message_id);")
                        } else {
                            val colInfoRs = stmt.executeQuery("PRAGMA table_info(mailbox_messages);")
                            var msgIdPk = 0
                            var recipientPk = 0
                            while (colInfoRs.next()) {
                                val colName = colInfoRs.getString("name")
                                val pk = colInfoRs.getInt("pk")
                                if (colName == "message_id") msgIdPk = pk
                                if (colName == "recipient_user_id") recipientPk = pk
                            }

                            if (msgIdPk == 1 && recipientPk == 0) {
                                stmt.execute("DROP TABLE IF EXISTS mailbox_messages_v2;")
                                stmt.execute(
                                    """
                                    CREATE TABLE mailbox_messages_v2 (
                                        message_id TEXT NOT NULL,
                                        conversation_id TEXT NOT NULL,
                                        sender_user_id TEXT NOT NULL,
                                        recipient_user_id TEXT NOT NULL,
                                        body TEXT NOT NULL,
                                        client_timestamp INTEGER NOT NULL,
                                        server_received_timestamp INTEGER NOT NULL,
                                        delivery_state TEXT NOT NULL,
                                        PRIMARY KEY (message_id, recipient_user_id)
                                    );
                                    """.trimIndent()
                                )
                                stmt.execute(
                                    """
                                    INSERT OR IGNORE INTO mailbox_messages_v2 (
                                        message_id, conversation_id, sender_user_id, recipient_user_id,
                                        body, client_timestamp, server_received_timestamp, delivery_state
                                    ) SELECT message_id, conversation_id, sender_user_id, recipient_user_id,
                                             body, client_timestamp, server_received_timestamp, delivery_state
                                      FROM mailbox_messages;
                                    """.trimIndent()
                                )
                                stmt.execute("DROP TABLE mailbox_messages;")
                                stmt.execute("ALTER TABLE mailbox_messages_v2 RENAME TO mailbox_messages;")
                                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mailbox_recipient_state ON mailbox_messages (recipient_user_id, delivery_state);")
                                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mailbox_order ON mailbox_messages (server_received_timestamp, message_id);")
                            }
                        }

                        // Step 3: Ensure groups and group_members tables exist
                        stmt.execute(
                            """
                            CREATE TABLE IF NOT EXISTS groups (
                                group_id TEXT PRIMARY KEY NOT NULL,
                                name TEXT NOT NULL,
                                creator_user_id TEXT NOT NULL,
                                created_at INTEGER NOT NULL,
                                updated_at INTEGER NOT NULL,
                                is_active INTEGER NOT NULL DEFAULT 1
                            );
                            """.trimIndent()
                        )

                        stmt.execute(
                            """
                            CREATE TABLE IF NOT EXISTS group_members (
                                group_id TEXT NOT NULL,
                                user_id TEXT NOT NULL,
                                role TEXT NOT NULL,
                                joined_at INTEGER NOT NULL,
                                is_active INTEGER NOT NULL DEFAULT 1,
                                left_at INTEGER,
                                PRIMARY KEY (group_id, user_id),
                                FOREIGN KEY (group_id) REFERENCES groups(group_id) ON DELETE CASCADE
                            );
                            """.trimIndent()
                        )

                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_group_members_user ON group_members (user_id, is_active);")
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_group_members_group ON group_members (group_id, is_active);")

                        stmt.execute("PRAGMA user_version = 2;")
                        connection.commit()
                        logger.info("MailboxDatabase migration to Schema Version 2 complete.")
                    } catch (e: Exception) {
                        connection.rollback()
                        logger.error("Failed migrating MailboxDatabase to Schema Version 2: ${e.message}", e)
                        throw e
                    } finally {
                        connection.autoCommit = prevAutoCommit
                        stmt.execute("PRAGMA foreign_keys = ON;")
                    }
                }

                if (version < 3) {
                    logger.info("Migrating MailboxDatabase from version $version to Schema Version 3...")
                    val prevAutoCommit = connection.autoCommit
                    try {
                        stmt.execute("PRAGMA foreign_keys = OFF;")
                        connection.autoCommit = false

                        // Step 1: Users table
                        stmt.execute(
                            """
                            CREATE TABLE IF NOT EXISTS users (
                                user_id TEXT PRIMARY KEY NOT NULL,
                                aster_id TEXT UNIQUE NOT NULL,
                                username TEXT UNIQUE NOT NULL,
                                display_name TEXT NOT NULL,
                                created_at INTEGER NOT NULL,
                                updated_at INTEGER NOT NULL,
                                is_active INTEGER NOT NULL DEFAULT 1
                            );
                            """.trimIndent()
                        )
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_users_aster_id ON users (aster_id);")
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_users_username ON users (username);")

                        // Step 2: Friend requests table
                        stmt.execute(
                            """
                            CREATE TABLE IF NOT EXISTS friend_requests (
                                id TEXT PRIMARY KEY NOT NULL,
                                sender_user_id TEXT NOT NULL,
                                recipient_user_id TEXT NOT NULL,
                                status TEXT NOT NULL DEFAULT 'PENDING',
                                created_at INTEGER NOT NULL,
                                updated_at INTEGER NOT NULL,
                                UNIQUE (sender_user_id, recipient_user_id)
                            );
                            """.trimIndent()
                        )
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_friend_requests_recipient ON friend_requests (recipient_user_id, status);")
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_friend_requests_sender ON friend_requests (sender_user_id, status);")

                        // Step 3: Blocked users table
                        stmt.execute(
                            """
                            CREATE TABLE IF NOT EXISTS blocked_users (
                                blocker_user_id TEXT NOT NULL,
                                blocked_user_id TEXT NOT NULL,
                                created_at INTEGER NOT NULL,
                                PRIMARY KEY (blocker_user_id, blocked_user_id)
                            );
                            """.trimIndent()
                        )
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_blocked_users_blocker ON blocked_users (blocker_user_id);")

                        // Step 4: Seed existing known test accounts into users table
                        val now = System.currentTimeMillis()
                        val defaultKnown = listOf(
                            Triple("user_saad", "AST-SAAD01", Pair("saad", "Saad")),
                            Triple("user_alex", "AST-ALEX01", Pair("alex", "Alex")),
                            Triple("user_rohan", "AST-ROHAN1", Pair("rohan", "Rohan")),
                            Triple("user_priya", "AST-PRIYA1", Pair("priya", "Priya"))
                        )
                        for ((uid, aid, names) in defaultKnown) {
                            connection.prepareStatement(
                                """
                                INSERT OR IGNORE INTO users (user_id, aster_id, username, display_name, created_at, updated_at, is_active)
                                VALUES (?, ?, ?, ?, ?, ?, 1)
                                """.trimIndent()
                            ).use { ps ->
                                ps.setString(1, uid)
                                ps.setString(2, aid)
                                ps.setString(3, names.first)
                                ps.setString(4, names.second)
                                ps.setLong(5, now)
                                ps.setLong(6, now)
                                ps.executeUpdate()
                            }
                        }

                        stmt.execute("PRAGMA user_version = 3;")
                        connection.commit()
                        logger.info("MailboxDatabase migration to Schema Version 3 complete.")
                    } catch (e: Exception) {
                        connection.rollback()
                        logger.error("Failed migrating MailboxDatabase to Schema Version 3: ${e.message}", e)
                        throw e
                    } finally {
                        connection.autoCommit = prevAutoCommit
                        stmt.execute("PRAGMA foreign_keys = ON;")
                    }
                }

                // Push tokens table for optional push delivery
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS push_tokens (
                        user_id TEXT NOT NULL,
                        device_id TEXT NOT NULL,
                        push_token TEXT NOT NULL,
                        endpoint TEXT,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (user_id, device_id)
                    );
                    """.trimIndent()
                )
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_push_tokens_user ON push_tokens (user_id);")
            }
        }
    }

    fun getSchemaVersion(): Int = synchronized(lock) {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA user_version;").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun hashToken(token: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(token.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun generateSecureToken(): String {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            return "ast_tok_" + bytes.joinToString("") { "%02x".format(it) }
        }

        private const val ASTER_ID_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

        fun generateAsterId(): String {
            val sb = StringBuilder("AST-")
            for (i in 0 until 6) {
                val idx = secureRandom.nextInt(ASTER_ID_ALPHABET.length)
                sb.append(ASTER_ID_ALPHABET[idx])
            }
            return sb.toString()
        }
    }

    // =========================================================================
    // Authentication Token Management
    // =========================================================================

    suspend fun createToken(
        userId: String,
        deviceId: String = UUID.randomUUID().toString()
    ): String = withContext(Dispatchers.IO) {
        val plaintextToken = generateSecureToken()
        val tokenHash = hashToken(plaintextToken)
        val now = System.currentTimeMillis()

        synchronized(lock) {
            connection.prepareStatement(
                """
                INSERT INTO auth_tokens (token_hash, user_id, device_id, created_at, revoked_at)
                VALUES (?, ?, ?, ?, NULL)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, tokenHash)
                ps.setString(2, userId)
                ps.setString(3, deviceId)
                ps.setLong(4, now)
                ps.executeUpdate()
            }
        }
        plaintextToken
    }

    suspend fun validateToken(token: String): AuthTokenRecord? = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext null
        val tokenHash = hashToken(token)

        synchronized(lock) {
            connection.prepareStatement(
                """
                SELECT token_hash, user_id, device_id, created_at, revoked_at
                FROM auth_tokens
                WHERE token_hash = ? AND revoked_at IS NULL
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, tokenHash)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        AuthTokenRecord(
                            tokenHash = rs.getString("token_hash"),
                            userId = rs.getString("user_id"),
                            deviceId = rs.getString("device_id"),
                            createdAt = rs.getLong("created_at"),
                            revokedAt = if (rs.getObject("revoked_at") != null) rs.getLong("revoked_at") else null
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    suspend fun revokeToken(token: String): Boolean = withContext(Dispatchers.IO) {
        val tokenHash = hashToken(token)
        val now = System.currentTimeMillis()

        synchronized(lock) {
            connection.prepareStatement(
                "UPDATE auth_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL"
            ).use { ps ->
                ps.setLong(1, now)
                ps.setString(2, tokenHash)
                val updated = ps.executeUpdate()
                updated > 0
            }
        }
    }

    suspend fun revokeTokensForUser(userId: String): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val count = synchronized(lock) {
            connection.prepareStatement(
                "UPDATE auth_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL"
            ).use { ps ->
                ps.setLong(1, now)
                ps.setString(2, userId)
                ps.executeUpdate()
            }
        }
        handleRevokedUserGroups(userId)
        removePushTokensForUser(userId)
        count
    }

    suspend fun revokeTokensForDevice(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val updated = synchronized(lock) {
            connection.prepareStatement(
                "UPDATE auth_tokens SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL"
            ).use { ps ->
                ps.setLong(1, now)
                ps.setString(2, deviceId)
                ps.executeUpdate()
            }
        }
        removePushTokenForDevice(deviceId)
        updated > 0
    }

    suspend fun getUserIdsForDevice(deviceId: String): List<String> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                "SELECT DISTINCT user_id FROM auth_tokens WHERE device_id = ? AND revoked_at IS NULL"
            ).use { ps ->
                ps.setString(1, deviceId)
                ps.executeQuery().use { rs ->
                    val userIds = mutableListOf<String>()
                    while (rs.next()) {
                        userIds.add(rs.getString(1))
                    }
                    userIds
                }
            }
        }
    }

    suspend fun purgeRevokedTokens(olderThanMs: Long = 30L * 24 * 3600 * 1000): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        synchronized(lock) {
            connection.prepareStatement(
                "DELETE FROM auth_tokens WHERE revoked_at IS NOT NULL AND revoked_at < ?"
            ).use { ps ->
                ps.setLong(1, cutoff)
                ps.executeUpdate()
            }
        }
    }

    suspend fun getActiveTokenCountForUser(userId: String): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                "SELECT COUNT(*) FROM auth_tokens WHERE user_id = ? AND revoked_at IS NULL"
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    // =========================================================================
    // Push Token Management
    // =========================================================================

    suspend fun registerPushToken(
        userId: String,
        deviceId: String,
        pushToken: String,
        endpoint: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            connection.prepareStatement(
                """
                INSERT INTO push_tokens (user_id, device_id, push_token, endpoint, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(user_id, device_id) DO UPDATE SET
                    push_token = excluded.push_token,
                    endpoint = excluded.endpoint,
                    updated_at = excluded.updated_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, deviceId)
                ps.setString(3, pushToken)
                ps.setString(4, endpoint)
                ps.setLong(5, now)
                ps.executeUpdate() > 0
            }
        }
    }

    suspend fun getPushTokensForUser(userId: String): List<String> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                "SELECT push_token FROM push_tokens WHERE user_id = ?"
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    val tokens = mutableListOf<String>()
                    while (rs.next()) {
                        tokens.add(rs.getString("push_token"))
                    }
                    tokens
                }
            }
        }
    }

    suspend fun removePushTokensForUser(userId: String): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                "DELETE FROM push_tokens WHERE user_id = ?"
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeUpdate()
            }
        }
    }

    suspend fun removePushTokenForDevice(deviceId: String): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                "DELETE FROM push_tokens WHERE device_id = ?"
            ).use { ps ->
                ps.setString(1, deviceId)
                ps.executeUpdate()
            }
        }
    }

    // =========================================================================
    // Group Management
    // =========================================================================

    suspend fun createGroup(
        name: String,
        creatorUserId: String,
        initialMemberUserIds: List<String> = emptyList(),
        customGroupId: String? = null
    ): GroupRecord = withContext(Dispatchers.IO) {
        val groupId = customGroupId ?: ("group_" + UUID.randomUUID().toString())
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val prevAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                // 1. Insert Group
                connection.prepareStatement(
                    """
                    INSERT INTO groups (group_id, name, creator_user_id, created_at, updated_at, is_active)
                    VALUES (?, ?, ?, ?, ?, 1)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, groupId)
                    ps.setString(2, name.trim())
                    ps.setString(3, creatorUserId)
                    ps.setLong(4, now)
                    ps.setLong(5, now)
                    ps.executeUpdate()
                }

                // 2. Insert Creator as CREATOR
                connection.prepareStatement(
                    """
                    INSERT INTO group_members (group_id, user_id, role, joined_at, is_active, left_at)
                    VALUES (?, ?, 'CREATOR', ?, 1, NULL)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, groupId)
                    ps.setString(2, creatorUserId)
                    ps.setLong(3, now)
                    ps.executeUpdate()
                }

                // 3. Insert Initial Members
                val uniqueMembers = initialMemberUserIds.filter { it.isNotBlank() && it != creatorUserId }.distinct()
                if (uniqueMembers.isNotEmpty()) {
                    connection.prepareStatement(
                        """
                        INSERT INTO group_members (group_id, user_id, role, joined_at, is_active, left_at)
                        VALUES (?, ?, 'MEMBER', ?, 1, NULL)
                        """.trimIndent()
                    ).use { ps ->
                        for (memberId in uniqueMembers) {
                            ps.setString(1, groupId)
                            ps.setString(2, memberId)
                            ps.setLong(3, now)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }

                connection.commit()
                GroupRecord(
                    groupId = groupId,
                    name = name.trim(),
                    creatorUserId = creatorUserId,
                    createdAt = now,
                    updatedAt = now,
                    isActive = true
                )
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = prevAutoCommit
            }
        }
    }

    suspend fun getGroup(groupId: String): GroupRecord? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                """
                SELECT group_id, name, creator_user_id, created_at, updated_at, is_active
                FROM groups
                WHERE group_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, groupId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        GroupRecord(
                            groupId = rs.getString("group_id"),
                            name = rs.getString("name"),
                            creatorUserId = rs.getString("creator_user_id"),
                            createdAt = rs.getLong("created_at"),
                            updatedAt = rs.getLong("updated_at"),
                            isActive = rs.getInt("is_active") == 1
                        )
                    } else null
                }
            }
        }
    }

    suspend fun getGroupsForUser(userId: String): List<GroupRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                """
                SELECT g.group_id, g.name, g.creator_user_id, g.created_at, g.updated_at, g.is_active
                FROM groups g
                INNER JOIN group_members gm ON g.group_id = gm.group_id
                WHERE gm.user_id = ? AND gm.is_active = 1 AND g.is_active = 1
                ORDER BY g.updated_at DESC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    val groups = mutableListOf<GroupRecord>()
                    while (rs.next()) {
                        groups.add(
                            GroupRecord(
                                groupId = rs.getString("group_id"),
                                name = rs.getString("name"),
                                creatorUserId = rs.getString("creator_user_id"),
                                createdAt = rs.getLong("created_at"),
                                updatedAt = rs.getLong("updated_at"),
                                isActive = rs.getInt("is_active") == 1
                            )
                        )
                    }
                    groups
                }
            }
        }
    }

    suspend fun getActiveMembers(groupId: String): List<GroupMemberRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                """
                SELECT group_id, user_id, role, joined_at, is_active, left_at
                FROM group_members
                WHERE group_id = ? AND is_active = 1
                ORDER BY joined_at ASC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, groupId)
                ps.executeQuery().use { rs ->
                    val members = mutableListOf<GroupMemberRecord>()
                    while (rs.next()) {
                        members.add(
                            GroupMemberRecord(
                                groupId = rs.getString("group_id"),
                                userId = rs.getString("user_id"),
                                role = rs.getString("role"),
                                joinedAt = rs.getLong("joined_at"),
                                isActive = rs.getInt("is_active") == 1,
                                leftAt = rs.getObject("left_at")?.let { rs.getLong("left_at") }
                            )
                        )
                    }
                    members
                }
            }
        }
    }

    suspend fun isMember(groupId: String, userId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                "SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ? AND is_active = 1 LIMIT 1"
            ).use { ps ->
                ps.setString(1, groupId)
                ps.setString(2, userId)
                ps.executeQuery().use { it.next() }
            }
        }
    }

    suspend fun getMemberRole(groupId: String, userId: String): String? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                "SELECT role FROM group_members WHERE group_id = ? AND user_id = ? AND is_active = 1 LIMIT 1"
            ).use { ps ->
                ps.setString(1, groupId)
                ps.setString(2, userId)
                ps.executeQuery().use { if (it.next()) it.getString("role") else null }
            }
        }
    }

    suspend fun addGroupMember(groupId: String, userId: String, role: String = "MEMBER"): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val prevAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                // Check if already active
                connection.prepareStatement(
                    "SELECT is_active FROM group_members WHERE group_id = ? AND user_id = ?"
                ).use { ps ->
                    ps.setString(1, groupId)
                    ps.setString(2, userId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            if (rs.getInt("is_active") == 1) {
                                // Already active
                                return@withContext true
                            } else {
                                // Reactivate former member
                                connection.prepareStatement(
                                    "UPDATE group_members SET is_active = 1, left_at = NULL, role = ?, joined_at = ? WHERE group_id = ? AND user_id = ?"
                                ).use { updatePs ->
                                    updatePs.setString(1, role)
                                    updatePs.setLong(2, now)
                                    updatePs.setString(3, groupId)
                                    updatePs.setString(4, userId)
                                    updatePs.executeUpdate()
                                }
                            }
                        } else {
                            // Insert new member
                            connection.prepareStatement(
                                "INSERT INTO group_members (group_id, user_id, role, joined_at, is_active, left_at) VALUES (?, ?, ?, ?, 1, NULL)"
                            ).use { insertPs ->
                                insertPs.setString(1, groupId)
                                insertPs.setString(2, userId)
                                insertPs.setString(3, role)
                                insertPs.setLong(4, now)
                                insertPs.executeUpdate()
                            }
                        }
                    }
                }

                connection.prepareStatement("UPDATE groups SET updated_at = ? WHERE group_id = ?").use { ps ->
                    ps.setLong(1, now)
                    ps.setString(2, groupId)
                    ps.executeUpdate()
                }

                connection.commit()
                true
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = prevAutoCommit
            }
        }
    }

    suspend fun removeGroupMember(groupId: String, userId: String): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val prevAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                // Fetch member role
                var memberRole: String? = null
                connection.prepareStatement(
                    "SELECT role FROM group_members WHERE group_id = ? AND user_id = ? AND is_active = 1"
                ).use { ps ->
                    ps.setString(1, groupId)
                    ps.setString(2, userId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            memberRole = rs.getString("role")
                        } else {
                            return@withContext false
                        }
                    }
                }

                // Deactivate member
                connection.prepareStatement(
                    "UPDATE group_members SET is_active = 0, left_at = ? WHERE group_id = ? AND user_id = ?"
                ).use { ps ->
                    ps.setLong(1, now)
                    ps.setString(2, groupId)
                    ps.setString(3, userId)
                    ps.executeUpdate()
                }

                // Discard any pending/undelivered messages for this removed member in this group
                connection.prepareStatement(
                    "DELETE FROM mailbox_messages WHERE conversation_id = ? AND recipient_user_id = ? AND delivery_state IN ('QUEUED', 'DELIVERED')"
                ).use { ps ->
                    ps.setString(1, groupId)
                    ps.setString(2, userId)
                    ps.executeUpdate()
                }

                // If departing user was CREATOR, handover to next oldest active member
                if (memberRole == "CREATOR") {
                    var nextCreator: String? = null
                    connection.prepareStatement(
                        "SELECT user_id FROM group_members WHERE group_id = ? AND is_active = 1 ORDER BY joined_at ASC LIMIT 1"
                    ).use { ps ->
                        ps.setString(1, groupId)
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                nextCreator = rs.getString("user_id")
                            }
                        }
                    }

                    if (nextCreator != null) {
                        connection.prepareStatement(
                            "UPDATE group_members SET role = 'CREATOR' WHERE group_id = ? AND user_id = ?"
                        ).use { ps ->
                            ps.setString(1, groupId)
                            ps.setString(2, nextCreator)
                            ps.executeUpdate()
                        }
                        connection.prepareStatement(
                            "UPDATE groups SET creator_user_id = ?, updated_at = ? WHERE group_id = ?"
                        ).use { ps ->
                            ps.setString(1, nextCreator)
                            ps.setLong(2, now)
                            ps.setString(3, groupId)
                            ps.executeUpdate()
                        }
                    } else {
                        // No active members remain: archive group
                        connection.prepareStatement(
                            "UPDATE groups SET is_active = 0, updated_at = ? WHERE group_id = ?"
                        ).use { ps ->
                            ps.setLong(1, now)
                            ps.setString(2, groupId)
                            ps.executeUpdate()
                        }
                    }
                } else {
                    connection.prepareStatement("UPDATE groups SET updated_at = ? WHERE group_id = ?").use { ps ->
                        ps.setLong(1, now)
                        ps.setString(2, groupId)
                        ps.executeUpdate()
                    }
                }

                connection.commit()
                true
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = prevAutoCommit
            }
        }
    }

    suspend fun leaveGroup(groupId: String, userId: String): Boolean = removeGroupMember(groupId, userId)

    suspend fun renameGroup(groupId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            connection.prepareStatement(
                "UPDATE groups SET name = ?, updated_at = ? WHERE group_id = ? AND is_active = 1"
            ).use { ps ->
                ps.setString(1, newName.trim())
                ps.setLong(2, now)
                ps.setString(3, groupId)
                val rows = ps.executeUpdate()
                rows > 0
            }
        }
    }

    suspend fun handleRevokedUserGroups(revokedUserId: String): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            // Find all groups where revoked user was creator
            val creatorGroups = mutableListOf<String>()
            connection.prepareStatement(
                "SELECT group_id FROM groups WHERE creator_user_id = ? AND is_active = 1"
            ).use { ps ->
                ps.setString(1, revokedUserId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        creatorGroups.add(rs.getString("group_id"))
                    }
                }
            }

            for (groupId in creatorGroups) {
                // Find next active member
                var nextCreator: String? = null
                connection.prepareStatement(
                    "SELECT user_id FROM group_members WHERE group_id = ? AND is_active = 1 AND user_id != ? ORDER BY joined_at ASC LIMIT 1"
                ).use { ps ->
                    ps.setString(1, groupId)
                    ps.setString(2, revokedUserId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            nextCreator = rs.getString("user_id")
                        }
                    }
                }

                if (nextCreator != null) {
                    connection.prepareStatement(
                        "UPDATE group_members SET role = 'CREATOR' WHERE group_id = ? AND user_id = ?"
                    ).use { ps ->
                        ps.setString(1, groupId)
                        ps.setString(2, nextCreator)
                        ps.executeUpdate()
                    }
                    connection.prepareStatement(
                        "UPDATE groups SET creator_user_id = ?, updated_at = ? WHERE group_id = ?"
                    ).use { ps ->
                        ps.setString(1, nextCreator)
                        ps.setLong(2, now)
                        ps.setString(3, groupId)
                        ps.executeUpdate()
                    }
                } else {
                    connection.prepareStatement(
                        "UPDATE groups SET is_active = 0, updated_at = ? WHERE group_id = ?"
                    ).use { ps ->
                        ps.setLong(1, now)
                        ps.setString(2, groupId)
                        ps.executeUpdate()
                    }
                }
            }
        }
    }

    // =========================================================================
    // Mailbox & Message Operations (Direct & Group Fan-Out)
    // =========================================================================

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

    suspend fun enqueueGroupMessage(msg: MailboxMessage, recipientUserIds: List<String>): Int = withContext(Dispatchers.IO) {
        if (recipientUserIds.isEmpty()) return@withContext 0
        synchronized(lock) {
            val prevAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT OR IGNORE INTO mailbox_messages (
                        message_id, conversation_id, sender_user_id, recipient_user_id,
                        body, client_timestamp, server_received_timestamp, delivery_state
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    for (recipientId in recipientUserIds) {
                        ps.setString(1, msg.messageId)
                        ps.setString(2, msg.conversationId)
                        ps.setString(3, msg.senderUserId)
                        ps.setString(4, recipientId)
                        ps.setString(5, msg.body)
                        ps.setLong(6, msg.clientTimestamp)
                        ps.setLong(7, msg.serverReceivedTimestamp)
                        ps.setString(8, msg.deliveryState.name)
                        ps.addBatch()
                    }
                    val results = ps.executeBatch()
                    connection.commit()
                    results.sumOf { if (it > 0) it else 0 }
                }
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = prevAutoCommit
            }
        }
    }

    suspend fun updateDeliveryState(messageId: String, newState: DeliveryState): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val sql = if (newState == DeliveryState.DELIVERED) {
                "UPDATE mailbox_messages SET delivery_state = ? WHERE message_id = ? AND delivery_state != 'ACKED'"
            } else {
                "UPDATE mailbox_messages SET delivery_state = ? WHERE message_id = ?"
            }
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, newState.name)
                ps.setString(2, messageId)
                val rows = ps.executeUpdate()
                rows > 0
            }
        }
    }

    suspend fun updateDeliveryState(messageId: String, recipientUserId: String, newState: DeliveryState): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val sql = if (newState == DeliveryState.DELIVERED) {
                "UPDATE mailbox_messages SET delivery_state = ? WHERE message_id = ? AND recipient_user_id = ? AND delivery_state != 'ACKED'"
            } else {
                "UPDATE mailbox_messages SET delivery_state = ? WHERE message_id = ? AND recipient_user_id = ?"
            }
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, newState.name)
                ps.setString(2, messageId)
                ps.setString(3, recipientUserId)
                val rows = ps.executeUpdate()
                rows > 0
            }
        }
    }

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

    suspend fun getMessageForRecipient(messageId: String, recipientUserId: String): MailboxMessage? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                """
                SELECT message_id, conversation_id, sender_user_id, recipient_user_id,
                       body, client_timestamp, server_received_timestamp, delivery_state
                FROM mailbox_messages
                WHERE message_id = ? AND recipient_user_id = ? LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, messageId)
                ps.setString(2, recipientUserId)
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

    suspend fun getMessageCount(): Long = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM mailbox_messages").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    }

    // =========================================================================
    // User Identity and Profile Management (Phase C)
    // =========================================================================

    suspend fun createUser(
        username: String,
        displayName: String,
        deviceId: String = UUID.randomUUID().toString()
    ): Pair<UserRecord, String> = withContext(Dispatchers.IO) {
        val cleanUsername = username.trim().lowercase()
        val cleanDisplayName = displayName.trim()
        val now = System.currentTimeMillis()

        synchronized(lock) {
            // Check username uniqueness
            connection.prepareStatement("SELECT user_id FROM users WHERE LOWER(username) = ? LIMIT 1").use { ps ->
                ps.setString(1, cleanUsername)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        throw IllegalArgumentException("Username '$cleanUsername' is already taken")
                    }
                }
            }

            // Generate unique collision-resistant Aster ID
            var asterId = ""
            for (attempt in 1..10) {
                val candidate = generateAsterId()
                connection.prepareStatement("SELECT user_id FROM users WHERE aster_id = ? LIMIT 1").use { ps ->
                    ps.setString(1, candidate)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) {
                            asterId = candidate
                        }
                    }
                }
                if (asterId.isNotBlank()) break
            }
            if (asterId.isBlank()) {
                throw IllegalStateException("Failed to generate unique Aster ID after 10 attempts")
            }

            val userId = "user_" + UUID.randomUUID().toString().replace("-", "").take(16)

            connection.prepareStatement(
                """
                INSERT INTO users (user_id, aster_id, username, display_name, created_at, updated_at, is_active)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, asterId)
                ps.setString(3, cleanUsername)
                ps.setString(4, cleanDisplayName)
                ps.setLong(5, now)
                ps.setLong(6, now)
                ps.executeUpdate()
            }

            val token = createTokenInternal(userId, deviceId)
            val userRecord = UserRecord(
                userId = userId,
                asterId = asterId,
                username = cleanUsername,
                displayName = cleanDisplayName,
                createdAt = now,
                updatedAt = now,
                isActive = true
            )
            Pair(userRecord, token)
        }
    }

    private fun createTokenInternal(userId: String, deviceId: String): String {
        val plaintextToken = generateSecureToken()
        val tokenHash = hashToken(plaintextToken)
        val now = System.currentTimeMillis()
        connection.prepareStatement(
            """
            INSERT INTO auth_tokens (token_hash, user_id, device_id, created_at, revoked_at)
            VALUES (?, ?, ?, ?, NULL)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, tokenHash)
            ps.setString(2, userId)
            ps.setString(3, deviceId)
            ps.setLong(4, now)
            ps.executeUpdate()
        }
        return plaintextToken
    }

    suspend fun getUser(userId: String): UserRecord? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                "SELECT user_id, aster_id, username, display_name, created_at, updated_at, is_active FROM users WHERE user_id = ? LIMIT 1"
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        UserRecord(
                            userId = rs.getString("user_id"),
                            asterId = rs.getString("aster_id"),
                            username = rs.getString("username"),
                            displayName = rs.getString("display_name"),
                            createdAt = rs.getLong("created_at"),
                            updatedAt = rs.getLong("updated_at"),
                            isActive = rs.getInt("is_active") == 1
                        )
                    } else null
                }
            }
        }
    }

    suspend fun getUserByAsterId(asterId: String): UserRecord? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                "SELECT user_id, aster_id, username, display_name, created_at, updated_at, is_active FROM users WHERE UPPER(aster_id) = ? AND is_active = 1 LIMIT 1"
            ).use { ps ->
                ps.setString(1, asterId.trim().uppercase())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        UserRecord(
                            userId = rs.getString("user_id"),
                            asterId = rs.getString("aster_id"),
                            username = rs.getString("username"),
                            displayName = rs.getString("display_name"),
                            createdAt = rs.getLong("created_at"),
                            updatedAt = rs.getLong("updated_at"),
                            isActive = rs.getInt("is_active") == 1
                        )
                    } else null
                }
            }
        }
    }

    suspend fun getUserByUsername(username: String): UserRecord? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                "SELECT user_id, aster_id, username, display_name, created_at, updated_at, is_active FROM users WHERE LOWER(username) = ? AND is_active = 1 LIMIT 1"
            ).use { ps ->
                ps.setString(1, username.trim().lowercase())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        UserRecord(
                            userId = rs.getString("user_id"),
                            asterId = rs.getString("aster_id"),
                            username = rs.getString("username"),
                            displayName = rs.getString("display_name"),
                            createdAt = rs.getLong("created_at"),
                            updatedAt = rs.getLong("updated_at"),
                            isActive = rs.getInt("is_active") == 1
                        )
                    } else null
                }
            }
        }
    }

    suspend fun searchUsers(query: String, currentUserId: String, limit: Int = 20): List<UserRecord> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        synchronized(lock) {
            val isAsterId = trimmed.startsWith("AST-", ignoreCase = true)
            val sql = if (isAsterId) {
                "SELECT user_id, aster_id, username, display_name, created_at, updated_at, is_active FROM users WHERE UPPER(aster_id) = ? AND user_id != ? AND is_active = 1 LIMIT ?"
            } else {
                "SELECT user_id, aster_id, username, display_name, created_at, updated_at, is_active FROM users WHERE (LOWER(username) LIKE ? OR LOWER(display_name) LIKE ?) AND user_id != ? AND is_active = 1 ORDER BY username ASC LIMIT ?"
            }

            connection.prepareStatement(sql).use { ps ->
                if (isAsterId) {
                    ps.setString(1, trimmed.uppercase())
                    ps.setString(2, currentUserId)
                    ps.setInt(3, limit)
                } else {
                    val pattern = "%${trimmed.lowercase()}%"
                    ps.setString(1, pattern)
                    ps.setString(2, pattern)
                    ps.setString(3, currentUserId)
                    ps.setInt(4, limit)
                }

                ps.executeQuery().use { rs ->
                    val results = mutableListOf<UserRecord>()
                    while (rs.next()) {
                        results.add(
                            UserRecord(
                                userId = rs.getString("user_id"),
                                asterId = rs.getString("aster_id"),
                                username = rs.getString("username"),
                                displayName = rs.getString("display_name"),
                                createdAt = rs.getLong("created_at"),
                                updatedAt = rs.getLong("updated_at"),
                                isActive = rs.getInt("is_active") == 1
                            )
                        )
                    }
                    results
                }
            }
        }
    }

    suspend fun updateDisplayName(userId: String, displayName: String): Boolean = withContext(Dispatchers.IO) {
        val clean = displayName.trim()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            connection.prepareStatement(
                "UPDATE users SET display_name = ?, updated_at = ? WHERE user_id = ?"
            ).use { ps ->
                ps.setString(1, clean)
                ps.setLong(2, now)
                ps.setString(3, userId)
                ps.executeUpdate() > 0
            }
        }
    }

    suspend fun updateUsername(userId: String, newUsername: String): Boolean = withContext(Dispatchers.IO) {
        val clean = newUsername.trim().lowercase()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            connection.prepareStatement(
                "SELECT user_id FROM users WHERE LOWER(username) = ? AND user_id != ? LIMIT 1"
            ).use { ps ->
                ps.setString(1, clean)
                ps.setString(2, userId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) return@withContext false
                }
            }
            connection.prepareStatement(
                "UPDATE users SET username = ?, updated_at = ? WHERE user_id = ?"
            ).use { ps ->
                ps.setString(1, clean)
                ps.setLong(2, now)
                ps.setString(3, userId)
                ps.executeUpdate() > 0
            }
        }
    }

    suspend fun deleteAccount(userId: String): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            // Revoke all tokens
            connection.prepareStatement(
                "UPDATE auth_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL"
            ).use { ps ->
                ps.setLong(1, now)
                ps.setString(2, userId)
                ps.executeUpdate()
            }
            // Mark user inactive
            connection.prepareStatement(
                "UPDATE users SET is_active = 0, updated_at = ? WHERE user_id = ?"
            ).use { ps ->
                ps.setLong(1, now)
                ps.setString(2, userId)
                ps.executeUpdate() > 0
            }
        }
    }

    // =========================================================================
    // Friend Discovery & Social Graph (Phase D)
    // =========================================================================

    suspend fun sendFriendRequest(senderUserId: String, recipientUserId: String): FriendRequestRecord = withContext(Dispatchers.IO) {
        require(senderUserId != recipientUserId) { "Cannot send a friend request to oneself" }

        synchronized(lock) {
            // Check if either user is blocked
            if (isUserBlockedInternal(senderUserId, recipientUserId) || isUserBlockedInternal(recipientUserId, senderUserId)) {
                throw IllegalStateException("Friend request prohibited due to user block")
            }

            // Check if already friends or existing pending request
            val existingSql = """
                SELECT id, status FROM friend_requests
                WHERE (sender_user_id = ? AND recipient_user_id = ?)
                   OR (sender_user_id = ? AND recipient_user_id = ?)
                LIMIT 1
            """.trimIndent()

            connection.prepareStatement(existingSql).use { ps ->
                ps.setString(1, senderUserId)
                ps.setString(2, recipientUserId)
                ps.setString(3, recipientUserId)
                ps.setString(4, senderUserId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val status = rs.getString("status")
                        if (status == "ACCEPTED") throw IllegalStateException("Already friends")
                        if (status == "PENDING") throw IllegalStateException("Friend request already pending")
                    }
                }
            }

            val reqId = "freq_" + UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()

            connection.prepareStatement(
                """
                INSERT INTO friend_requests (id, sender_user_id, recipient_user_id, status, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?)
                ON CONFLICT(sender_user_id, recipient_user_id) DO UPDATE SET
                   status = 'PENDING', updated_at = excluded.updated_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, reqId)
                ps.setString(2, senderUserId)
                ps.setString(3, recipientUserId)
                ps.setLong(4, now)
                ps.setLong(5, now)
                ps.executeUpdate()
            }

            FriendRequestRecord(
                id = reqId,
                senderUserId = senderUserId,
                recipientUserId = recipientUserId,
                status = "PENDING",
                createdAt = now,
                updatedAt = now
            )
        }
    }

    suspend fun respondFriendRequest(requestId: String, recipientUserId: String, accept: Boolean): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val newStatus = if (accept) "ACCEPTED" else "REJECTED"
        synchronized(lock) {
            connection.prepareStatement(
                """
                UPDATE friend_requests
                SET status = ?, updated_at = ?
                WHERE id = ? AND recipient_user_id = ? AND status = 'PENDING'
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, newStatus)
                ps.setLong(2, now)
                ps.setString(3, requestId)
                ps.setString(4, recipientUserId)
                ps.executeUpdate() > 0
            }
        }
    }

    suspend fun cancelFriendRequest(requestId: String, senderUserId: String): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            connection.prepareStatement(
                """
                UPDATE friend_requests
                SET status = 'CANCELLED', updated_at = ?
                WHERE id = ? AND sender_user_id = ? AND status = 'PENDING'
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, now)
                ps.setString(2, requestId)
                ps.setString(3, senderUserId)
                ps.executeUpdate() > 0
            }
        }
    }

    suspend fun getIncomingFriendRequests(userId: String): List<FriendRequestRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                """
                SELECT id, sender_user_id, recipient_user_id, status, created_at, updated_at
                FROM friend_requests
                WHERE recipient_user_id = ? AND status = 'PENDING'
                ORDER BY created_at DESC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<FriendRequestRecord>()
                    while (rs.next()) {
                        list.add(
                            FriendRequestRecord(
                                id = rs.getString("id"),
                                senderUserId = rs.getString("sender_user_id"),
                                recipientUserId = rs.getString("recipient_user_id"),
                                status = rs.getString("status"),
                                createdAt = rs.getLong("created_at"),
                                updatedAt = rs.getLong("updated_at")
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    suspend fun getOutgoingFriendRequests(userId: String): List<FriendRequestRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                """
                SELECT id, sender_user_id, recipient_user_id, status, created_at, updated_at
                FROM friend_requests
                WHERE sender_user_id = ? AND status = 'PENDING'
                ORDER BY created_at DESC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<FriendRequestRecord>()
                    while (rs.next()) {
                        list.add(
                            FriendRequestRecord(
                                id = rs.getString("id"),
                                senderUserId = rs.getString("sender_user_id"),
                                recipientUserId = rs.getString("recipient_user_id"),
                                status = rs.getString("status"),
                                createdAt = rs.getLong("created_at"),
                                updatedAt = rs.getLong("updated_at")
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    suspend fun getFriends(userId: String): List<UserRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val sql = """
                SELECT u.user_id, u.aster_id, u.username, u.display_name, u.created_at, u.updated_at, u.is_active
                FROM users u
                INNER JOIN friend_requests fr ON (
                    (fr.sender_user_id = ? AND fr.recipient_user_id = u.user_id) OR
                    (fr.recipient_user_id = ? AND fr.sender_user_id = u.user_id)
                )
                WHERE fr.status = 'ACCEPTED' AND u.is_active = 1
                ORDER BY u.display_name ASC
            """.trimIndent()

            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, userId)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<UserRecord>()
                    while (rs.next()) {
                        list.add(
                            UserRecord(
                                userId = rs.getString("user_id"),
                                asterId = rs.getString("aster_id"),
                                username = rs.getString("username"),
                                displayName = rs.getString("display_name"),
                                createdAt = rs.getLong("created_at"),
                                updatedAt = rs.getLong("updated_at"),
                                isActive = rs.getInt("is_active") == 1
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    suspend fun areFriends(userId1: String, userId2: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val sql = """
                SELECT id FROM friend_requests
                WHERE ((sender_user_id = ? AND recipient_user_id = ?) OR (sender_user_id = ? AND recipient_user_id = ?))
                  AND status = 'ACCEPTED'
                LIMIT 1
            """.trimIndent()
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, userId1)
                ps.setString(2, userId2)
                ps.setString(3, userId2)
                ps.setString(4, userId1)
                ps.executeQuery().use { it.next() }
            }
        }
    }

    // =========================================================================
    // User Blocking & Abuse Prevention
    // =========================================================================

    suspend fun blockUser(blockerUserId: String, blockedUserId: String): Boolean = withContext(Dispatchers.IO) {
        require(blockerUserId != blockedUserId) { "Cannot block oneself" }
        val now = System.currentTimeMillis()
        synchronized(lock) {
            // Remove friendship if active
            connection.prepareStatement(
                """
                UPDATE friend_requests SET status = 'CANCELLED'
                WHERE (sender_user_id = ? AND recipient_user_id = ?) OR (sender_user_id = ? AND recipient_user_id = ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, blockerUserId)
                ps.setString(2, blockedUserId)
                ps.setString(3, blockedUserId)
                ps.setString(4, blockerUserId)
                ps.executeUpdate()
            }

            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO blocked_users (blocker_user_id, blocked_user_id, created_at)
                VALUES (?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, blockerUserId)
                ps.setString(2, blockedUserId)
                ps.setLong(3, now)
                ps.executeUpdate() > 0
            }
        }
    }

    suspend fun unblockUser(blockerUserId: String, blockedUserId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            connection.prepareStatement(
                "DELETE FROM blocked_users WHERE blocker_user_id = ? AND blocked_user_id = ?"
            ).use { ps ->
                ps.setString(1, blockerUserId)
                ps.setString(2, blockedUserId)
                ps.executeUpdate() > 0
            }
        }
    }

    suspend fun isUserBlocked(blockerUserId: String, targetUserId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            isUserBlockedInternal(blockerUserId, targetUserId)
        }
    }

    private fun isUserBlockedInternal(blockerUserId: String, targetUserId: String): Boolean {
        return connection.prepareStatement(
            "SELECT 1 FROM blocked_users WHERE blocker_user_id = ? AND blocked_user_id = ? LIMIT 1"
        ).use { ps ->
            ps.setString(1, blockerUserId)
            ps.setString(2, targetUserId)
            ps.executeQuery().use { it.next() }
        }
    }

    suspend fun getBlockedUsers(blockerUserId: String): List<UserRecord> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val sql = """
                SELECT u.user_id, u.aster_id, u.username, u.display_name, u.created_at, u.updated_at, u.is_active
                FROM users u
                INNER JOIN blocked_users b ON b.blocked_user_id = u.user_id
                WHERE b.blocker_user_id = ?
                ORDER BY u.display_name ASC
            """.trimIndent()

            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, blockerUserId)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<UserRecord>()
                    while (rs.next()) {
                        list.add(
                            UserRecord(
                                userId = rs.getString("user_id"),
                                asterId = rs.getString("aster_id"),
                                username = rs.getString("username"),
                                displayName = rs.getString("display_name"),
                                createdAt = rs.getLong("created_at"),
                                updatedAt = rs.getLong("updated_at"),
                                isActive = rs.getInt("is_active") == 1
                            )
                        )
                    }
                    list
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
