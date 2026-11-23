package com.brainrot.pigeon.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brainrot.pigeon.data.R2Uploader
import com.brainrot.pigeon.data.SavedItem
import com.brainrot.pigeon.data.SavedRepository
import com.brainrot.pigeon.data.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SavedViewModel(
    private val repository: SavedRepository,
    private val settings: SettingsRepository,
    private val uploader: R2Uploader,
) : ViewModel() {

    val items: StateFlow<List<SavedItem>> = repository.items.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _busyId = MutableStateFlow<String?>(null)
    val busyId: StateFlow<String?> = _busyId.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun upload(item: SavedItem) {
        if (_busyId.value != null) return
        viewModelScope.launch {
            _busyId.value = item.id
            try {
                val config = settings.current()
                uploader.upload(item, config)
                    .onSuccess { url ->
                        repository.markUploaded(item.id, url)
                        _messages.emit("تم الرفع إلى R2")
                    }
                    .onFailure { error ->
                        _messages.emit(error.message ?: "فشل الرفع إلى R2")
                    }
            } finally {
                _busyId.value = null
            }
        }
    }
}
