package com.aster.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aster.app.data.network.FriendRequestSummary
import com.aster.app.data.network.UserSummary

@Entity(tableName = "friend_requests")
data class FriendRequestEntity(
    @PrimaryKey
    val id: String,
    val senderUserId: String,
    val recipientUserId: String,
    val status: String,
    val senderDisplayName: String,
    val senderUsername: String,
    val senderAsterId: String,
    val createdAt: Long
) {
    fun toSummary(): FriendRequestSummary = FriendRequestSummary(
        id = id,
        senderUserId = senderUserId,
        recipientUserId = recipientUserId,
        status = status,
        sender = UserSummary(
            userId = senderUserId,
            asterId = senderAsterId,
            username = senderUsername,
            displayName = senderDisplayName
        ),
        createdAt = createdAt
    )

    companion object {
        fun fromSummary(summary: FriendRequestSummary): FriendRequestEntity = FriendRequestEntity(
            id = summary.id,
            senderUserId = summary.senderUserId,
            recipientUserId = summary.recipientUserId,
            status = summary.status,
            senderDisplayName = summary.sender?.displayName ?: summary.senderUserId,
            senderUsername = summary.sender?.username ?: "",
            senderAsterId = summary.sender?.asterId ?: "",
            createdAt = summary.createdAt
        )
    }
}
