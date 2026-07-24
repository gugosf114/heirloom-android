package com.heirloom.app.data

internal const val MAX_UPLOAD_DIMENSION = 1_024
internal const val MAX_UPLOAD_BYTES = 4 * 1_024 * 1_024

internal fun needsUploadTranscode(width: Int, height: Int, byteCount: Int): Boolean =
    byteCount > MAX_UPLOAD_BYTES ||
        width > MAX_UPLOAD_DIMENSION ||
        height > MAX_UPLOAD_DIMENSION

internal fun uploadDecodeSampleSize(width: Int, height: Int): Int {
    var sample = 1
    val largest = maxOf(width, height)
    while (largest / sample > MAX_UPLOAD_DIMENSION * 2) {
        sample *= 2
    }
    return sample
}
