package com.aster.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.network.ApiResult
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ConnectionState
import com.aster.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val asterId: String = "",
    val username: String = "",
    val displayName: String = "",
    val connectionState: ConnectionState = ConnectionState.CONNECTED,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val feedbackMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val apiClient: AsterApiClient,
    private val authStorage: AsterAuthStorage,
    private val webSocketClient: AsterWebSocketClient? = null,
    private val userRepository: UserRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ProfileUiState(
            asterId = authStorage.getAsterId().orEmpty(),
            username = authStorage.getUsername().orEmpty(),
            displayName = authStorage.getDisplayName().orEmpty(),
            connectionState = webSocketClient?.connectionState?.value ?: ConnectionState.CONNECTED
        )
    )
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
        if (webSocketClient != null) {
            viewModelScope.launch {
                webSocketClient.connectionState.collect { state ->
                    _uiState.value = _uiState.value.copy(connectionState = state)
                }
            }
        }
    }

    fun loadProfile() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val res = apiClient.getProfile()) {
                is ApiResult.Success -> {
                    authStorage.saveDisplayName(res.data.displayName)
                    val uid = authStorage.getAuthenticatedUserId()
                    if (!uid.isNullOrBlank()) {
                        userRepository?.setAuthenticatedUser(uid, res.data.displayName)
                    }
                    _uiState.value = _uiState.value.copy(
                        asterId = res.data.asterId,
                        username = res.data.username,
                        displayName = res.data.displayName,
                        isLoading = false
                    )
                }
                is ApiResult.Error -> {
                    // Fail silently during passive screen load if network is unavailable
                    _uiState.value = _uiState.value.copy(
                        isLoading = false
                    )
                }
            }
        }
    }

    fun updateDisplayName(newDisplayName: String) {
        val clean = newDisplayName.trim()
        if (clean.isBlank() || clean.length > 32) {
            _uiState.value = _uiState.value.copy(errorMessage = "Display name must be 1-32 characters")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null, feedbackMessage = null)
            when (val res = apiClient.updateProfile(displayName = clean)) {
                is ApiResult.Success -> {
                    authStorage.saveDisplayName(res.data.displayName)
                    val uid = authStorage.getAuthenticatedUserId()
                    if (!uid.isNullOrBlank()) {
                        userRepository?.setAuthenticatedUser(uid, res.data.displayName)
                    }
                    _uiState.value = _uiState.value.copy(
                        displayName = res.data.displayName,
                        isSaving = false,
                        feedbackMessage = "Display name updated"
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        errorMessage = res.message
                    )
                }
            }
        }
    }

    fun clearFeedback() {
        _uiState.value = _uiState.value.copy(feedbackMessage = null, errorMessage = null)
    }
}
