package com.aster.app.domain.model

/**
 * Domain model representing a user in Aster.
 *
 * Each user is an identity in the Aster constellation.
 *
 * @property id Unique identifier for the user.
 * @property username Handle (e.g. "alex", "saad").
 * @property displayName Human-readable name.
 * @property isLocalUser True if this is the active user on this client device.
 * @property isOnline Presence state; true represents a luminous point (✦), false represents a quiet point (·).
 */
data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val isLocalUser: Boolean = false,
    val isOnline: Boolean = false
)
