package com.aster.backend

import com.aster.backend.database.DeliveryState
import com.aster.backend.database.MailboxDatabase
import com.aster.backend.database.MailboxMessage
import com.aster.backend.protocol.ClientMessage
import com.aster.backend.protocol.ServerMessage
import com.aster.backend.protocol.asterJson
import com.aster.backend.relay.MessageRelay
import com.aster.backend.session.SessionManager
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AsterAuthenticationTest {

    private suspend fun SendChannel<Frame>.sendMsg(msg: ClientMessage) {
        val json = asterJson.encodeToString(ClientMessage.serializer(), msg)
        send(Frame.Text(json))
    }

    private suspend fun ReceiveChannel<Frame>.receiveServerMsg(): ServerMessage {
        val frame = receive()
        assertIs<Frame.Text>(frame)
        return asterJson.decodeFromString(ServerMessage.serializer(), frame.readText())
    }

    @Test
    fun testValidCredentialAuthenticates() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay) }

        val saadToken = runBlocking { mailboxDb.createToken("user_saad") }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            val response = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(response)
            assertEquals("user_saad", response.userId)
            assertTrue(sessionManager.isOnline("user_saad"))
        }
    }

    @Test
    fun testInvalidCredentialRejected() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay) }

        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = "ast_tok_invalid_secret_credential"))
            val response = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthError>(response)
            assertEquals(0, sessionManager.activeUserCount())
        }
    }

    @Test
    fun testMissingCredentialRejected() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay) }

        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = "   "))
            val response = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthError>(response)
            assertEquals(0, sessionManager.activeUserCount())
        }
    }

    @Test
    fun testRevokedCredentialRejected() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay) }

        val token = runBlocking { mailboxDb.createToken("user_saad") }
        runBlocking { mailboxDb.revokeToken(token) }

        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = token))
            val response = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthError>(response)
            assertEquals(0, sessionManager.activeUserCount())
        }
    }

    @Test
    fun testIdentityCannotBeOverriddenInAuth() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay) }

        // Token belongs to user_saad
        val saadToken = runBlocking { mailboxDb.createToken("user_saad") }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            // Client attempts to claim identity as user_sam using Saad's credential
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken, userId = "user_sam"))
            val response = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(response)
            // Server MUST resolve to user_saad, overriding the client claim
            assertEquals("user_saad", response.userId)
            assertTrue(sessionManager.isOnline("user_saad"))
            assertEquals(false, sessionManager.isOnline("user_sam"))
        }
    }

    @Test
    fun testSendUsesAuthenticatedIdentityAndIgnoresForgedSender() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay) }

        val saadToken = runBlocking { mailboxDb.createToken("user_saad") }
        val alexToken = runBlocking { mailboxDb.createToken("user_alex") }
        val client = createClient { install(WebSockets) }

        // 1. Alex connects as recipient
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            // 2. Saad connects and sends a message forged as user_sam
            client.webSocket("/ws") {
                outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                outgoing.sendMsg(
                    ClientMessage.SendMessage(
                        messageId = "forged_msg_001",
                        conversationId = "conv_1",
                        recipientId = "user_alex",
                        body = "Hello from forged Sam",
                        timestamp = System.currentTimeMillis(),
                        senderId = "user_sam" // Attempting to forge sender identity
                    )
                )

                val ack = incoming.receiveServerMsg()
                assertIs<ServerMessage.MessageAccepted>(ack)
                assertEquals("forged_msg_001", ack.messageId)
            }

            // 3. Alex receives the relayed message
            val relayed = incoming.receiveServerMsg()
            assertIs<ServerMessage.RelayMessage>(relayed)
            assertEquals("forged_msg_001", relayed.messageId)
            // Relayed message MUST have authenticated sender user_saad, NOT forged user_sam
            assertEquals("user_saad", relayed.senderId)
        }

        // 4. Verify durable database record also has user_saad
        val storedMsg = runBlocking { mailboxDb.getMessage("forged_msg_001") }
        assertNotNull(storedMsg)
        assertEquals("user_saad", storedMsg.senderUserId)
    }

    @Test
    fun testUnauthenticatedClientCannotSendMessages() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay) }

        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(
                ClientMessage.SendMessage(
                    messageId = "unauth_msg",
                    conversationId = "conv_1",
                    recipientId = "user_alex",
                    body = "Sneaky message",
                    timestamp = System.currentTimeMillis()
                )
            )

            val error = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(error)
            assertEquals("UNAUTHENTICATED", error.code)
        }
    }

    @Test
    fun testUnauthenticatedClientCannotSendAcks() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay) }

        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.AckReceived(messageId = "some_msg_id"))
            val error = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(error)
            assertEquals("UNAUTHENTICATED", error.code)
        }
    }

    @Test
    fun testAckIsScopedToAuthenticatedIdentity() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay) }

        // Seed a message sent from Saad to Alex
        runBlocking {
            mailboxDb.enqueueMessage(
                MailboxMessage(
                    messageId = "msg_for_alex",
                    conversationId = "conv_1",
                    senderUserId = "user_saad",
                    recipientUserId = "user_alex",
                    body = "Private for Alex",
                    clientTimestamp = 1000L,
                    serverReceivedTimestamp = 1050L,
                    deliveryState = DeliveryState.QUEUED
                )
            )
        }

        val rohanToken = runBlocking { mailboxDb.createToken("user_rohan") }
        val alexToken = runBlocking { mailboxDb.createToken("user_alex") }
        val client = createClient { install(WebSockets) }

        // Rohan attempts to ACK Alex's message
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = rohanToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            outgoing.sendMsg(ClientMessage.AckReceived(messageId = "msg_for_alex"))
            val error = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(error)
            assertEquals("UNAUTHORIZED_ACK", error.code)
        }

        // Verify message state was NOT manipulated by Rohan
        val msgAfterRohan = runBlocking { mailboxDb.getMessage("msg_for_alex") }
        assertEquals(DeliveryState.QUEUED, msgAfterRohan?.deliveryState)

        // Now intended recipient Alex ACKs the message
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            // Drain queued message delivered to Alex
            assertIs<ServerMessage.RelayMessage>(incoming.receiveServerMsg())

            outgoing.sendMsg(ClientMessage.AckReceived(messageId = "msg_for_alex"))
            kotlinx.coroutines.delay(150)
        }

        // Verify state is now ACKED
        val msgAfterAlex = runBlocking { mailboxDb.getMessage("msg_for_alex") }
        assertEquals(DeliveryState.ACKED, msgAfterAlex?.deliveryState)
    }

    @Test
    fun testMailboxAccessIsScopedToAuthenticatedIdentity() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay) }

        // Enqueue offline message for Saad
        runBlocking {
            mailboxDb.enqueueMessage(
                MailboxMessage(
                    messageId = "secret_for_saad",
                    conversationId = "conv_saad",
                    senderUserId = "user_alex",
                    recipientUserId = "user_saad",
                    body = "Confidential meeting at 9pm",
                    clientTimestamp = 1000L,
                    serverReceivedTimestamp = 1050L,
                    deliveryState = DeliveryState.QUEUED
                )
            )
        }

        val samToken = runBlocking { mailboxDb.createToken("user_sam") }
        val saadToken = runBlocking { mailboxDb.createToken("user_saad") }
        val client = createClient { install(WebSockets) }

        // Sam connects. Sam should receive ONLY Sam's messages (none)
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = samToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())
            // Sam does NOT receive secret_for_saad
            assertEquals(true, incoming.isEmpty)
        }

        // Saad connects with Saad's token. Saad receives the secret message
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())
            val received = incoming.receiveServerMsg()
            assertIs<ServerMessage.RelayMessage>(received)
            assertEquals("secret_for_saad", received.messageId)
            assertEquals("Confidential meeting at 9pm", received.body)
        }
    }

    @Test
    fun testProvisioningRequiresValidAdminKey() = testApplication {
        val testAdminKey = "test_super_secret_admin_key_12345"
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay, adminKey = testAdminKey) }

        val client = createClient { }

        // 1. Missing admin credential -> 401 Unauthorized
        val missingAuthResp = client.post("/api/provision") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"user_test","deviceId":"phone_1"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, missingAuthResp.status)

        // 2. Empty admin credential -> 401 Unauthorized
        val emptyAuthResp = client.post("/api/provision") {
            header("X-Aster-Admin-Key", "   ")
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"user_test","deviceId":"phone_1"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, emptyAuthResp.status)

        // 3. Invalid admin credential -> 401 Unauthorized
        val wrongAuthResp = client.post("/api/provision") {
            header("X-Aster-Admin-Key", "wrong_admin_key_attempt")
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"user_test","deviceId":"phone_1"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongAuthResp.status)

        // 4. Valid admin credential -> 201 Created
        val validResp = client.post("/api/provision") {
            header("X-Aster-Admin-Key", testAdminKey)
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"user_test","deviceId":"phone_1"}""")
        }
        assertEquals(HttpStatusCode.Created, validResp.status)
        val bodyText = validResp.bodyAsText()
        assertTrue(bodyText.contains(""""status":"ok""""))
        assertTrue(bodyText.contains(""""userId":"user_test""""))
        assertTrue(bodyText.contains(""""token":""""))
    }

    @Test
    fun testFailClosedWhenAdminKeyNotConfigured() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        // No admin key configured (adminKey = null)
        application { asterModule(sessionManager, mailboxDb, relay, adminKey = null) }

        val client = createClient { }

        // Even with a caller-supplied key header, request MUST fail closed with 401
        val resp = client.post("/api/provision") {
            header("X-Aster-Admin-Key", "some_key")
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"user_test","deviceId":"phone_1"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)

        val revokeResp = client.post("/api/revoke") {
            header("X-Aster-Admin-Key", "some_key")
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"user_test"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, revokeResp.status)
    }

    @Test
    fun testRevocationRequiresValidAdminKeyAndTerminatesActiveSession() = testApplication {
        val testAdminKey = "test_super_secret_admin_key_12345"
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay, adminKey = testAdminKey) }

        val wsClient = createClient { install(WebSockets) }
        val httpClient = createClient { }

        // Provision token for Saad
        val saadToken = runBlocking { mailboxDb.createToken("user_saad", "dev_saad") }

        // 1. Missing admin credential on revoke -> 401
        val unauthRevoke = httpClient.post("/api/revoke") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$saadToken"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthRevoke.status)

        // 2. Invalid admin credential on revoke -> 401
        val invalidRevoke = httpClient.post("/api/revoke") {
            header("X-Aster-Admin-Key", "bad_key")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$saadToken"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, invalidRevoke.status)

        // Connect Saad over WebSocket
        wsClient.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())
            assertTrue(sessionManager.isOnline("user_saad"))

            // 3. Authorized admin revokes Saad's token
            val validRevoke = httpClient.post("/api/revoke") {
                header("X-Aster-Admin-Key", testAdminKey)
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$saadToken"}""")
            }
            assertEquals(HttpStatusCode.OK, validRevoke.status)
            assertTrue(validRevoke.bodyAsText().contains(""""revoked":true"""))

            // Session is closed by server
            for (i in 1..20) {
                if (!sessionManager.isOnline("user_saad")) break
                Thread.sleep(50)
            }
            assertEquals(false, sessionManager.isOnline("user_saad"))
        }

        // 4. Revoked token can no longer authenticate
        wsClient.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            val err = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthError>(err)
            assertTrue(err.reason.contains("revoked") || err.reason.contains("Invalid"))
        }
    }

    @Test
    fun testDeviceSpecificRevocationIsolation() = testApplication {
        val testAdminKey = "test_super_secret_admin_key_12345"
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay, adminKey = testAdminKey) }

        val wsClient = createClient { install(WebSockets) }
        val httpClient = createClient { }

        // Provision two devices for Saad: Phone and Tablet
        val phoneToken = runBlocking { mailboxDb.createToken("user_saad", "device_phone") }
        val tabletToken = runBlocking { mailboxDb.createToken("user_saad", "device_tablet") }

        assertEquals(2, runBlocking { mailboxDb.getActiveTokenCountForUser("user_saad") })

        // Connect Phone over WebSocket
        wsClient.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = phoneToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())
            assertTrue(sessionManager.isOnline("user_saad"))

            // Revoke ONLY the Phone device via admin API
            val revokeResp = httpClient.post("/api/revoke") {
                header("X-Aster-Admin-Key", testAdminKey)
                contentType(ContentType.Application.Json)
                setBody("""{"deviceId":"device_phone"}""")
            }
            assertEquals(HttpStatusCode.OK, revokeResp.status)

            // Session is actively terminated
            for (i in 1..20) {
                if (!sessionManager.isOnline("user_saad")) break
                Thread.sleep(50)
            }
            assertEquals(false, sessionManager.isOnline("user_saad"))
        }

        // Tablet token is still valid!
        assertEquals(1, runBlocking { mailboxDb.getActiveTokenCountForUser("user_saad") })
        wsClient.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = tabletToken))
            val ok = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(ok)
            assertEquals("user_saad", ok.userId)
        }

        // Phone token is rejected!
        wsClient.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = phoneToken))
            assertIs<ServerMessage.AuthError>(incoming.receiveServerMsg())
        }
    }

    @Test
    fun testUserWideRevocationInvalidatesAllDevices() = testApplication {
        val testAdminKey = "test_super_secret_admin_key_12345"
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay, adminKey = testAdminKey) }

        val wsClient = createClient { install(WebSockets) }
        val httpClient = createClient { }

        val token1 = runBlocking { mailboxDb.createToken("user_saad", "dev_1") }
        val token2 = runBlocking { mailboxDb.createToken("user_saad", "dev_2") }
        val alexToken = runBlocking { mailboxDb.createToken("user_alex", "dev_alex") }

        // Revoke all tokens for user_saad
        val revokeResp = httpClient.post("/api/revoke") {
            header("X-Aster-Admin-Key", testAdminKey)
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"user_saad"}""")
        }
        assertEquals(HttpStatusCode.OK, revokeResp.status)

        // Both Saad tokens are rejected
        wsClient.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = token1))
            assertIs<ServerMessage.AuthError>(incoming.receiveServerMsg())
        }
        wsClient.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = token2))
            assertIs<ServerMessage.AuthError>(incoming.receiveServerMsg())
        }

        // Alex token remains completely unaffected
        wsClient.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())
        }
    }

    @Test
    fun testAdministrativeImpersonationAttackChainPrevention() = testApplication {
        val testAdminKey = "production_admin_secret_987654321"
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay, adminKey = testAdminKey) }

        val wsClient = createClient { install(WebSockets) }
        val httpClient = createClient { }

        // 1. Unauthenticated attacker tries to provision user_sam
        val unauthAttackerResp = httpClient.post("/api/provision") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"user_sam","deviceId":"attacker_device"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthAttackerResp.status)

        // 2. Attacker with wrong admin credential tries to provision user_sam
        val badKeyAttackerResp = httpClient.post("/api/provision") {
            header("X-Aster-Admin-Key", "guessed_wrong_admin_secret")
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"user_sam","deviceId":"attacker_device"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, badKeyAttackerResp.status)

        // 3. Authorized administrator provisions user_sam
        val adminResp = httpClient.post("/api/provision") {
            header("X-Aster-Admin-Key", testAdminKey)
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"user_sam","deviceId":"device_sam_legit"}""")
        }
        assertEquals(HttpStatusCode.Created, adminResp.status)
        val adminBody = adminResp.bodyAsText()
        val samToken = asterJson.parseToJsonElement(adminBody)
            .let { (it as kotlinx.serialization.json.JsonObject)["token"] }
            ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertNotNull(samToken)

        // 4. Token authenticates strictly as user_sam
        wsClient.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = samToken))
            val authOk = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(authOk)
            assertEquals("user_sam", authOk.userId)
        }
    }

    @Test
    fun testProvisioningRejectsInvalidUserIdAndDeviceId() = testApplication {
        val testAdminKey = "production_admin_secret_987654321"
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay, adminKey = testAdminKey) }

        val client = createClient { }

        // 1. Invalid userId format (missing user_ prefix)
        val badUserResp = client.post("/api/provision") {
            header("X-Aster-Admin-Key", testAdminKey)
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"invalid_prefix","deviceId":"dev_1"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badUserResp.status)

        // 2. Invalid deviceId format (disallowed chars)
        val badDevResp = client.post("/api/provision") {
            header("X-Aster-Admin-Key", testAdminKey)
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"user_test","deviceId":"invalid device with spaces!"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badDevResp.status)
    }

    @Test
    fun testRevocationRejectsEmptyTarget() = testApplication {
        val testAdminKey = "production_admin_secret_987654321"
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay, adminKey = testAdminKey) }

        val client = createClient { }

        val emptyTargetResp = client.post("/api/revoke") {
            header("X-Aster-Admin-Key", testAdminKey)
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.BadRequest, emptyTargetResp.status)
    }

    @Test
    fun testPurgeRevokedTokensLifecycle() = runBlocking {
        val mailboxDb = MailboxDatabase(":memory:")
        val token1 = mailboxDb.createToken("user_alice", "dev_1")
        val token2 = mailboxDb.createToken("user_bob", "dev_2")

        // Revoke token1
        mailboxDb.revokeToken(token1)

        // Purge tokens revoked older than 0 ms (immediate purge for test)
        val purgedCount = mailboxDb.purgeRevokedTokens(olderThanMs = -1000)
        assertEquals(1, purgedCount)

        // Token1 record is purged
        assertEquals(null, mailboxDb.validateToken(token1))
        // Token2 is still valid
        assertNotNull(mailboxDb.validateToken(token2))
    }

    @Test
    fun testAdminEndpointNetworkExposureRestriction() {
        // 1. Loopback IPs are allowed by default (host machine local control plane)
        assertTrue(isPermittedAdminIp("127.0.0.1", allowedIpsOverride = null))
        assertTrue(isPermittedAdminIp("::1", allowedIpsOverride = null))

        // 2. Untrusted remote IPs (e.g., public internet, outside subnets) are strictly rejected
        assertFalse(isPermittedAdminIp("203.0.113.195", allowedIpsOverride = null))
        assertFalse(isPermittedAdminIp("198.51.100.22", allowedIpsOverride = null))
        assertFalse(isPermittedAdminIp("invalid_not_an_ip", allowedIpsOverride = null))

        // 3. Explicit whitelist via ASTER_ADMIN_ALLOWED_IPS overrides
        assertTrue(isPermittedAdminIp("10.0.0.5", allowedIpsOverride = "10.0.0.5, 10.0.0.6"))
        assertFalse(isPermittedAdminIp("10.0.0.7", allowedIpsOverride = "10.0.0.5, 10.0.0.6"))
        assertTrue(isPermittedAdminIp("any_ip_allowed", allowedIpsOverride = "*"))
    }
}
