package com.heirloom.app.billing

import android.content.Context

/**
 * Local, account-free free-tier counter. Clearing all App data also clears
 * this counter; that privacy-friendly tradeoff avoids creating a persistent
 * user or device identifier solely to enforce one promotional restoration.
 */
class UsageTracker(context: Context) {
    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    fun used(): Int = prefs.getInt(KEY_USED, 0)

    fun remaining(): Int = (FREE_TIER_QUOTA - used()).coerceAtLeast(0)

    fun increment() {
        prefs.edit().putInt(KEY_USED, used() + 1).apply()
    }

    fun reset() {
        prefs.edit().remove(KEY_USED).apply()
    }

    companion object {
        private const val KEY_USED = "free_used_count"
    }
}
