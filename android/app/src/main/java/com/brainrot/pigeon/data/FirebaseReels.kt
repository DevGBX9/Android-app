package com.brainrot.pigeon.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val LOG_TAG = "PigeonReels"
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

    private val endpoint = databaseUrl.trim().trimEnd('/') + "/reels"
    private val schedulesEndpoint = databaseUrl.trim().trimEnd('/') + "/schedules"

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

    /**
     * يرفع موعد النشر إلى `/schedules/<id>` (نفس معرّف الريل). نفصله عن سجل
     * الريل حتى لا يمسح PATCH حقول الحالة التي يكتبها الـWorker لاحقاً.
     */
    suspend fun uploadSchedule(id: String, schedule: PublishSchedule): Boolean =
        withContext(Dispatchers.IO) {
            val url = "$schedulesEndpoint/$id.json".toHttpUrlOrNull()
                ?: return@withContext false
            // نرسل الوقت والتوقيت فقط: `state` و`attempts` و`containerId` ملك
            // الـWorker، وأي PATCH بها من التطبيق قد يعيد ريلاً منشوراً للجدولة.
            val body = buildJsonObject {
                put("at", schedule.at)
                put("timezone", schedule.timezone)
            }.toString().toRequestBody(JSON_MEDIA)

            runCatching {
                client.newCall(Request.Builder().url(url).patch(body).build()).execute().use {
                    if (!it.isSuccessful) Log.w(LOG_TAG, "schedule HTTP ${it.code}")
                    it.isSuccessful
                }
            }.getOrElse { error ->
                Log.w(LOG_TAG, "schedule upload failed: ${error.message}")
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

    /** يقرأ حالة الأرشيف لسجل واحد من نفس العقدة التي يكتبها الـWorker. */
    suspend fun fetchArchive(id: String): ArchiveStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$endpoint/$id/archive.json".toHttpUrlOrNull() ?: return@runCatching null

            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank() || body.trim() == "null") {
                    return@use null
                }
                json.decodeFromString<ArchiveStatus>(body)
            }
        }.getOrNull()
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

    /** يحذف سجل الريل من القاعدة بعد نجاح نشره على إنستغرام. */
    suspend fun deleteReel(id: String): Boolean = delete("$endpoint/$id.json", id)

    /** يحذف موعد الريل مع سجله حتى لا يعيد الـWorker معالجته. */
    suspend fun deleteSchedule(id: String): Boolean =
        delete("$schedulesEndpoint/$id.json", id)

    private suspend fun delete(url: String, id: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = url.toHttpUrlOrNull() ?: return@withContext false
                client.newCall(
                    Request.Builder().url(target).delete().build(),
                ).execute().use { response ->
                    if (!response.isSuccessful) Log.w(LOG_TAG, "delete $id HTTP ${response.code}")
                    response.isSuccessful
                }
            }.getOrElse { error ->
                Log.w(LOG_TAG, "delete $id failed: ${error.message}")
                false
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
