package com.aster.app.data.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AsterWebSocketClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val TAG = "AsterWebSocketClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverAcks = MutableSharedFlow<ServerMessage.MessageAccepted>(extraBufferCapacity = 64)
    val serverAcks: SharedFlow<ServerMessage.MessageAccepted> = _serverAcks.asSharedFlow()

    private val _incomingMessages = MutableSharedFlow<ServerMessage.RelayMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<ServerMessage.RelayMessage> = _incomingMessages.asSharedFlow()

    private var currentWebSocket: WebSocket? = null
    private var currentUserId: String = "user_saad"
    private var reconnectJob: Job? = null
    private var currentBackoffMs = NetworkConfig.INITIAL_RETRY_DELAY_MS
    private var shouldReconnect = true

    private var connectionAttempt = 0

    fun connect(userId: String) {
        currentUserId = userId
        shouldReconnect = true
        reconnectJob?.cancel()
        currentBackoffMs = NetworkConfig.INITIAL_RETRY_DELAY_MS
        initiateConnection()
    }

    private fun initiateConnection() {
        if (_connectionState.value == ConnectionState.CONNECTED) return

        connectionAttempt++
        val attemptNum = connectionAttempt
        val targetUrl = NetworkConfig.serverUrl

        _connectionState.value = if (currentBackoffMs > NetworkConfig.INITIAL_RETRY_DELAY_MS) {
            ConnectionState.RECONNECTING
        } else {
            ConnectionState.CONNECTING
        }

        try {
            // Cancel and close previous socket instance to avoid connection races
            val oldWs = currentWebSocket
            currentWebSocket = null
            try {
                oldWs?.cancel()
            } catch (e: Exception) {
                // Ignore cancellation exceptions
            }

            val request = Request.Builder()
                .url(targetUrl)
                .build()

            Log.d(TAG, "Initiating connection [attempt #$attemptNum] to $targetUrl on thread [${Thread.currentThread().name}]")

            currentWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (webSocket !== currentWebSocket) {
                        Log.d(TAG, "Ignoring onOpen from stale WebSocket instance")
                        webSocket.close(1000, "Stale instance")
                        return
                    }
                    connectionAttempt = 0
                    Log.i(TAG, "WebSocket open [connected to $targetUrl], sending development AUTH for $currentUserId")
                    val authPayload = ClientMessage.Auth(userId = currentUserId).toJson()
                    webSocket.send(authPayload)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== currentWebSocket) {
                        Log.d(TAG, "Ignoring onMessage from stale WebSocket instance")
                        return
                    }
                    val serverMsg = ServerMessage.fromJson(text) ?: run {
                        Log.w(TAG, "Unrecognized server message: $text")
                        return
                    }

                    when (serverMsg) {
                        is ServerMessage.AuthOk -> {
                            Log.i(TAG, "Authentication accepted by server: ${serverMsg.userId}")
                            currentBackoffMs = NetworkConfig.INITIAL_RETRY_DELAY_MS
                            _connectionState.value = ConnectionState.CONNECTED
                        }
                        is ServerMessage.AuthError -> {
                            Log.e(TAG, "Authentication failed: ${serverMsg.reason}")
                            _connectionState.value = ConnectionState.DISCONNECTED
                        }
                        is ServerMessage.MessageAccepted -> {
                            Log.i(TAG, "Server accepted message: ${serverMsg.messageId}")
                            _serverAcks.tryEmit(serverMsg)
                        }
                        is ServerMessage.RelayMessage -> {
                            Log.i(TAG, "Incoming message from ${serverMsg.senderId}: ${serverMsg.messageId}")
                            _incomingMessages.tryEmit(serverMsg)
                        }
                        is ServerMessage.MessageDelivered -> {
                            Log.i(TAG, "Message delivery confirmed: ${serverMsg.messageId}")
                        }
                        is ServerMessage.Error -> {
                            Log.w(TAG, "Server error (${serverMsg.code}): ${serverMsg.message}")
                        }
                        is ServerMessage.Pong -> {
                            Log.d(TAG, "PONG received")
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket !== currentWebSocket) return
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket !== currentWebSocket) return
                    Log.i(TAG, "WebSocket closed ($code: $reason)")
                    handleDisconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== currentWebSocket) return
                    val causeChain = generateSequence(t.cause) { it.cause }
                        .map { "${it.javaClass.name}: ${it.message}" }
                        .joinToString(" -> ")
                    Log.w(
                        TAG,
                        "WebSocket connection failure [attempt #$attemptNum] to $targetUrl on thread [${Thread.currentThread().name}]:\n" +
                        "  Exception: ${t.javaClass.name}: ${t.message}\n" +
                        "  Cause chain: ${if (causeChain.isBlank()) "None" else causeChain}\n" +
                        "  HTTP response: ${response?.code}"
                    )
                    handleDisconnect()
                }
            })
        } catch (e: Exception) {
            val causeChain = generateSequence(e.cause) { it.cause }
                .map { "${it.javaClass.name}: ${it.message}" }
                .joinToString(" -> ")
            Log.e(
                TAG,
                "Exception opening WebSocket [attempt #$attemptNum] to $targetUrl on thread [${Thread.currentThread().name}]:\n" +
                "  Exception: ${e.javaClass.name}: ${e.message}\n" +
                "  Cause chain: ${if (causeChain.isBlank()) "None" else causeChain}"
            )
            handleDisconnect()
        }
    }

    private fun handleDisconnect() {
        currentWebSocket = null
        if (!shouldReconnect) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        _connectionState.value = ConnectionState.RECONNECTING
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.d(TAG, "Waiting ${currentBackoffMs}ms before reconnecting...")
            delay(currentBackoffMs)
            currentBackoffMs = (currentBackoffMs * NetworkConfig.BACKOFF_MULTIPLIER).toLong()
                .coerceAtMost(NetworkConfig.MAX_RETRY_DELAY_MS)
            if (shouldReconnect) {
                initiateConnection()
            }
        }
    }

    fun sendMessage(msg: ClientMessage.SendMessage): Boolean {
        val ws = currentWebSocket
        if (_connectionState.value != ConnectionState.CONNECTED || ws == null) {
            Log.d(TAG, "WebSocket not connected. Message ${msg.messageId} remaining pending.")
            return false
        }
        val sent = ws.send(msg.toJson())
        if (sent) {
            Log.i(TAG, "Sent SEND_MESSAGE ${msg.messageId} to network")
        } else {
            Log.w(TAG, "Failed to send SEND_MESSAGE ${msg.messageId} to network")
        }
        return sent
    }

    fun sendAckReceived(messageId: String): Boolean {
        val ws = currentWebSocket ?: return false
        return ws.send(ClientMessage.AckReceived(messageId).toJson())
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        currentWebSocket?.close(1000, "Normal closure")
        currentWebSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
