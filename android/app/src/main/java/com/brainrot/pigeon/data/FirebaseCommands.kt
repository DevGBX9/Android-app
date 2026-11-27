package com.brainrot.pigeon.data

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val COMMAND_MEDIA = "application/json; charset=utf-8".toMediaType()

/** أقصى انتظار لنتيجة أمر ينفّذه الـWorker في دورته (كل دقيقة). */
const val COMMAND_TIMEOUT_MS = 150_000L
const val PUBLISH_COMMAND_TIMEOUT_MS = 240_000L

/** نتيجة أمر نفّذه الخادم: نجاح مع جسم النتيجة، أو رفض مع سبب. */
data class CommandOutcome(
    val ok: Boolean,
    val result: JsonObject?,
    val error: String?,
)

/**
 * قناة الأوامر عبر Firebase Realtime Database.
 *
 * السبب: كثير من الشبكات تحجب نطاق `*.workers.dev` أو تُسقط الاتصال به لفترات،
 * فيصل سجل الريل إلى Firebase ويضيع طلب الأرشفة/الجدولة/الحذف/النشر بلا أثر.
 * هنا يكتب التطبيق الأمر في `commands/<id>`، والـWorker يقرأه في دورته
 * وينفّذه بنفس الكود المختبَر، ثم يكتب `state: done|failed` و`result`.
 * القرار والتنفيذ يبقيان على الخادم وحده؛ Firebase ناقل فقط.
 */
class FirebaseCommands(private val client: OkHttpClient, databaseUrl: String) {

    private val root = databaseUrl.trim().trimEnd('/')
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val configured: Boolean get() = root.isNotBlank()

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun node(path: String) = "$root/$path.json".toHttpUrlOrNull()

    /**
     * يكتب أمراً جديداً. يعيد معرّفه إذا وصل (200/204)، أو null إذا فشل الوصول.
     */
    suspend fun enqueue(
        type: String,
        reelId: String?,
        extra: JsonObject = JsonObject(emptyMap()),
    ): String? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val url = node("commands/${newId()}") ?: return@withContext null
        val body = buildJsonObject {
            put("type", type)
            if (reelId != null) put("reelId", reelId)
            extra.forEach { (key, value) -> put(key, value) }
            put("state", "pending")
            put("createdAt", System.currentTimeMillis())
            put("by", "android")
        }

