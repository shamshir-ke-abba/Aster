package com.aster.backend
 
import com.aster.backend.database.AuthTokenRecord
import com.aster.backend.database.MailboxDatabase
import com.aster.backend.protocol.FriendRequestSummary
import com.aster.backend.protocol.ProtocolLimits
import com.aster.backend.protocol.UserSummary
import com.aster.backend.protocol.asterJson
import com.aster.backend.relay.MessageRelay
import com.aster.backend.session.SessionManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.NetworkInterface
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal class RateLimiter(private val maxRequests: Int, private val windowMs: Long) {
    private val requestCounts = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()

    fun tryAcquire(key: String): Boolean {
        val now = System.currentTimeMillis()
        val queue = requestCounts.computeIfAbsent(key) { ConcurrentLinkedQueue() }
        synchronized(queue) {
            while (queue.isNotEmpty() && queue.peek() < now - windowMs) {
                queue.poll()
            }
            if (queue.size >= maxRequests) {
                return false
            }
            queue.add(now)
            return true
        }
    }
}

internal fun isPermittedAdminIp(remoteAddress: String, allowedIpsOverride: String? = System.getenv("ASTER_ADMIN_ALLOWED_IPS")): Boolean {
    if (!allowedIpsOverride.isNullOrBlank()) {
        val allowedList = allowedIpsOverride.split(",").map { it.trim() }
        if (allowedList.contains("*") || allowedList.contains(remoteAddress)) {
            return true
        }
    }
    val addr = try {
        InetAddress.getByName(remoteAddress)
    } catch (_: Exception) {
        return false
    }
    return addr.isLoopbackAddress || NetworkInterface.getByInetAddress(addr) != null
}

private fun isPermittedAdminHost(call: io.ktor.server.application.ApplicationCall): Boolean {
    return isPermittedAdminIp(call.request.local.remoteAddress)
}

private fun checkAdminAccess(call: io.ktor.server.application.ApplicationCall, configuredKey: String?): HttpStatusCode? {
    if (!isPermittedAdminHost(call)) {
        return HttpStatusCode.Forbidden
    }
    if (configuredKey.isNullOrBlank()) {
        // Fail-closed: administrative control plane is strictly disabled if no key configured
        return HttpStatusCode.Unauthorized
    }
    val headerKey = call.request.headers["X-Aster-Admin-Key"] ?: return HttpStatusCode.Unauthorized
    if (headerKey.isBlank()) return HttpStatusCode.Unauthorized
    val headerBytes = headerKey.toByteArray(Charsets.UTF_8)
    val configuredBytes = configuredKey.toByteArray(Charsets.UTF_8)
    if (!java.security.MessageDigest.isEqual(headerBytes, configuredBytes)) {
        return HttpStatusCode.Unauthorized
    }
    return null
}

