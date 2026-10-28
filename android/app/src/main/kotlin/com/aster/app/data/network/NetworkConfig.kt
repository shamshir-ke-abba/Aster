package com.aster.app.data.network

enum class NetworkEnvironment {
    PRODUCTION,
    DEVELOPMENT_TLS,
    DEVELOPMENT_UNSECURED_LOCAL // Exclusively for local offline unit tests with unencrypted MockWebServer
}

object NetworkConfig {
    // Port constants
    const val DEFAULT_TLS_PORT = 8443
    const val DEFAULT_PLAINTEXT_PORT = 8080

    // Supported Secure Endpoints (wss://)
    const val ENDPOINT_CLOUDFLARE_PRODUCTION_TLS = "wss://aster-backend-cloudflare.kmsaadbinafzal.workers.dev/ws"
    const val ENDPOINT_PUBLIC_TUNNEL_TLS = ENDPOINT_CLOUDFLARE_PRODUCTION_TLS // Cloudflare Production Backend (TLS)
    const val ENDPOINT_WAYDROID_HOST_TLS = "wss://192.168.240.1:8443/ws"      // Waydroid host bridge (TLS)
    const val ENDPOINT_LAN_HOST_TLS = "wss://192.168.0.114:8443/ws"           // Physical device LAN host (TLS)
    const val ENDPOINT_TABLET_BACKEND_TLS = "wss://192.168.0.159:8443/ws"     // Physical Samsung SM-T285 tablet (TLS)
    const val ENDPOINT_LOCALHOST_TLS = "wss://127.0.0.1:8443/ws"              // Local loopback (TLS)
    const val ENDPOINT_HOSTNAME_TLS = "wss://aster.local:8443/ws"             // Hostname resolution (TLS)

    // Insecure Endpoints (ws://) - Strictly restricted to development / mock tests
    const val ENDPOINT_DEVELOPMENT_PLAINTEXT_HOST = "ws://192.168.240.1:8080/ws"
    const val ENDPOINT_DEVELOPMENT_PLAINTEXT_TABLET = "ws://192.168.0.159:8080/ws"
    const val ENDPOINT_DEVELOPMENT_PLAINTEXT_LOCAL = "ws://127.0.0.1:8080/ws"

    var currentEnvironment: NetworkEnvironment = NetworkEnvironment.PRODUCTION

    fun defaultEndpointForDevice(env: NetworkEnvironment = currentEnvironment): String {
        return if (env == NetworkEnvironment.PRODUCTION) {
            ENDPOINT_CLOUDFLARE_PRODUCTION_TLS
        } else {
            try {
                val hardware = android.os.Build.HARDWARE?.lowercase() ?: ""
                val product = android.os.Build.PRODUCT?.lowercase() ?: ""
                if (hardware.contains("waydroid") || product.contains("waydroid")) {
                    ENDPOINT_WAYDROID_HOST_TLS
                } else {
                    ENDPOINT_CLOUDFLARE_PRODUCTION_TLS
                }
            } catch (e: Throwable) {
                ENDPOINT_CLOUDFLARE_PRODUCTION_TLS
            }
        }
    }

    /**
     * Active WebSocket relay endpoint.
     * In PRODUCTION and DEVELOPMENT_TLS, setting a plaintext 'ws://' endpoint throws IllegalArgumentException.
     */
    private var _serverUrl: String = defaultEndpointForDevice()

    var serverUrl: String
        get() = _serverUrl
        set(value) {
            validateUrlForEnvironment(value, currentEnvironment)
            _serverUrl = value
        }

    fun setServerUrlForEnvironment(url: String, env: NetworkEnvironment) {
        validateUrlForEnvironment(url, env)
        currentEnvironment = env
        _serverUrl = url
    }

    /**
     * Identifies whether a given hostname or IP literal is a local, private LAN,
     * loopback, link-local, or development test domain.
     * Operates purely via string parsing to prevent any synchronous DNS lookups or main-thread network calls.
     */
    fun isLocalOrDevelopmentHost(host: String): Boolean {
        if (host.isBlank()) return true
        val lowerHost = host.lowercase().trim()
        val normalizedHost = when {
            lowerHost.startsWith("[") -> lowerHost.substringBefore("]").removePrefix("[")
            lowerHost.count { it == ':' } == 1 -> lowerHost.substringBefore(":")
            else -> lowerHost
        }

        if (normalizedHost == "localhost" ||
            normalizedHost.endsWith(".local") ||
            normalizedHost.endsWith(".internal") ||
            normalizedHost.endsWith(".lan") ||
            normalizedHost.endsWith(".home")
        ) {
            return true
        }

        // IPv4 literal parsing: check for loopback (127/8), RFC 1918 private ranges (10/8, 172.16/12, 192.168/16), link-local (169.254/16)
        val ipv4Parts = normalizedHost.split(".")
        if (ipv4Parts.size == 4) {
            val octets = ipv4Parts.mapNotNull { it.toIntOrNull() }
            if (octets.size == 4 && octets.all { it in 0..255 }) {
                val (a, b, c, d) = octets
                // Loopback: 127.0.0.0/8
                if (a == 127) return true
                // RFC 1918 Private: 10.0.0.0/8
                if (a == 10) return true
                // RFC 1918 Private: 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
                if (a == 172 && b in 16..31) return true
                // RFC 1918 Private: 192.168.0.0/16
                if (a == 192 && b == 168) return true
                // Link-local: 169.254.0.0/16
                if (a == 169 && b == 254) return true
                // Current network / broadcast / multicast
                if (a == 0 || a >= 224) return true
            }
        }

        // IPv6 literal check
        val cleanedIpv6 = normalizedHost.removeSurrounding("[", "]")
        if (cleanedIpv6 == "::1" ||
            cleanedIpv6.startsWith("fe80:") ||
            cleanedIpv6.startsWith("fc00:") ||
            cleanedIpv6.startsWith("fd00:")
        ) {
            return true
        }

        return false
    }

