package com.heirloom.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.heirloom.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import java.util.concurrent.TimeUnit

data class RestoreResult(
    val restoredUrl: String,
    val cosineSimilarity: Double?,
    val identityWarning: Boolean,
    val wasColorized: Boolean,
    val identityUnverified: Boolean,
)

data class ResultReport(
    val reason: String,
    val details: String,
    val cosineSimilarity: Double?,
    val identityWarning: Boolean,
    val identityUnverified: Boolean,
    val wasColorized: Boolean,
)

class RestoreHttpException(val statusCode: Int) : IOException("restore failed: $statusCode")
class PipelineReportedException : IOException("pipeline reported a failure")

object RestoreApi {
    private val client = buildRestoreHttpClient()

    private const val DATA_URL_PREFIX = "data:image/jpeg;base64,"

    suspend fun restore(
        context: Context,
        source: Uri,
        onEvent: (PipelineEvent) -> Unit,
    ): RestoreResult = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
            ?: error("Could not read selected image")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            error("Could not decode selected image")
        }
        val bytes = if (
            needsUploadTranscode(bounds.outWidth, bounds.outHeight, raw.size)
        ) {
            downscaleToJpeg(raw, bounds.outWidth, bounds.outHeight)
        } else {
            raw
        }
        if (bytes.size > MAX_UPLOAD_BYTES) error("Selected image is too large")

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                "input.jpg",
                bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()),
            )
            .build()

        val request = Request.Builder()
            .url("${BuildConfig.WORKER_BASE_URL}/restore-stream")
            .apply { addAppKey() }
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RestoreHttpException(response.code)
            val responseBody = response.body ?: error("Restoration returned no data")
            var final: PipelineFinal? = null
            responseBody.source().use { sourceBuffer ->
                while (!sourceBuffer.exhausted()) {
                    val line = sourceBuffer.readUtf8Line()?.trim().orEmpty()
                    if (line.isEmpty()) continue
                    when (val event = parsePipelineEvent(line)) {
                        is PipelineEvent.Final -> {
                            final = event.result
                            onEvent(event)
                        }
                        is PipelineEvent.Error -> {
                            onEvent(event)
                            throw PipelineReportedException()
                        }
                        else -> onEvent(event)
                    }
                }
            }

            val result = final ?: error("Restoration ended before a result arrived")
            val localUrl = persistRestoredImage(context, result.restoredUrl)
            RestoreResult(
                restoredUrl = localUrl,
                cosineSimilarity = result.cosineSimilarity,
                identityWarning = result.identityWarning,
                wasColorized = result.wasColorized,
                identityUnverified = result.identityUnverified,
            )
        }
    }

    suspend fun report(report: ResultReport) = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("reason", report.reason)
            .put("details", report.details)
            .put("cosine_similarity", report.cosineSimilarity ?: JSONObject.NULL)
            .put("identity_warning", report.identityWarning)
            .put("identity_unverified", report.identityUnverified)
            .put("was_colorized", report.wasColorized)
            .put("app_version", BuildConfig.VERSION_NAME)

        val request = Request.Builder()
            .url("${BuildConfig.WORKER_BASE_URL}/report")
            .apply { addAppKey() }
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RestoreHttpException(response.code)
        }
    }

    private fun Request.Builder.addAppKey() {
        if (BuildConfig.APP_SHARED_SECRET.isNotEmpty()) {
            header("X-App-Key", BuildConfig.APP_SHARED_SECRET)
        }
    }

    private fun persistRestoredImage(context: Context, value: String): String {
        val bytes = when {
            value.startsWith(DATA_URL_PREFIX) ->
                Base64.decode(value.removePrefix(DATA_URL_PREFIX), Base64.DEFAULT)
            value.startsWith("https://") || value.startsWith("http://") -> {
                val request = Request.Builder().url(value).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw RestoreHttpException(response.code)
                    response.body?.bytes() ?: error("Restoration returned an empty image")
                }
            }
            else -> error("Restoration returned an unsupported image format")
        }

        val directory = File(context.cacheDir, "restored").apply { mkdirs() }
        val file = File(directory, "heirloom_${System.currentTimeMillis()}.jpg")
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    private fun downscaleToJpeg(raw: ByteArray, width: Int, height: Int): ByteArray {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = uploadDecodeSampleSize(width, height)
        }
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            ?: error("Could not decode selected image")
        val largest = maxOf(decoded.width, decoded.height)
        val bitmap = if (largest > MAX_UPLOAD_DIMENSION) {
            val scale = MAX_UPLOAD_DIMENSION.toFloat() / largest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true,
            ).also { decoded.recycle() }
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        var quality = 92
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        while (out.size() > MAX_UPLOAD_BYTES && quality > 50) {
            quality -= 10
            out.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        bitmap.recycle()
        return out.toByteArray()
    }
}

internal fun buildRestoreHttpClient(): OkHttpClient = OkHttpClient.Builder()
    // Cloudflare's long-lived HTTP/2 stream was reset on the real Android
    // device even though the Cloud Run job completed successfully. The same
    // production route is stable over HTTP/1.1.
    .protocols(listOf(Protocol.HTTP_1_1))
    // A restoration POST is expensive and not idempotent. OkHttp's default
    // connection retry replayed the same photo several times after a reset.
    .retryOnConnectionFailure(false)
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(620, TimeUnit.SECONDS)
    .writeTimeout(90, TimeUnit.SECONDS)
    .build()
