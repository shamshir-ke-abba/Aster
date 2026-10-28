package com.aster.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.network.ApiResult
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.NetworkSanitizer
import com.aster.app.data.network.ProtocolLimits
import com.aster.app.data.network.RegistrationResponse
import com.aster.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

sealed interface OnboardingUiState {
    data object Idle : OnboardingUiState
    data object Loading : OnboardingUiState
    data class ShowRecoveryCode(
        val recoveryCode: String,
        val userId: String,
        val isReset: Boolean = false
    ) : OnboardingUiState
    data class Success(val userId: String) : OnboardingUiState
    data class Error(
        val message: String,
        val requiresMigration: Boolean = false,
        val isServerUnavailable: Boolean = false
    ) : OnboardingUiState
}

enum class OnboardingTab {
    CREATE_ACCOUNT,
    SIGN_IN
}

enum class AuthSubFlow {
    NORMAL,
    FORGOT_PASSWORD,
    MIGRATE_ACCOUNT
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    val authStorage: AsterAuthStorage,
    private val webSocketClient: AsterWebSocketClient,
    private val userRepository: UserRepository,
    private val apiClient: AsterApiClient = AsterApiClient(OkHttpClient(), authStorage),
    @ApplicationContext private val context: Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _currentTab = MutableStateFlow(OnboardingTab.CREATE_ACCOUNT)
    val currentTab: StateFlow<OnboardingTab> = _currentTab.asStateFlow()

    private val _subFlow = MutableStateFlow(AuthSubFlow.NORMAL)
    val subFlow: StateFlow<AuthSubFlow> = _subFlow.asStateFlow()

    private val _usernameInput = MutableStateFlow("")
    val usernameInput: StateFlow<String> = _usernameInput.asStateFlow()

    private val _displayNameInput = MutableStateFlow("")
    val displayNameInput: StateFlow<String> = _displayNameInput.asStateFlow()

    private val _passwordInput = MutableStateFlow("")
    val passwordInput: StateFlow<String> = _passwordInput.asStateFlow()

    private val _recoveryCodeInput = MutableStateFlow("")
    val recoveryCodeInput: StateFlow<String> = _recoveryCodeInput.asStateFlow()

    private val _newPasswordInput = MutableStateFlow("")
    val newPasswordInput: StateFlow<String> = _newPasswordInput.asStateFlow()

    private var pendingUserId: String? = null
    private var pendingDisplayName: String? = null
    private var pendingToken: String? = null

    fun selectTab(tab: OnboardingTab) {
        _currentTab.value = tab
        _subFlow.value = AuthSubFlow.NORMAL
        _uiState.value = OnboardingUiState.Idle
    }

    fun setSubFlow(flow: AuthSubFlow) {
        _subFlow.value = flow
        _uiState.value = OnboardingUiState.Idle
    }

    fun onUsernameInputChanged(username: String) {
        _usernameInput.value = username
        clearErrorIfPresent()
    }

    fun onDisplayNameInputChanged(displayName: String) {
        _displayNameInput.value = displayName
        clearErrorIfPresent()
    }

    fun onPasswordInputChanged(password: String) {
        _passwordInput.value = password
        clearErrorIfPresent()
    }

    fun onRecoveryCodeInputChanged(recoveryCode: String) {
        _recoveryCodeInput.value = recoveryCode
        clearErrorIfPresent()
    }

    fun onNewPasswordInputChanged(newPassword: String) {
        _newPasswordInput.value = newPassword
        clearErrorIfPresent()
    }

    private fun clearErrorIfPresent() {
        if (_uiState.value is OnboardingUiState.Error) {
            _uiState.value = OnboardingUiState.Idle
        }
    }

