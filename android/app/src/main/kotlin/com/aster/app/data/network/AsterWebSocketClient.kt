package com.aster.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val authStorage: AsterAuthStorage,
    @ApplicationContext private val context: Context? = null
) {
    private val TAG = "AsterWebSocketClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverAcks = MutableSharedFlow<ServerMessage.MessageAccepted>(extraBufferCapacity = 64)
    val serverAcks: SharedFlow<ServerMessage.MessageAccepted> = _serverAcks.asSharedFlow()

    private val _incomingMessages = MutableSharedFlow<ServerMessage.RelayMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<ServerMessage.RelayMessage> = _incomingMessages.asSharedFlow()

    private val _deliveredEvents = MutableSharedFlow<ServerMessage.MessageDelivered>(extraBufferCapacity = 64)
    val deliveredEvents: SharedFlow<ServerMessage.MessageDelivered> = _deliveredEvents.asSharedFlow()

    private val _readEvents = MutableSharedFlow<ServerMessage.MessageRead>(extraBufferCapacity = 64)
    val readEvents: SharedFlow<ServerMessage.MessageRead> = _readEvents.asSharedFlow()

    private val _unsentEvents = MutableSharedFlow<ServerMessage.MessageUnsent>(extraBufferCapacity = 64)
    val unsentEvents: SharedFlow<ServerMessage.MessageUnsent> = _unsentEvents.asSharedFlow()

    private val _reactionUpdates = MutableSharedFlow<ServerMessage.ReactionUpdate>(extraBufferCapacity = 64)
    val reactionUpdates: SharedFlow<ServerMessage.ReactionUpdate> = _reactionUpdates.asSharedFlow()

    private val _groupOperations = MutableSharedFlow<ServerMessage.GroupOperationOk>(extraBufferCapacity = 64)
    val groupOperations: SharedFlow<ServerMessage.GroupOperationOk> = _groupOperations.asSharedFlow()

    private val _groupLists = MutableSharedFlow<ServerMessage.GroupList>(extraBufferCapacity = 64)
    val groupLists: SharedFlow<ServerMessage.GroupList> = _groupLists.asSharedFlow()

    private val _groupMembersLists = MutableSharedFlow<ServerMessage.GroupMembersList>(extraBufferCapacity = 64)
    val groupMembersLists: SharedFlow<ServerMessage.GroupMembersList> = _groupMembersLists.asSharedFlow()

    private val _groupEvents = MutableSharedFlow<ServerMessage.GroupEvent>(extraBufferCapacity = 64)
    val groupEvents: SharedFlow<ServerMessage.GroupEvent> = _groupEvents.asSharedFlow()

    private val _presenceUpdates = MutableSharedFlow<ServerMessage.PresenceUpdate>(replay = 1, extraBufferCapacity = 64)
    val presenceUpdates: SharedFlow<ServerMessage.PresenceUpdate> = _presenceUpdates.asSharedFlow()

    private val _userDeletedEvents = MutableSharedFlow<ServerMessage.UserDeleted>(replay = 1, extraBufferCapacity = 64)
    val userDeletedEvents: SharedFlow<ServerMessage.UserDeleted> = _userDeletedEvents.asSharedFlow()

    private val _friendRequestReceivedEvents = MutableSharedFlow<ServerMessage.FriendRequestReceived>(extraBufferCapacity = 64)
    val friendRequestReceivedEvents: SharedFlow<ServerMessage.FriendRequestReceived> = _friendRequestReceivedEvents.asSharedFlow()

    private val _friendRequestUpdatedEvents = MutableSharedFlow<ServerMessage.FriendRequestUpdated>(extraBufferCapacity = 64)
    val friendRequestUpdatedEvents: SharedFlow<ServerMessage.FriendRequestUpdated> = _friendRequestUpdatedEvents.asSharedFlow()

    private val _authEvents = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 16)
    val authEvents: SharedFlow<ServerMessage> = _authEvents.asSharedFlow()

    private val _errors = MutableSharedFlow<ServerMessage.Error>(extraBufferCapacity = 64)
    val errors: SharedFlow<ServerMessage.Error> = _errors.asSharedFlow()

    fun emitIncomingMessageForTesting(msg: ServerMessage.RelayMessage) {
        _incomingMessages.tryEmit(msg)
    }

    fun emitDeliveredForTesting(msg: ServerMessage.MessageDelivered) {
        _deliveredEvents.tryEmit(msg)
    }

    fun emitReadForTesting(msg: ServerMessage.MessageRead) {
        _readEvents.tryEmit(msg)
    }

    fun emitUnsentForTesting(msg: ServerMessage.MessageUnsent) {
        _unsentEvents.tryEmit(msg)
    }

    fun emitReactionUpdateForTesting(msg: ServerMessage.ReactionUpdate) {
        _reactionUpdates.tryEmit(msg)
    }

    fun emitPresenceUpdateForTesting(msg: ServerMessage.PresenceUpdate) {
        _presenceUpdates.tryEmit(msg)
    }

    fun emitUserDeletedForTesting(msg: ServerMessage.UserDeleted) {
        _userDeletedEvents.tryEmit(msg)
    }

    fun emitFriendRequestReceivedForTesting(msg: ServerMessage.FriendRequestReceived) {
        _friendRequestReceivedEvents.tryEmit(msg)
    }

    fun emitFriendRequestUpdatedForTesting(msg: ServerMessage.FriendRequestUpdated) {
        _friendRequestUpdatedEvents.tryEmit(msg)
    }

    fun emitGroupEventForTesting(msg: ServerMessage.GroupEvent) {
        _groupEvents.tryEmit(msg)
    }

    private var currentWebSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var networkDebounceJob: Job? = null
    private var currentBackoffMs = NetworkConfig.INITIAL_RETRY_DELAY_MS
    private var shouldReconnect = true
    private var connectionAttempt = 0

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val ctx = context ?: return
        try {
            val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onNetworkAvailableOrChanged()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        onNetworkAvailableOrChanged()
                    }
                }

                override fun onLost(network: Network) {
                    onNetworkLost()
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not register NetworkCallback: ${e.message}")
        }
    }

    fun cleanup() {
        try {
            val ctx = context ?: return
            val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (_: Exception) {}
    }

    @androidx.annotation.VisibleForTesting
    internal var networkAvailableOverride: Boolean? = null

    open fun isNetworkAvailable(): Boolean {
        networkAvailableOverride?.let { return it }
        if (android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) {
            return true
        }
        val ctx = context ?: return true
        return try {
            val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            true
        }
    }

    internal fun onNetworkAvailableOrChanged() {
        networkDebounceJob?.cancel()
        networkDebounceJob = scope.launch {
            delay(300) // Debounce rapid network state flutters (e.g. cell handoff)
            if (_connectionState.value != ConnectionState.CONNECTED && shouldReconnect) {
                val token = authStorage.getAuthToken()
                if (!token.isNullOrBlank()) {
                    Log.i(TAG, "[AsterLifecycle] Usable network connectivity available; reprioritizing silent connection")
                    reconnectJob?.cancel()
                    reconnectJob = null
                    currentBackoffMs = NetworkConfig.INITIAL_RETRY_DELAY_MS
                    connectionAttempt = 0
                    initiateConnection()
                }
            }
        }
    }

    internal fun onNetworkLost() {
        Log.i(TAG, "[AsterLifecycle] Network lost")
        val ws = currentWebSocket
        currentWebSocket = null
        try {
            ws?.cancel()
        } catch (_: Exception) {}

        reconnectJob?.cancel()
        reconnectJob = null

        _connectionState.value = ConnectionState.DISCONNECTED
        Log.i(TAG, "[AsterLifecycle] Entering state: DISCONNECTED (Offline)")
    }

    fun connect(token: String? = null) {
        if (!token.isNullOrBlank()) {
            authStorage.saveAuthToken(token)
        }
        shouldReconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        currentBackoffMs = NetworkConfig.INITIAL_RETRY_DELAY_MS
        connectionAttempt = 0

        // Cancel existing socket instance so connect() performs a fresh connection
        val oldWs = currentWebSocket
        currentWebSocket = null
        try {
            oldWs?.cancel()
        } catch (_: Exception) {}
        _connectionState.value = ConnectionState.CONNECTING

        initiateConnection()
    }

    private fun initiateConnection() {
        if (_connectionState.value == ConnectionState.CONNECTED) return

        if (!isNetworkAvailable()) {
            Log.i(TAG, "[AsterLifecycle] Network is not available; staying in DISCONNECTED state")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        val token = authStorage.getAuthToken()
        if (token.isNullOrBlank()) {
            Log.d(TAG, "No authentication token found in AsterAuthStorage; connection deferred until provisioned")
            _connectionState.value = ConnectionState.DISCONNECTED
            Log.i(TAG, "[AsterLifecycle] Entering state: DISCONNECTED")
            return
        }

        connectionAttempt++
        val attemptNum = connectionAttempt
        Log.i(TAG, "[AsterLifecycle] Retry number: $attemptNum")
        val targetUrl = NetworkConfig.serverUrl

        _connectionState.value = if (attemptNum == 1 && currentBackoffMs == NetworkConfig.INITIAL_RETRY_DELAY_MS) {
            ConnectionState.CONNECTING
        } else {
            ConnectionState.RECONNECTING
        }
        if (_connectionState.value == ConnectionState.RECONNECTING) {
            Log.i(TAG, "[AsterLifecycle] Entering state: RECONNECTING")
        }

        try {
            // Cancel and close previous socket instance to avoid connection races
            val oldWs = currentWebSocket
            currentWebSocket = null
            try {
                oldWs?.cancel()
            } catch (_: Exception) {}

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
                    Log.i(TAG, "[AsterLifecycle] WebSocket opened: connected to $targetUrl")
                    val currentToken = authStorage.getAuthToken()
                    if (currentToken.isNullOrBlank()) {
                        Log.w(TAG, "Cannot authenticate: no token in storage")
                        webSocket.close(1008, "Missing credentials")
                        return
                    }
                    val authPayload = ClientMessage.Auth(token = currentToken).toJson()
                    webSocket.send(authPayload)
                    Log.i(TAG, "[AsterLifecycle] AUTH sent for token=${currentToken.take(8)}...")
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
                            Log.i(TAG, "[AsterLifecycle] AUTH accepted by server for user: ${serverMsg.userId}")
                            authStorage.saveAuthenticatedUserId(serverMsg.userId)
                            reconnectJob?.cancel()
                            reconnectJob = null
                            currentBackoffMs = NetworkConfig.INITIAL_RETRY_DELAY_MS
                            connectionAttempt = 0
                            _connectionState.value = ConnectionState.CONNECTED
                            Log.i(TAG, "[AsterLifecycle] Entering state: CONNECTED")
                            _authEvents.tryEmit(serverMsg)
                        }
                        is ServerMessage.AuthError -> {
                            Log.e(TAG, "[AsterLifecycle] AUTH failed: ${serverMsg.reason}")
                            authStorage.clearAuthToken()
                            shouldReconnect = false
                            reconnectJob?.cancel()
                            reconnectJob = null
                            _connectionState.value = ConnectionState.DISCONNECTED
                            Log.i(TAG, "[AsterLifecycle] Entering state: DISCONNECTED")
                            _authEvents.tryEmit(serverMsg)
                            webSocket.close(1008, "Authentication failed: ${serverMsg.reason}")
                            currentWebSocket = null
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
                            _deliveredEvents.tryEmit(serverMsg)
                        }
                        is ServerMessage.MessageRead -> {
                            Log.i(TAG, "Message read confirmed: ${serverMsg.messageId} by ${serverMsg.readerId}")
                            _readEvents.tryEmit(serverMsg)
                        }
                        is ServerMessage.MessageUnsent -> {
                            Log.i(TAG, "Message unsent confirmed: ${serverMsg.messageId} in ${serverMsg.conversationId}")
                            _unsentEvents.tryEmit(serverMsg)
                        }
                        is ServerMessage.ReactionUpdate -> {
                            Log.i(TAG, "Reaction update received for ${serverMsg.messageId}: ${serverMsg.emoji} (${serverMsg.action}) by ${serverMsg.userId}")
                            _reactionUpdates.tryEmit(serverMsg)
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
                        is ServerMessage.PresenceUpdate -> {
                            Log.i(TAG, "Received presence update for ${serverMsg.userId}: isOnline=${serverMsg.isOnline}, lastSeen=${serverMsg.lastSeen}")
                            _presenceUpdates.tryEmit(serverMsg)
                        }
                        is ServerMessage.UserDeleted -> {
                            Log.i(TAG, "Received user deleted event for ${serverMsg.userId}")
                            _userDeletedEvents.tryEmit(serverMsg)
                        }
                        is ServerMessage.FriendRequestReceived -> {
                            Log.i(TAG, "Received friend request ${serverMsg.request.id} from ${serverMsg.request.senderUserId}")
                            _friendRequestReceivedEvents.tryEmit(serverMsg)
                        }
                        is ServerMessage.FriendRequestUpdated -> {
                            Log.i(TAG, "Received friend request updated ${serverMsg.requestId}: status=${serverMsg.status}")
                            _friendRequestUpdatedEvents.tryEmit(serverMsg)
                        }
                        is ServerMessage.Error -> {
                            Log.w(TAG, "Server error (${serverMsg.code}): ${serverMsg.message}")
                            _errors.tryEmit(serverMsg)
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
                    Log.i(TAG, "[AsterLifecycle] Socket closed: code=$code reason=$reason")
                    handleDisconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== currentWebSocket) return
                    val causeChain = generateSequence(t.cause) { it.cause }
                        .map { "${it.javaClass.name}: ${it.message}" }
                        .joinToString(" -> ")
                    Log.w(
                        TAG,
                        "[AsterLifecycle] Socket failure: ${t.javaClass.simpleName}: ${t.message}\n" +
                        "  Attempt: #$attemptNum to $targetUrl\n" +
                        "  Cause chain: ${if (causeChain.isBlank()) "None" else causeChain}\n" +
                        "  HTTP response: ${response?.code}"
                    )
                    _authEvents.tryEmit(ServerMessage.Error(code = "NETWORK_ERROR", message = NetworkSanitizer.sanitizeMessage(t.message, "Connection failure")))
                    handleDisconnect()
                }
            })
        } catch (e: Exception) {
            val causeChain = generateSequence(e.cause) { it.cause }
                .map { "${it.javaClass.name}: ${it.message}" }
                .joinToString(" -> ")
            Log.e(
                TAG,
                "[AsterLifecycle] Socket failure: ${e.javaClass.simpleName}: ${e.message}\n" +
                "  Attempt: #$attemptNum to $targetUrl\n" +
                "  Cause chain: ${if (causeChain.isBlank()) "None" else causeChain}"
            )
            _authEvents.tryEmit(ServerMessage.Error(code = "NETWORK_ERROR", message = NetworkSanitizer.sanitizeMessage(e.message, "Connection exception")))
            handleDisconnect()
        }
    }

    private fun handleDisconnect() {
        currentWebSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.i(TAG, "[AsterLifecycle] Entering state: DISCONNECTED")

        if (!shouldReconnect) {
            return
        }

        if (isNetworkAvailable()) {
            scheduleReconnect()
        } else {
            Log.i(TAG, "[AsterLifecycle] Network is unavailable; waiting for network restoration before reconnecting")
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        if (!isNetworkAvailable()) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _connectionState.value = ConnectionState.RECONNECTING
            Log.i(TAG, "[AsterLifecycle] Entering state: RECONNECTING")

            val delayMs = currentBackoffMs
            Log.i(TAG, "[AsterLifecycle] Silent retry backoff delay: ${delayMs}ms")
            delay(delayMs)

            currentBackoffMs = (currentBackoffMs * NetworkConfig.BACKOFF_MULTIPLIER).toLong()
                .coerceAtMost(NetworkConfig.MAX_RETRY_DELAY_MS)

            if (shouldReconnect && isNetworkAvailable()) {
                initiateConnection()
            } else {
                _connectionState.value = ConnectionState.DISCONNECTED
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

    fun sendMarkRead(messageId: String, conversationId: String): Boolean {
        val ws = currentWebSocket ?: return false
        return ws.send(ClientMessage.MarkRead(messageId, conversationId).toJson())
    }

    fun sendUnsend(messageId: String, conversationId: String): Boolean {
        val ws = currentWebSocket ?: return false
        return ws.send(ClientMessage.UnsendMessage(messageId, conversationId).toJson())
    }

    fun toggleReaction(messageId: String, conversationId: String, emoji: String): Boolean {
        val ws = currentWebSocket ?: return false
        return ws.send(ClientMessage.ToggleReaction(messageId, conversationId, emoji).toJson())
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        networkDebounceJob?.cancel()
        networkDebounceJob = null
        currentWebSocket?.close(1000, "Normal closure")
        currentWebSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.i(TAG, "[AsterLifecycle] Entering state: DISCONNECTED")
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
