package com.aster.app.domain.repository

import com.aster.app.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for user identity and contacts.
 */
interface UserRepository {
    fun getLocalUser(): Flow<User?>
    suspend fun getLocalUserOnce(): User?
    fun getUser(userId: String): Flow<User?>
    fun getUsers(): Flow<List<User>>
    suspend fun saveUser(user: User)
    suspend fun setAuthenticatedUser(userId: String)
    suspend fun clearAuthenticatedUser()
}
