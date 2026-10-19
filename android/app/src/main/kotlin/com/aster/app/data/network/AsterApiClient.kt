package com.aster.app.data.network

import android.util.Log
import com.aster.app.data.auth.AsterAuthStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ApiResult<out T> {
    data class Success<out T>(val data: T) : ApiResult<T>
    data class Error(val message: String, val statusCode: Int? = null) : ApiResult<Nothing>
}

data class RegistrationResponse(
    val userId: String,
    val asterId: String,
    val username: String,
    val displayName: String,
    val token: String
)

data class FriendRequestsPayload(
    val incoming: List<FriendRequestSummary>,
    val outgoing: List<FriendRequestSummary>
)

@Singleton
open class AsterApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val authStorage: AsterAuthStorage
) {
    private val TAG = "AsterApiClient"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    protected open fun baseUrl(): String = NetworkConfig.getHttpBaseUrl()

    private fun addAuthHeader(builder: Request.Builder): Request.Builder {
        val token = authStorage.getAuthToken()
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    suspend fun register(
        username: String,
        displayName: String,
        deviceId: String = UUID.randomUUID().toString()
    ): ApiResult<RegistrationResponse> = withContext(Dispatchers.IO) {
        val jsonBody = JSONObject().apply {
            put("username", username.trim())
            put("displayName", displayName.trim())
            put("deviceId", deviceId)
        }.toString()

        val request = Request.Builder()
            .url("${baseUrl()}/api/register")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val json = JSONObject(bodyString)
                val resp = RegistrationResponse(
                    userId = json.getString("userId"),
                    asterId = json.getString("asterId"),
                    username = json.getString("username"),
                    displayName = json.getString("displayName"),
                    token = json.getString("token")
                )
                authStorage.saveAuthToken(resp.token)
                authStorage.saveAuthenticatedUserId(resp.userId)
                authStorage.saveAsterId(resp.asterId)
                authStorage.saveUsername(resp.username)
                authStorage.saveDisplayName(resp.displayName)
                ApiResult.Success(resp)
            } else {
                val err = parseErrorMessage(bodyString, "Registration failed (${response.code})")
                ApiResult.Error(err, response.code)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Registration error: ${e.message}", e)
            ApiResult.Error("Network error: ${e.message ?: "Unable to connect"}")
        }
    }

    suspend fun getProfile(): ApiResult<UserSummary> = withContext(Dispatchers.IO) {
        val request = addAuthHeader(Request.Builder().url("${baseUrl()}/api/profile")).get().build()
        try {
            val response = okHttpClient.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val json = JSONObject(bodyString)
                val user = UserSummary(
                    userId = json.getString("userId"),
                    asterId = json.getString("asterId"),
                    username = json.getString("username"),
                    displayName = json.getString("displayName")
                )
                authStorage.saveAsterId(user.asterId)
                authStorage.saveUsername(user.username)
                authStorage.saveDisplayName(user.displayName)
                ApiResult.Success(user)
            } else {
                ApiResult.Error(parseErrorMessage(bodyString, "Failed to fetch profile"), response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun updateProfile(
        displayName: String? = null,
        username: String? = null
    ): ApiResult<UserSummary> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            if (displayName != null) put("displayName", displayName)
            if (username != null) put("username", username)
        }
        val request = addAuthHeader(Request.Builder().url("${baseUrl()}/api/profile"))
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            val response = okHttpClient.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val respJson = JSONObject(bodyString)
                val user = UserSummary(
                    userId = respJson.getString("userId"),
                    asterId = respJson.getString("asterId"),
                    username = respJson.getString("username"),
                    displayName = respJson.getString("displayName")
                )
                authStorage.saveDisplayName(user.displayName)
                authStorage.saveUsername(user.username)
                ApiResult.Success(user)
            } else {
                ApiResult.Error(parseErrorMessage(bodyString, "Failed to update profile"), response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun searchUsers(query: String): ApiResult<List<UserSummary>> = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val request = addAuthHeader(Request.Builder().url("${baseUrl()}/api/users/search?q=$encoded")).get().build()
        try {
            val response = okHttpClient.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val json = JSONObject(bodyString)
                val array = json.getJSONArray("users")
                val list = mutableListOf<UserSummary>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        UserSummary(
                            userId = obj.getString("userId"),
                            asterId = obj.getString("asterId"),
                            username = obj.getString("username"),
                            displayName = obj.getString("displayName")
                        )
                    )
                }
                ApiResult.Success(list)
            } else {
                ApiResult.Error(parseErrorMessage(bodyString, "User search failed"), response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun sendFriendRequest(
        targetUserId: String? = null,
        targetAsterId: String? = null
    ): ApiResult<FriendRequestSummary> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            if (targetUserId != null) put("targetUserId", targetUserId)
            if (targetAsterId != null) put("targetAsterId", targetAsterId)
        }
        val request = addAuthHeader(Request.Builder().url("${baseUrl()}/api/friends/request"))
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            val response = okHttpClient.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val obj = JSONObject(bodyString)
                val recipientObj = obj.optJSONObject("recipient")
                val recipientSummary = recipientObj?.let {
                    UserSummary(
                        userId = it.getString("userId"),
                        asterId = it.getString("asterId"),
                        username = it.getString("username"),
                        displayName = it.getString("displayName")
                    )
                }
                val summary = FriendRequestSummary(
                    id = obj.getString("id"),
                    senderUserId = obj.getString("senderUserId"),
                    recipientUserId = obj.getString("recipientUserId"),
                    status = obj.getString("status"),
                    recipient = recipientSummary,
                    createdAt = obj.getLong("createdAt")
                )
                ApiResult.Success(summary)
            } else {
                ApiResult.Error(parseErrorMessage(bodyString, "Could not send friend request"), response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun respondFriendRequest(requestId: String, accept: Boolean): ApiResult<Boolean> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("requestId", requestId)
            put("action", if (accept) "ACCEPT" else "REJECT")
        }
        val request = addAuthHeader(Request.Builder().url("${baseUrl()}/api/friends/respond"))
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                ApiResult.Success(true)
            } else {
                val bodyString = response.body?.string().orEmpty()
                ApiResult.Error(parseErrorMessage(bodyString, "Could not respond to request"), response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun cancelFriendRequest(requestId: String): ApiResult<Boolean> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("requestId", requestId)
        }
        val request = addAuthHeader(Request.Builder().url("${baseUrl()}/api/friends/cancel"))
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                ApiResult.Success(true)
            } else {
                val bodyString = response.body?.string().orEmpty()
                ApiResult.Error(parseErrorMessage(bodyString, "Could not cancel request"), response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun getFriendRequests(): ApiResult<FriendRequestsPayload> = withContext(Dispatchers.IO) {
        val request = addAuthHeader(Request.Builder().url("${baseUrl()}/api/friends/requests")).get().build()
        try {
            val response = okHttpClient.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val root = JSONObject(bodyString)
                val incomingArray = root.getJSONArray("incoming")
                val outgoingArray = root.getJSONArray("outgoing")

                val incomingList = parseFriendRequests(incomingArray)
                val outgoingList = parseFriendRequests(outgoingArray)

                ApiResult.Success(FriendRequestsPayload(incomingList, outgoingList))
            } else {
                ApiResult.Error(parseErrorMessage(bodyString, "Failed to load friend requests"), response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun getFriends(): ApiResult<List<UserSummary>> = withContext(Dispatchers.IO) {
        val request = addAuthHeader(Request.Builder().url("${baseUrl()}/api/friends")).get().build()
        try {
            val response = okHttpClient.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val root = JSONObject(bodyString)
                val friendsArray = root.getJSONArray("friends")
                val list = mutableListOf<UserSummary>()
                for (i in 0 until friendsArray.length()) {
                    val obj = friendsArray.getJSONObject(i)
                    list.add(
                        UserSummary(
                            userId = obj.getString("userId"),
                            asterId = obj.getString("asterId"),
                            username = obj.getString("username"),
                            displayName = obj.getString("displayName")
                        )
                    )
                }
                ApiResult.Success(list)
            } else {
                ApiResult.Error(parseErrorMessage(bodyString, "Failed to load friends"), response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun blockUser(targetUserId: String? = null, targetAsterId: String? = null): ApiResult<Boolean> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            if (targetUserId != null) put("targetUserId", targetUserId)
            if (targetAsterId != null) put("targetAsterId", targetAsterId)
        }
        val request = addAuthHeader(Request.Builder().url("${baseUrl()}/api/friends/block"))
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                ApiResult.Success(true)
            } else {
                val bodyString = response.body?.string().orEmpty()
                ApiResult.Error(parseErrorMessage(bodyString, "Could not block user"), response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun unblockUser(targetUserId: String): ApiResult<Boolean> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("targetUserId", targetUserId)
        }
        val request = addAuthHeader(Request.Builder().url("${baseUrl()}/api/friends/unblock"))
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                ApiResult.Success(true)
            } else {
                val bodyString = response.body?.string().orEmpty()
                ApiResult.Error(parseErrorMessage(bodyString, "Could not unblock user"), response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun getBlockedUsers(): ApiResult<List<UserSummary>> = withContext(Dispatchers.IO) {
        val request = addAuthHeader(Request.Builder().url("${baseUrl()}/api/friends/blocked")).get().build()
        try {
            val response = okHttpClient.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val root = JSONObject(bodyString)
                val blockedArray = root.getJSONArray("blocked")
                val list = mutableListOf<UserSummary>()
                for (i in 0 until blockedArray.length()) {
                    val obj = blockedArray.getJSONObject(i)
                    list.add(
                        UserSummary(
                            userId = obj.getString("userId"),
                            asterId = obj.getString("asterId"),
                            username = obj.getString("username"),
                            displayName = obj.getString("displayName")
                        )
                    )
                }
                ApiResult.Success(list)
            } else {
                ApiResult.Error(parseErrorMessage(bodyString, "Failed to load blocked users"), response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun logout(): ApiResult<Boolean> = withContext(Dispatchers.IO) {
        val request = addAuthHeader(Request.Builder().url("${baseUrl()}/api/logout"))
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            val response = okHttpClient.newCall(request).execute()
            authStorage.clearAuthToken()
            ApiResult.Success(response.isSuccessful)
        } catch (e: Exception) {
            authStorage.clearAuthToken()
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun deleteAccount(): ApiResult<Boolean> = withContext(Dispatchers.IO) {
        val request = addAuthHeader(Request.Builder().url("${baseUrl()}/api/account/delete"))
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            val response = okHttpClient.newCall(request).execute()
            authStorage.clearAuthToken()
            ApiResult.Success(response.isSuccessful)
        } catch (e: Exception) {
            authStorage.clearAuthToken()
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    private fun parseFriendRequests(array: JSONArray): List<FriendRequestSummary> {
        val list = mutableListOf<FriendRequestSummary>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val senderObj = obj.optJSONObject("sender")
            val senderSummary = senderObj?.let {
                UserSummary(
                    userId = it.getString("userId"),
                    asterId = it.getString("asterId"),
                    username = it.getString("username"),
                    displayName = it.getString("displayName")
                )
            }
            val recipientObj = obj.optJSONObject("recipient")
            val recipientSummary = recipientObj?.let {
                UserSummary(
                    userId = it.getString("userId"),
                    asterId = it.getString("asterId"),
                    username = it.getString("username"),
                    displayName = it.getString("displayName")
                )
            }
            list.add(
                FriendRequestSummary(
                    id = obj.getString("id"),
                    senderUserId = obj.getString("senderUserId"),
                    recipientUserId = obj.getString("recipientUserId"),
                    status = obj.getString("status"),
                    sender = senderSummary,
                    recipient = recipientSummary,
                    createdAt = obj.getLong("createdAt")
                )
            )
        }
        return list
    }

    private fun parseErrorMessage(body: String, fallback: String): String {
        return try {
            val json = JSONObject(body)
            json.optString("error", fallback)
        } catch (_: Exception) {
            if (body.isNotBlank() && body.length < 200) body else fallback
        }
    }
}
