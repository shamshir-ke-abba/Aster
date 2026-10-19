package com.aster.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.network.ApiResult
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.AuthResult
import com.aster.app.data.network.ProtocolLimits
import com.aster.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

sealed interface OnboardingUiState {
    data object Idle : OnboardingUiState
    data object Loading : OnboardingUiState
    data class Success(val userId: String) : OnboardingUiState
    data class Error(
        val message: String,
        val isRevoked: Boolean = false,
        val isServerUnavailable: Boolean = false
    ) : OnboardingUiState
}

enum class OnboardingTab {
    REGISTER,
    INVITE_TOKEN
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    val authStorage: AsterAuthStorage,
    private val webSocketClient: AsterWebSocketClient,
    private val userRepository: UserRepository,
    private val apiClient: AsterApiClient = AsterApiClient(OkHttpClient(), authStorage)
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _currentTab = MutableStateFlow(OnboardingTab.REGISTER)
    val currentTab: StateFlow<OnboardingTab> = _currentTab.asStateFlow()

    private val _tokenInput = MutableStateFlow("")
    val tokenInput: StateFlow<String> = _tokenInput.asStateFlow()

    private val _usernameInput = MutableStateFlow("")
    val usernameInput: StateFlow<String> = _usernameInput.asStateFlow()

    private val _displayNameInput = MutableStateFlow("")
    val displayNameInput: StateFlow<String> = _displayNameInput.asStateFlow()

    fun selectTab(tab: OnboardingTab) {
        _currentTab.value = tab
        _uiState.value = OnboardingUiState.Idle
    }

    fun onTokenInputChanged(token: String) {
        _tokenInput.value = token
        if (_uiState.value is OnboardingUiState.Error) {
            _uiState.value = OnboardingUiState.Idle
        }
    }

    fun onUsernameInputChanged(username: String) {
        _usernameInput.value = username
        if (_uiState.value is OnboardingUiState.Error) {
            _uiState.value = OnboardingUiState.Idle
        }
    }

    fun onDisplayNameInputChanged(displayName: String) {
        _displayNameInput.value = displayName
        if (_uiState.value is OnboardingUiState.Error) {
            _uiState.value = OnboardingUiState.Idle
        }
    }

    fun submitRegistration() {
        val username = _usernameInput.value.trim().lowercase()
        val displayName = _displayNameInput.value.trim()

        if (username.length < 3 || username.length > 24 || !ProtocolLimits.USERNAME_REGEX.matches(username)) {
            _uiState.value = OnboardingUiState.Error("Username must be 3-24 alphanumeric or underscore characters")
            return
        }
        if (displayName.isBlank() || displayName.length > 32) {
            _uiState.value = OnboardingUiState.Error("Display name must be between 1 and 32 characters")
            return
        }

        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            when (val result = apiClient.register(username, displayName)) {
                is ApiResult.Success -> {
                    userRepository.setAuthenticatedUser(result.data.userId)
                    webSocketClient.connect(result.data.token)
                    _uiState.value = OnboardingUiState.Success(result.data.userId)
                }
                is ApiResult.Error -> {
                    _uiState.value = OnboardingUiState.Error(result.message)
                }
            }
        }
    }

    fun submitToken(tokenToSubmit: String? = null) {
        val token = (tokenToSubmit ?: _tokenInput.value).trim()
        if (token.isBlank()) {
            _uiState.value = OnboardingUiState.Error("Please enter an invitation token")
            return
        }

        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            when (val result = webSocketClient.authenticateWithToken(token)) {
                is AuthResult.Success -> {
                    userRepository.setAuthenticatedUser(result.userId)
                    _uiState.value = OnboardingUiState.Success(result.userId)
                }
                is AuthResult.InvalidToken -> {
                    val isRevoked = result.reason.contains("revoked", ignoreCase = true)
                    val errorMsg = if (isRevoked) {
                        "This invitation token has been revoked by the administrator."
                    } else {
                        "Invalid invitation token. Please check and try again."
                    }
                    _uiState.value = OnboardingUiState.Error(
                        message = errorMsg,
                        isRevoked = isRevoked
                    )
                }
                is AuthResult.ServerUnavailable -> {
                    _uiState.value = OnboardingUiState.Error(
                        message = "Cannot connect to Aster server. Please verify your network or try again.",
                        isServerUnavailable = true
                    )
                }
            }
        }
    }

    fun setLinkError(error: String) {
        _uiState.value = OnboardingUiState.Error(error)
    }

    fun resetState() {
        _uiState.value = OnboardingUiState.Idle
    }
}
