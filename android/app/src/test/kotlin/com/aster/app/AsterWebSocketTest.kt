package com.aster.app

import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ClientMessage
import com.aster.app.data.network.ConnectionState
import com.aster.app.data.network.NetworkConfig
import com.aster.app.data.network.ServerMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AsterWebSocketTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        okHttpClient = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testClientMessageAuthSerialization() {
        val auth = ClientMessage.Auth(token = "ast_tok_test_123", userId = "user_saad")
        val json = JSONObject(auth.toJson())
        assertEquals("AUTH", json.getString("type"))
        assertEquals("ast_tok_test_123", json.getString("token"))
        assertEquals("user_saad", json.getString("userId"))
    }

    @Test
    fun testClientMessageSendMessageSerialization() {
        val msg = ClientMessage.SendMessage(
            messageId = "msg_123",
            conversationId = "conv_alex",
            recipientId = "user_alex",
            body = "Hello Alex!",
            timestamp = 1700000000000L
        )
        val json = JSONObject(msg.toJson())
        assertEquals("SEND_MESSAGE", json.getString("type"))
        assertEquals("msg_123", json.getString("messageId"))
        assertEquals("conv_alex", json.getString("conversationId"))
        assertEquals("user_alex", json.getString("recipientId"))
        assertEquals("Hello Alex!", json.getString("body"))
        assertEquals(1700000000000L, json.getLong("timestamp"))
    }

    @Test
    fun testClientMessageAckReceivedSerialization() {
        val ack = ClientMessage.AckReceived(messageId = "msg_123")
        val json = JSONObject(ack.toJson())
        assertEquals("ACK_RECEIVED", json.getString("type"))
        assertEquals("msg_123", json.getString("messageId"))
    }

    @Test
    fun testClientMessagePingSerialization() {
        val ping = ClientMessage.Ping(timestamp = 1700000000000L)
        val json = JSONObject(ping.toJson())
        assertEquals("PING", json.getString("type"))
        assertEquals(1700000000000L, json.getLong("timestamp"))
    }

    @Test
    fun testServerMessageParsing() {
        // AuthOk
        val authOk = ServerMessage.fromJson("""{"type":"AUTH_OK","userId":"user_saad"}""")
        assertTrue(authOk is ServerMessage.AuthOk)
        assertEquals("user_saad", (authOk as ServerMessage.AuthOk).userId)

        // AuthError
        val authErr = ServerMessage.fromJson("""{"type":"AUTH_ERROR","reason":"Invalid user"}""")
        assertTrue(authErr is ServerMessage.AuthError)
        assertEquals("Invalid user", (authErr as ServerMessage.AuthError).reason)

        // MessageAccepted
        val ack = ServerMessage.fromJson("""{"type":"MESSAGE_ACCEPTED","messageId":"msg_1","timestamp":1700000000}""")
        assertTrue(ack is ServerMessage.MessageAccepted)
        assertEquals("msg_1", (ack as ServerMessage.MessageAccepted).messageId)
        assertEquals(1700000000L, ack.timestamp)

        // RelayMessage
        val relay = ServerMessage.fromJson(
            """{"type":"MESSAGE","messageId":"msg_2","conversationId":"conv_1","senderId":"user_alex","body":"Hi Saad","timestamp":1700000001}"""
        )
        assertTrue(relay is ServerMessage.RelayMessage)
        assertEquals("msg_2", (relay as ServerMessage.RelayMessage).messageId)
        assertEquals("conv_1", relay.conversationId)
        assertEquals("user_alex", relay.senderId)
        assertEquals("Hi Saad", relay.body)
        assertEquals(1700000001L, relay.timestamp)

        // Error
        val err = ServerMessage.fromJson("""{"type":"ERROR","code":"INVALID_MESSAGE","message":"Body is empty"}""")
        assertTrue(err is ServerMessage.Error)
        assertEquals("INVALID_MESSAGE", (err as ServerMessage.Error).code)
        assertEquals("Body is empty", err.message)

        // Unknown type
        val unknown = ServerMessage.fromJson("""{"type":"UNKNOWN_TYPE"}""")
        assertNull(unknown)

        // Malformed JSON
        val malformed = ServerMessage.fromJson("""not a json""")
        assertNull(malformed)
    }

    @Test
    fun testWebSocketClientInitialStateIsDisconnected() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val authStorage = com.aster.app.data.auth.AsterAuthStorage(context)
        val client = AsterWebSocketClient(okHttpClient, authStorage)
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
    }

    @Test
    fun testServerMessageParsingMissingRequiredFieldsReturnsNull() {
        // Missing messageId in MESSAGE_ACCEPTED
        assertNull(ServerMessage.fromJson("""{"type":"MESSAGE_ACCEPTED"}"""))
        assertNull(ServerMessage.fromJson("""{"type":"MESSAGE_ACCEPTED","messageId":""}"""))

        // Missing fields in MESSAGE
        assertNull(ServerMessage.fromJson("""{"type":"MESSAGE","messageId":"m1"}"""))
        assertNull(ServerMessage.fromJson("""{"type":"MESSAGE","messageId":"m1","conversationId":"c1"}"""))
        assertNull(ServerMessage.fromJson("""{"type":"MESSAGE","messageId":"m1","conversationId":"c1","senderId":""}"""))

        // Missing userId in AUTH_OK
        assertNull(ServerMessage.fromJson("""{"type":"AUTH_OK"}"""))
        assertNull(ServerMessage.fromJson("""{"type":"AUTH_OK","userId":""}"""))
    }
}
