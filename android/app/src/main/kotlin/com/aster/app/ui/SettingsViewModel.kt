package com.aster.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ConnectionState
import com.aster.app.data.network.NetworkConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val asterId: String = "",
    val username: String = "",
    val displayName: String = "",
    val serverUrl: String = "",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val viewMode: String = "CONSTELLATION",
    val readReceiptsEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val isLoggingOut: Boolean = false,
    val isDeletingAccount: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val apiClient: AsterApiClient,
    private val authStorage: AsterAuthStorage,
    private val webSocketClient: AsterWebSocketClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            asterId = authStorage.getAsterId().orEmpty(),
            username = authStorage.getUsername().orEmpty(),
            displayName = authStorage.getDisplayName().orEmpty(),
            serverUrl = NetworkConfig.serverUrl,
            viewMode = authStorage.getViewMode()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            webSocketClient.connectionState.collect { connState ->
                _uiState.value = _uiState.value.copy(connectionState = connState)
            }
        }
    }

    fun setViewMode(mode: String) {
        authStorage.saveViewMode(mode)
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }

    fun toggleReadReceipts(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(readReceiptsEnabled = enabled)
    }

    fun toggleNotifications(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(notificationsEnabled = enabled)
    }

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingOut = true)
            apiClient.logout()
            webSocketClient.disconnect()
            authStorage.clearAuthToken()
            _uiState.value = _uiState.value.copy(isLoggingOut = false)
            onComplete()
        }
    }

    fun deleteAccount(onComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeletingAccount = true)
            apiClient.deleteAccount()
            webSocketClient.disconnect()
            authStorage.clearAuthToken()
            _uiState.value = _uiState.value.copy(isDeletingAccount = false)
            onComplete()
        }
    }

    fun reconnect() {
        webSocketClient.connect()
    }
}
