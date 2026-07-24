package com.heirloom.app.billing

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * Anonymous, app-scoped device identity for the three-restoration trial.
 *
 * Android scopes ANDROID_ID to this device, user, and app signing key. Hashing
 * it keeps the raw platform value off the network while surviving app-data
 * clearing and normal reinstalls signed with the same key.
 */
object ClientIdentity {
    fun get(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        return sha256("${context.packageName}:$androidId")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
