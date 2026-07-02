package com.heirloom.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * The result we expect back from the Worker. Identity warning is decided
 * server-side based on AdaFace cosine similarity — the client just renders
 * the warning if the flag is set.
 */
data class RestoreResult(
    val restoredUrl: String,
    /** Null when the worker skipped or couldn't run the identity check. */
    val cosineSimilarity: Double?,
    val identityWarning: Boolean,
    val wasColorized: Boolean,
    val identityUnverified: Boolean,
)

object RestoreApi {
    // Worker's own pipeline budget is 480s (replicate.ts POLL_TIMEOUT_MS);
    // reading for less than that shows the user a failure while the worker
    // finishes (and bills) anyway.
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(490, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Worker rejects uploads over 5MB; scanner JPEGs of physical photos can
    // exceed that. Downscale/re-encode only when needed so the common path
    // uploads the original bytes untouched.
    private const val MAX_DIMENSION = 2048
    private const val MAX_UPLOAD_BYTES = 4 * 1024 * 1024

    suspend fun restore(context: Context, source: Uri, idToken: String?): RestoreResult =
        withContext(Dispatchers.IO) {
            val raw = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
                ?: error("Could not read picked image")
            val bytes = if (raw.size > MAX_UPLOAD_BYTES) downscaleToJpeg(raw) else raw

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
                .apply {
                    idToken?.let { header("Authorization", "Bearer $it") }
                    if (BuildConfig.APP_SHARED_SECRET.isNotEmpty()) {
                        header("X-App-Key", BuildConfig.APP_SHARED_SECRET)
                    }
                }
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Worker returned ${response.code}: $text")
                }
                val json = JSONObject(text)
                val cosine = if (json.isNull("cosine_similarity")) null else json.optDouble("cosine_similarity")
                val skipped = json.optBoolean("adaface_skipped", false)
                RestoreResult(
                    restoredUrl = json.getString("restored_url"),
                    cosineSimilarity = cosine,
                    identityWarning = json.optBoolean("identity_warning", false),
                    wasColorized = json.optBoolean("was_colorized", false),
                    identityUnverified = skipped || cosine == null,
                )
            }
        }

    private fun downscaleToJpeg(raw: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DIMENSION || bounds.outHeight / (sample * 2) >= MAX_DIMENSION) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            ?: error("Could not decode picked image")
        val out = ByteArrayOutputStream()
        var quality = 92
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        while (out.size() > MAX_UPLOAD_BYTES && quality > 60) {
            quality -= 10
            out.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        bitmap.recycle()
        return out.toByteArray()
    }
}
