package com.heirloom.app.data

import android.content.Context
import android.net.Uri
import com.heirloom.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The result we expect back from the Worker. Identity warning is decided
 * server-side based on AdaFace cosine similarity — the client just renders
 * the warning if the flag is set.
 */
data class RestoreResult(
    val restoredUrl: String,
    val cosineSimilarity: Double,
    val identityWarning: Boolean,
    val wasColorized: Boolean,
)

object RestoreApi {
    // Pipeline can take 60-120s end to end on Replicate cold starts. Generous timeout.
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun restore(context: Context, source: Uri, idToken: String?): RestoreResult =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
                ?: error("Could not read picked image")

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    "input.jpg",
                    bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()),
                )
                .build()

            val request = Request.Builder()
                .url("${BuildConfig.WORKER_BASE_URL}/restore")
                .apply { idToken?.let { header("Authorization", "Bearer $it") } }
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Worker returned ${response.code}: $text")
                }
                val json = JSONObject(text)
                RestoreResult(
                    restoredUrl = json.getString("restored_url"),
                    cosineSimilarity = json.optDouble("cosine_similarity", 1.0),
                    identityWarning = json.optBoolean("identity_warning", false),
                    wasColorized = json.optBoolean("was_colorized", false),
                )
            }
        }
}
