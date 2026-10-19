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
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<UserEntity>)

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

    @Query("SELECT COUNT(*) FROM users")
    suspend fun getUserCount(): Int

    @Query("UPDATE users SET is_local_user = 0")
    suspend fun clearLocalUser()

    @Query("UPDATE users SET is_local_user = CASE WHEN id = :authenticatedUserId THEN 1 ELSE 0 END")
    suspend fun setLocalUser(authenticatedUserId: String)
}
