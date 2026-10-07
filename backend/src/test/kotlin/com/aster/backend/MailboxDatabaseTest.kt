package com.aster.backend

import com.aster.backend.database.DeliveryState
import com.aster.backend.database.MailboxDatabase
import com.aster.backend.database.MailboxMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class MailboxDatabaseTest {

    @Test
    fun testInitializationAndSchemaCreation() = runBlocking {
        val db = MailboxDatabase(":memory:")
        assertEquals(0L, db.getMessageCount())
        assertFalse(db.hasMessage("non_existent"))
        db.close()
    }

    @Test
    fun testInsertionAndRetrieval() = runBlocking {
        val db = MailboxDatabase(":memory:")
        val msg = MailboxMessage(
            messageId = "msg_001",
            conversationId = "conv_1",
            senderUserId = "user_saad",
            recipientUserId = "user_alex",
            body = "Hello Alex",
            clientTimestamp = 1000L,
            serverReceivedTimestamp = 1050L,
            deliveryState = DeliveryState.QUEUED
        )

        val inserted = db.enqueueMessage(msg)
        assertTrue(inserted)
        assertTrue(db.hasMessage("msg_001"))
        assertEquals(1L, db.getMessageCount())

        val fetched = db.getMessage("msg_001")
        assertNotNull(fetched)
        assertEquals("msg_001", fetched.messageId)
        assertEquals("conv_1", fetched.conversationId)
        assertEquals("user_saad", fetched.senderUserId)
        assertEquals("user_alex", fetched.recipientUserId)
        assertEquals("Hello Alex", fetched.body)
        assertEquals(1000L, fetched.clientTimestamp)
        assertEquals(1050L, fetched.serverReceivedTimestamp)
        assertEquals(DeliveryState.QUEUED, fetched.deliveryState)

        db.close()
    }

    @Test
    fun testDuplicateMessageIdIgnored() = runBlocking {
        val db = MailboxDatabase(":memory:")
        val msg = MailboxMessage(
            messageId = "msg_dup",
            conversationId = "conv_1",
            senderUserId = "user_saad",
            recipientUserId = "user_alex",
            body = "Original message",
            clientTimestamp = 1000L,
            serverReceivedTimestamp = 1050L,
            deliveryState = DeliveryState.QUEUED
        )

        assertTrue(db.enqueueMessage(msg))

        // Attempt duplicate insertion with same messageId
        val msgDup = msg.copy(body = "Different body attempted")
        assertFalse(db.enqueueMessage(msgDup))

        // Total count remains 1 and original body is preserved
        assertEquals(1L, db.getMessageCount())
        assertEquals("Original message", db.getMessage("msg_dup")?.body)

        db.close()
    }

    @Test
    fun testRestartPersistence() = runBlocking {
        val tempFile = File.createTempFile("aster_test_restart_", ".db")
        try {
            val db1 = MailboxDatabase(tempFile.absolutePath)
            db1.enqueueMessage(
                MailboxMessage(
                    messageId = "persist_1",
                    conversationId = "conv_1",
                    senderUserId = "user_saad",
                    recipientUserId = "user_alex",
                    body = "Durable text",
                    clientTimestamp = 2000L,
                    serverReceivedTimestamp = 2050L,
                    deliveryState = DeliveryState.QUEUED
                )
            )
            db1.close()

            // Re-open from same file on disk to simulate restart
            val db2 = MailboxDatabase(tempFile.absolutePath)
            assertTrue(db2.hasMessage("persist_1"))
            val restored = db2.getMessage("persist_1")
            assertNotNull(restored)
            assertEquals("Durable text", restored.body)
            assertEquals(DeliveryState.QUEUED, restored.deliveryState)

            // Verify duplicate check works immediately after restart
            assertFalse(db2.enqueueMessage(restored))

            db2.close()
        } finally {
            tempFile.delete()
            File("${tempFile.absolutePath}-wal").delete()
            File("${tempFile.absolutePath}-shm").delete()
        }
    }

    @Test
    fun testDeliveryStateTransitionsAndAck() = runBlocking {
        val db = MailboxDatabase(":memory:")
        db.enqueueMessage(
            MailboxMessage(
                messageId = "msg_ack",
                conversationId = "conv_1",
                senderUserId = "user_saad",
                recipientUserId = "user_alex",
                body = "Ack test",
                clientTimestamp = 1000L,
                serverReceivedTimestamp = 1050L,
                deliveryState = DeliveryState.QUEUED
            )
        )

        // Initial state is QUEUED
        var pending = db.getPendingMessagesForRecipient("user_alex")
        assertEquals(1, pending.size)
        assertEquals(DeliveryState.QUEUED, pending[0].deliveryState)

        // Mark as DELIVERED (in-flight over socket)
        assertTrue(db.updateDeliveryState("msg_ack", DeliveryState.DELIVERED))
        pending = db.getPendingMessagesForRecipient("user_alex")
        assertEquals(1, pending.size)
        assertEquals(DeliveryState.DELIVERED, pending[0].deliveryState)

        // Recipient ACKs message -> state transitions to ACKED
        assertTrue(db.updateDeliveryState("msg_ack", DeliveryState.ACKED))
        assertEquals(DeliveryState.ACKED, db.getMessage("msg_ack")?.deliveryState)

        // Message is no longer pending delivery
        pending = db.getPendingMessagesForRecipient("user_alex")
        assertEquals(0, pending.size)

        // Duplicate ACK is harmless
        assertTrue(db.updateDeliveryState("msg_ack", DeliveryState.ACKED))
        assertEquals(DeliveryState.ACKED, db.getMessage("msg_ack")?.deliveryState)

        db.close()
    }

    @Test
    fun testDeterministicOrderingForOfflineRecipient() = runBlocking {
        val db = MailboxDatabase(":memory:")

        // Enqueue messages with out-of-order timestamps
        db.enqueueMessage(
            MailboxMessage(
                messageId = "m3",
                conversationId = "conv_1",
                senderUserId = "user_saad",
                recipientUserId = "user_alex",
                body = "Third",
                clientTimestamp = 300L,
                serverReceivedTimestamp = 3000L,
                deliveryState = DeliveryState.QUEUED
            )
        )
        db.enqueueMessage(
            MailboxMessage(
                messageId = "m1",
                conversationId = "conv_1",
                senderUserId = "user_saad",
                recipientUserId = "user_alex",
                body = "First",
                clientTimestamp = 100L,
                serverReceivedTimestamp = 1000L,
                deliveryState = DeliveryState.QUEUED
            )
        )
        db.enqueueMessage(
            MailboxMessage(
                messageId = "m2",
                conversationId = "conv_1",
                senderUserId = "user_saad",
                recipientUserId = "user_alex",
                body = "Second",
                clientTimestamp = 200L,
                serverReceivedTimestamp = 2000L,
                deliveryState = DeliveryState.QUEUED
            )
        )

        val pending = db.getPendingMessagesForRecipient("user_alex")
        assertEquals(3, pending.size)
        assertEquals("m1", pending[0].messageId)
        assertEquals("m2", pending[1].messageId)
        assertEquals("m3", pending[2].messageId)

        db.close()
    }

    @Test
    fun testConcurrentInsertsSafety() = runBlocking {
        val db = MailboxDatabase(":memory:")
        val count = 50

        val jobs = (1..count).map { i ->
            async(Dispatchers.IO) {
                db.enqueueMessage(
                    MailboxMessage(
                        messageId = "concurrent_$i",
                        conversationId = "conv_c",
                        senderUserId = "user_sender_$i",
                        recipientUserId = "user_alex",
                        body = "Concurrent message $i",
                        clientTimestamp = 1000L + i,
                        serverReceivedTimestamp = 2000L + i,
                        deliveryState = DeliveryState.QUEUED
                    )
                )
            }
        }
        val results = jobs.awaitAll()
        assertEquals(count, results.count { it })
        assertEquals(count.toLong(), db.getMessageCount())

        val pending = db.getPendingMessagesForRecipient("user_alex")
        assertEquals(count, pending.size)
        // Verify deterministic order
        for (i in 0 until count - 1) {
            assertTrue(pending[i].serverReceivedTimestamp <= pending[i + 1].serverReceivedTimestamp)
        }

        db.close()
    }
}
