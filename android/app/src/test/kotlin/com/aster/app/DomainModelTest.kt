package com.aster.app

import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.ConversationMember
import com.aster.app.domain.model.Message
import com.aster.app.domain.model.MessageStatus
import com.aster.app.domain.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainModelTest {

    @Test
    fun messageCreation_defaultStatusIsSending() {
        val now = System.currentTimeMillis()
        val message = Message(
            id = "msg_123",
            conversationId = "conv_abc",
            senderId = "user_saad",
            body = "Hello Aster",
            createdAt = now
        )

        assertEquals("msg_123", message.id)
        assertEquals("conv_abc", message.conversationId)
        assertEquals("user_saad", message.senderId)
        assertEquals("Hello Aster", message.body)
        assertEquals(now, message.createdAt)
        assertEquals(MessageStatus.SENDING, message.status)
        assertNull(message.replyToId)
    }

    @Test
    fun messageStatus_allLifecycleStatesSupported() {
        val states = MessageStatus.values()
        assertTrue(states.contains(MessageStatus.SENDING))
        assertTrue(states.contains(MessageStatus.SENT))
        assertTrue(states.contains(MessageStatus.DELIVERED))
        assertTrue(states.contains(MessageStatus.READ))
        assertTrue(states.contains(MessageStatus.FAILED))
    }

    @Test
    fun userModel_propertiesAndPresence() {
        val user = User(
            id = "user_alex",
            username = "alex",
            displayName = "Alex",
            isLocalUser = false,
            isOnline = true
        )

        assertEquals("user_alex", user.id)
        assertEquals("alex", user.username)
        assertEquals("Alex", user.displayName)
        assertFalse(user.isLocalUser)
        assertTrue(user.isOnline)
    }

    @Test
    fun conversationModel_propertiesAndMemberAssociation() {
        val now = System.currentTimeMillis()
        val contact = User("user_rohan", "rohan", "Rohan", isLocalUser = false, isOnline = false)
        val conversation = Conversation(
            id = "conv_rohan",
            title = "Rohan",
            isGroup = false,
            createdAt = now,
            unreadCount = 2,
            otherMember = contact
        )

        assertEquals("conv_rohan", conversation.id)
        assertEquals("Rohan", conversation.title)
        assertFalse(conversation.isGroup)
        assertEquals(2, conversation.unreadCount)
        assertEquals(contact, conversation.otherMember)
    }

    @Test
    fun conversationMember_roleAndAssociation() {
        val now = System.currentTimeMillis()
        val member = ConversationMember(
            conversationId = "conv_1",
            userId = "user_1",
            role = "ADMIN",
            joinedAt = now
        )

        assertEquals("conv_1", member.conversationId)
        assertEquals("user_1", member.userId)
        assertEquals("ADMIN", member.role)
        assertEquals(now, member.joinedAt)
    }

    @Test
    fun testValidStateTransitions() {
        // Forward progression
        assertTrue(MessageStatus.SENDING.canTransitionTo(MessageStatus.SENT))
        assertTrue(MessageStatus.SENDING.canTransitionTo(MessageStatus.FAILED))
        assertTrue(MessageStatus.SENT.canTransitionTo(MessageStatus.DELIVERED))
        assertTrue(MessageStatus.SENT.canTransitionTo(MessageStatus.READ))
        assertTrue(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.READ))
        assertTrue(MessageStatus.FAILED.canTransitionTo(MessageStatus.SENDING)) // Retry

        // Idempotent self-transitions
        assertTrue(MessageStatus.SENDING.canTransitionTo(MessageStatus.SENDING))
        assertTrue(MessageStatus.SENT.canTransitionTo(MessageStatus.SENT))
        assertTrue(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.DELIVERED))
        assertTrue(MessageStatus.READ.canTransitionTo(MessageStatus.READ))
        assertTrue(MessageStatus.FAILED.canTransitionTo(MessageStatus.FAILED))
    }

    @Test
    fun testInvalidStateTransitions() {
        // Regressions back to SENDING
        assertFalse(MessageStatus.SENT.canTransitionTo(MessageStatus.SENDING))
        assertFalse(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.SENDING))
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.SENDING))

        // Regressions from READ
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.DELIVERED))
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.SENT))
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.FAILED))

        // Regressions from DELIVERED to SENT
        assertFalse(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.SENT))
        assertFalse(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.FAILED))

        // Invalid transitions from FAILED (must retry via SENDING)
        assertFalse(MessageStatus.FAILED.canTransitionTo(MessageStatus.SENT))
        assertFalse(MessageStatus.FAILED.canTransitionTo(MessageStatus.DELIVERED))
        assertFalse(MessageStatus.FAILED.canTransitionTo(MessageStatus.READ))
    }
}
