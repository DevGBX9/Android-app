package com.brainrot.pigeon.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brainrot.pigeon.data.SavedItem
import com.brainrot.pigeon.data.SavedRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * يعرض قائمة الريلز المجدولة؛ المزامنة مع Firebase تحدث عند فتح التطبيق،
 * وصور الغلاف الناقصة تُجلب هنا مرة واحدة.
 */
class SavedViewModel(private val repository: SavedRepository) : ViewModel() {

    val items: StateFlow<List<SavedItem>> = repository.items.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    init {
        viewModelScope.launch { repository.ensureThumbnails() }
    }
}
