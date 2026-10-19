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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

import com.aster.app.data.auth.AsterAuthStorage

sealed interface AuthResult {
    data class Success(val userId: String) : AuthResult
    data class InvalidToken(val reason: String) : AuthResult
    data class ServerUnavailable(val message: String) : AuthResult
}

@Singleton
open class AsterWebSocketClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    val authStorage: AsterAuthStorage
) {
    private val TAG = "AsterWebSocketClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverAcks = MutableSharedFlow<ServerMessage.MessageAccepted>(extraBufferCapacity = 64)
    val serverAcks: SharedFlow<ServerMessage.MessageAccepted> = _serverAcks.asSharedFlow()

    private val _incomingMessages = MutableSharedFlow<ServerMessage.RelayMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<ServerMessage.RelayMessage> = _incomingMessages.asSharedFlow()

    private val _groupOperations = MutableSharedFlow<ServerMessage.GroupOperationOk>(extraBufferCapacity = 64)
    val groupOperations: SharedFlow<ServerMessage.GroupOperationOk> = _groupOperations.asSharedFlow()

    private val _groupLists = MutableSharedFlow<ServerMessage.GroupList>(extraBufferCapacity = 64)
    val groupLists: SharedFlow<ServerMessage.GroupList> = _groupLists.asSharedFlow()

    private val _groupMembersLists = MutableSharedFlow<ServerMessage.GroupMembersList>(extraBufferCapacity = 64)
    val groupMembersLists: SharedFlow<ServerMessage.GroupMembersList> = _groupMembersLists.asSharedFlow()

    private val _groupEvents = MutableSharedFlow<ServerMessage.GroupEvent>(extraBufferCapacity = 64)
    val groupEvents: SharedFlow<ServerMessage.GroupEvent> = _groupEvents.asSharedFlow()

    private val _authEvents = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 16)
    val authEvents: SharedFlow<ServerMessage> = _authEvents.asSharedFlow()

    fun emitIncomingMessageForTesting(msg: ServerMessage.RelayMessage) {
        _incomingMessages.tryEmit(msg)
    }

    private var currentWebSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var currentBackoffMs = NetworkConfig.INITIAL_RETRY_DELAY_MS
    private var shouldReconnect = true

    private var connectionAttempt = 0

    fun connect(token: String? = null) {
        if (!token.isNullOrBlank()) {
            authStorage.saveAuthToken(token)
        }
        shouldReconnect = true
        reconnectJob?.cancel()
        currentBackoffMs = NetworkConfig.INITIAL_RETRY_DELAY_MS
        initiateConnection()
    }

    private fun initiateConnection() {
        if (_connectionState.value == ConnectionState.CONNECTED) return

        val token = authStorage.getAuthToken()
        if (token.isNullOrBlank()) {
            Log.d(TAG, "No authentication token found in AsterAuthStorage; connection deferred until provisioned")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

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
                    val currentToken = authStorage.getAuthToken()
                    if (currentToken.isNullOrBlank()) {
                        Log.w(TAG, "Cannot authenticate: no token in storage")
                        webSocket.close(1008, "Missing credentials")
                        return
                    }
                    Log.i(TAG, "WebSocket open [connected to $targetUrl], sending authenticated AUTH frame")
                    val authPayload = ClientMessage.Auth(token = currentToken).toJson()
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
                            Log.i(TAG, "Authentication accepted by server for user: ${serverMsg.userId}")
                            authStorage.saveAuthenticatedUserId(serverMsg.userId)
                            currentBackoffMs = NetworkConfig.INITIAL_RETRY_DELAY_MS
                            _connectionState.value = ConnectionState.CONNECTED
                            _authEvents.tryEmit(serverMsg)
                        }
                        is ServerMessage.AuthError -> {
                            Log.e(TAG, "Authentication failed: ${serverMsg.reason}")
                            authStorage.clearAuthToken()
                            _connectionState.value = ConnectionState.DISCONNECTED
                            shouldReconnect = false
                            _authEvents.tryEmit(serverMsg)
                            webSocket.close(1008, "Authentication failed: ${serverMsg.reason}")
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
                        is ServerMessage.GroupOperationOk -> {
                            Log.i(TAG, "Group operation succeeded: ${serverMsg.action} for ${serverMsg.groupId}")
                            _groupOperations.tryEmit(serverMsg)
                        }
                        is ServerMessage.GroupList -> {
                            Log.i(TAG, "Received group list: ${serverMsg.groups.size} groups")
                            _groupLists.tryEmit(serverMsg)
                        }
                        is ServerMessage.GroupMembersList -> {
                            Log.i(TAG, "Received group members for ${serverMsg.groupId}: ${serverMsg.members.size} members")
                            _groupMembersLists.tryEmit(serverMsg)
                        }
                        is ServerMessage.GroupEvent -> {
                            Log.i(TAG, "Received group event ${serverMsg.eventType} for ${serverMsg.groupId} by ${serverMsg.actorId}")
                            _groupEvents.tryEmit(serverMsg)
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
                    _authEvents.tryEmit(ServerMessage.Error(code = "NETWORK_ERROR", message = t.message ?: "Connection failure"))
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
            _authEvents.tryEmit(ServerMessage.Error(code = "NETWORK_ERROR", message = e.message ?: "Connection exception"))
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

    fun createGroup(name: String, memberIds: List<String>, requestId: String = java.util.UUID.randomUUID().toString()): Boolean {
        val ws = currentWebSocket ?: return false
        return ws.send(ClientMessage.CreateGroup(requestId, name, memberIds).toJson())
    }

    fun getGroups(requestId: String = java.util.UUID.randomUUID().toString()): Boolean {
        val ws = currentWebSocket ?: return false
        return ws.send(ClientMessage.GetGroups(requestId).toJson())
    }

    fun getGroupMembers(groupId: String, requestId: String = java.util.UUID.randomUUID().toString()): Boolean {
        val ws = currentWebSocket ?: return false
        return ws.send(ClientMessage.GetGroupMembers(requestId, groupId).toJson())
    }

    fun addGroupMember(groupId: String, userId: String, requestId: String = java.util.UUID.randomUUID().toString()): Boolean {
        val ws = currentWebSocket ?: return false
        return ws.send(ClientMessage.AddGroupMember(requestId, groupId, userId).toJson())
    }

    fun removeGroupMember(groupId: String, userId: String, requestId: String = java.util.UUID.randomUUID().toString()): Boolean {
        val ws = currentWebSocket ?: return false
        return ws.send(ClientMessage.RemoveGroupMember(requestId, groupId, userId).toJson())
    }

    fun leaveGroup(groupId: String, requestId: String = java.util.UUID.randomUUID().toString()): Boolean {
        val ws = currentWebSocket ?: return false
        return ws.send(ClientMessage.LeaveGroup(requestId, groupId).toJson())
    }

    fun renameGroup(groupId: String, newName: String, requestId: String = java.util.UUID.randomUUID().toString()): Boolean {
        val ws = currentWebSocket ?: return false
        return ws.send(ClientMessage.RenameGroup(requestId, groupId, newName).toJson())
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        currentWebSocket?.close(1000, "Normal closure")
        currentWebSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    open suspend fun authenticateWithToken(token: String, timeoutMs: Long = 8000L): AuthResult {
        val trimmed = token.trim()
        if (trimmed.isBlank()) {
            return AuthResult.InvalidToken("Invitation token cannot be blank")
        }

        disconnect()
        authStorage.saveAuthToken(trimmed)
        connect(trimmed)

        return try {
            withTimeout(timeoutMs) {
                val event = authEvents.first {
                    it is ServerMessage.AuthOk || it is ServerMessage.AuthError || it is ServerMessage.Error
                }
                when (event) {
                    is ServerMessage.AuthOk -> {
                        AuthResult.Success(event.userId)
                    }
                    is ServerMessage.AuthError -> {
                        authStorage.clearAuthToken()
                        disconnect()
                        AuthResult.InvalidToken(event.reason)
                    }
                    is ServerMessage.Error -> {
                        authStorage.clearAuthToken()
                        disconnect()
                        AuthResult.ServerUnavailable(event.message)
                    }
                    else -> {
                        authStorage.clearAuthToken()
                        disconnect()
                        AuthResult.ServerUnavailable("Unexpected authentication response")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            authStorage.clearAuthToken()
            disconnect()
            AuthResult.ServerUnavailable("Authentication timed out. Aster server is unreachable.")
        } catch (e: Exception) {
            authStorage.clearAuthToken()
            disconnect()
            AuthResult.ServerUnavailable(e.message ?: "Connection error")
        }
    }
}
