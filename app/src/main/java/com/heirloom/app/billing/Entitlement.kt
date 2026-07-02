package com.heirloom.app.billing

/**
 * The user's restoration entitlement at a moment in time. Resolved by
 * combining geo (Armenia exempts), billing (paid unlock), and usage
 * (free-tier counter).
 *
 * UI consumes this via a single `when`. Adding a new tier means adding a
 * new branch — the compiler enforces exhaustive handling.
 */
sealed interface Entitlement {
    /** Free in Armenia, no paywall ever, unlimited usage. */
    data object ArmeniaExempt : Entitlement

    /** One-time unlock active (price set in Play Console). Unlimited usage. */
    data object LifetimeUnlocked : Entitlement

    /** Legacy: yearly subscription. Not offered in UI; honored if ever present. */
    data object YearlySubscriber : Entitlement

    /** No purchase, free-tier remaining. `remaining` decrements on each restore. */
    data class FreeTier(val remaining: Int) : Entitlement

    /** Free tier exhausted. UI shows paywall. */
    data object PaywallRequired : Entitlement
}

/** Product IDs. Wire to actual SKU IDs in Play Console before launch. */
object ProductIds {
    const val LIFETIME = "heirloom_lifetime_unlock_v1"
    const val YEARLY = "heirloom_yearly_v1"
}

const val FREE_TIER_QUOTA = 1