        runCatching {
            client.newCall(
                Request.Builder().url(url).put(body.toString().toRequestBody(COMMAND_MEDIA)).build(),
            ).execute().use { response -> if (response.isSuccessful) url else null }
        }.getOrNull()?.toString()
    }

    /**
     * ينتظر نتيجة الأمر الذي كتبه [enqueueId] (معرّف كامل للرابط) حتى ينفّذه
     * الخادم. يعيد null إذا لم يصل شيء قبل انتهاء المهلة.
     */
    suspend fun awaitCommand(enqueueId: String, timeoutMs: Long): CommandOutcome? =
        withContext(Dispatchers.IO) {
            if (!configured) return@withContext null
            val url = enqueueId.toHttpUrlOrNull() ?: node("commands/$enqueueId")
                ?: return@withContext null
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                val text = runCatching {
                    client.newCall(
                        Request.Builder().url(url).header("Accept", "application/json").build(),
                    ).execute().use { response ->
                        if (response.isSuccessful) response.body?.string().orEmpty() else ""
                    }
                }.getOrDefault("")

                if (text.isNotBlank() && text.trim() != "null") {
                    val record = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    val state = record?.string("state").orEmpty()
                    if (state == "done" || state == "failed") {
                        val ok = record?.string("ok") == "true" && state == "done"
                        val result = record?.get("result") as? JsonObject
                        val error = record?.string("error")?.takeIf { it.isNotBlank() }
                        // الأمر انتهى: نمسحه من القاعدة فوراً.
                        runCatching {
                            client.newCall(Request.Builder().url(url).delete().build())
                                .execute().close()
                        }
                        return@withContext CommandOutcome(ok, result, error)
                    }
                }

                delay(2_000)
            }
            null
        }

    /** يكتب الأمر وينتظر نتيجته في نداء واحد. */
    suspend fun run(
        type: String,
        reelId: String?,
        extra: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
    ): CommandOutcome? {
        val id = enqueue(type, reelId, extra) ?: return null
        return awaitCommand(id, timeoutMs)
    }

    /**
     * قراءة حالة الأرشيف من Firebase مباشرة: مسار بديل عندما لا يصل التطبيق
     * إلى الـWorker. الخادم هو من كتب هذه الحقول أثناء الأرشفة.
     */
    suspend fun reelArchive(id: String): ArchiveStatus? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val url = node("reels/$id/archive") ?: return@withContext null
        val text = runCatching {
            client.newCall(
                Request.Builder().url(url).header("Accept", "application/json").build(),
            ).execute().use { response ->
                if (response.isSuccessful) response.body?.string().orEmpty() else ""
            }
        }.getOrDefault("")
        if (text.isBlank() || text.trim() == "null") return@withContext null

        val record = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return@withContext null
        ArchiveStatus(
            state = record.string("state").ifBlank { "unknown" },
            progress = record.int("progress").coerceIn(0, 100),
            url = record.string("url").takeIf { it.isNotBlank() },
            bytes = record.long("bytes"),
            error = record.string("error").takeIf { it.isNotBlank() },
        )
    }

    /**
     * لقطة العدّ التنازلي من Firebase: نفس بيانات الخادم من `schedules` و`reels`
     * عندما لا يصل طلب HTTP. الوقت المتبقي يُحسب بساعة الهاتف هنا فقط لأن
     * ساعة الخادم غير متاحة، وكل شيء آخر (المواعيد والحالات) من الخادم.
     */
    suspend fun snapshot(): ScheduleSnapshot? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val schedulesUrl = node("schedules") ?: return@withContext null
        val reelsUrl = node("reels") ?: return@withContext null

        suspend fun fetch(url: okhttp3.HttpUrl): JsonObject? {
            val text = runCatching {
                client.newCall(
                    Request.Builder().url(url).header("Accept", "application/json").build(),
                ).execute().use { response ->
                    if (response.isSuccessful) response.body?.string().orEmpty() else ""
                }
            }.getOrDefault("")
            if (text.isBlank() || text.trim() == "null") return JsonObject(emptyMap())
            return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        }

        val schedules = fetch(schedulesUrl) ?: return@withContext null
        val reels = fetch(reelsUrl) ?: JsonObject(emptyMap())
        if (schedules.isEmpty()) {
            return@withContext ScheduleSnapshot(
                ok = true,
                serverTime = System.currentTimeMillis(),
                reels = emptyMap(),
            )
        }

        val now = System.currentTimeMillis()
        val parsed = schedules.mapNotNull { (id, element) ->
            val record = element as? JsonObject ?: return@mapNotNull null
            val at = record.long("at")
            if (at <= 0L) return@mapNotNull null
            val state = record.string("state").ifBlank { ScheduleSlots.STATE_SCHEDULED }
            val reel = reels[id] as? JsonObject
            val archive = reel?.get("archive") as? JsonObject
            val archiveState = archive?.string("state").orEmpty().ifBlank { null }
            val ready = archiveState == "ready"
            val gone = state == ScheduleSlots.STATE_PUBLISHED ||
                state == ScheduleSlots.STATE_CLEANING ||
                state == ScheduleSlots.STATE_VERIFYING
            val actions = when {
                gone -> emptyList()
                state == ScheduleSlots.STATE_UNVERIFIED -> listOf("delete")
                else -> buildList {
                    if (ready) add("download")
                    add("publish_now")
                    add("reschedule")
                    add("delete")
                }
            }
            id to ServerSchedule(
                at = at,
                state = state,
                remainingMs = (at - now).coerceAtLeast(0L),
                error = record.string("error").takeIf { it.isNotBlank() },
                publishedAt = record.long("publishedAt"),
                mediaId = record.string("mediaId").takeIf { it.isNotBlank() },
                archiveState = archiveState,
                archiveProgress = archive?.int("progress") ?: 0,
                downloadable = "download" in actions,
                publishable = "publish_now" in actions,
                allowedActions = actions,
            )
        }

        // ترتيب النشر: 1 = الريل القادم، بنفس قاعدة الخادم.
        val ordered = parsed.sortedBy { it.second.at }
        val withOrder = ordered.mapIndexed { index, pair ->
            pair.first to pair.second.copy(order = index + 1)
        }.toMap()

        val publishing = withOrder.entries.firstOrNull {
            it.value.state in setOf(
                ScheduleSlots.STATE_PUBLISHING,
                ScheduleSlots.STATE_VERIFYING,
                ScheduleSlots.STATE_CLEANING,
            )
        }

        ScheduleSnapshot(
            ok = true,
            serverTime = now,
            reels = withOrder,
            publishing = publishing?.let { ServerPublishing(id = it.key, state = it.value.state) },
        )
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull
            ?: (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: 0L
}
