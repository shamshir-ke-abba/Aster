package com.aster.backend

import com.aster.backend.database.MailboxDatabase
import com.aster.backend.relay.MessageRelay
import com.aster.backend.session.SessionManager
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import org.slf4j.LoggerFactory
import java.time.Duration

fun Application.asterModule(
    sessionManager: SessionManager = SessionManager(),
    mailboxDatabase: MailboxDatabase = MailboxDatabase(System.getenv("ASTER_DB_PATH") ?: ":memory:"),
    relay: MessageRelay = MessageRelay(sessionManager, mailboxDatabase)
) {
    val logger = LoggerFactory.getLogger("AsterServer")

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(30)
        maxFrameSize = com.aster.backend.protocol.ProtocolLimits.MAX_FRAME_SIZE
        masking = false
    }

    routing {
        get("/health") {
            val body = """{"status":"ok","users":${sessionManager.activeUserCount()},"connections":${sessionManager.activeConnectionCount()}}"""
            call.respondText(body, ContentType.Application.Json)
        }

        webSocket("/ws") {
            logger.info("New WebSocket connection opened")
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            relay.handleMessage(this, text)
                        }
                        is Frame.Close -> {
                            logger.info("Close frame received")
                        }
                        else -> {
                            // Ignore binary/ping/pong frames handled by Ktor
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("WebSocket session exception: ${e.message}")
            } finally {
                relay.handleDisconnect(this)
            }
        }
    }
}
