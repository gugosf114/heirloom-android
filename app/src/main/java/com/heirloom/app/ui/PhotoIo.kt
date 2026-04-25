package com.heirloom.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Helpers for saving the restored image to the user's gallery and sharing it.
 *
 * Save path differs by API level:
 *   - API 29+ uses MediaStore with RELATIVE_PATH (no permission needed for own dir).
 *   - API 26-28 falls back to writing into the app-private external dir, then
 *     inserting a MediaStore record so it shows up in the gallery.
 *
 * Share uses FileProvider against a cache copy — content:// URIs survive
 * cross-process grants, file:// URIs do not (FileUriExposedException on N+).
 */
object PhotoIo {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Download failed: ${res.code}")
            res.body?.bytes() ?: error("Empty body")
        }
    }

    suspend fun saveToGallery(context: Context, url: String): Uri = withContext(Dispatchers.IO) {
        val bytes = download(url)
        val name = "heirloom_${System.currentTimeMillis()}.jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Heirloom")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Heirloom")
            dir.mkdirs()
            val file = File(dir, name)
            file.writeBytes(bytes)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
        }
    }

    suspend fun share(context: Context, url: String) = withContext(Dispatchers.IO) {
        val bytes = download(url)
        val cacheDir = File(context.cacheDir, "restored").apply { mkdirs() }
        val file = File(cacheDir, "heirloom_${System.currentTimeMillis()}.jpg")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share restored photo").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
