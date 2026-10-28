package com.aster.app.data.network

/**
 * Sanitizer for user-facing network and connection error messages.
 * Prevents technical jargon, backend URLs, server hosts, ports,
 * and Java/Kotlin exception names from leaking into the UI.
 */
object NetworkSanitizer {
    private val URL_OR_HOST_REGEX = Regex(
        """(?i)(https?://|wss?://|[a-zA-Z0-9.-]+\.workers\.dev|[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}(:\d+)?|localhost(:\d+)?)"""
    )
    private val EXCEPTION_NAME_REGEX = Regex("""[a-zA-Z0-9_.]*(Exception|Error|Failure|Timeout)""")
    private val PORT_REGEX = Regex(""":\d{2,5}""")

    private const val DEFAULT_NETWORK_ERROR = "Connection unavailable. Aster will keep trying automatically."

    /**
     * Sanitizes a Throwable into a clean, user-friendly message.
     */
    fun sanitizeError(throwable: Throwable?, fallback: String = DEFAULT_NETWORK_ERROR): String {
        if (throwable == null) return fallback
        return when (throwable) {
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.SocketTimeoutException,
            is java.io.IOException -> DEFAULT_NETWORK_ERROR
            else -> sanitizeMessage(throwable.message, fallback)
        }
    }

    /**
     * Sanitizes an arbitrary error message string into a clean, user-friendly message.
     */
    fun sanitizeMessage(message: String?, fallback: String = DEFAULT_NETWORK_ERROR): String {
        if (message.isNullOrBlank()) return fallback
        val raw = message.trim()

        // Check if message exposes technical details, URLs, hostnames, ports, or exception classes
        if (URL_OR_HOST_REGEX.containsMatchIn(raw) ||
            PORT_REGEX.containsMatchIn(raw) ||
            raw.contains("cloudflare", ignoreCase = true) ||
            raw.contains("websocket", ignoreCase = true) ||
            raw.contains("socket", ignoreCase = true) ||
            raw.contains("Failed to connect", ignoreCase = true) ||
            raw.contains("CLEARTEXT", ignoreCase = true) ||
            raw.contains("Connection refused", ignoreCase = true) ||
            raw.contains("Broken pipe", ignoreCase = true) ||
            raw.contains("Software caused connection abort", ignoreCase = true) ||
            raw.contains("Network is unreachable", ignoreCase = true) ||
            raw.contains("No route to host", ignoreCase = true) ||
            raw.contains("SSLHandshake", ignoreCase = true) ||
            raw.contains("CertPathValidator", ignoreCase = true) ||
            raw.contains("Trust anchor", ignoreCase = true) ||
            raw.contains("HTTP 5", ignoreCase = true) ||
            raw.contains("HTTP 4", ignoreCase = true) ||
            raw.contains("status code", ignoreCase = true) ||
            EXCEPTION_NAME_REGEX.containsMatchIn(raw)
        ) {
            return DEFAULT_NETWORK_ERROR
        }

        // Avoid leaking long stack traces or technical strings
        if (raw.length > 100) {
            return DEFAULT_NETWORK_ERROR
        }

        return raw
    }
}
