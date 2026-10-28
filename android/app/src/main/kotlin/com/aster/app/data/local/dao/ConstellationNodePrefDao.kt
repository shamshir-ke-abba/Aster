package com.aster.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aster.app.data.local.entities.ConstellationNodePrefEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for local Constellation Node preferences.
 */
@Dao
interface ConstellationNodePrefDao {

    @Query("SELECT * FROM constellation_node_prefs")
    fun getAllNodePrefs(): Flow<List<ConstellationNodePrefEntity>>

    @Query("SELECT * FROM constellation_node_prefs WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getNodePref(conversationId: String): ConstellationNodePrefEntity?

    @Query("SELECT * FROM constellation_node_prefs WHERE conversationId = :conversationId LIMIT 1")
    fun getNodePrefFlow(conversationId: String): Flow<ConstellationNodePrefEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNodePref(pref: ConstellationNodePrefEntity)

    @Query("UPDATE constellation_node_prefs SET isHighlighted = :isHighlighted, updatedAt = :updatedAt WHERE conversationId = :conversationId")
    suspend fun updateHighlight(
        conversationId: String,
        isHighlighted: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE constellation_node_prefs SET customNormalizedX = :x, customNormalizedY = :y, updatedAt = :updatedAt WHERE conversationId = :conversationId")
    suspend fun updatePosition(
        conversationId: String,
        x: Float?,
        y: Float?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("DELETE FROM constellation_node_prefs WHERE conversationId = :conversationId")
    suspend fun deleteNodePref(conversationId: String)
}