    /**
     * Determines whether the given URL points to a local, private LAN, or development endpoint.
     */
    fun isLocalOrDevelopmentEndpoint(url: String): Boolean {
        if (url.isBlank()) return true
        val host = try {
            val uri = java.net.URI(url)
            uri.host ?: uri.authority?.substringBefore(":")
        } catch (_: Exception) {
            null
        } ?: return true
        return isLocalOrDevelopmentHost(host)
    }

    /**
     * Resolves and applies the active endpoint on application startup.
     *
     * In PRODUCTION:
     * - The canonical endpoint is strictly ENDPOINT_CLOUDFLARE_PRODUCTION_TLS.
     * - Stale LAN, Waydroid, tablet, localhost, or .local development endpoints
     *   must NEVER be restored as the production endpoint. If detected, they are discarded
     *   and onStaleEndpointCleared is triggered so storage can be sanitized.
     * - Only valid, non-stale production endpoints are permitted.
     *
     * In DEVELOPMENT_TLS or other development environments:
     * - Stored development endpoints and device-specific defaults (e.g. Waydroid bridge)
     *   are fully supported and restored.
     */
    fun initializeEndpoint(
        savedUrl: String?,
        env: NetworkEnvironment = currentEnvironment,
        onStaleEndpointCleared: (() -> Unit)? = null
    ): String {
        currentEnvironment = env
        if (env == NetworkEnvironment.PRODUCTION) {
            if (!savedUrl.isNullOrBlank()) {
                if (isLocalOrDevelopmentEndpoint(savedUrl)) {
                    onStaleEndpointCleared?.invoke()
                    _serverUrl = ENDPOINT_CLOUDFLARE_PRODUCTION_TLS
                    return _serverUrl
                }
                try {
                    validateUrlForEnvironment(savedUrl, NetworkEnvironment.PRODUCTION)
                    _serverUrl = savedUrl
                    return _serverUrl
                } catch (_: Exception) {
                    onStaleEndpointCleared?.invoke()
                }
            }
            _serverUrl = ENDPOINT_CLOUDFLARE_PRODUCTION_TLS
            return _serverUrl
        } else {
            if (!savedUrl.isNullOrBlank()) {
                try {
                    validateUrlForEnvironment(savedUrl, env)
                    _serverUrl = savedUrl
                    return _serverUrl
                } catch (_: Exception) {
                    // Fall back to device default
                }
            }
            _serverUrl = defaultEndpointForDevice(env)
            return _serverUrl
        }
    }

    fun resetToDefault(env: NetworkEnvironment = NetworkEnvironment.PRODUCTION) {
        currentEnvironment = env
        _serverUrl = defaultEndpointForDevice(env)
    }

    fun getHttpBaseUrl(): String {
        val wsUrl = serverUrl
        val withoutScheme = when {
            wsUrl.startsWith("wss://", ignoreCase = true) -> wsUrl.substring(6)
            wsUrl.startsWith("ws://", ignoreCase = true) -> wsUrl.substring(5)
            else -> wsUrl
        }
        val hostAndPort = withoutScheme.substringBefore("/ws").substringBefore("/")
        val scheme = if (wsUrl.startsWith("ws://", ignoreCase = true)) "http" else "https"
        return "$scheme://$hostAndPort"
    }

    fun validateUrlForEnvironment(url: String, env: NetworkEnvironment) {
        val uri = try {
            java.net.URI(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed server URI: ${e.message}", e)
        }

        require(!uri.host.isNullOrBlank()) {
            "Server endpoint must specify a non-empty host: $url"
        }

        require(uri.userInfo == null) {
            "Server endpoint must not contain user credentials: $url"
        }

        if (uri.port != -1) {
            require(uri.port in 1..65535) {
                "Server endpoint port out of range: ${uri.port}"
            }
        }

        if (env == NetworkEnvironment.PRODUCTION) {
            require(uri.scheme.equals("wss", ignoreCase = true)) {
                "Production transport requires wss:// endpoint; rejected insecure URL: $url"
            }
        } else if (env == NetworkEnvironment.DEVELOPMENT_TLS) {
            require(uri.scheme.equals("wss", ignoreCase = true)) {
                "Development TLS transport requires wss:// endpoint; rejected insecure URL: $url"
            }
        } else {
            require(uri.scheme.equals("ws", ignoreCase = true) || uri.scheme.equals("wss", ignoreCase = true)) {
                "Unsupported WebSocket scheme: ${uri.scheme}"
            }
        }
    }

    const val INITIAL_RETRY_DELAY_MS = 1000L
    const val MAX_RETRY_DELAY_MS = 30000L
    const val BACKOFF_MULTIPLIER = 2.0
}
