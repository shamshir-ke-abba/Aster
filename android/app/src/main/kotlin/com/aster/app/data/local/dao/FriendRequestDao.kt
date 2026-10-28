package com.aster.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aster.app.data.local.entities.FriendRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FriendRequestDao {

    @Query("SELECT * FROM friend_requests WHERE UPPER(status) = 'PENDING' ORDER BY createdAt DESC")
    fun getIncomingPendingRequestsFlow(): Flow<List<FriendRequestEntity>>

    @Query("SELECT * FROM friend_requests WHERE UPPER(status) = 'PENDING' ORDER BY createdAt DESC")
    suspend fun getIncomingPendingRequestsOnce(): List<FriendRequestEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(request: FriendRequestEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(requests: List<FriendRequestEntity>)

    @Query("UPDATE friend_requests SET status = :status WHERE id = :requestId")
    suspend fun updateStatus(requestId: String, status: String)

    @Query("DELETE FROM friend_requests WHERE id = :requestId")
    suspend fun deleteById(requestId: String)

    @Query("DELETE FROM friend_requests")
    suspend fun clearAll()
}
