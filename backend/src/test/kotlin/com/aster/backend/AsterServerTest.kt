package com.aster.backend

import com.aster.backend.database.MailboxDatabase
import com.aster.backend.protocol.ClientMessage
import com.aster.backend.protocol.ServerMessage
import com.aster.backend.protocol.asterJson
import com.aster.backend.session.SessionManager
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
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
import com.aster.backend.relay.MessageRelay
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AsterServerTest {

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
    fun testAuthenticationSuccess() = testApplication {
        val sessionManager = SessionManager()
        val mailboxDb = MailboxDatabase(":memory:")
        val saadToken = mailboxDb.createToken("user_saad", "device_saad")
        application { asterModule(sessionManager, mailboxDb) }
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
    fun testAuthenticationInvalidUserIdRejected() = testApplication {
        application { asterModule() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth("invalid-user-format"))
            val response = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthError>(response)
        }
    }

    @Test
    fun testUnauthenticatedMessageRejected() = testApplication {
        application { asterModule() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(
                ClientMessage.SendMessage(
                    messageId = "m1",
                    conversationId = "conv_alex",
                    recipientId = "user_alex",
                    body = "hello",
                    timestamp = 1000L
                )
            )
            val response = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(response)
            assertEquals("UNAUTHENTICATED", response.code)
        }
    }

    @Test
    fun testMalformedJsonRejected() = testApplication {
        application { asterModule() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.send(Frame.Text("this is not json"))
            val response = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(response)
            assertEquals("MALFORMED_JSON", response.code)
        }
    }

    @Test
    fun testSendMessageAcknowledgedAndRelayed() = testApplication {
        val sessionManager = SessionManager()
        val mailboxDb = MailboxDatabase(":memory:")
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")
        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        // Connect recipient (Alex)
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            val alexAuth = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(alexAuth)

            // Connect sender (Saad)
            client.webSocket("/ws") {
                outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                val saadAuth = incoming.receiveServerMsg()
                assertIs<ServerMessage.AuthOk>(saadAuth)

                // Saad sends message
                outgoing.sendMsg(
                    ClientMessage.SendMessage(
                        messageId = "msg_test_100",
                        conversationId = "conv_alex",
                        recipientId = "user_alex",
                        body = "Hello Alex",
                        timestamp = 123456789L
                    )
                )

                // Sender receives MESSAGE_ACCEPTED with preserved messageId
                val ack = incoming.receiveServerMsg()
                assertIs<ServerMessage.MessageAccepted>(ack)
                assertEquals("msg_test_100", ack.messageId)
            }

            // Alex receives relayed MESSAGE with preserved messageId and senderId
            val relay = incoming.receiveServerMsg()
            assertIs<ServerMessage.RelayMessage>(relay)
            assertEquals("msg_test_100", relay.messageId)
            assertEquals("conv_alex", relay.conversationId)
            assertEquals("user_saad", relay.senderId)
            assertEquals("Hello Alex", relay.body)
            assertEquals(123456789L, relay.timestamp)
        }
    }

    @Test
    fun testSendMessageToOfflineRecipient() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        application { asterModule(mailboxDatabase = mailboxDb) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            val auth = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(auth)

            // Recipient user_offline is not connected
            outgoing.sendMsg(
                ClientMessage.SendMessage(
                    messageId = "msg_offline_1",
                    conversationId = "conv_offline",
                    recipientId = "user_offline",
                    body = "Are you there?",
                    timestamp = 5555L
                )
            )

            // Sender still receives MESSAGE_ACCEPTED without server crash
            val ack = incoming.receiveServerMsg()
            assertIs<ServerMessage.MessageAccepted>(ack)
            assertEquals("msg_offline_1", ack.messageId)
        }
    }

    @Test
    fun testDuplicateMessageHandledIdempotently() = testApplication {
        val sessionManager = SessionManager()
        val mailboxDb = MailboxDatabase(":memory:")
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")
        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        // Recipient Alex connected
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            incoming.receiveServerMsg()

            client.webSocket("/ws") {
                outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                incoming.receiveServerMsg()

                val sendMsg = ClientMessage.SendMessage(
                    messageId = "msg_dup_1",
                    conversationId = "conv_alex",
                    recipientId = "user_alex",
                    body = "First transmission",
                    timestamp = 1000L
                )

                // Send first time
                outgoing.sendMsg(sendMsg)
                val ack1 = incoming.receiveServerMsg()
                assertIs<ServerMessage.MessageAccepted>(ack1)

                // Send second time (duplicate)
                outgoing.sendMsg(sendMsg)
                val ack2 = incoming.receiveServerMsg()
                assertIs<ServerMessage.MessageAccepted>(ack2)
                assertEquals("msg_dup_1", ack2.messageId)
            }

            // Alex should receive the message once
            val relayed = incoming.receiveServerMsg()
            assertIs<ServerMessage.RelayMessage>(relayed)
            assertEquals("msg_dup_1", relayed.messageId)
        }
    }

    @Test
    fun testPingPong() = testApplication {
        application { asterModule() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Ping(1234L))
            val pong = incoming.receiveServerMsg()
            assertIs<ServerMessage.Pong>(pong)
        }
    }

    @Test
    fun testCleanDisconnectRemovesSession() = testApplication {
        val sessionManager = SessionManager()
        val mailboxDb = MailboxDatabase(":memory:")
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            val auth = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(auth)
            assertTrue(sessionManager.isOnline("user_saad"))
        }

        // After webSocket block finishes, connection is closed
        for (i in 1..20) {
            if (sessionManager.activeConnectionCount() == 0) break
            Thread.sleep(50)
        }
        assertEquals(0, sessionManager.activeConnectionCount())
        assertEquals(false, sessionManager.isOnline("user_saad"))
    }

    @Test
    fun testMissingTypeRejected() = testApplication {
        application { asterModule() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.send(Frame.Text("""{"userId":"user_saad"}"""))
            val response = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(response)
            assertEquals("MISSING_TYPE", response.code)
        }
    }

    @Test
    fun testUnknownTypeRejected() = testApplication {
        application { asterModule() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.send(Frame.Text("""{"type":"UNKNOWN_OP","userId":"user_saad"}"""))
            val response = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(response)
            assertEquals("UNKNOWN_TYPE", response.code)
        }
    }

    @Test
    fun testEmptyAndOversizedBodyRejected() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        application { asterModule(mailboxDatabase = mailboxDb) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            incoming.receiveServerMsg()

            // 1. Empty body
            outgoing.sendMsg(
                ClientMessage.SendMessage(
                    messageId = "m_empty",
                    conversationId = "conv_alex",
                    recipientId = "user_alex",
                    body = "   ",
                    timestamp = 1000L
                )
            )
            val err1 = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(err1)
            assertEquals("INVALID_BODY", err1.code)

            // 2. Oversized body (> 4096)
            val hugeBody = "a".repeat(4097)
            outgoing.sendMsg(
                ClientMessage.SendMessage(
                    messageId = "m_huge",
                    conversationId = "conv_alex",
                    recipientId = "user_alex",
                    body = hugeBody,
                    timestamp = 1000L
                )
            )
            val err2 = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(err2)
            assertEquals("INVALID_BODY", err2.code)
        }
    }

    @Test
    fun testSelfMessagingRejected() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        application { asterModule(mailboxDatabase = mailboxDb) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            incoming.receiveServerMsg()

            outgoing.sendMsg(
                ClientMessage.SendMessage(
                    messageId = "m_self",
                    conversationId = "conv_saad",
                    recipientId = "user_saad",
                    body = "hello myself",
                    timestamp = 1000L
                )
            )
            val err = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(err)
            assertEquals("SELF_MESSAGE_NOT_ALLOWED", err.code)
        }
    }

    @Test
    fun testInvalidTimestampRejected() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        application { asterModule(mailboxDatabase = mailboxDb) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            incoming.receiveServerMsg()

            outgoing.sendMsg(
                ClientMessage.SendMessage(
                    messageId = "m_time",
                    conversationId = "conv_alex",
                    recipientId = "user_alex",
                    body = "hello",
                    timestamp = 0L
                )
            )
            val err = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(err)
            assertEquals("INVALID_TIMESTAMP", err.code)
        }
    }

    @Test
    fun testSingleSessionReplacementPolicy() = testApplication {
        val sessionManager = SessionManager()
        val mailboxDb = MailboxDatabase(":memory:")
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        // Connection 1 connects as user_saad
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            val auth1 = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(auth1)
            assertEquals(1, sessionManager.activeConnectionCount())

            // Connection 2 connects as user_saad (same user)
            client.webSocket("/ws") {
                outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                val auth2 = incoming.receiveServerMsg()
                assertIs<ServerMessage.AuthOk>(auth2)

                // The active connection count for user_saad remains exactly 1
                assertEquals(1, sessionManager.activeConnectionCount())
                assertEquals(1, sessionManager.activeUserCount())
                assertTrue(sessionManager.isOnline("user_saad"))
            }
        }
    }

    @Test
    fun testRapidMessagesRelayedInOrder() = testApplication {
        val sessionManager = SessionManager()
        val mailboxDb = MailboxDatabase(":memory:")
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")
        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            incoming.receiveServerMsg()

            client.webSocket("/ws") {
                outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                incoming.receiveServerMsg()

                // Rapidly send 5 messages: A, B, C, D, E
                for (i in 1..5) {
                    outgoing.sendMsg(
                        ClientMessage.SendMessage(
                            messageId = "rapid_$i",
                            conversationId = "conv_alex",
                            recipientId = "user_alex",
                            body = "Message $i",
                            timestamp = 1000L + i
                        )
                    )
                    val ack = incoming.receiveServerMsg()
                    assertIs<ServerMessage.MessageAccepted>(ack)
                    assertEquals("rapid_$i", ack.messageId)
                }
            }

            // Alex receives all 5 in the exact order: 1, 2, 3, 4, 5
            for (i in 1..5) {
                val relay = incoming.receiveServerMsg()
                assertIs<ServerMessage.RelayMessage>(relay)
                assertEquals("rapid_$i", relay.messageId)
                assertEquals("Message $i", relay.body)
            }
        }
    }

    @Test
    fun testOfflineMessageDeliveredOnReconnectAndAcked() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")
        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        // 1. Saad connects and sends message to offline Alex
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            val saadAuth = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(saadAuth)

            outgoing.sendMsg(
                ClientMessage.SendMessage(
                    messageId = "msg_offline_100",
                    conversationId = "conv_alex",
                    recipientId = "user_alex",
                    body = "Saved in mailbox for Alex",
                    timestamp = 5000L
                )
            )
            val ack = incoming.receiveServerMsg()
            assertIs<ServerMessage.MessageAccepted>(ack)
            assertEquals("msg_offline_100", ack.messageId)
        }

        // Verify message is stored in SQLite
        assertTrue(mailboxDb.hasMessage("msg_offline_100"))

        // 2. Alex connects later
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            val alexAuth = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(alexAuth)

            // Alex receives the queued message immediately after AUTH_OK
            val relay = incoming.receiveServerMsg()
            assertIs<ServerMessage.RelayMessage>(relay)
            assertEquals("msg_offline_100", relay.messageId)
            assertEquals("Saved in mailbox for Alex", relay.body)
            assertEquals("user_saad", relay.senderId)

            // 3. Alex sends ACK_RECEIVED
            outgoing.sendMsg(ClientMessage.AckReceived("msg_offline_100"))
        }

        // 4. Alex reconnects again - message is now ACKED so it should NOT be redelivered
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            val alexAuth2 = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(alexAuth2)

            // Send ping to verify no unexpected messages are pending in the channel
            outgoing.sendMsg(ClientMessage.Ping(9999L))
            val pong = incoming.receiveServerMsg()
            assertIs<ServerMessage.Pong>(pong)
        }
    }

    @Test
    fun testServerCrashAndRestartOfflineDelivery() {
        val tempFile = File.createTempFile("aster_server_crash_test_", ".db")
        try {
            var saadToken = ""
            var alexToken = ""

            // Server instance 1: Saad sends message to offline Alex, then server stops
            testApplication {
                val db1 = MailboxDatabase(tempFile.absolutePath)
                saadToken = db1.createToken("user_saad", "dev_saad")
                alexToken = db1.createToken("user_alex", "dev_alex")
                val sm1 = SessionManager()
                application { asterModule(sm1, db1) }
                val client = createClient { install(WebSockets) }

                client.webSocket("/ws") {
                    outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                    assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                    outgoing.sendMsg(
                        ClientMessage.SendMessage(
                            messageId = "msg_survive_crash",
                            conversationId = "conv_alex",
                            recipientId = "user_alex",
                            body = "Survives crash and reboot",
                            timestamp = 7777L
                        )
                    )
                    val ack = incoming.receiveServerMsg()
                    assertIs<ServerMessage.MessageAccepted>(ack)
                }
                db1.close()
            }

            // Server instance 2: Restart server with same SQLite file on disk
            testApplication {
                val db2 = MailboxDatabase(tempFile.absolutePath)
                val sm2 = SessionManager()
                application { asterModule(sm2, db2) }
                val client = createClient { install(WebSockets) }

                // Alex connects to newly restarted server using token stored in SQLite
                client.webSocket("/ws") {
                    outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
                    assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                    // Queued message is delivered from SQLite mailbox!
                    val relay = incoming.receiveServerMsg()
                    assertIs<ServerMessage.RelayMessage>(relay)
                    assertEquals("msg_survive_crash", relay.messageId)
                    assertEquals("Survives crash and reboot", relay.body)

                    outgoing.sendMsg(ClientMessage.AckReceived("msg_survive_crash"))
                }
                db2.close()
            }
        } finally {
            tempFile.delete()
            File("${tempFile.absolutePath}-wal").delete()
            File("${tempFile.absolutePath}-shm").delete()
        }
    }

    @Test
    fun testMultipleQueuedMessagesDeliveredInDeterministicOrderOnReconnect() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")
        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        // Saad queues 3 messages while Alex is offline
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            for (i in 1..3) {
                outgoing.sendMsg(
                    ClientMessage.SendMessage(
                        messageId = "queue_ord_$i",
                        conversationId = "conv_alex",
                        recipientId = "user_alex",
                        body = "Queued message $i",
                        timestamp = 1000L + i
                    )
                )
                val ack = incoming.receiveServerMsg()
                assertIs<ServerMessage.MessageAccepted>(ack)
            }
        }

        // Alex connects and receives all 3 in deterministic order
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            for (i in 1..3) {
                val relay = incoming.receiveServerMsg()
                assertIs<ServerMessage.RelayMessage>(relay)
                assertEquals("queue_ord_$i", relay.messageId)
                assertEquals("Queued message $i", relay.body)
                outgoing.sendMsg(ClientMessage.AckReceived("queue_ord_$i"))
            }
        }
    }

    @Test
    fun testDuplicateSendMessageIdempotencyAcrossRestart() {
        val tempFile = File.createTempFile("aster_dup_restart_test_", ".db")
        try {
            var saadToken = ""
            var alexToken = ""

            // First server session: Saad sends message
            testApplication {
                val db1 = MailboxDatabase(tempFile.absolutePath)
                saadToken = db1.createToken("user_saad", "dev_saad")
                alexToken = db1.createToken("user_alex", "dev_alex")
                val sm1 = SessionManager()
                application { asterModule(sm1, db1) }
                val client = createClient { install(WebSockets) }

                client.webSocket("/ws") {
                    outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                    assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                    outgoing.sendMsg(
                        ClientMessage.SendMessage(
                            messageId = "msg_dup_restart",
                            conversationId = "conv_alex",
                            recipientId = "user_alex",
                            body = "Idempotent body",
                            timestamp = 1000L
                        )
                    )
                    assertIs<ServerMessage.MessageAccepted>(incoming.receiveServerMsg())
                }
                db1.close()
            }

            // Second server session: Saad retries the same messageId after server restart
            testApplication {
                val db2 = MailboxDatabase(tempFile.absolutePath)
                val sm2 = SessionManager()
                application { asterModule(sm2, db2) }
                val client = createClient { install(WebSockets) }

                client.webSocket("/ws") {
                    outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                    assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                    // Duplicate send: server should accept idempotently
                    outgoing.sendMsg(
                        ClientMessage.SendMessage(
                            messageId = "msg_dup_restart",
                            conversationId = "conv_alex",
                            recipientId = "user_alex",
                            body = "Idempotent body",
                            timestamp = 1000L
                        )
                    )
                    val ack = incoming.receiveServerMsg()
                    assertIs<ServerMessage.MessageAccepted>(ack)
                    assertEquals("msg_dup_restart", ack.messageId)
                }

                // Verify Alex connects and receives it only once
                client.webSocket("/ws") {
                    outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
                    assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                    val relay = incoming.receiveServerMsg()
                    assertIs<ServerMessage.RelayMessage>(relay)
                    assertEquals("msg_dup_restart", relay.messageId)
                    outgoing.sendMsg(ClientMessage.AckReceived("msg_dup_restart"))

                    // Ensure no second duplicate message follows
                    outgoing.sendMsg(ClientMessage.Ping())
                    assertIs<ServerMessage.Pong>(incoming.receiveServerMsg())
                }
                db2.close()
            }
        } finally {
            tempFile.delete()
            File("${tempFile.absolutePath}-wal").delete()
            File("${tempFile.absolutePath}-shm").delete()
        }
    }

    @Test
    fun testPushTokenRegistrationAndOfflineDispatch() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val dispatchedPushes = mutableListOf<com.aster.backend.push.PushPayload>()
        val mockPushGateway = object : com.aster.backend.push.PushGateway {
            override suspend fun sendPushNotification(payload: com.aster.backend.push.PushPayload): Boolean {
                dispatchedPushes.add(payload)
                return true
            }
        }
        val relay = MessageRelay(sessionManager, mailboxDb, mockPushGateway)
        application { asterModule(sessionManager, mailboxDb, relay) }

        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")

        val httpClient = createClient { }

        // 1. Alex registers push token via POST /api/push/register
        val regResp = httpClient.post("/api/push/register") {
            header("Authorization", "Bearer $alexToken")
            contentType(ContentType.Application.Json)
            setBody("""{"pushToken":"fcm_token_alex_xyz","deviceId":"dev_alex"}""")
        }
        assertEquals(HttpStatusCode.OK, regResp.status)

        // 2. Unauthenticated registration is rejected with 401
        val unauthResp = httpClient.post("/api/push/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"pushToken":"fcm_token_bad"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthResp.status)

        // 3. Saad connects and sends message to offline recipient Alex
        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            outgoing.sendMsg(
                ClientMessage.SendMessage(
                    messageId = "msg_push_1",
                    conversationId = "conv_alex",
                    recipientId = "user_alex",
                    body = "Hey Alex, wake up!",
                    timestamp = 1000L
                )
            )
            assertIs<ServerMessage.MessageAccepted>(incoming.receiveServerMsg())
        }

        // 4. Verify push gateway was dispatched for Alex
        for (i in 1..20) {
            if (dispatchedPushes.isNotEmpty()) break
            Thread.sleep(50)
        }
        assertEquals(1, dispatchedPushes.size)
        val push = dispatchedPushes[0]
        assertEquals("user_alex", push.recipientUserId)
        assertEquals("fcm_token_alex_xyz", push.pushToken)
        assertEquals("user_saad", push.senderUserId)
        assertEquals("msg_push_1", push.messageId)
        assertEquals(false, push.isGroup)
    }

    @Test
    fun testOpenRegistrationAndAsterIdValidation() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        application { asterModule(sessionManager, mailboxDb) }
        val httpClient = createClient { }

        // 1. Successful registration
        val regResp = httpClient.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"maya","displayName":"Maya Lin"}""")
        }
        assertEquals(HttpStatusCode.Created, regResp.status)
        val body = regResp.bodyAsText()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        val userId = json["userId"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        val asterId = json["asterId"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        val token = json["token"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }

        assertTrue(userId != null && userId.startsWith("user_"))
        assertTrue(asterId != null && com.aster.backend.protocol.ProtocolLimits.ASTER_ID_REGEX.matches(asterId))
        assertTrue(!token.isNullOrBlank())

        // 2. Duplicate username returns 409 Conflict
        val dupResp = httpClient.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"MAYA","displayName":"Maya Clone"}""")
        }
        assertEquals(HttpStatusCode.Conflict, dupResp.status)

        // 3. Invalid username format returns 400 Bad Request
        val invalidResp = httpClient.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"ab","displayName":"Too Short"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidResp.status)
    }

    @Test
    fun testUserProfileEndpoints() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        application { asterModule(sessionManager, mailboxDb) }
        val httpClient = createClient { }

        // Register user
        val regResp = httpClient.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"rachel","displayName":"Rachel"}""")
        }
        val regJson = kotlinx.serialization.json.Json.parseToJsonElement(regResp.bodyAsText()) as kotlinx.serialization.json.JsonObject
        val token = (regJson["token"] as kotlinx.serialization.json.JsonPrimitive).content

        // GET /api/profile
        val getProfileResp = httpClient.get("/api/profile") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, getProfileResp.status)
        val profileJson = kotlinx.serialization.json.Json.parseToJsonElement(getProfileResp.bodyAsText()) as kotlinx.serialization.json.JsonObject
        assertEquals("rachel", (profileJson["username"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("Rachel", (profileJson["displayName"] as kotlinx.serialization.json.JsonPrimitive).content)

        // POST /api/profile (update display name)
        val updateResp = httpClient.post("/api/profile") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Rachel Carson"}""")
        }
        assertEquals(HttpStatusCode.OK, updateResp.status)
        val updatedJson = kotlinx.serialization.json.Json.parseToJsonElement(updateResp.bodyAsText()) as kotlinx.serialization.json.JsonObject
        assertEquals("Rachel Carson", (updatedJson["displayName"] as kotlinx.serialization.json.JsonPrimitive).content)

        // Unauthorized access returns 401
        val unauthResp = httpClient.get("/api/profile")
        assertEquals(HttpStatusCode.Unauthorized, unauthResp.status)
    }

    @Test
    fun testUserSearchAndFriendRequestFlow() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        application { asterModule(sessionManager, mailboxDb) }
        val httpClient = createClient { }

        // Register Alice
        val aliceResp = httpClient.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","displayName":"Alice Springs"}""")
        }
        val aliceJson = kotlinx.serialization.json.Json.parseToJsonElement(aliceResp.bodyAsText()) as kotlinx.serialization.json.JsonObject
        val aliceToken = (aliceJson["token"] as kotlinx.serialization.json.JsonPrimitive).content

        // Register Bob
        val bobResp = httpClient.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"bob","displayName":"Bob Builder"}""")
        }
        val bobJson = kotlinx.serialization.json.Json.parseToJsonElement(bobResp.bodyAsText()) as kotlinx.serialization.json.JsonObject
        val bobUserId = (bobJson["userId"] as kotlinx.serialization.json.JsonPrimitive).content
        val bobAsterId = (bobJson["asterId"] as kotlinx.serialization.json.JsonPrimitive).content
        val bobToken = (bobJson["token"] as kotlinx.serialization.json.JsonPrimitive).content

        // Alice searches for Bob by username prefix
        val searchUsernameResp = httpClient.get("/api/users/search?q=bob") {
            header("Authorization", "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, searchUsernameResp.status)
        assertTrue(searchUsernameResp.bodyAsText().contains("Bob Builder"))

        // Alice searches for Bob by Aster ID
        val searchAsterIdResp = httpClient.get("/api/users/search?q=$bobAsterId") {
            header("Authorization", "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, searchAsterIdResp.status)
        assertTrue(searchAsterIdResp.bodyAsText().contains("bob"))

        // Alice sends friend request to Bob
        val reqResp = httpClient.post("/api/friends/request") {
            header("Authorization", "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":"$bobUserId"}""")
        }
        assertEquals(HttpStatusCode.Created, reqResp.status)
        val reqJson = kotlinx.serialization.json.Json.parseToJsonElement(reqResp.bodyAsText()) as kotlinx.serialization.json.JsonObject
        val requestId = (reqJson["id"] as kotlinx.serialization.json.JsonPrimitive).content

        // Bob checks incoming requests
        val bobRequestsResp = httpClient.get("/api/friends/requests") {
            header("Authorization", "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, bobRequestsResp.status)
        assertTrue(bobRequestsResp.bodyAsText().contains(requestId))

        // Bob accepts friend request
        val respondResp = httpClient.post("/api/friends/respond") {
            header("Authorization", "Bearer $bobToken")
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"$requestId","action":"ACCEPT"}""")
        }
        assertEquals(HttpStatusCode.OK, respondResp.status)

        // Both Alice and Bob verify friendship
        val aliceFriendsResp = httpClient.get("/api/friends") {
            header("Authorization", "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, aliceFriendsResp.status)
        assertTrue(aliceFriendsResp.bodyAsText().contains("Bob Builder"))

        val bobFriendsResp = httpClient.get("/api/friends") {
            header("Authorization", "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, bobFriendsResp.status)
        assertTrue(bobFriendsResp.bodyAsText().contains("Alice Springs"))
    }

    @Test
    fun testBlockingEnforcementInMessagingAndSearch() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val relay = MessageRelay(sessionManager, mailboxDb)
        application { asterModule(sessionManager, mailboxDb, relay) }
        val httpClient = createClient { }

        // Register Charlie and David
        val charlieResp = httpClient.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"charlie","displayName":"Charlie"}""")
        }
        val charlieJson = kotlinx.serialization.json.Json.parseToJsonElement(charlieResp.bodyAsText()) as kotlinx.serialization.json.JsonObject
        val charlieUserId = (charlieJson["userId"] as kotlinx.serialization.json.JsonPrimitive).content
        val charlieToken = (charlieJson["token"] as kotlinx.serialization.json.JsonPrimitive).content

        val davidResp = httpClient.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"david","displayName":"David"}""")
        }
        val davidJson = kotlinx.serialization.json.Json.parseToJsonElement(davidResp.bodyAsText()) as kotlinx.serialization.json.JsonObject
        val davidUserId = (davidJson["userId"] as kotlinx.serialization.json.JsonPrimitive).content
        val davidToken = (davidJson["token"] as kotlinx.serialization.json.JsonPrimitive).content

        // David blocks Charlie
        val blockResp = httpClient.post("/api/friends/block") {
            header("Authorization", "Bearer $davidToken")
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":"$charlieUserId"}""")
        }
        assertEquals(HttpStatusCode.OK, blockResp.status)

        // Charlie searches for David -> filtered out
        val searchResp = httpClient.get("/api/users/search?q=david") {
            header("Authorization", "Bearer $charlieToken")
        }
        assertEquals(HttpStatusCode.OK, searchResp.status)
        assertTrue(!searchResp.bodyAsText().contains("David"))

        // Charlie tries to send direct message to David over WebSocket -> BLOCKED error
        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = charlieToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            outgoing.sendMsg(
                ClientMessage.SendMessage(
                    messageId = "blocked_msg_1",
                    conversationId = "conv_charlie_david",
                    recipientId = davidUserId,
                    body = "Are you there?",
                    timestamp = 1000L
                )
            )
            val response = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(response)
            assertEquals("BLOCKED", response.code)
        }

        // David unblocks Charlie
        val unblockResp = httpClient.post("/api/friends/unblock") {
            header("Authorization", "Bearer $davidToken")
            contentType(ContentType.Application.Json)
            setBody("""{"targetUserId":"$charlieUserId"}""")
        }
        assertEquals(HttpStatusCode.OK, unblockResp.status)

        // Charlie searches for David -> visible again
        val searchAfterUnblock = httpClient.get("/api/users/search?q=david") {
            header("Authorization", "Bearer $charlieToken")
        }
        assertEquals(HttpStatusCode.OK, searchAfterUnblock.status)
        assertTrue(searchAfterUnblock.bodyAsText().contains("David"))
    }

    @Test
    fun testLogoutAndTokenRevocation() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        application { asterModule(sessionManager, mailboxDb) }
        val httpClient = createClient { }

        val regResp = httpClient.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"kevin","displayName":"Kevin"}""")
        }
        val regJson = kotlinx.serialization.json.Json.parseToJsonElement(regResp.bodyAsText()) as kotlinx.serialization.json.JsonObject
        val token = (regJson["token"] as kotlinx.serialization.json.JsonPrimitive).content

        // Profile works before logout
        assertEquals(HttpStatusCode.OK, httpClient.get("/api/profile") { header("Authorization", "Bearer $token") }.status)

        // Call POST /api/logout
        val logoutResp = httpClient.post("/api/logout") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, logoutResp.status)

        // Profile fails with 401 after logout
        assertEquals(HttpStatusCode.Unauthorized, httpClient.get("/api/profile") { header("Authorization", "Bearer $token") }.status)

        // WebSocket auth fails with AuthError
        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = token))
            val resp = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthError>(resp)
        }
    }
}