fun Application.asterModule(
    sessionManager: SessionManager = SessionManager(),
    mailboxDatabase: MailboxDatabase = MailboxDatabase(System.getenv("ASTER_DB_PATH") ?: ":memory:"),
    relay: MessageRelay = MessageRelay(sessionManager, mailboxDatabase),
    adminKey: String? = System.getenv("ASTER_ADMIN_KEY")?.takeIf { it.isNotBlank() }
) {
    val logger = LoggerFactory.getLogger("AsterServer")

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(30)
        maxFrameSize = com.aster.backend.protocol.ProtocolLimits.MAX_FRAME_SIZE
        masking = false
    }

    routing {
        get("/health") {
            val body = """{"status":"ok","users":${sessionManager.activeUserCount()},"connections":${sessionManager.activeConnectionCount()}}"""
            call.respondText(body, ContentType.Application.Json)
        }

        post("/api/provision") {
            val accessErr = checkAdminAccess(call, adminKey)
            if (accessErr != null) {
                val errBody = if (accessErr == HttpStatusCode.Forbidden) {
                    """{"error":"Forbidden: administrative routes restricted to trusted host boundary"}"""
                } else {
                    """{"error":"Unauthorized"}"""
                }
                call.respondText(errBody, ContentType.Application.Json, accessErr)
                return@post
            }

            val body = call.receiveText()
            val json = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                call.respondText("""{"error":"Invalid JSON"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val userId = try { json["userId"]?.jsonPrimitive?.content } catch (_: Exception) { null }
            val deviceId = try { json["deviceId"]?.jsonPrimitive?.content } catch (_: Exception) { null } ?: UUID.randomUUID().toString()

            if (userId.isNullOrBlank() || !com.aster.backend.protocol.ProtocolLimits.USER_ID_REGEX.matches(userId)) {
                call.respondText("""{"error":"Invalid userId format"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (!com.aster.backend.protocol.ProtocolLimits.DEVICE_ID_REGEX.matches(deviceId)) {
                call.respondText("""{"error":"Invalid deviceId format"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val token = mailboxDatabase.createToken(userId, deviceId)
            val resp = """{"status":"ok","userId":"$userId","deviceId":"$deviceId","token":"$token"}"""
            call.respondText(resp, ContentType.Application.Json, HttpStatusCode.Created)
        }

        post("/api/revoke") {
            val accessErr = checkAdminAccess(call, adminKey)
            if (accessErr != null) {
                val errBody = if (accessErr == HttpStatusCode.Forbidden) {
                    """{"error":"Forbidden: administrative routes restricted to trusted host boundary"}"""
                } else {
                    """{"error":"Unauthorized"}"""
                }
                call.respondText(errBody, ContentType.Application.Json, accessErr)
                return@post
            }

            val body = call.receiveText()
            val json = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                call.respondText("""{"error":"Invalid JSON"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val token = try { json["token"]?.jsonPrimitive?.content } catch (_: Exception) { null }
            val userId = try { json["userId"]?.jsonPrimitive?.content } catch (_: Exception) { null }
            val deviceId = try { json["deviceId"]?.jsonPrimitive?.content } catch (_: Exception) { null }

            when {
                !token.isNullOrBlank() -> {
                    val record = mailboxDatabase.validateToken(token)
                    val revoked = mailboxDatabase.revokeToken(token)
                    if (record != null) {
                        sessionManager.closeSession(record.userId, "Credential revoked")
                    }
                    call.respondText(
                        """{"status":"ok","revoked":$revoked,"target":"token"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                }
                !userId.isNullOrBlank() -> {
                    val count = mailboxDatabase.revokeTokensForUser(userId)
                    sessionManager.closeSession(userId, "User credentials revoked")
                    call.respondText(
                        """{"status":"ok","revoked":true,"revokedCount":$count,"target":"user"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                }
                !deviceId.isNullOrBlank() -> {
                    val userIds = mailboxDatabase.getUserIdsForDevice(deviceId)
                    val revoked = mailboxDatabase.revokeTokensForDevice(deviceId)
                    for (uid in userIds) {
                        sessionManager.closeSession(uid, "Device credentials revoked")
                    }
                    call.respondText(
                        """{"status":"ok","revoked":$revoked,"target":"device"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                }
                else -> {
                    call.respondText(
                        """{"error":"Must specify token, userId, or deviceId"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                }
            }
        }

        post("/api/push/register") {
            val authHeader = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
                ?: call.request.headers["X-Aster-Token"]?.trim()
            val tokenRecord = if (!authHeader.isNullOrBlank()) mailboxDatabase.validateToken(authHeader) else null
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }

            val body = call.receiveText()
            val json = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                call.respondText("""{"error":"Invalid JSON"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val pushToken = try { json["pushToken"]?.jsonPrimitive?.content } catch (_: Exception) { null }
            val deviceId = try { json["deviceId"]?.jsonPrimitive?.content } catch (_: Exception) { null } ?: tokenRecord.deviceId
            val endpoint = try { json["endpoint"]?.jsonPrimitive?.content } catch (_: Exception) { null }

            if (pushToken.isNullOrBlank()) {
                call.respondText("""{"error":"Must specify pushToken"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            mailboxDatabase.registerPushToken(tokenRecord.userId, deviceId, pushToken, endpoint)
            call.respondText("""{"status":"ok","userId":"${tokenRecord.userId}"}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        val registrationRateLimit = System.getenv("ASTER_REGISTRATION_RATE_LIMIT")?.toIntOrNull() ?: 20
        val registrationLimiter = RateLimiter(maxRequests = registrationRateLimit, windowMs = 60_000L)

        suspend fun authenticate(reqCall: io.ktor.server.application.ApplicationCall): AuthTokenRecord? {
            val authHeader = reqCall.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
                ?: reqCall.request.headers["X-Aster-Token"]?.trim()
            return if (!authHeader.isNullOrBlank()) mailboxDatabase.validateToken(authHeader) else null
        }

        post("/api/register") {
            val remoteIp = call.request.local.remoteAddress
            if (!registrationLimiter.tryAcquire(remoteIp)) {
                call.respondText(
                    """{"error":"Rate limit exceeded. Please wait before registering again."}""",
                    ContentType.Application.Json,
                    HttpStatusCode.TooManyRequests
                )
                return@post
            }

            val body = call.receiveText()
            val json = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                call.respondText("""{"error":"Invalid JSON"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val username = try { json["username"]?.jsonPrimitive?.content?.trim() } catch (_: Exception) { null }
            val displayName = try { json["displayName"]?.jsonPrimitive?.content?.trim() } catch (_: Exception) { null }
            val deviceId = try { json["deviceId"]?.jsonPrimitive?.content?.trim() } catch (_: Exception) { null } ?: UUID.randomUUID().toString()

            if (username.isNullOrBlank() || !ProtocolLimits.USERNAME_REGEX.matches(username.lowercase())) {
                call.respondText(
                    """{"error":"Invalid username: must be 3-24 alphanumeric or underscore characters"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }

            if (displayName.isNullOrBlank() || displayName.length > ProtocolLimits.MAX_DISPLAY_NAME_LENGTH) {
                call.respondText(
                    """{"error":"Invalid displayName: must be 1-32 non-blank characters"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }

            if (!ProtocolLimits.DEVICE_ID_REGEX.matches(deviceId)) {
                call.respondText("""{"error":"Invalid deviceId format"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            try {
                val (userRecord, token) = mailboxDatabase.createUser(username, displayName, deviceId)
                val resp = """{"status":"ok","userId":"${userRecord.userId}","asterId":"${userRecord.asterId}","username":"${userRecord.username}","displayName":"${userRecord.displayName}","token":"$token"}"""
                call.respondText(resp, ContentType.Application.Json, HttpStatusCode.Created)
            } catch (e: IllegalArgumentException) {
                call.respondText("""{"error":"${e.message}"}""", ContentType.Application.Json, HttpStatusCode.Conflict)
            } catch (e: Exception) {
                logger.error("Failed to register user: ${e.message}", e)
                call.respondText("""{"error":"Registration failed: ${e.message}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        get("/api/profile") {
            val tokenRecord = authenticate(call)
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }
            val user = mailboxDatabase.getUser(tokenRecord.userId)
            if (user == null || !user.isActive) {
                call.respondText("""{"error":"User not found or inactive"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }
            val resp = """{"status":"ok","userId":"${user.userId}","asterId":"${user.asterId}","username":"${user.username}","displayName":"${user.displayName}","createdAt":${user.createdAt},"updatedAt":${user.updatedAt}}"""
            call.respondText(resp, ContentType.Application.Json, HttpStatusCode.OK)
        }

        post("/api/profile") {
            val tokenRecord = authenticate(call)
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            val body = call.receiveText()
            val json = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                call.respondText("""{"error":"Invalid JSON"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val newDisplayName = try { json["displayName"]?.jsonPrimitive?.content?.trim() } catch (_: Exception) { null }
            val newUsername = try { json["username"]?.jsonPrimitive?.content?.trim() } catch (_: Exception) { null }

            if (newDisplayName != null) {
                if (newDisplayName.isBlank() || newDisplayName.length > ProtocolLimits.MAX_DISPLAY_NAME_LENGTH) {
                    call.respondText("""{"error":"Invalid displayName"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                mailboxDatabase.updateDisplayName(tokenRecord.userId, newDisplayName)
            }

            if (newUsername != null) {
                if (!ProtocolLimits.USERNAME_REGEX.matches(newUsername.lowercase())) {
                    call.respondText("""{"error":"Invalid username format"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                val updated = mailboxDatabase.updateUsername(tokenRecord.userId, newUsername)
                if (!updated) {
                    call.respondText("""{"error":"Username is already taken"}""", ContentType.Application.Json, HttpStatusCode.Conflict)
                    return@post
                }
            }

            val updatedUser = mailboxDatabase.getUser(tokenRecord.userId)
            if (updatedUser == null) {
                call.respondText("""{"error":"User not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }
            val resp = """{"status":"ok","userId":"${updatedUser.userId}","asterId":"${updatedUser.asterId}","username":"${updatedUser.username}","displayName":"${updatedUser.displayName}","createdAt":${updatedUser.createdAt},"updatedAt":${updatedUser.updatedAt}}"""
            call.respondText(resp, ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/api/users/search") {
            val tokenRecord = authenticate(call)
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }
            val q = call.request.queryParameters["q"]?.trim() ?: ""
            if (q.isBlank()) {
                call.respondText("""{"status":"ok","users":[]}""", ContentType.Application.Json, HttpStatusCode.OK)
                return@get
            }
            val rawResults = mailboxDatabase.searchUsers(q, tokenRecord.userId)
            val filtered = rawResults.filter { other ->
                !mailboxDatabase.isUserBlocked(other.userId, tokenRecord.userId) &&
                !mailboxDatabase.isUserBlocked(tokenRecord.userId, other.userId)
            }.map {
                UserSummary(it.userId, it.asterId, it.username, it.displayName)
            }
            val jsonArray = asterJson.encodeToString(filtered)
            call.respondText("""{"status":"ok","users":$jsonArray}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        post("/api/friends/request") {
            val tokenRecord = authenticate(call)
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            val body = call.receiveText()
            val json = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                call.respondText("""{"error":"Invalid JSON"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            val targetUserId = try { json["targetUserId"]?.jsonPrimitive?.content?.trim() } catch (_: Exception) { null }
            val targetAsterId = try { json["targetAsterId"]?.jsonPrimitive?.content?.trim() } catch (_: Exception) { null }

            val target = when {
                !targetAsterId.isNullOrBlank() -> mailboxDatabase.getUserByAsterId(targetAsterId)
                !targetUserId.isNullOrBlank() -> mailboxDatabase.getUser(targetUserId)
                else -> null
            }

            if (target == null || !target.isActive) {
                call.respondText("""{"error":"Target user not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

            if (target.userId == tokenRecord.userId) {
                call.respondText("""{"error":"Cannot send friend request to yourself"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            try {
                val req = mailboxDatabase.sendFriendRequest(tokenRecord.userId, target.userId)
                val summary = FriendRequestSummary(
                    id = req.id,
                    senderUserId = req.senderUserId,
                    recipientUserId = req.recipientUserId,
                    status = req.status,
                    recipient = UserSummary(target.userId, target.asterId, target.username, target.displayName),
                    createdAt = req.createdAt
                )
                call.respondText(asterJson.encodeToString(FriendRequestSummary.serializer(), summary), ContentType.Application.Json, HttpStatusCode.Created)
            } catch (e: IllegalStateException) {
                if (e.message?.contains("prohibited due to user block") == true) {
                    call.respondText("""{"error":"Cannot send friend request: user unavailable"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
                } else {
                    call.respondText("""{"error":"${e.message}"}""", ContentType.Application.Json, HttpStatusCode.Conflict)
                }
            } catch (e: Exception) {
                call.respondText("""{"error":"Failed to send friend request: ${e.message}"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }

        post("/api/friends/respond") {
            val tokenRecord = authenticate(call)
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            val body = call.receiveText()
            val json = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                call.respondText("""{"error":"Invalid JSON"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            val requestId = try { json["requestId"]?.jsonPrimitive?.content?.trim() } catch (_: Exception) { null }
            val action = try { json["action"]?.jsonPrimitive?.content?.trim()?.uppercase() } catch (_: Exception) { null }

            if (requestId.isNullOrBlank() || (action != "ACCEPT" && action != "REJECT")) {
                call.respondText("""{"error":"Must provide requestId and valid action ('ACCEPT' or 'REJECT')"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val updated = mailboxDatabase.respondFriendRequest(requestId, tokenRecord.userId, accept = (action == "ACCEPT"))
            if (!updated) {
                call.respondText("""{"error":"Pending friend request not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }
            call.respondText("""{"status":"ok","requestId":"$requestId","action":"$action"}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        post("/api/friends/cancel") {
            val tokenRecord = authenticate(call)
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            val body = call.receiveText()
            val json = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                call.respondText("""{"error":"Invalid JSON"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            val requestId = try { json["requestId"]?.jsonPrimitive?.content?.trim() } catch (_: Exception) { null }
            if (requestId.isNullOrBlank()) {
                call.respondText("""{"error":"Must provide requestId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val cancelled = mailboxDatabase.cancelFriendRequest(requestId, tokenRecord.userId)
            if (!cancelled) {
                call.respondText("""{"error":"Pending friend request not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }
            call.respondText("""{"status":"ok","requestId":"$requestId","action":"CANCELLED"}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/api/friends/requests") {
            val tokenRecord = authenticate(call)
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }
            val incoming = mailboxDatabase.getIncomingFriendRequests(tokenRecord.userId).map { req ->
                val sender = mailboxDatabase.getUser(req.senderUserId)?.let {
                    UserSummary(it.userId, it.asterId, it.username, it.displayName)
                }
                FriendRequestSummary(
                    id = req.id,
                    senderUserId = req.senderUserId,
                    recipientUserId = req.recipientUserId,
                    status = req.status,
                    sender = sender,
                    createdAt = req.createdAt
                )
            }
            val outgoing = mailboxDatabase.getOutgoingFriendRequests(tokenRecord.userId).map { req ->
                val recipient = mailboxDatabase.getUser(req.recipientUserId)?.let {
                    UserSummary(it.userId, it.asterId, it.username, it.displayName)
                }
                FriendRequestSummary(
                    id = req.id,
                    senderUserId = req.senderUserId,
                    recipientUserId = req.recipientUserId,
                    status = req.status,
                    recipient = recipient,
                    createdAt = req.createdAt
                )
            }
            val incomingJson = asterJson.encodeToString(incoming)
            val outgoingJson = asterJson.encodeToString(outgoing)
            call.respondText("""{"status":"ok","incoming":$incomingJson,"outgoing":$outgoingJson}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/api/friends") {
            val tokenRecord = authenticate(call)
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }
            val friends = mailboxDatabase.getFriends(tokenRecord.userId).map {
                UserSummary(it.userId, it.asterId, it.username, it.displayName)
            }
            val json = asterJson.encodeToString(friends)
            call.respondText("""{"status":"ok","friends":$json}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        post("/api/friends/block") {
            val tokenRecord = authenticate(call)
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            val body = call.receiveText()
            val json = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                call.respondText("""{"error":"Invalid JSON"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            val targetUserId = try { json["targetUserId"]?.jsonPrimitive?.content?.trim() } catch (_: Exception) { null }
            val targetAsterId = try { json["targetAsterId"]?.jsonPrimitive?.content?.trim() } catch (_: Exception) { null }

            val target = when {
                !targetAsterId.isNullOrBlank() -> mailboxDatabase.getUserByAsterId(targetAsterId)
                !targetUserId.isNullOrBlank() -> mailboxDatabase.getUser(targetUserId)
                else -> null
            }

            if (target == null) {
                call.respondText("""{"error":"Target user not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }
            if (target.userId == tokenRecord.userId) {
                call.respondText("""{"error":"Cannot block yourself"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            mailboxDatabase.blockUser(tokenRecord.userId, target.userId)
            call.respondText("""{"status":"ok","blockedUserId":"${target.userId}"}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        post("/api/friends/unblock") {
            val tokenRecord = authenticate(call)
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            val body = call.receiveText()
            val json = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                call.respondText("""{"error":"Invalid JSON"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            val targetUserId = try { json["targetUserId"]?.jsonPrimitive?.content?.trim() } catch (_: Exception) { null }
            if (targetUserId.isNullOrBlank()) {
                call.respondText("""{"error":"Must provide targetUserId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            mailboxDatabase.unblockUser(tokenRecord.userId, targetUserId)
            call.respondText("""{"status":"ok","unblockedUserId":"$targetUserId"}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/api/friends/blocked") {
            val tokenRecord = authenticate(call)
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }
            val blocked = mailboxDatabase.getBlockedUsers(tokenRecord.userId).map {
                UserSummary(it.userId, it.asterId, it.username, it.displayName)
            }
            val json = asterJson.encodeToString(blocked)
            call.respondText("""{"status":"ok","blocked":$json}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        post("/api/logout") {
            val authHeader = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
                ?: call.request.headers["X-Aster-Token"]?.trim()
            val tokenRecord = if (!authHeader.isNullOrBlank()) mailboxDatabase.validateToken(authHeader) else null
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            mailboxDatabase.revokeToken(authHeader!!)
            sessionManager.closeSession(tokenRecord.userId, "User logged out")
            call.respondText("""{"status":"ok","message":"Logged out successfully"}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        post("/api/account/delete") {
            val authHeader = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
                ?: call.request.headers["X-Aster-Token"]?.trim()
            val tokenRecord = if (!authHeader.isNullOrBlank()) mailboxDatabase.validateToken(authHeader) else null
            if (tokenRecord == null) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            mailboxDatabase.deleteAccount(tokenRecord.userId)
            sessionManager.closeSession(tokenRecord.userId, "Account deleted")
            call.respondText("""{"status":"ok","message":"Account deleted successfully"}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        webSocket("/ws") {
            logger.info("New WebSocket connection opened")
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            relay.handleMessage(this, text)
                        }
                        is Frame.Close -> {
                            logger.info("Close frame received")
                        }
                        else -> {
                            // Ignore binary/ping/pong frames handled by Ktor
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("WebSocket session exception: ${e.message}")
            } finally {
                relay.handleDisconnect(this)
            }
        }
    }
}
