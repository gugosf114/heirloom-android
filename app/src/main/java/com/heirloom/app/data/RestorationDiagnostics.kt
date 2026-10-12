package com.heirloom.app.data

import android.content.Context
import java.io.File

/** Bounded stage/failure history. No photos, URIs, keys, or raw exception messages. */
internal object RestorationDiagnostics {
    @Synchronized
    fun record(context: Context, event: String) {
        runCatching {
            val file = File(context.filesDir, "restoration.log")
            if (file.exists() && file.length() > 64 * 1024) {
                file.writeText(file.readLines().takeLast(100).joinToString("\n", postfix = "\n"))
            }
            file.appendText("${System.currentTimeMillis()} ${event.take(200)}\n")
        }
    }
}
