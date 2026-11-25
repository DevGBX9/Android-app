package com.brainrot.pigeon.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brainrot.pigeon.data.PublishSchedule
import com.brainrot.pigeon.data.SavedItem
import com.brainrot.pigeon.data.SavedRepository
import com.brainrot.pigeon.data.ServerSchedule
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

// العدّ التنازلي وحالة النشر من الخادم وحده: نقرأ لقطة `/schedules` كل خمس
// ثوانٍ، ونحرّك الساعة المعروضة من نفس نقطة وقت الخادم بين لقطة وأخرى.
private const val SCHEDULE_POLL_MS = 5_000L

/** نقطة ساعة الخادم: وقته لحظة القراءة، وما مضى عليه بساعة الجهاز المستقرة. */
private data class ClockAnchor(val serverTime: Long, val elapsed: Long)

private fun ServerSchedule.toSchedule() = PublishSchedule(
    at = at,
    state = state,
    error = error,
    publishedAt = publishedAt,
    mediaId = mediaId,
)

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

    /** مواعيد الخادم وحالاتها كما وصلت في آخر لقطة ناجحة. */
    private val serverSchedules = MutableStateFlow<Map<String, ServerSchedule>>(emptyMap())

    /**
     * ما تعرضه الشبكة: القائمة الحيّة مع إبقاء الريل المعلَّم ظاهراً حتى
     * ينتهي أنيميشن الصح أو الحذف، ومع الموعد والحالة اللذين يقررهما الخادم
     * بدل النسخة المحلية.
     */
    val items: StateFlow<List<SavedItem>> =
        combine(live, marks, serverSchedules) { current, exits, schedules ->
            val liveIds = current.mapTo(HashSet(current.size)) { it.id }
            val held = exits.values.mapNotNull { mark ->
                mark.item.takeIf { it.id !in liveIds }
            }
            (current + held)
                .map { item ->
                    val fromServer = schedules[item.id]?.takeIf { it.at > 0L }
                    if (fromServer == null) item else item.copy(schedule = fromServer.toSchedule())
                }
                .sortedByDescending { it.createdAt }
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

    /** آخر نقطة ساعة من الخادم؛ كل العرض يُحسب منها. */
    private val clock = MutableStateFlow(
        ClockAnchor(System.currentTimeMillis(), SystemClock.elapsedRealtime()),
    )

    /** ساعة الخادم المعروضة، تتقدّم كل ثانية من نقطة الخادم نفسها. */
    private val _serverNow = MutableStateFlow(System.currentTimeMillis())
    val serverNow: StateFlow<Long> = _serverNow.asStateFlow()

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
        // لقطة العدّ التنازلي من الخادم: الوقت المتبقي والحالة لكل ريل.
        viewModelScope.launch {
            while (isActive) {
                refreshSchedules()
                delay(SCHEDULE_POLL_MS)
            }
        }
        // ساعة العرض تنطلق من وقت الخادم ولا تتقدّم إلا بساعة الجهاز المستقرة،
        // فلا يهم ضبط ساعة الهاتف ولا يوجد عدّاد ثانٍ يخالف الخادم.
        viewModelScope.launch {
            while (isActive) {
                val anchor = clock.value
                _serverNow.value =
                    anchor.serverTime + (SystemClock.elapsedRealtime() - anchor.elapsed)
                delay(1_000L)
            }
        }
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

    /** يغيّر موعد النشر على الخادم من قائمة الضغطة المطوّلة. */
    fun reschedule(item: SavedItem, at: Long) {
        viewModelScope.launch {
            repository.reschedule(item, at).onFailure { error ->
                _scheduleError.value = error.message ?: "could not change the date"
            }
            // الموعد الجديد والوقت المتبقي يصلان من الخادم بعد الكتابة.
            refreshSchedules()
        }
    }

    fun dismissScheduleError() {
        _scheduleError.value = null
    }

    /**
     * يقرأ لقطة العدّ التنازلي من الخادم ويثبّت ساعة العرض عليها. الفشل لا
     * يمسح آخر لقطة: يبقى العدّاد يتقدّم من آخر نقطة صحيحة حتى تعود الشبكة.
     */
    private suspend fun refreshSchedules() {
        val snapshot = repository.scheduleSnapshot() ?: return
        if (snapshot.serverTime <= 0L) return
        clock.value = ClockAnchor(snapshot.serverTime, SystemClock.elapsedRealtime())
        serverSchedules.value = snapshot.reels
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
