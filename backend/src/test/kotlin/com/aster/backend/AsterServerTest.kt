package com.aster.backend

import com.aster.backend.protocol.ClientMessage
import com.aster.backend.protocol.ServerMessage
import com.aster.backend.protocol.asterJson
import com.aster.backend.session.SessionManager
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import org.junit.jupiter.api.Test
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
        application { asterModule(sessionManager) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth("user_saad"))
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
        application { asterModule(sessionManager) }
        val client = createClient { install(WebSockets) }

        // Connect recipient (Alex)
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth("user_alex"))
            val alexAuth = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(alexAuth)

            // Connect sender (Saad)
            client.webSocket("/ws") {
                outgoing.sendMsg(ClientMessage.Auth("user_saad"))
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
        application { asterModule() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth("user_saad"))
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
        application { asterModule(sessionManager) }
        val client = createClient { install(WebSockets) }

        // Recipient Alex connected
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth("user_alex"))
            incoming.receiveServerMsg()

            client.webSocket("/ws") {
                outgoing.sendMsg(ClientMessage.Auth("user_saad"))
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
        application { asterModule(sessionManager) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth("user_saad"))
            val auth = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(auth)
            assertTrue(sessionManager.isOnline("user_saad"))
        }

        // After webSocket block finishes, connection is closed
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
        application { asterModule() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth("user_saad"))
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
        application { asterModule() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth("user_saad"))
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
        application { asterModule() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth("user_saad"))
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
        application { asterModule(sessionManager) }
        val client = createClient { install(WebSockets) }

        // Connection 1 connects as user_saad
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth("user_saad"))
            val auth1 = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthOk>(auth1)
            assertEquals(1, sessionManager.activeConnectionCount())

            // Connection 2 connects as user_saad (same user)
            client.webSocket("/ws") {
                outgoing.sendMsg(ClientMessage.Auth("user_saad"))
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
        application { asterModule(sessionManager) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth("user_alex"))
            incoming.receiveServerMsg()

            client.webSocket("/ws") {
                outgoing.sendMsg(ClientMessage.Auth("user_saad"))
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
}
