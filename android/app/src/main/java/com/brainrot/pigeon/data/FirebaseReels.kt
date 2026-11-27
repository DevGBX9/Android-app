package com.brainrot.pigeon.data

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val LOG_TAG = "PigeonReels"
private const val STREAM_RETRY_MS = 2_000L
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * قائمة الريلز المجدولة في Firebase Realtime Database عبر REST مباشرة،
 * بدون SDK وبدون google-services.json: الشكل هو
 * `<db>/reels/<id>.json` بمفاتيح مسطّحة.
 *
 * كل السجلات تحمل [RemoteReel.registered] لتوضيح أنها مسجلة في التطبيق.
 */
class FirebaseReels(
    private val client: OkHttpClient,
    databaseUrl: String,
) {

    private val root = databaseUrl.trim().trimEnd('/')
    private val endpoint = "$root/reels"
    private val schedulesEndpoint = "$root/schedules"

    // بث حيّ بلا مهلة قراءة: الاتصال يبقى مفتوحاً حتى يقطعه الخادم أو التطبيق.
    private val streamClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * يرفع سجلاً واحداً. نستخدم PATCH لا PUT حتى لا يمسح حقول الأرشيف
     * التي يكتبها Worker على Cloudflare تحت نفس السجل.
     */
    suspend fun upload(item: SavedItem): Boolean = withContext(Dispatchers.IO) {
        val url = "$endpoint/${item.id}.json".toHttpUrlOrNull() ?: return@withContext false
        val body = json.encodeToString(RemoteReel.from(item)).toRequestBody(JSON_MEDIA)

        runCatching {
            client.newCall(Request.Builder().url(url).patch(body).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(LOG_TAG, "upload HTTP ${response.code}")
                }
                response.isSuccessful
            }
        }.getOrElse { error ->
            Log.w(LOG_TAG, "upload failed: ${error.message}")
            false
        }
    }

    /** يسحب كل المواعيد؛ عقدة فاضية تعني خريطة فاضية وليست خطأ. */
    suspend fun fetchSchedules(): Result<Map<String, PublishSchedule>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$schedulesEndpoint/.json".toHttpUrlOrNull()
                    ?: error("bad database url")

                client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .build(),
                ).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("firebase HTTP ${response.code}")
                    if (body.isBlank() || body.trim() == "null") {
                        return@use emptyMap<String, PublishSchedule>()
                    }

                    val records = json.decodeFromString<Map<String, RemoteSchedule>>(body)
                    records.mapValues { (_, record) -> record.toSchedule() }
                }
            }.onFailure { error -> Log.w(LOG_TAG, "schedules fetch failed: ${error.message}") }
        }

    /** يسحب كل الريلز؛ القيمة `null` تعني قائمة فاضية وليست خطأ. */
    suspend fun fetchAll(): Result<List<SavedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$endpoint/.json".toHttpUrlOrNull() ?: error("bad database url")

            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build(),
            ).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("firebase HTTP ${response.code}")
                if (body.isBlank() || body.trim() == "null") {
                    return@use emptyList<SavedItem>()
                }

                val records = json.decodeFromString<Map<String, RemoteReel>>(body)
                records.mapNotNull { (id, record) -> record.toSavedItem(id) }
            }
        }.onFailure { error -> Log.w(LOG_TAG, "fetch failed: ${error.message}") }
    }

    /**
     * بث حيّ لعقدة القاعدة كاملة (Server-Sent Events): كل ما يكتبه الـWorker
     * — نشر ريل، تغيّر حالته، أو حذفه بعد النشر — يصل هنا لحظياً بدل انتظار
     * دورة مزامنة. لا ترجع الدالة أبداً، وتعيد الاتصال بعد أي انقطاع.
     */
    suspend fun streamChanges(onChange: suspend () -> Unit) = withContext(Dispatchers.IO) {
        val url = "$root/.json"
        while (currentCoroutineContext().isActive) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "text/event-stream")
                    .build()

                streamClient.newCall(request).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) {
                        error("live stream HTTP ${response.code}")
                    }

                    val source = body.source()
                    var event = ""
                    while (currentCoroutineContext().isActive) {
                        // readUtf8Line يرمي عند قطع الخادم للاتصال، فنعيد الفتح.
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.startsWith("event:") ->
                                event = line.removePrefix("event:").trim()

                            line.startsWith("data:") -> {
                                // keep-alive لا يحمل تغييراً، وput/patch يحملان.
                                if (event == "put" || event == "patch") onChange()
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(LOG_TAG, "live stream lost: ${error.message}")
            }

            if (currentCoroutineContext().isActive) delay(STREAM_RETRY_MS)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class RemoteReel(
    val platform: String = "instagram",
    val kind: String = "reel",
    val url: String? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val registered: Boolean = true,
    val source: String = "android",
    // لا يُرسل أبداً: PATCH بقيمة null في Firebase يمسح حالة الأرشيف التي يكتبها
    // الـWorker، وهذا يجعل شريط التقدّم يعود إلى 0% ولا يصل "ready" أبداً.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val archive: ArchiveStatus? = null,
    val createdAt: Long = 0L,
) {
    fun toSavedItem(id: String): SavedItem? {
        val link = url?.takeIf { it.isNotBlank() } ?: return null
        return SavedItem(
            id = id,
            platform = platform,
            kind = kind,
            url = link,
            title = title,
            thumbnail = thumbnail?.takeIf { it.isNotBlank() },
            registered = registered,
            archiveState = archive?.state,
            archiveProgress = archive?.progress ?: 0,
            downloadUrl = archive?.url?.takeIf { it.isNotBlank() },
            createdAt = if (createdAt > 0L) createdAt else System.currentTimeMillis(),
        )
    }

    companion object {
        fun from(item: SavedItem) = RemoteReel(
            platform = item.platform,
            kind = item.kind,
            url = item.url,
            title = item.title,
            thumbnail = item.thumbnail,
            registered = true,
            source = "android",
            createdAt = if (item.createdAt > 0L) item.createdAt else System.currentTimeMillis(),
        )
    }
}

/**
 * شكل عقدة `/schedules/<id>` في Firebase كما يقرأها التطبيق. الرفع لا يستعمل
 * هذا الصنف بل JSON مصغّر (`at` و`timezone` فقط)، لأن حقول الحالة ملك الـWorker.
 */
@Serializable
private data class RemoteSchedule(
    val at: Long = 0L,
    val timezone: String = ScheduleSlots.TIMEZONE,
    val state: String = ScheduleSlots.STATE_SCHEDULED,
    val error: String? = null,
    val attempts: Int = 0,
    val containerId: String? = null,
    val mediaId: String? = null,
    val publishedAt: Long = 0L,
) {
    fun toSchedule() = PublishSchedule(
        at = at,
        timezone = timezone,
        state = state,
        error = error,
        attempts = attempts,
        containerId = containerId,
        mediaId = mediaId,
        publishedAt = publishedAt,
    )
}
