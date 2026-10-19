package com.aster.backend

import com.aster.backend.database.DeliveryState
import com.aster.backend.database.MailboxDatabase
import com.aster.backend.database.MailboxMessage
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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import kotlin.test.*

class GroupServerTest {

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
    fun testGroupCreationAndListing() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")
        val samToken = mailboxDb.createToken("user_sam", "dev_sam")

        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        var createdGroupId = ""

        // 1. Saad connects and creates group
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            outgoing.sendMsg(
                ClientMessage.CreateGroup(
                    requestId = "req_create_1",
                    name = "Constellation Crew",
                    memberIds = listOf("user_alex", "user_sam")
                )
            )
            val createResp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupOperationOk>(createResp)
            assertEquals("req_create_1", createResp.requestId)
            assertEquals("CREATE_GROUP", createResp.action)
            assertEquals("Constellation Crew", createResp.name)
            assertTrue(createResp.groupId.startsWith("group_"))
            createdGroupId = createResp.groupId

            // Saad lists groups
            outgoing.sendMsg(ClientMessage.GetGroups(requestId = "req_list_saad"))
            val listResp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupList>(listResp)
            assertEquals("req_list_saad", listResp.requestId)
            assertEquals(1, listResp.groups.size)
            assertEquals(createdGroupId, listResp.groups[0].id)
            assertEquals("CREATOR", listResp.groups[0].role)
            assertEquals(3, listResp.groups[0].memberCount)
        }

        // 2. Alex connects and lists groups; Alex should see group with role MEMBER
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            outgoing.sendMsg(ClientMessage.GetGroups(requestId = "req_list_alex"))
            val listResp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupList>(listResp)
            assertEquals("req_list_alex", listResp.requestId)
            assertEquals(1, listResp.groups.size)
            assertEquals(createdGroupId, listResp.groups[0].id)
            assertEquals("MEMBER", listResp.groups[0].role)

            // Alex gets member list
            outgoing.sendMsg(ClientMessage.GetGroupMembers(requestId = "req_members_alex", groupId = createdGroupId))
            val membersResp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupMembersList>(membersResp)
            assertEquals(3, membersResp.members.size)
            val creatorMember = membersResp.members.firstOrNull { it.userId == "user_saad" }
            assertNotNull(creatorMember)
            assertEquals("CREATOR", creatorMember.role)
        }
    }

    @Test
    fun testGroupMemberManagementAndPermissions() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")
        val samToken = mailboxDb.createToken("user_sam", "dev_sam")
        val elenaToken = mailboxDb.createToken("user_elena", "dev_elena")
        val rohanToken = mailboxDb.createToken("user_rohan", "dev_rohan")

        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        var groupId = ""

        // Saad creates group with Alex and Sam
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            outgoing.sendMsg(
                ClientMessage.CreateGroup(
                    requestId = "req_c1",
                    name = "Aster Explorers",
                    memberIds = listOf("user_alex", "user_sam")
                )
            )
            val resp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupOperationOk>(resp)
            groupId = resp.groupId

            // Saad (CREATOR) adds Elena
            outgoing.sendMsg(ClientMessage.AddGroupMember(requestId = "req_add_1", groupId = groupId, userId = "user_elena"))
            val addResp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupOperationOk>(addResp)
            assertEquals("ADD_MEMBER", addResp.action)

            // Saad renames group
            outgoing.sendMsg(ClientMessage.RenameGroup(requestId = "req_ren_1", groupId = groupId, newName = "Galactic Navigators"))
            val renameResp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupOperationOk>(renameResp)
            assertEquals("RENAME_GROUP", renameResp.action)
            assertEquals("Galactic Navigators", renameResp.name)
        }

        // Alex (MEMBER) attempts unauthorized operations
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            // Alex tries to add Rohan -> MUST BE REJECTED
            outgoing.sendMsg(ClientMessage.AddGroupMember(requestId = "req_unauth_add", groupId = groupId, userId = "user_rohan"))
            val err1 = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(err1)
            assertEquals("PERMISSION_DENIED", err1.code)

            // Alex tries to rename group -> MUST BE REJECTED
            outgoing.sendMsg(ClientMessage.RenameGroup(requestId = "req_unauth_ren", groupId = groupId, newName = "Alex Fleet"))
            val err2 = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(err2)
            assertEquals("PERMISSION_DENIED", err2.code)

            // Alex tries to remove Sam -> MUST BE REJECTED
            outgoing.sendMsg(ClientMessage.RemoveGroupMember(requestId = "req_unauth_rem", groupId = groupId, userId = "user_sam"))
            val err3 = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(err3)
            assertEquals("PERMISSION_DENIED", err3.code)
        }

        // Rohan (NON-MEMBER) attempts unauthorized access
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = rohanToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            // Rohan tries to get group members -> MUST BE REJECTED
            outgoing.sendMsg(ClientMessage.GetGroupMembers(requestId = "req_rohan_get", groupId = groupId))
            val err = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(err)
            assertEquals("UNAUTHORIZED_GROUP_ACCESS", err.code)
        }
    }

    @Test
    fun testGroupMessageFanOutAndDelivery() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")
        val samToken = mailboxDb.createToken("user_sam", "dev_sam")

        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        var groupId = ""

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            outgoing.sendMsg(
                ClientMessage.CreateGroup(
                    requestId = "req_cf1",
                    name = "Starfleet",
                    memberIds = listOf("user_alex", "user_sam")
                )
            )
            val resp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupOperationOk>(resp)
            groupId = resp.groupId
        }

        // Alex connects
        client.webSocket("/ws") {
            val alexWs = this
            alexWs.outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            assertIs<ServerMessage.AuthOk>(alexWs.incoming.receiveServerMsg())

            // In parallel, Saad connects and sends a message to the group
            client.webSocket("/ws") {
                val saadWs = this
                saadWs.outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                assertIs<ServerMessage.AuthOk>(saadWs.incoming.receiveServerMsg())

                saadWs.outgoing.sendMsg(
                    ClientMessage.SendMessage(
                        messageId = "grp_msg_101",
                        conversationId = groupId,
                        recipientId = null,
                        body = "Hello Starfleet!",
                        timestamp = 1000L
                    )
                )

                // Saad receives MESSAGE_ACCEPTED
                val ack = saadWs.incoming.receiveServerMsg()
                assertIs<ServerMessage.MessageAccepted>(ack)
                assertEquals("grp_msg_101", ack.messageId)
            }

            // Alex receives the relayed group message!
            val relayed = alexWs.incoming.receiveServerMsg()
            assertIs<ServerMessage.RelayMessage>(relayed)
            assertEquals("grp_msg_101", relayed.messageId)
            assertEquals(groupId, relayed.conversationId)
            assertEquals("user_saad", relayed.senderId)
            assertEquals("Hello Starfleet!", relayed.body)

            // Alex sends ACK
            alexWs.outgoing.sendMsg(ClientMessage.AckReceived("grp_msg_101"))
        }

        // Sam was offline during send. Sam connects now and should receive the queued message!
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = samToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            val replayed = incoming.receiveServerMsg()
            assertIs<ServerMessage.RelayMessage>(replayed)
            assertEquals("grp_msg_101", replayed.messageId)
            assertEquals(groupId, replayed.conversationId)
            assertEquals("user_saad", replayed.senderId)
            assertEquals("Hello Starfleet!", replayed.body)

            // Sam ACKs
            outgoing.sendMsg(ClientMessage.AckReceived("grp_msg_101"))
        }

        // Alex reconnects again: should NOT receive the message again because Alex already ACKed
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            // Request group list to confirm channel is active and no stale queued message arrived
            outgoing.sendMsg(ClientMessage.GetGroups(requestId = "req_check_alex"))
            val listResp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupList>(listResp)
        }
    }

    @Test
    fun testRemovedMemberCannotSendOrReceiveNewMessages() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")
        val samToken = mailboxDb.createToken("user_sam", "dev_sam")

        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        var groupId = ""

        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            outgoing.sendMsg(
                ClientMessage.CreateGroup(
                    requestId = "req_rem_init",
                    name = "Vanguard",
                    memberIds = listOf("user_alex", "user_sam")
                )
            )
            val resp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupOperationOk>(resp)
            groupId = resp.groupId

            // Saad removes Sam from Vanguard
            outgoing.sendMsg(ClientMessage.RemoveGroupMember(requestId = "req_rem_sam", groupId = groupId, userId = "user_sam"))
            val removeResp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupOperationOk>(removeResp)
        }

        // Sam attempts to send a message to Vanguard -> MUST BE REJECTED
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = samToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            outgoing.sendMsg(
                ClientMessage.SendMessage(
                    messageId = "sam_rejected_msg",
                    conversationId = groupId,
                    recipientId = null,
                    body = "Can you still hear me?",
                    timestamp = 2000L
                )
            )
            val err = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(err)
            assertEquals("UNAUTHORIZED_GROUP_ACCESS", err.code)
        }

        // Saad sends a new message to Vanguard
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            outgoing.sendMsg(
                ClientMessage.SendMessage(
                    messageId = "saad_post_remove_msg",
                    conversationId = groupId,
                    recipientId = null,
                    body = "Sam has been removed.",
                    timestamp = 3000L
                )
            )
            assertIs<ServerMessage.MessageAccepted>(incoming.receiveServerMsg())
        }

        // Sam connects: MUST NOT receive the new message
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = samToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            // Send ping or get groups to ensure no pending message is sent
            outgoing.sendMsg(ClientMessage.Ping())
            val pong = incoming.receiveServerMsg()
            assertIs<ServerMessage.Pong>(pong)
        }
    }

    @Test
    fun testCreatorDepartureSuccession() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")
        val samToken = mailboxDb.createToken("user_sam", "dev_sam")

        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        var groupId = ""

        // Saad creates group with Alex and Sam
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            outgoing.sendMsg(
                ClientMessage.CreateGroup(
                    requestId = "req_cd_init",
                    name = "Orbiters",
                    memberIds = listOf("user_alex", "user_sam")
                )
            )
            val resp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupOperationOk>(resp)
            groupId = resp.groupId

            // Saad leaves group
            outgoing.sendMsg(ClientMessage.LeaveGroup(requestId = "req_leave_saad", groupId = groupId))
            val leaveResp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupOperationOk>(leaveResp)
            assertEquals("LEAVE_GROUP", leaveResp.action)
        }

        // Alex connects: Alex (the next oldest member) is now CREATOR
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            outgoing.sendMsg(ClientMessage.GetGroups(requestId = "req_groups_alex"))
            val listResp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupList>(listResp)
            assertEquals(1, listResp.groups.size)
            assertEquals(groupId, listResp.groups[0].id)
            assertEquals("CREATOR", listResp.groups[0].role)

            // Alex now has permission to rename group
            outgoing.sendMsg(ClientMessage.RenameGroup(requestId = "req_alex_ren", groupId = groupId, newName = "Alex Orbiters"))
            val renameResp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupOperationOk>(renameResp)
            assertEquals("Alex Orbiters", renameResp.name)
        }
    }

    @Test
    fun testDatabaseMigrationV1ToV2PreservesData() = runBlocking {
        val tempDb = File.createTempFile("aster_migration_test_", ".db")
        try {
            // 1. Manually construct schema version 1
            DriverManager.getConnection("jdbc:sqlite:${tempDb.absolutePath}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("PRAGMA user_version = 1;")
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS mailbox_messages (
                            message_id TEXT PRIMARY KEY,
                            conversation_id TEXT NOT NULL,
                            sender_user_id TEXT NOT NULL,
                            recipient_user_id TEXT NOT NULL,
                            body TEXT NOT NULL,
                            client_timestamp INTEGER NOT NULL,
                            server_received_timestamp INTEGER NOT NULL,
                            delivery_state TEXT NOT NULL DEFAULT 'QUEUED'
                        );
                    """.trimIndent())
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS auth_tokens (
                            token_hash TEXT PRIMARY KEY,
                            user_id TEXT NOT NULL,
                            device_id TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            revoked_at INTEGER
                        );
                    """.trimIndent())
                    stmt.execute("""
                        INSERT INTO mailbox_messages (message_id, conversation_id, sender_user_id, recipient_user_id, body, client_timestamp, server_received_timestamp, delivery_state)
                        VALUES ('v1_direct_msg', 'conv_saad_alex', 'user_saad', 'user_alex', 'Preserved across migration', 1000, 1005, 'QUEUED');
                    """.trimIndent())
                    val tokenHash = MailboxDatabase.hashToken("v1_token_saad")
                    stmt.execute("""
                        INSERT INTO auth_tokens (token_hash, user_id, device_id, created_at, revoked_at)
                        VALUES ('$tokenHash', 'user_saad', 'dev_saad', 1000, NULL);
                    """.trimIndent())
                }
            }

            // 2. Initialize MailboxDatabase on the v1 file — migration runs
            val db = MailboxDatabase(tempDb.absolutePath)

            // 3. Verify user_version is now 3 (migrated through v2 and v3)
            assertEquals(3, db.getSchemaVersion())

            // 4. Verify existing v1 direct message is preserved
            val preservedMsg = db.getMessage("v1_direct_msg")
            assertNotNull(preservedMsg)
            assertEquals("Preserved across migration", preservedMsg.body)
            assertEquals("user_alex", preservedMsg.recipientUserId)
            assertEquals(DeliveryState.QUEUED, preservedMsg.deliveryState)

            // 5. Verify v1 token is preserved
            val tokenRecord = db.validateToken("v1_token_saad")
            assertNotNull(tokenRecord)
            assertEquals("user_saad", tokenRecord.userId)

            // 6. Verify groups and fan-out message storage work in migrated database
            val grp = db.createGroup("Migrated Group", "user_saad", listOf("user_alex", "user_sam"))
            assertNotNull(grp)

            val groupMsg = MailboxMessage(
                messageId = "v2_group_msg",
                conversationId = grp.groupId,
                senderUserId = "user_saad",
                recipientUserId = "user_alex",
                body = "Fan-out to Alex and Sam",
                clientTimestamp = 2000L,
                serverReceivedTimestamp = 2005L,
                deliveryState = DeliveryState.QUEUED
            )
            val enqueued = db.enqueueGroupMessage(groupMsg, listOf("user_alex", "user_sam"))
            assertEquals(2, enqueued)

            // Alex and Sam both have queued rows with same messageId
            val alexCopy = db.getMessageForRecipient("v2_group_msg", "user_alex")
            val samCopy = db.getMessageForRecipient("v2_group_msg", "user_sam")
            assertNotNull(alexCopy)
            assertNotNull(samCopy)
            assertEquals(DeliveryState.QUEUED, alexCopy.deliveryState)
            assertEquals(DeliveryState.QUEUED, samCopy.deliveryState)

            // Alex ACKs, Sam remains QUEUED
            db.updateDeliveryState("v2_group_msg", "user_alex", DeliveryState.ACKED)
            assertEquals(DeliveryState.ACKED, db.getMessageForRecipient("v2_group_msg", "user_alex")?.deliveryState)
            assertEquals(DeliveryState.QUEUED, db.getMessageForRecipient("v2_group_msg", "user_sam")?.deliveryState)

            db.close()
        } finally {
            tempDb.delete()
        }
    }

    @Test
    fun testGroupPersistenceAndOfflineDeliveryAcrossRestart() {
        val tempDb = File.createTempFile("aster_group_restart_test_", ".db")
        try {
            var saadToken = ""
            var alexToken = ""
            var samToken = ""
            var groupId = ""

            // --- Server Session 1: Create group, send message to online Alex and offline Sam ---
            testApplication {
                val db1 = MailboxDatabase(tempDb.absolutePath)
                saadToken = db1.createToken("user_saad", "dev_saad")
                alexToken = db1.createToken("user_alex", "dev_alex")
                samToken = db1.createToken("user_sam", "dev_sam")
                val sm1 = SessionManager()
                application { asterModule(sm1, db1) }
                val client = createClient { install(WebSockets) }

                // 1. Saad connects and creates group
                client.webSocket("/ws") {
                    outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                    assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                    outgoing.sendMsg(
                        ClientMessage.CreateGroup(
                            requestId = "req_c_restart",
                            name = "Persistent Constellation",
                            memberIds = listOf("user_alex", "user_sam")
                        )
                    )
                    val createOk = incoming.receiveServerMsg()
                    assertIs<ServerMessage.GroupOperationOk>(createOk)
                    groupId = createOk.groupId
                }

                // 2. Alex connects; Saad sends group message 1 while Sam is offline
                client.webSocket("/ws") {
                    val alexWs = this
                    alexWs.outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
                    assertIs<ServerMessage.AuthOk>(alexWs.incoming.receiveServerMsg())

                    client.webSocket("/ws") {
                        val saadWs = this
                        saadWs.outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                        assertIs<ServerMessage.AuthOk>(saadWs.incoming.receiveServerMsg())

                        saadWs.outgoing.sendMsg(
                            ClientMessage.SendMessage(
                                messageId = "grp_restart_msg_1",
                                conversationId = groupId,
                                recipientId = null,
                                body = "Hello before server restart!",
                                timestamp = 1000L
                            )
                        )
                        val ack = saadWs.incoming.receiveServerMsg()
                        assertIs<ServerMessage.MessageAccepted>(ack)
                    }

                    // Alex receives online and ACKs
                    val alexRelay = alexWs.incoming.receiveServerMsg()
                    assertIs<ServerMessage.RelayMessage>(alexRelay)
                    assertEquals("grp_restart_msg_1", alexRelay.messageId)
                    assertEquals("Hello before server restart!", alexRelay.body)
                    alexWs.outgoing.sendMsg(ClientMessage.AckReceived("grp_restart_msg_1"))

                    // Synchronize with server to ensure ACK is committed to SQLite before shutdown
                    alexWs.outgoing.sendMsg(ClientMessage.Ping())
                    assertIs<ServerMessage.Pong>(alexWs.incoming.receiveServerMsg())
                }
                db1.close()
            } // Server 1 terminates

            // --- Server Session 2: Server restarted from disk SQLite DB ---
            testApplication {
                val db2 = MailboxDatabase(tempDb.absolutePath)
                val sm2 = SessionManager()
                application { asterModule(sm2, db2) }
                val client = createClient { install(WebSockets) }

                // 1. Alex connects: Alex already ACKed grp_restart_msg_1, must NOT receive it again
                client.webSocket("/ws") {
                    outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
                    assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                    outgoing.sendMsg(ClientMessage.Ping())
                    assertIs<ServerMessage.Pong>(incoming.receiveServerMsg())
                }

                // 2. Sam (was offline during send and across restart) connects:
                // Sam MUST receive the replayed group message from SQLite mailbox!
                client.webSocket("/ws") {
                    outgoing.sendMsg(ClientMessage.Auth(token = samToken))
                    assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                    val samRelay = incoming.receiveServerMsg()
                    assertIs<ServerMessage.RelayMessage>(samRelay)
                    assertEquals("grp_restart_msg_1", samRelay.messageId)
                    assertEquals(groupId, samRelay.conversationId)
                    assertEquals("user_saad", samRelay.senderId)
                    assertEquals("Hello before server restart!", samRelay.body)

                    // Sam ACKs message and syncs
                    outgoing.sendMsg(ClientMessage.AckReceived("grp_restart_msg_1"))
                    outgoing.sendMsg(ClientMessage.Ping())
                    assertIs<ServerMessage.Pong>(incoming.receiveServerMsg())
                }

                // 3. Saad connects and renames group
                client.webSocket("/ws") {
                    outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                    assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                    outgoing.sendMsg(ClientMessage.RenameGroup(requestId = "req_ren_post_rst", groupId = groupId, newName = "Persistent Constellation 2.0"))
                    val renOk = incoming.receiveServerMsg()
                    assertIs<ServerMessage.GroupOperationOk>(renOk)
                    assertEquals("Persistent Constellation 2.0", renOk.name)
                }
                db2.close()
            } // Server 2 terminates

            // --- Server Session 3: Verify metadata persisted across 2nd restart ---
            testApplication {
                val db3 = MailboxDatabase(tempDb.absolutePath)
                val sm3 = SessionManager()
                application { asterModule(sm3, db3) }
                val client = createClient { install(WebSockets) }

                client.webSocket("/ws") {
                    outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
                    assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                    outgoing.sendMsg(ClientMessage.GetGroups(requestId = "req_g_alex_rst3"))
                    val gList = incoming.receiveServerMsg()
                    assertIs<ServerMessage.GroupList>(gList)
                    assertEquals(1, gList.groups.size)
                    assertEquals("Persistent Constellation 2.0", gList.groups[0].name)
                    assertEquals("MEMBER", gList.groups[0].role)
                    assertEquals(3, gList.groups[0].memberCount)
                }
                db3.close()
            }
        } finally {
            tempDb.delete()
        }
    }

    @Test
    fun testRemovedMemberQueuedMessagesDiscarded() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")
        val samToken = mailboxDb.createToken("user_sam", "dev_sam")

        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        var groupId = ""

        // Saad creates group with Alex and Sam
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            outgoing.sendMsg(
                ClientMessage.CreateGroup(
                    requestId = "req_c_rem_q",
                    name = "Expulsion Test",
                    memberIds = listOf("user_alex", "user_sam")
                )
            )
            val resp = incoming.receiveServerMsg()
            assertIs<ServerMessage.GroupOperationOk>(resp)
            groupId = resp.groupId

            // Saad sends message while Sam is offline -> queued for Sam
            outgoing.sendMsg(
                ClientMessage.SendMessage(
                    messageId = "msg_before_removal",
                    conversationId = groupId,
                    recipientId = null,
                    body = "Confidential pre-removal discussion",
                    timestamp = 1000L
                )
            )
            assertIs<ServerMessage.MessageAccepted>(incoming.receiveServerMsg())

            // Saad removes Sam from the group before Sam ever connects
            outgoing.sendMsg(ClientMessage.RemoveGroupMember(requestId = "req_rem_sam_q", groupId = groupId, userId = "user_sam"))
            assertIs<ServerMessage.GroupOperationOk>(incoming.receiveServerMsg())
        }

        // Sam connects for the first time:
        // Because Sam was removed, queued messages for that group MUST be discarded/skipped!
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = samToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            // Send ping to verify replay queue was empty and no leaked group messages arrive
            outgoing.sendMsg(ClientMessage.Ping())
            val pong = incoming.receiveServerMsg()
            assertIs<ServerMessage.Pong>(pong)

            // Sam tries to get members of the group: unauthorized
            outgoing.sendMsg(ClientMessage.GetGroupMembers(requestId = "req_sam_unauth", groupId = groupId))
            val err = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(err)
            assertEquals("UNAUTHORIZED_GROUP_ACCESS", err.code)
        }
    }

    @Test
    fun testDatabaseMigrationV0ToV2PreservesData() = runBlocking {
        val tempDb = File.createTempFile("aster_migration_v0_test_", ".db")
        try {
            // 1. Manually construct schema version 0 (original M9.1: no user_version, no auth_tokens, single PK)
            DriverManager.getConnection("jdbc:sqlite:${tempDb.absolutePath}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("""
                        CREATE TABLE mailbox_messages (
                            message_id TEXT PRIMARY KEY NOT NULL,
                            conversation_id TEXT NOT NULL,
                            sender_user_id TEXT NOT NULL,
                            recipient_user_id TEXT NOT NULL,
                            body TEXT NOT NULL,
                            client_timestamp INTEGER NOT NULL,
                            server_received_timestamp INTEGER NOT NULL,
                            delivery_state TEXT NOT NULL
                        );
                    """.trimIndent())
                    stmt.execute("""
                        INSERT INTO mailbox_messages (message_id, conversation_id, sender_user_id, recipient_user_id, body, client_timestamp, server_received_timestamp, delivery_state)
                        VALUES ('v0_legacy_direct_msg', 'conv_saad_alex', 'user_saad', 'user_alex', 'Legacy message from v0', 1000, 1005, 'QUEUED');
                    """.trimIndent())
                }
            }

            // 2. Open with MailboxDatabase -> triggers migration to version 2
            val db = MailboxDatabase(tempDb.absolutePath)

            // 3. Verify user_version is 3
            assertEquals(3, db.getSchemaVersion())

            // 4. Verify existing legacy message is intact
            val legacyMsg = db.getMessage("v0_legacy_direct_msg")
            assertNotNull(legacyMsg)
            assertEquals("Legacy message from v0", legacyMsg.body)
            assertEquals("user_alex", legacyMsg.recipientUserId)
            assertEquals(DeliveryState.QUEUED, legacyMsg.deliveryState)

            // 5. Verify auth_tokens table works now
            val token = db.createToken("user_saad", "dev_saad")
            assertNotNull(db.validateToken(token))

            // 6. Verify groups and composite PK fan-out work
            val group = db.createGroup("Upgraded V0 Group", "user_saad", listOf("user_alex", "user_sam"))
            assertNotNull(group)

            val groupMsg = MailboxMessage(
                messageId = "v2_upgraded_group_msg",
                conversationId = group.groupId,
                senderUserId = "user_saad",
                recipientUserId = "user_alex",
                body = "Fan-out after upgrade from v0",
                clientTimestamp = 2000L,
                serverReceivedTimestamp = 2005L,
                deliveryState = DeliveryState.QUEUED
            )
            val enqueued = db.enqueueGroupMessage(groupMsg, listOf("user_alex", "user_sam"))
            assertEquals(2, enqueued)

            db.close()
        } finally {
            tempDb.delete()
        }
    }

    @Test
    fun testBackendRestartPreservesGroupMailboxAndSettlement() = runBlocking {
        val tempDb = File.createTempFile("aster_restart_test_", ".db")
        try {
            var groupId = ""
            var samToken = ""

            // Instance 1: Backend runs, creates group, Saad sends message while Sam is offline
            testApplication {
                val db1 = MailboxDatabase(tempDb.absolutePath)
                val sm1 = SessionManager()
                val saadToken = db1.createToken("user_saad", "dev_saad")
                samToken = db1.createToken("user_sam", "dev_sam")

                application { asterModule(sm1, db1) }
                val client = createClient { install(WebSockets) }

                client.webSocket("/ws") {
                    outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                    assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                    outgoing.sendMsg(
                        ClientMessage.CreateGroup(
                            requestId = "req_create_grp",
                            name = "Restart Preservation Group",
                            memberIds = listOf("user_sam")
                        )
                    )
                    val createOk = incoming.receiveServerMsg()
                    assertIs<ServerMessage.GroupOperationOk>(createOk)
                    groupId = createOk.groupId

                    outgoing.sendMsg(
                        ClientMessage.SendMessage(
                            messageId = "msg_restart_1",
                            conversationId = groupId,
                            body = "Persistent message across restart",
                            timestamp = 1000L
                        )
                    )
                    assertIs<ServerMessage.MessageAccepted>(incoming.receiveServerMsg())
                }
                db1.close()
            }

            // Instance 2: Backend restarted with SAME database file
            testApplication {
                val db2 = MailboxDatabase(tempDb.absolutePath)
                val sm2 = SessionManager()

                application { asterModule(sm2, db2) }
                val client = createClient { install(WebSockets) }

                // Sam connects to the restarted backend instance
                client.webSocket("/ws") {
                    outgoing.sendMsg(ClientMessage.Auth(token = samToken))
                    assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                    // Sam must receive the queued message
                    val replayedMsg = incoming.receiveServerMsg()
                    assertIs<ServerMessage.RelayMessage>(replayedMsg)
                    assertEquals("msg_restart_1", replayedMsg.messageId)
                    assertEquals(groupId, replayedMsg.conversationId)
                    assertEquals("Persistent message across restart", replayedMsg.body)

                    // Sam ACKs the message (settles it)
                    outgoing.sendMsg(ClientMessage.AckReceived(messageId = "msg_restart_1"))

                    outgoing.sendMsg(ClientMessage.Ping())
                    assertIs<ServerMessage.Pong>(incoming.receiveServerMsg())
                }

                // Sam reconnects: settled message must NOT reappear
                client.webSocket("/ws") {
                    outgoing.sendMsg(ClientMessage.Auth(token = samToken))
                    assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                    outgoing.sendMsg(ClientMessage.Ping())
                    val pong = incoming.receiveServerMsg()
                    assertIs<ServerMessage.Pong>(pong)
                }

                db2.close()
            }
        } finally {
            tempDb.delete()
        }
    }

    @Test
    fun testDuplicateMessageSendRetriesDoNotDuplicateDeliveryOrMailbox() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")

        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        val group = mailboxDb.createGroup("Deduplication Test Group", "user_saad", listOf("user_alex"))

        // Alex connects and stays online
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

            // Saad connects and sends message M1
            client.webSocket("/ws") {
                outgoing.sendMsg(ClientMessage.Auth(token = saadToken))
                assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())

                outgoing.sendMsg(
                    ClientMessage.SendMessage(
                        messageId = "retry_msg_1",
                        conversationId = group.groupId,
                        body = "Hello with retry",
                        timestamp = 1000L
                    )
                )
                assertIs<ServerMessage.MessageAccepted>(incoming.receiveServerMsg())

                // Saad retries with exact same messageId
                outgoing.sendMsg(
                    ClientMessage.SendMessage(
                        messageId = "retry_msg_1",
                        conversationId = group.groupId,
                        body = "Hello with retry",
                        timestamp = 1000L
                    )
                )
                // Server must accept idempotently
                assertIs<ServerMessage.MessageAccepted>(incoming.receiveServerMsg())
            }

            // Alex receives the FIRST message
            val firstMsg = incoming.receiveServerMsg()
            assertIs<ServerMessage.RelayMessage>(firstMsg)
            assertEquals("retry_msg_1", firstMsg.messageId)

            // Send Ping to verify no second duplicate message is in the incoming queue
            outgoing.sendMsg(ClientMessage.Ping())
            val pong = incoming.receiveServerMsg()
            assertIs<ServerMessage.Pong>(pong)
        }

        // Verify mailbox database only has 1 record for this recipient and message
        val delivered = mailboxDb.getMessageForRecipient("retry_msg_1", "user_alex")
        assertNotNull(delivered)
        assertEquals(1, mailboxDb.getMessageCount())
    }

    @Test
    fun testTokenRevocationBlocksGroupOperationsAndClosesSessions() = testApplication {
        val mailboxDb = MailboxDatabase(":memory:")
        val sessionManager = SessionManager()
        val saadToken = mailboxDb.createToken("user_saad", "dev_saad")
        val alexToken = mailboxDb.createToken("user_alex", "dev_alex")

        application { asterModule(sessionManager, mailboxDb) }
        val client = createClient { install(WebSockets) }

        mailboxDb.createGroup("Revocation Test Group", "user_saad", listOf("user_alex"))

        // Alex connects
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            assertIs<ServerMessage.AuthOk>(incoming.receiveServerMsg())
            assertTrue(sessionManager.isOnline("user_alex"))

            // Server revokes Alex's token
            mailboxDb.revokeToken(alexToken)
            sessionManager.closeSession("user_alex", "Token revoked")

            // Alex should be offline now
            assertFalse(sessionManager.isOnline("user_alex"))
        }

        // Alex tries to reconnect with revoked token
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.Auth(token = alexToken))
            val authResp = incoming.receiveServerMsg()
            assertIs<ServerMessage.AuthError>(authResp)
            assertEquals("Invalid or revoked authentication token", authResp.reason)
        }

        // Attempt group operation with an unauthenticated session
        client.webSocket("/ws") {
            outgoing.sendMsg(ClientMessage.GetGroups("req_unauth_list"))
            val opResp = incoming.receiveServerMsg()
            assertIs<ServerMessage.Error>(opResp)
            assertEquals("UNAUTHENTICATED", opResp.code)
        }
    }
}
