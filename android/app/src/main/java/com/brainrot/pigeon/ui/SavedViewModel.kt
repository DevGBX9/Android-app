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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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

/** العلامة التي تظهر على الخانة قبل اختفائها من الشبكة. */
enum class ReelExitKind { Published, Deleted }

private data class ExitMark(val item: SavedItem, val kind: ReelExitKind)

/** مدة بقاء علامة النشر/الحذف على الخانة قبل أن تختفي. */
private const val EXIT_HOLD_MS = 1_150L

/**
 * يعرض قائمة الريلز المجدولة؛ المزامنة مع Firebase تحدث عند فتح التطبيق،
 * وتُعاد كل 1.5 ثانية ما دام فيه ريل قيد المعالجة على الخادم.
 */
class SavedViewModel(private val repository: SavedRepository) : ViewModel() {

    /** القائمة الحيّة: الريل المنشور يغادرها فوراً بعد وصول البث الحيّ. */
    private val live: StateFlow<List<SavedItem>> = repository.items.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** خانات تظهر عليها علامة خروج مؤقتاً قبل أن تختفي. */
    private val marks = MutableStateFlow<Map<String, ExitMark>>(emptyMap())

    /**
     * ما تعرضه الشبكة: القائمة الحيّة مع إبقاء الريل المعلَّم ظاهراً حتى
     * ينتهي أنيميشن الصح أو الحذف، فلا تختفي الخانة قبل ظهور العلامة.
     */
    val items: StateFlow<List<SavedItem>> = combine(live, marks) { current, exits ->
        val liveIds = current.mapTo(HashSet(current.size)) { it.id }
        val held = exits.values.mapNotNull { mark ->
            mark.item.takeIf { it.id !in liveIds }
        }
        (current + held).sortedByDescending { it.createdAt }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** معرّف كل خانة خارجة ونوع علامتها. */
    val exits: StateFlow<Map<String, ReelExitKind>> = marks
        .map { marked -> marked.mapValues { (_, mark) -> mark.kind } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap(),
        )

    private val _downloads = MutableStateFlow<Map<String, DownloadUi>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadUi>> = _downloads.asStateFlow()

    /** رسالة فشل حذف ريل (تُعرض مرة واحدة ثم تُمسح). */
    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    /** رسالة فشل تغيير الموعد (تُعرض مرة واحدة ثم تُمسح). */
    private val _scheduleError = MutableStateFlow<String?>(null)
    val scheduleError: StateFlow<String?> = _scheduleError.asStateFlow()

    init {
        // قبل المزامنة: نلتقط حدث النشر ونعرض عليه علامة الصح.
        viewModelScope.launch {
            repository.publishedExits.collect { item ->
                if (marks.value.containsKey(item.id)) return@collect
                marks.update { it + (item.id to ExitMark(item, ReelExitKind.Published)) }
                launch {
                    delay(EXIT_HOLD_MS)
                    marks.update { it - item.id }
                }
            }
        }
        viewModelScope.launch {
            repository.ensureThumbnails()
            repository.syncFromRemote()
        }
        // متابعة حيّة: الريل يختفي من الشاشة لحظة حذف الـWorker له بعد النشر.
        viewModelScope.launch { repository.observeRemote() }
        pollArchives()
    }

    /**
     * يحذف الريل من التطبيق ومن Firebase وR2. تظهر علامة السلة الحمراء
     * مباشرة، وتبقى حتى ينتهي الطلب ومدّة الأنيميشن معاً ثم تختفي الخانة.
     * لو فشل الحذف تُرفع العلامة ويبقى الريل في مكانه.
     */
    fun delete(item: SavedItem) {
        if (marks.value.containsKey(item.id)) return
        marks.update { it + (item.id to ExitMark(item, ReelExitKind.Deleted)) }

        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val error = repository.deleteReel(item).exceptionOrNull()
            if (error != null) {
                marks.update { it - item.id }
                _deleteError.value = error.message ?: "delete failed"
                return@launch
            }

            val remaining = EXIT_HOLD_MS - (System.currentTimeMillis() - startedAt)
            if (remaining > 0L) delay(remaining)
            marks.update { it - item.id }
        }
    }

    fun dismissDeleteError() {
        _deleteError.value = null
    }

    /** يغيّر موعد النشر من قائمة الضغطة المطوّلة. */
    fun reschedule(item: SavedItem, at: Long) {
        viewModelScope.launch {
            repository.reschedule(item, at).onFailure { error ->
                _scheduleError.value = error.message ?: "could not change the date"
            }
        }
    }

    fun dismissScheduleError() {
        _scheduleError.value = null
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
                val processing = live.value.any { it.isProcessing }
                // نزامن دائماً: الريل المنشور يختفي من القائمة والقاعدة بلا حاجة
                // لإغلاق التطبيق وفتحه من جديد.
                repository.syncFromRemote()
                // احتياط فقط لو انقطع البث الحيّ؛ التحديث اللحظي يأتي منه.
                delay(if (processing) 1_500 else 30_000)
            }
        }
    }
}

private val SavedItem.isProcessing: Boolean
    get() = archiveState == "queued" || archiveState == "downloading" || archiveState == "storing"
