package com.aster.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aster.app.data.local.entities.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUser(user: UserEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUsers(users: List<UserEntity>): List<Long>

    @Update
    suspend fun updateUser(user: UserEntity)

    @Query("SELECT * FROM users WHERE id = :userId")
    fun getUserById(userId: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserByIdOnce(userId: String): UserEntity?

    @Query("SELECT * FROM users WHERE is_local_user = 1 LIMIT 1")
    fun getLocalUser(): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE is_local_user = 1 LIMIT 1")
    suspend fun getLocalUserOnce(): UserEntity?

    @Query("SELECT * FROM users ORDER BY display_name ASC")
    fun getAllUsers(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users ORDER BY display_name ASC")
    suspend fun getAllUsersOnce(): List<UserEntity>

    @Query("SELECT COUNT(*) FROM users")
    suspend fun getUserCount(): Int

    @Query("UPDATE users SET is_local_user = 0")
    suspend fun clearLocalUser()

    @Query("UPDATE users SET is_local_user = CASE WHEN id = :authenticatedUserId THEN 1 ELSE 0 END")
    suspend fun setLocalUser(authenticatedUserId: String)

    @Query("UPDATE users SET display_name = :displayName WHERE id = :userId")
    suspend fun updateDisplayName(userId: String, displayName: String)

    @Query("UPDATE users SET is_online = :isOnline, last_seen = :lastSeen WHERE id = :userId")
    suspend fun updatePresence(userId: String, isOnline: Boolean, lastSeen: Long)

    @Query("UPDATE users SET is_deleted = 1, display_name = 'Deleted User', username = 'deleted', is_online = 0, last_seen = 0 WHERE id = :userId")
    suspend fun markUserDeleted(userId: String)

    @Query("UPDATE users SET is_blocked = :isBlocked WHERE id = :userId")
    suspend fun updateBlockedStatus(userId: String, isBlocked: Boolean)

    @Query("SELECT id FROM users WHERE is_blocked = 1")
    suspend fun getBlockedUserIdsOnce(): List<String>

    @Query("UPDATE users SET is_blocked = 0 WHERE id NOT IN (:blockedIds) AND is_blocked = 1")
    suspend fun unmarkUnblockedUsers(blockedIds: List<String>)

    @Query("UPDATE users SET is_blocked = 0 WHERE is_blocked = 1")
    suspend fun clearAllBlocked()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUser(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsers(users: List<UserEntity>)

    @Query("DELETE FROM users")
    suspend fun deleteAllUsers()
}
