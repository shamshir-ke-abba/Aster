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
    const val ENDPOINT_PUBLIC_TUNNEL_TLS = "wss://nat-validation-surely-receipt.trycloudflare.com/ws" // Active Cloudflare Quick Tunnel (TLS)
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

    fun defaultEndpointForDevice(): String {
        return try {
            val hardware = android.os.Build.HARDWARE?.lowercase() ?: ""
            val product = android.os.Build.PRODUCT?.lowercase() ?: ""
            if (hardware.contains("waydroid") || product.contains("waydroid")) {
                ENDPOINT_WAYDROID_HOST_TLS
            } else {
                ENDPOINT_PUBLIC_TUNNEL_TLS
            }
        } catch (e: Throwable) {
            ENDPOINT_PUBLIC_TUNNEL_TLS
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
