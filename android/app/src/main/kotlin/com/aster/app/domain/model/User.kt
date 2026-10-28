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
    val isOnline: Boolean = false,
    val lastSeen: Long = 0L,
    val isDeleted: Boolean = false,
    val isBlocked: Boolean = false
)

fun formatPresenceText(isOnline: Boolean, lastSeen: Long, isDeleted: Boolean, isBlocked: Boolean = false): String? {
    if (isBlocked) return "Blocked"
    if (isDeleted) return "This user no longer exists"
    if (isOnline) return "Active now"
    if (lastSeen > 0L) {
        val date = java.util.Date(lastSeen)
        val format = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        return "Last seen ${format.format(date)}"
    }
    return "Offline"
}

/**
 * Returns true if the string appears to be a machine-generated/system identifier
 * (e.g. "user_e9eefbaob70", "e9eefbaob70", UUIDs, or raw hex/alphanumeric hashes)
 * rather than a human-chosen display name.
 */
fun isInternalIdentifier(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    val trimmed = value.trim()

    // Explicit system prefixes
    val prefixes = listOf("user_", "ast_", "tok_", "ses_", "dev_", "msg_", "conv_", "grp_")
    if (prefixes.any { trimmed.startsWith(it, ignoreCase = true) }) {
        return true
    }

    // Standard UUID pattern (with hyphens)
    if (trimmed.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))) {
        return true
    }

    // Pure hex string of 8+ characters (e.g. 4f8a29b1c0e3, 1234567890abcdef)
    if (trimmed.length >= 8 && trimmed.matches(Regex("^[0-9a-fA-F]+$"))) {
        return true
    }

    // Alphanumeric random hash of 8+ characters without spaces containing both letters and digits (e.g. e9eefbaob70)
    if (trimmed.length >= 8 && !trimmed.contains(' ') && trimmed.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
        val hasLetter = trimmed.any { it.isLetter() }
        val hasDigit = trimmed.any { it.isDigit() }
        if (hasLetter && hasDigit) {
            return true
        }
    }

    return false
}

/**
 * Returns a human-friendly display name, or null if the input is an internal/system identifier.
 * Prevents internal IDs (like "user_e9eefbaob70", "E9eefbaob70", UUIDs, or raw hex hashes)
 * from being rendered as user greetings or profile titles.
 */
fun sanitizeDisplayName(rawName: String?, userId: String? = null): String? {
    if (rawName.isNullOrBlank()) return null
    val trimmed = rawName.trim()

    if (isInternalIdentifier(trimmed)) {
        return null
    }

    if (userId != null && trimmed.equals(userId.trim(), ignoreCase = true)) {
        return null
    }

    return trimmed
}