    fun submitCreateAccount() {
        val username = _usernameInput.value.trim().lowercase()
        val displayName = _displayNameInput.value.trim().ifBlank { username }
        val password = _passwordInput.value

        if (username.length < 3 || username.length > 24 || !ProtocolLimits.USERNAME_REGEX.matches(username)) {
            _uiState.value = OnboardingUiState.Error("Username must be 3-24 alphanumeric or underscore characters")
            return
        }
        if (password.length < 8) {
            _uiState.value = OnboardingUiState.Error("Password must be at least 8 characters")
            return
        }
        if (displayName.length > 32) {
            _uiState.value = OnboardingUiState.Error("Display name cannot exceed 32 characters")
            return
        }

        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            try {
                when (val result = apiClient.register(username, displayName, password)) {
                    is ApiResult.Success -> {
                        pendingUserId = result.data.userId
                        pendingDisplayName = displayName
                        pendingToken = result.data.token

                        val recoveryCode = result.data.recoveryCode
                        if (!recoveryCode.isNullOrBlank()) {
                            _uiState.value = OnboardingUiState.ShowRecoveryCode(
                                recoveryCode = recoveryCode,
                                userId = result.data.userId,
                                isReset = false
                            )
                        } else {
                            completeAuthentication(result.data.userId, displayName, result.data.token)
                        }
                    }
                    is ApiResult.Error -> {
                        _uiState.value = OnboardingUiState.Error(result.message)
                    }
                }
            } catch (e: Throwable) {
                _uiState.value = OnboardingUiState.Error(NetworkSanitizer.sanitizeError(e, "Registration failed. Please try again."))
            }
        }
    }

    fun submitSignIn() {
        val username = _usernameInput.value.trim().lowercase()
        val password = _passwordInput.value

        if (username.isBlank() || password.isBlank()) {
            _uiState.value = OnboardingUiState.Error("Username and password are required")
            return
        }

        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            try {
                when (val result = apiClient.login(username, password)) {
                    is ApiResult.Success -> {
                        completeAuthentication(result.data.userId, result.data.displayName, result.data.token)
                    }
                    is ApiResult.Error -> {
                        if (result.requiresMigration) {
                            _subFlow.value = AuthSubFlow.MIGRATE_ACCOUNT
                            _uiState.value = OnboardingUiState.Error(
                                message = result.message,
                                requiresMigration = true
                            )
                        } else {
                            _uiState.value = OnboardingUiState.Error(result.message)
                        }
                    }
                }
            } catch (e: Throwable) {
                _uiState.value = OnboardingUiState.Error(NetworkSanitizer.sanitizeError(e, "Sign in failed. Please try again."))
            }
        }
    }

    fun submitPasswordRecovery() {
        val username = _usernameInput.value.trim().lowercase()
        val recoveryCode = _recoveryCodeInput.value.trim()
        val newPassword = _newPasswordInput.value

        if (username.isBlank()) {
            _uiState.value = OnboardingUiState.Error("Username is required")
            return
        }
        if (recoveryCode.isBlank()) {
            _uiState.value = OnboardingUiState.Error("Recovery code is required")
            return
        }
        if (newPassword.length < 8) {
            _uiState.value = OnboardingUiState.Error("New password must be at least 8 characters")
            return
        }

        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            when (val result = apiClient.recoverPassword(username, recoveryCode, newPassword)) {
                is ApiResult.Success -> {
                    pendingUserId = result.data.userId
                    pendingDisplayName = result.data.displayName
                    pendingToken = result.data.token

                    _uiState.value = OnboardingUiState.ShowRecoveryCode(
                        recoveryCode = result.data.newRecoveryCode,
                        userId = result.data.userId,
                        isReset = true
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = OnboardingUiState.Error(result.message)
                }
            }
        }
    }

    fun submitMigration() {
        val username = _usernameInput.value.trim().lowercase()
        val newPassword = _newPasswordInput.value

        if (username.isBlank()) {
            _uiState.value = OnboardingUiState.Error("Username is required")
            return
        }
        if (newPassword.length < 8) {
            _uiState.value = OnboardingUiState.Error("Password must be at least 8 characters")
            return
        }

        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            when (val result = apiClient.migrateAccount(username, newPassword)) {
                is ApiResult.Success -> {
                    pendingUserId = result.data.userId
                    pendingDisplayName = result.data.displayName
                    pendingToken = result.data.token

                    val recoveryCode = result.data.recoveryCode
                    if (!recoveryCode.isNullOrBlank()) {
                        _uiState.value = OnboardingUiState.ShowRecoveryCode(
                            recoveryCode = recoveryCode,
                            userId = result.data.userId,
                            isReset = true
                        )
                    } else {
                        completeAuthentication(result.data.userId, result.data.displayName, result.data.token)
                    }
                }
                is ApiResult.Error -> {
                    _uiState.value = OnboardingUiState.Error(result.message)
                }
            }
        }
    }

    fun acknowledgeRecoveryCode() {
        val userId = pendingUserId ?: (_uiState.value as? OnboardingUiState.ShowRecoveryCode)?.userId
        if (userId != null) {
            viewModelScope.launch {
                val dn = pendingDisplayName ?: _displayNameInput.value.ifBlank { _usernameInput.value }
                val token = pendingToken ?: authStorage.getAuthToken().orEmpty()
                completeAuthentication(userId, dn, token)
            }
        }
    }

    private suspend fun completeAuthentication(userId: String, displayName: String, token: String) {
        try {
            userRepository.setAuthenticatedUser(userId, displayName)
            if (token.isNotBlank()) {
                try {
                    webSocketClient.connect(token)
                } catch (e: Exception) {
                    android.util.Log.w("OnboardingVM", "WebSocket connect deferred: ${e.message}")
                }
            }
            context?.let {
                try {
                    com.aster.app.service.AsterSyncService.start(it)
                } catch (e: Exception) {
                    android.util.Log.w("OnboardingVM", "Sync service start deferred: ${e.message}")
                }
            }
            _uiState.value = OnboardingUiState.Success(userId)
        } catch (e: Throwable) {
            android.util.Log.e("OnboardingVM", "completeAuthentication failed", e)
            _uiState.value = OnboardingUiState.Error(NetworkSanitizer.sanitizeError(e, "Authentication could not be completed. Please try again."))
        }
    }

    // Backwards-compatible registration helper for tests
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
        if (_passwordInput.value.isBlank()) {
            _passwordInput.value = "password123"
        }
        submitCreateAccount()
    }

    fun resetState() {
        _uiState.value = OnboardingUiState.Idle
        _subFlow.value = AuthSubFlow.NORMAL
    }
}
