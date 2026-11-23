package com.brainrot.pigeon.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brainrot.pigeon.data.AvatarLoader
import com.brainrot.pigeon.data.Profile
import com.brainrot.pigeon.data.ProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AvatarUiState(
    val base: ImageBitmap? = null,
    val incoming: ImageBitmap? = null,
    val key: String = "",
    val pendingKey: String = "",
)

class ProfileViewModel(
    private val repository: ProfileRepository,
    private val avatars: AvatarLoader,
) : ViewModel() {

    val profile: StateFlow<Profile> = repository.profile

    /** سبب فشل آخر تحديث، أو null إذا نجح. */
    val error: StateFlow<String?> = repository.error

    private val _avatar = MutableStateFlow(AvatarUiState())
    val avatar: StateFlow<AvatarUiState> = _avatar.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch { repository.loadCached() }

        // نحمّل الصورة فقط لما يتغير مسارها، لا مع كل تحديث للعدادات
        viewModelScope.launch {
            profile
                .map { it.avatarUrl }
                .distinctUntilChanged()
                .collect { url ->
                    if (url.isNotBlank()) {
                        val bitmap = avatars.load(url)
                        if (bitmap != null) showAvatar(url, bitmap)
                    }
                }
        }

        startPolling()
    }

    private fun showAvatar(url: String, bitmap: ImageBitmap) {
        val key = avatars.cacheKey(url)
        val current = _avatar.value
        if (key == current.key || key == current.pendingKey) return

        _avatar.value = if (current.base == null) {
            current.copy(base = bitmap, key = key)
        } else {
            current.copy(incoming = bitmap, pendingKey = key)
        }
    }

    fun onAvatarLanded() {
        _avatar.value = _avatar.value.let { state ->
            state.copy(
                base = state.incoming ?: state.base,
                key = state.pendingKey.ifBlank { state.key },
                incoming = null,
                pendingKey = "",
            )
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                // عند الفشل نعيد المحاولة أسرع بدل انتظار الدورة الكاملة.
                val result = repository.refresh()
                delay(if (result.isSuccess) REFRESH_INTERVAL_MS else RETRY_INTERVAL_MS)
            }
        }
    }

    /** تحديث فوري عند رجوع التطبيق للواجهة (فاصل ثانية واحدة فقط لمنع التكرار). */
    fun onForeground() {
        // نعيد تفعيل الطلبات عند كل عودة للتطبيق حتى لو رفض Graph التوكن سابقاً.
        repository.reset()
        viewModelScope.launch { repository.refresh(minGapMs = FOREGROUND_MIN_GAP_MS) }
    }

    companion object {
        // تحديث شبه لحظي: الدورات كل 5 ثوانٍ بدل 30، والإعادة عند الفشل فوراً تقريباً.
        const val REFRESH_INTERVAL_MS = 5_000L
        const val RETRY_INTERVAL_MS = 5_000L
        const val FOREGROUND_MIN_GAP_MS = 1_000L
    }
}
