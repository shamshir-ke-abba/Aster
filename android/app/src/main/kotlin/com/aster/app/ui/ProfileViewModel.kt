package com.aster.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.network.ApiResult
import com.aster.app.data.network.AsterApiClient
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
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val feedbackMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val apiClient: AsterApiClient,
    private val authStorage: AsterAuthStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ProfileUiState(
            asterId = authStorage.getAsterId().orEmpty(),
            username = authStorage.getUsername().orEmpty(),
            displayName = authStorage.getDisplayName().orEmpty()
        )
    )
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
    }

    fun loadProfile() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val res = apiClient.getProfile()) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        asterId = res.data.asterId,
                        username = res.data.username,
                        displayName = res.data.displayName,
                        isLoading = false
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = res.message
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
