package com.aster.backend

import com.aster.backend.database.MailboxDatabase
import com.aster.backend.relay.MessageRelay
import com.aster.backend.session.SessionManager
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.io.File
import java.security.KeyStore

fun main() {
    val logger = LoggerFactory.getLogger("AsterMain")
    val host = System.getenv("ASTER_HOST") ?: "0.0.0.0"
    val sslPort = System.getenv("ASTER_SSL_PORT")?.toIntOrNull() ?: 8443
    val plaintextPort = System.getenv("ASTER_PLAINTEXT_PORT")?.toIntOrNull()
        ?: System.getenv("ASTER_PORT")?.toIntOrNull()
        ?: 8080
    val dbPath = System.getenv("ASTER_DB_PATH") ?: "aster.db"

    val enableTls = System.getenv("ASTER_ENABLE_TLS")?.toBooleanStrictOrNull() ?: true
    val enablePlaintext = System.getenv("ASTER_ENABLE_PLAINTEXT")?.toBooleanStrictOrNull() ?: false

    val keyStorePath = System.getenv("ASTER_KEYSTORE_PATH")
    val keyStorePassword = System.getenv("ASTER_KEYSTORE_PASSWORD") ?: "aster_keystore_secret"
    val keyAlias = System.getenv("ASTER_KEY_ALIAS") ?: "aster-server"
    val privateKeyPassword = System.getenv("ASTER_KEY_PASSWORD") ?: keyStorePassword

    val mailboxDatabase = MailboxDatabase(dbPath)
    val sessionManager = SessionManager()
    val relay = MessageRelay(sessionManager, mailboxDatabase)

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down Aster Mailbox Database...")
        mailboxDatabase.close()
    })

    val adminKeyFileEnv = System.getenv("ASTER_ADMIN_KEY_FILE")?.trim()
    val adminKey = when {
        !adminKeyFileEnv.isNullOrBlank() -> {
            val file = File(adminKeyFileEnv)
            if (file.exists()) file.readText().trim() else null
        }
        !System.getenv("ASTER_ADMIN_KEY").isNullOrBlank() -> {
            System.getenv("ASTER_ADMIN_KEY")?.trim()
        }
        else -> {
            val candidateFiles = listOf(
                File(".admin_key.secret"),
                File("../.admin_key.secret"),
                File(System.getProperty("user.dir"), ".admin_key.secret")
            )
            val found = candidateFiles.firstOrNull { it.exists() }
            found?.readText()?.trim()
        }
    }
    if (adminKey.isNullOrBlank()) {
        logger.error("FATAL: ASTER_ADMIN_KEY or ASTER_ADMIN_KEY_FILE (.admin_key.secret) is required to start Aster server.")
        throw IllegalStateException(
            "Administrative key is required for server deployment. " +
            "Set ASTER_ADMIN_KEY, ASTER_ADMIN_KEY_FILE, or provide .admin_key.secret. " +
            "Aster server refuses to start without administrative authentication configured."
        )
    }
    if (adminKey.length < 16) {
        logger.error("FATAL: Admin key must be at least 16 characters long.")
        throw IllegalStateException("Admin key is too short (must be >= 16 characters).")
    }

    val resolvedKeyStoreFile = resolveKeyStoreFile(keyStorePath)

    if (enableTls && (resolvedKeyStoreFile == null || !resolvedKeyStoreFile.exists())) {
        throw IllegalStateException(
            "TLS is enabled but keystore file was not found. " +
            "Please check ASTER_KEYSTORE_PATH or run certs/generate_certs.sh"
        )
    }

    val env = applicationEngineEnvironment {
        this.log = LoggerFactory.getLogger("ktor.application")

        if (enableTls && resolvedKeyStoreFile != null) {
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                resolvedKeyStoreFile.inputStream().use { load(it, keyStorePassword.toCharArray()) }
            }
            sslConnector(
                keyStore = keyStore,
                keyAlias = keyAlias,
                keyStorePassword = { keyStorePassword.toCharArray() },
                privateKeyPassword = { privateKeyPassword.toCharArray() }
            ) {
                this.host = host
                this.port = sslPort
                this.keyStorePath = resolvedKeyStoreFile
            }
            logger.info("Configured TLS / WSS connector on $host:$sslPort (Keystore: ${resolvedKeyStoreFile.absolutePath})")
        }

        if (enablePlaintext) {
            connector {
                this.host = host
                this.port = plaintextPort
            }
            logger.warn("WARNING: Insecure plaintext connector enabled on $host:$plaintextPort (Development/testing only)")
        }

        module {
            asterModule(sessionManager, mailboxDatabase, relay, adminKey)
        }
    }

    logger.info("Starting Aster Server with Netty engine (TLS=$enableTls, Plaintext=$enablePlaintext, DB=$dbPath)...")
    embeddedServer(Netty, env).start(wait = true)
}

fun resolveKeyStoreFile(configuredPath: String?): File? {
    if (!configuredPath.isNullOrBlank()) {
        val f = File(configuredPath)
        if (f.exists()) return f
    }
    val candidates = listOf(
        File("certs/aster_server.p12"),
        File("../certs/aster_server.p12"),
        File("/data/local/tmp/aster-data/aster_server.p12"),
        File(System.getProperty("user.dir"), "certs/aster_server.p12"),
        File(System.getProperty("user.dir"), "../certs/aster_server.p12")
    )
    return candidates.firstOrNull { it.exists() }
}
