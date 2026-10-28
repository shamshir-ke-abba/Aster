package com.aster.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "deleted_messages")
data class DeletedMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long = System.currentTimeMillis()
)
