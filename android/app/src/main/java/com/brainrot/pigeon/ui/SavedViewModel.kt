package com.brainrot.pigeon.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brainrot.pigeon.data.SavedItem
import com.brainrot.pigeon.data.SavedRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** حالة تحميل ريل واحد على الجهاز. */
data class DownloadUi(
    val percent: Int = 0,
    val running: Boolean = true,
    val done: Boolean = false,
    val error: String? = null,
)

/**
 * يعرض قائمة الريلز المجدولة؛ المزامنة مع Firebase تحدث عند فتح التطبيق،
 * وتُعاد كل 1.5 ثانية ما دام فيه ريل قيد المعالجة على الخادم.
 */
class SavedViewModel(private val repository: SavedRepository) : ViewModel() {

    val items: StateFlow<List<SavedItem>> = repository.items.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _downloads = MutableStateFlow<Map<String, DownloadUi>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadUi>> = _downloads.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureThumbnails()
            repository.syncFromRemote()
        }
        pollArchives()
    }

    /** ينزّل الريل من R2 إلى Downloads مع نسبة 0..100 على البطاقة. */
    fun download(item: SavedItem) {
        if (_downloads.value[item.id]?.running == true) return

        _downloads.update { it + (item.id to DownloadUi(percent = 0)) }
        viewModelScope.launch {
            repository.download(item) { percent ->
                _downloads.update { it + (item.id to DownloadUi(percent = percent)) }
            }.onSuccess {
                _downloads.update {
                    it + (item.id to DownloadUi(percent = 100, running = false, done = true))
                }
            }.onFailure { error ->
                _downloads.update {
                    it + (
                        item.id to DownloadUi(
                            running = false,
                            error = error.message ?: "download failed",
                        )
                        )
                }
            }
        }
    }

    fun dismissDownload(id: String) {
        _downloads.update { it - id }
    }

    private fun pollArchives() {
        viewModelScope.launch {
            while (isActive) {
                val processing = items.value.any { it.isProcessing }
                if (processing) {
                    repository.syncFromRemote()
                    delay(1_500)
                } else {
                    delay(8_000)
                }
            }
        }
    }
}

private val SavedItem.isProcessing: Boolean
    get() = archiveState == "queued" || archiveState == "downloading" || archiveState == "storing"
