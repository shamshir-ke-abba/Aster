package com.aster.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aster.app.domain.model.User

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: String,
    val username: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "is_local_user")
    val isLocalUser: Boolean,
    @ColumnInfo(name = "is_online")
    val isOnline: Boolean,
    @ColumnInfo(name = "last_seen", defaultValue = "0")
    val lastSeen: Long = 0L,
    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    val isDeleted: Boolean = false,
    @ColumnInfo(name = "is_blocked", defaultValue = "0")
    val isBlocked: Boolean = false
) {
    fun toDomain(): User = User(
        id = id,
        username = username,
        displayName = displayName,
        isLocalUser = isLocalUser,
        isOnline = isOnline,
        lastSeen = lastSeen,
        isDeleted = isDeleted,
        isBlocked = isBlocked
    )

    companion object {
        fun fromDomain(user: User): UserEntity = UserEntity(
            id = user.id,
            username = user.username,
            displayName = user.displayName,
            isLocalUser = user.isLocalUser,
            isOnline = user.isOnline,
            lastSeen = user.lastSeen,
            isDeleted = user.isDeleted,
            isBlocked = user.isBlocked
        )
    }
}
