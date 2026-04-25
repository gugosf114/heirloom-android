package com.heirloom.app.billing

import android.content.Context

/**
 * Local free-tier counter. v1 only.
 *
 * KNOWN GAP: anyone who clears app data resets their counter. Before launch,
 * the canonical counter must move to Firestore keyed by Firebase UID. Local
 * value becomes a cache only.
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
