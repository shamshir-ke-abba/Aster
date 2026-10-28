package com.aster.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ConnectionState
import com.aster.app.data.network.NetworkConfig
import com.aster.app.data.local.AsterDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val asterId: String = "",
    val username: String = "",
    val displayName: String = "",
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
    private val webSocketClient: AsterWebSocketClient,
    private val database: AsterDatabase? = null,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context? = null,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            asterId = authStorage.getAsterId().orEmpty(),
            username = authStorage.getUsername().orEmpty(),
            displayName = authStorage.getDisplayName().orEmpty(),
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
            try {
                apiClient.logout()
            } catch (_: Exception) {}
            context?.let { com.aster.app.service.AsterSyncService.stop(it) }
            webSocketClient.disconnect()
            authStorage.clearAuthToken()
            _uiState.value = _uiState.value.copy(isLoggingOut = false)
            onComplete()
        }
    }

    fun deleteAccount(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeletingAccount = true, errorMessage = null)
            val result = apiClient.deleteAccount()
            when (result) {
                is com.aster.app.data.network.ApiResult.Success -> {
                    context?.let { com.aster.app.service.AsterSyncService.stop(it) }
                    webSocketClient.disconnect()
                    authStorage.clearAuthToken()
                    withContext(ioDispatcher) {
                        try {
                            database?.clearAllTables()
                        } catch (_: Exception) {}
                    }
                    _uiState.value = _uiState.value.copy(isDeletingAccount = false)
                    onSuccess()
                }
                is com.aster.app.data.network.ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isDeletingAccount = false,
                        errorMessage = result.message ?: "Failed to delete account. Please check your network connection and try again."
                    )
                }
            }
        }
    }

    fun clearErrorMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun reconnect() {
        webSocketClient.connect()
    }
}
