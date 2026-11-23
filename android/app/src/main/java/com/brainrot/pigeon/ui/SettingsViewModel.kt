package com.brainrot.pigeon.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brainrot.pigeon.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val apiEndpoint: String = "",
    val r2UploadEndpoint: String = "",
    val r2Token: String = "",
    val loaded: Boolean = false,
    val savedAt: Long = 0L,
)

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val current = repository.current()
            _state.value = SettingsUiState(
                apiEndpoint = current.apiEndpoint,
                r2UploadEndpoint = current.r2UploadEndpoint,
                r2Token = current.r2Token,
                loaded = true,
            )
        }
    }

    fun setApiEndpoint(value: String) = _state.update { it.copy(apiEndpoint = value) }

    fun setR2Endpoint(value: String) = _state.update { it.copy(r2UploadEndpoint = value) }

    fun setR2Token(value: String) = _state.update { it.copy(r2Token = value) }

    fun save() {
        val snapshot = _state.value
        viewModelScope.launch {
            repository.save(
                apiEndpoint = snapshot.apiEndpoint,
                r2UploadEndpoint = snapshot.r2UploadEndpoint,
                r2Token = snapshot.r2Token,
            )
            _state.update { it.copy(savedAt = System.currentTimeMillis()) }
        }
    }
}
