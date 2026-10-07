package com.aster.backend.session

import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active connected WebSocket sessions and user mapping.
 * Thread-safe and in-memory, designed for Aster's target group (~100 users).
 * Enforces single active session per user: new authenticated connection replaces old connection.
 */
class SessionManager {

    private val logger = LoggerFactory.getLogger(SessionManager::class.java)

    // userId -> active WebSocketSession (at most 1 active session per user)
    private val userSession = ConcurrentHashMap<String, WebSocketSession>()

    // session -> userId for fast reverse lookup on disconnect
    private val sessionToUser = ConcurrentHashMap<WebSocketSession, String>()

    // Bounded duplicate protection cache: stores recently processed message IDs (up to 10,000)
    private val seenMessageIds: MutableSet<String> = Collections.newSetFromMap(
        object : java.util.LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 10000
            }
        }
    )

    /**
     * Registers a new session for the given user.
     * Enforces deterministic session replacement policy:
     * If an older session already exists for this user, it is closed and replaced.
     */
    suspend fun registerSession(userId: String, session: WebSocketSession) {
        val oldSession = userSession.put(userId, session)
        sessionToUser[session] = userId

        if (oldSession != null && oldSession != session) {
            logger.info("Replacing existing session for user '$userId' with new connection")
            sessionToUser.remove(oldSession)
            try {
                oldSession.close(CloseReason(CloseReason.Codes.NORMAL, "Replaced by new connection"))
            } catch (e: Exception) {
                logger.debug("Error closing replaced session: ${e.message}")
            }
        }
    }

    /**
     * Removes session on close/disconnect.
     * Only removes from userSession if the session being removed is still the active one.
     */
    fun removeSession(session: WebSocketSession): String? {
        val userId = sessionToUser.remove(session) ?: return null
        userSession.compute(userId) { _, currentActive ->
            if (currentActive == session) null else currentActive
        }
        return userId
    }

    fun getSession(userId: String): WebSocketSession? {
        return userSession[userId]
    }

    fun getSessions(userId: String): List<WebSocketSession> {
        val s = userSession[userId]
        return if (s != null) listOf(s) else emptyList()
    }

    fun getUserForSession(session: WebSocketSession): String? {
        return sessionToUser[session]
    }

    fun isOnline(userId: String): Boolean {
        return userSession.containsKey(userId)
    }

    fun activeUserCount(): Int = userSession.size

    fun activeConnectionCount(): Int = sessionToUser.size

    @Synchronized
    fun isDuplicateMessage(messageId: String): Boolean {
        return !seenMessageIds.add(messageId)
    }

    fun clear() {
        userSession.clear()
        sessionToUser.clear()
        synchronized(this) {
            seenMessageIds.clear()
        }
    }
}
