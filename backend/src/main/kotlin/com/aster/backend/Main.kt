package com.aster.backend

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory

fun main() {
    val logger = LoggerFactory.getLogger("AsterMain")
    val port = System.getenv("ASTER_PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("ASTER_HOST") ?: "0.0.0.0"

    logger.info("Starting Aster WebSocket Relay Server on $host:$port...")

    embeddedServer(CIO, port = port, host = host) {
        asterModule()
    }.start(wait = true)
}
