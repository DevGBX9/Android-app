package com.brainrot.pigeon.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * يرفع عنصراً محفوظاً إلى Cloudflare R2 عبر نقطة نهاية (Worker أو رابط موقّع).
 * لا توجد مفاتيح R2 داخل التطبيق: العنوان والتوكن الاختياري يجيان من الإعدادات،
 * والأفضل أن يكون الـ Worker هو من يحمل أسرار R2 ويولّد رابطاً موقّعاً.
 *
 * الرد المتوقع: JSON فيه { "url": "https://..." } أو نص يبدأ بـ http.
 */
class R2Uploader(private val client: OkHttpClient) {

    suspend fun upload(item: SavedItem, settings: AppSettings): Result<String> =
        withContext(Dispatchers.IO) {
            val endpoint = settings.r2UploadEndpoint.trim()
            if (endpoint.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("No R2 upload endpoint configured"),
                )
            }

            runCatching {
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("platform", item.platform)
                    .addFormDataPart("kind", item.kind)
                    .addFormDataPart("title", item.title.orEmpty())
                    .addFormDataPart("url", item.url.orEmpty())
                    .addFormDataPart("receivedAt", item.createdAt.toString())

                val file = item.filePath?.let(::File)?.takeIf { it.isFile }
                if (file != null) {
                    val mediaType = (item.mimeType ?: "application/octet-stream").toMediaType()
                    multipart.addFormDataPart(
                        "file",
                        item.fileName ?: file.name,
                        file.asRequestBody(mediaType),
                    )
                }

                val request = Request.Builder()
                    .url(endpoint)
                    .post(multipart.build())
                    .apply {
                        if (settings.r2Token.isNotBlank()) {
                            header("Authorization", "Bearer ${settings.r2Token}")
                        }
                    }
                    .build()

                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} ${text.take(180)}")
                    }

                    val fromJson = runCatching {
                        Json.parseToJsonElement(text)
                            .jsonObject["url"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                    }.getOrNull()

                    fromJson?.takeIf { it.isNotBlank() }
                        ?: text.trim().takeIf { it.startsWith("http") }
                        ?: throw IOException("Response did not contain a file URL")
                }
            }
        }
}
