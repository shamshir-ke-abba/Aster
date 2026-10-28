package com.aster.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists user-local constellation customization preferences:
 *  - Custom normalized spatial coordinates (customNormalizedX, customNormalizedY)
 *  - Highlight status (Aster's version of Pinning)
 *  - Local timestamp of last update
 *
 * Fully local to this client; never leaked to network or other devices.
 */
@Entity(tableName = "constellation_node_prefs")
data class ConstellationNodePrefEntity(
    @PrimaryKey val conversationId: String,
    val customNormalizedX: Float?,
    val customNormalizedY: Float?,
    val isHighlighted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)
