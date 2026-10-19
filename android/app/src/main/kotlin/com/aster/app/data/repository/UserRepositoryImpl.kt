package com.aster.app.data.repository

import com.aster.app.data.local.dao.UserDao
import com.aster.app.data.local.entities.UserEntity
import com.aster.app.domain.model.User
import com.aster.app.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao
) : UserRepository {

    override fun getLocalUser(): Flow<User?> {
        return userDao.getLocalUser().map { it?.toDomain() }
    }

    override suspend fun getLocalUserOnce(): User? {
        return userDao.getLocalUserOnce()?.toDomain()
    }

    override fun getUser(userId: String): Flow<User?> {
        return userDao.getUserById(userId).map { it?.toDomain() }
    }

    override fun getUsers(): Flow<List<User>> {
        return userDao.getAllUsers().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun saveUser(user: User) {
        userDao.insertUser(UserEntity.fromDomain(user))
    }

    override suspend fun setAuthenticatedUser(userId: String) {
        val existing = userDao.getUserByIdOnce(userId)
        if (existing == null) {
            val displayName = userId.removePrefix("user_").replaceFirstChar { it.uppercase() }
            val username = userId.removePrefix("user_")
            userDao.insertUser(
                UserEntity(
                    id = userId,
                    username = username,
                    displayName = displayName,
                    isLocalUser = true,
                    isOnline = true
                )
            )
        }
        userDao.setLocalUser(userId)
    }

    override suspend fun clearAuthenticatedUser() {
        userDao.clearLocalUser()
    }
}
