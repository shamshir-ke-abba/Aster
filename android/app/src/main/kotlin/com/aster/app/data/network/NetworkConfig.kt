package com.aster.app.data.network

object NetworkConfig {
    // Standard development endpoints
    const val ENDPOINT_WAYDROID_HOST = "ws://192.168.240.1:8080/ws"       // Linux host IP from Waydroid
    const val ENDPOINT_EMULATOR_HOST = "ws://10.0.2.2:8080/ws"            // Android Studio emulator host
    const val ENDPOINT_LOCALHOST = "ws://127.0.0.1:8080/ws"               // Localhost
    const val ENDPOINT_TABLET_BACKEND = "ws://192.168.0.159:8080/ws"      // Samsung SM-T285 test target

    /**
     * Active WebSocket relay endpoint.
     *
     * Set to ENDPOINT_WAYDROID_HOST for standard Waydroid local host backend (192.168.240.1).
     * Set to ENDPOINT_TABLET_BACKEND for Samsung SM-T285 tablet testing (192.168.0.159).
     */
    var serverUrl: String = ENDPOINT_WAYDROID_HOST

    const val INITIAL_RETRY_DELAY_MS = 1000L
    const val MAX_RETRY_DELAY_MS = 30000L
    const val BACKOFF_MULTIPLIER = 2.0
}
