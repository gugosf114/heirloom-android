package com.heirloom.app.billing

sealed interface Entitlement {
    data object Loading : Entitlement

    data class Credits(
        val freeRemaining: Int,
        val paidRemaining: Int,
    ) : Entitlement {
        val totalRemaining: Int get() = freeRemaining + paidRemaining
    }

    data object PaywallRequired : Entitlement

    data class Unavailable(val message: String) : Entitlement
}

fun Entitlement.allowsRestore(): Boolean =
    this is Entitlement.Credits && totalRemaining > 0

data class RestorationPack(
    val productId: String,
    val restorations: Int,
    val expectedUsdPrice: String,
)

object ProductIds {
    const val FIVE = "heirloom_restorations_5_v1"
    const val TWENTY = "heirloom_restorations_20_v1"
    const val FIFTY = "heirloom_restorations_50_v1"

    val PACKS = listOf(
        RestorationPack(FIVE, 5, "$2.99"),
        RestorationPack(TWENTY, 20, "$7.99"),
        RestorationPack(FIFTY, 50, "$14.99"),
    )

    val ALL: Set<String> = PACKS.mapTo(linkedSetOf()) { it.productId }
}

const val FREE_TIER_QUOTA = 3
