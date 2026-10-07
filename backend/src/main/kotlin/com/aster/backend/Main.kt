package com.aster.backend

import com.aster.backend.database.MailboxDatabase
import com.aster.backend.relay.MessageRelay
import com.aster.backend.session.SessionManager
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory

fun main() {
    val logger = LoggerFactory.getLogger("AsterMain")
    val port = System.getenv("ASTER_PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("ASTER_HOST") ?: "0.0.0.0"
    val dbPath = System.getenv("ASTER_DB_PATH") ?: "aster.db"

    logger.info("Starting Aster WebSocket Server on $host:$port (DB: $dbPath)...")

    val mailboxDatabase = MailboxDatabase(dbPath)
    val sessionManager = SessionManager()
    val relay = MessageRelay(sessionManager, mailboxDatabase)

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down Aster Mailbox Database...")
        mailboxDatabase.close()
    })

    embeddedServer(CIO, port = port, host = host) {
        asterModule(sessionManager, mailboxDatabase, relay)
    }.start(wait = true)
}
